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
 * *anonymized* playback configurations, so the trigger is the *usage* of the active stream —
 * navigation guidance / accessibility / notification / notification event — not a specific
 * package; that also covers Maps and Waze.
 *
 * [status] records what happened at the last announcement so the settings screen can show it; that
 * is how a mismatch (an unexpected usage, missing Notification access) is diagnosed on a phone
 * without logcat.
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

    /** What happened at the last announcement (or the watching state), for the settings screen. */
    @Volatile
    var status: String? = null
        private set

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
        if (!Settings.pauseForAnnouncements(ctx)) {
            status = null
            return
        }
        if (audioManager != null) return
        audioManager = ctx.getSystemService(AudioManager::class.java)?.also {
            it.registerAudioPlaybackCallback(callback, handler)
            Log.i(TAG, "watching for announcements")
        }
        if (status == null) status = "Watching for announcements…"
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
        status = null
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        // getActivePlaybackConfigurations() only lists *active* players, so presence is the signal.
        val activeUsages = configs.map { it.audioAttributes.usage }.toSet()
        Log.d(TAG, "active usages: $activeUsages")

        val announcementUsage = activeUsages.firstOrNull { it in ANNOUNCEMENT_USAGES }
        val mediaPlaying = AudioAttributes.USAGE_MEDIA in activeUsages

        if (announcementUsage != null) {
            handler.removeCallbacks(resume)
            if (!announcementActive) {
                announcementActive = true
                Log.i(TAG, "announcement started (usage=$announcementUsage)")
                act(announcementUsage, mediaPlaying)
            }
        } else if (announcementActive) {
            announcementActive = false
            Log.i(TAG, "announcement ended")
            if (pausedByUs) handler.postDelayed(resume, RESUME_DELAY_MS)
        }
    }

    private fun act(usage: Int, mediaPlaying: Boolean) {
        status = when {
            !shouldAct() -> "Announcement (usage $usage): not mirroring"
            !mediaPlaying -> "Announcement (usage $usage): no media was playing"
            !MediaControlService.isConnected -> "Announcement (usage $usage): grant notification access"
            else -> {
                pausedByUs = MediaControlService.pausePlayingMedia()
                if (pausedByUs) {
                    "Announcement (usage $usage): media paused"
                } else {
                    "Announcement (usage $usage): found no playing media session"
                }
            }
        }
        Log.i(TAG, "status: $status")
    }

    /** Only act while the car is actually showing us. */
    private fun shouldAct(): Boolean {
        val context = appContext ?: return false
        return CarConnectionWatcher.isProjecting(context)
    }
}
