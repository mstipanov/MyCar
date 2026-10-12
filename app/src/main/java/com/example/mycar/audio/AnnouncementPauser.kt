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
 * switched on during a session; [stop] runs when mirroring ends. Two signals count as an
 * announcement:
 *
 * - an audio player whose **usage** is navigation guidance / accessibility / notification, or a
 *   **speech** content type (from `AudioManager` playback configurations — anonymized, so the
 *   package is not visible), and
 * - a media **session** playing a speech stream (`MediaControlService`), which is how a
 *   "News & Talk / Speech" app such as Radarbot shows up.
 *
 * The stream that is paused is always a *non-speech* media stream, so the announcement itself is
 * left alone. [status] reports what was seen and done, so the settings screen can show why it
 * acted or did not, without logcat.
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

    // Announcement signals.
    private var audioAnnouncing = false
    private var sessionAnnouncing = false
    private var mediaPlaying = false
    private var announcementActive = false
    private var pausedByUs = false

    /** A short summary of the audio players seen at the last change, for the diagnostic. */
    @Volatile
    private var lastSeen: String = "nothing seen yet"

    /** What the last announcement did, or null if none has been seen. */
    @Volatile
    private var lastAction: String? = null

    /** One-line diagnostic for the settings screen; null when not watching. */
    val status: String?
        get() {
            if (audioManager == null) return null
            val sessions = MediaControlService.activeSessionSummary() ?: "no access"
            return "Audio $lastSeen · Sessions $sessions — ${lastAction ?: "no announcement yet"}"
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
        audioAnnouncing = false
        sessionAnnouncing = false
        mediaPlaying = false
        announcementActive = false
        lastSeen = "nothing seen yet"
        lastAction = null
    }

    /** Called by [MediaControlService] when a session playing a speech stream starts or stops. */
    fun onSpeechSessionPlaying(playing: Boolean) {
        if (sessionAnnouncing == playing) return
        sessionAnnouncing = playing
        Log.i(TAG, "speech session playing=$playing")
        reconcile()
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        // getActivePlaybackConfigurations() only lists *active* players, so presence is the signal.
        val attrs = configs.map { it.audioAttributes }
        val usages = attrs.map { it.usage }.toSet()
        val speech = attrs.any { it.contentType == AudioAttributes.CONTENT_TYPE_SPEECH }
        lastSeen = "usages=${usages.sorted().joinToString(",")} speech=$speech"
        Log.d(TAG, "active: $lastSeen")

        audioAnnouncing = speech || usages.any { it in ANNOUNCEMENT_USAGES }
        // Media we may pause: an ordinary media stream that is not the speech announcement itself.
        mediaPlaying = attrs.any {
            it.usage == AudioAttributes.USAGE_MEDIA &&
                it.contentType != AudioAttributes.CONTENT_TYPE_SPEECH
        }
        reconcile()
    }

    private fun reconcile() {
        if (audioManager == null) return
        val announcing = audioAnnouncing || sessionAnnouncing
        if (announcing) {
            handler.removeCallbacks(resume)
            if (!announcementActive) {
                announcementActive = true
                act()
            }
        } else if (announcementActive) {
            announcementActive = false
            if (pausedByUs) handler.postDelayed(resume, RESUME_DELAY_MS)
        }
    }

    private fun act() {
        val trigger = if (sessionAnnouncing) "speech session" else "audio announcement"
        val action = when {
            !shouldAct() -> "$trigger: not mirroring"
            !sessionAnnouncing && !mediaPlaying -> "$trigger: no media was playing"
            !MediaControlService.isConnected -> "$trigger: grant notification access"
            else -> {
                pausedByUs = MediaControlService.pausePlayingMedia()
                if (pausedByUs) "$trigger: media paused"
                else "$trigger: found no playing media session"
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
