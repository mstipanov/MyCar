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
 * Started by [com.example.mycar.capture.ScreenMirrorService] while a capture is live (the "only
 * while mirroring" rule), and only when the user asked for it and Notification access is granted.
 * A normal app receives *anonymized* playback configurations, so the trigger is the *usage* of the
 * active stream — navigation guidance / accessibility / notification — not a specific package;
 * that also covers Maps and Waze. If Radarbot turns out to use none of these, the log line below
 * says which usage it does use, and the set is easy to extend.
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

    /** Starts watching while a capture is live. Idempotent; no-op unless the setting is on. */
    fun start(context: Context) {
        if (audioManager != null) return
        if (!Settings.pauseForAnnouncements(context)) return
        if (!MediaControlService.isConnected) {
            Log.w(TAG, "Notification access is not granted; not watching for announcements")
            return
        }
        appContext = context.applicationContext
        audioManager = context.getSystemService(AudioManager::class.java)?.also {
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
        if (pausedByUs) {
            MediaControlService.resumePausedMedia()
        }
        pausedByUs = false
        announcementActive = false
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        // getActivePlaybackConfigurations() only lists *active* players, so presence is the signal.
        val activeUsages = configs.map { it.audioAttributes.usage }.toSet()

        val announcing = activeUsages.any { it in ANNOUNCEMENT_USAGES }
        val mediaPlaying = AudioAttributes.USAGE_MEDIA in activeUsages

        if (announcing) {
            handler.removeCallbacks(resume)
            if (!announcementActive) {
                announcementActive = true
                Log.i(TAG, "announcement started (active usages: $activeUsages)")
                if (mediaPlaying && shouldAct()) {
                    pausedByUs = MediaControlService.pausePlayingMedia()
                }
            }
        } else if (announcementActive) {
            announcementActive = false
            Log.i(TAG, "announcement ended (active usages: $activeUsages)")
            if (pausedByUs) handler.postDelayed(resume, RESUME_DELAY_MS)
        }
    }

    /** Only act while the car is actually showing us. */
    private fun shouldAct(): Boolean {
        val context = appContext ?: return false
        return CarConnectionWatcher.isProjecting(context)
    }
}
