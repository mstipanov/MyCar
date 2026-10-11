package com.example.mycar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.mycar.Settings
import com.example.mycar.auto.CarConnectionWatcher

/**
 * Pauses whatever is playing while a navigation or alert app speaks, then resumes it.
 *
 * Started while a capture is live (the "only while mirroring" rule) and whenever the setting is
 * switched on during a session; [stop] runs when mirroring ends. A normal app receives
 * *anonymized* playback configurations, so the trigger is how the stream is tagged, not which
 * package it is:
 *
 * - an announcement **usage** (navigation guidance / accessibility / notification), or
 * - a **speech** content type — this is Radarbot's "News & Talk / Speech" stream, and covers apps
 *   that play announcements as ordinary media.
 *
 * What gets paused is a media stream that is *not* speech, so the announcement itself is left
 * alone. [status] reports what was seen and done, so the settings screen can show why it acted or
 * did not, without logcat.
 */
object AnnouncementPauser {

    private const val TAG = "AnnouncementPauser"

    /** Usages that mean "an app is speaking over the media". */
    private val ANNOUNCEMENT_USAGES = setOf(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
    )

    /**
     * Wait this long after the announcement goes quiet before resuming, so a pause between two
     * sentences does not make the media stutter back on.
     */
    private const val RESUME_DELAY_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var announcementActive = false
    private var pausedByUs = false

    /** A short summary of the usages/content types seen at the last change, for the diagnostic. */
    @Volatile
    private var lastSeen: String = "nothing seen yet"

    /** What the last announcement did, or null if none has been seen. */
    @Volatile
    private var lastAction: String? = null

    /** One-line diagnostic for the settings screen; null when not watching. */
    val status: String?
        get() {
            if (audioManager == null) return null
            return "Audio $lastSeen — ${lastAction ?: "no announcement seen yet"}"
        }

    private val resume = Runnable {
        if (!announcementActive && pausedByUs) {
            MediaControlService.resumePausedMedia()
            pausedByUs = false
        }
    }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onConfigs(configs.orEmpty())
        }
    }

    /**
     * Registers the playback watcher. Idempotent, and safe to call again later (e.g. after
     * Notification access is granted); it does not require the media-control listener to be bound
     * yet — that is only needed when an announcement actually starts.
     */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!Settings.pauseForAnnouncements(ctx)) return
        if (audioManager != null) return
        audioManager = ctx.getSystemService(AudioManager::class.java)?.also {
            it.registerAudioPlaybackCallback(callback, handler)
            Log.i(TAG, "watching for announcements")
        }
    }

    /** Stops watching and lets any media we paused play again. Idempotent. */
    fun stop() {
        handler.removeCallbacks(resume)
        audioManager?.let { runCatching { it.unregisterAudioPlaybackCallback(callback) } }
        audioManager = null
        appContext = null
        if (pausedByUs) MediaControlService.resumePausedMedia()
        pausedByUs = false
        announcementActive = false
        lastSeen = "nothing seen yet"
        lastAction = null
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        // getActivePlaybackConfigurations() only lists *active* players, so presence is the signal.
        val attrs = configs.map { it.audioAttributes }
        val usages = attrs.map { it.usage }.toSet()
        val speech = attrs.any { it.contentType == AudioAttributes.CONTENT_TYPE_SPEECH }
        lastSeen = "usages=${usages.sorted().joinToString(",")} speech=$speech"
        Log.d(TAG, "active: $lastSeen")

        val announcing = speech || usages.any { it in ANNOUNCEMENT_USAGES }
        // Media we may pause: an ordinary media stream that is not the speech announcement itself.
        val mediaPlaying = attrs.any {
            it.usage == AudioAttributes.USAGE_MEDIA &&
                it.contentType != AudioAttributes.CONTENT_TYPE_SPEECH
        }

        if (announcing) {
            handler.removeCallbacks(resume)
            if (!announcementActive) {
                announcementActive = true
                Log.i(TAG, "announcement started ($lastSeen)")
                act(speech, mediaPlaying)
            }
        } else if (announcementActive) {
            announcementActive = false
            Log.i(TAG, "announcement ended")
            if (pausedByUs) handler.postDelayed(resume, RESUME_DELAY_MS)
        }
    }

    private fun act(speech: Boolean, mediaPlaying: Boolean) {
        val trigger = if (speech) "speech" else "announcement usage"
        val action = when {
            !shouldAct() -> "$trigger: not mirroring"
            !mediaPlaying -> "$trigger: no media was playing"
            !MediaControlService.isConnected -> "$trigger: grant notification access"
            else -> {
                pausedByUs = MediaControlService.pausePlayingMedia()
                if (pausedByUs) {
                    "$trigger: media paused"
                } else {
                    "$trigger: found no playing media session"
                }
            }
        }
        lastAction = action
        Log.i(TAG, action)
    }

    /** Only act while the car is actually showing us. */
    private fun shouldAct(): Boolean {
        val context = appContext ?: return false
        return CarConnectionWatcher.isProjecting(context)
    }
}
