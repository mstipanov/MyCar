package com.example.mycar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.mycar.BuildConfig
import com.example.mycar.Settings
import com.example.mycar.auto.CarConnectionWatcher

/**
 * Pauses whatever is playing while a navigation or alert app speaks, then resumes it.
 *
 * Started with the app process when the setting is on, and only *acts* while a capture is live
 * (the "only while mirroring" rule). Announcements are detected from three signals, because apps
 * tag their audio differently:
 *
 * - an audio player whose **usage** is navigation guidance / accessibility / notification,
 * - a **speech** content type (both from `AudioManager` playback configurations — anonymized, so
 *   the package is not visible), or
 * - a **second media player overlapping** one that is already playing. This is the only visible
 *   sign of Radarbot's spoken alerts, which it plays as plain `USAGE_MEDIA` with no content type
 *   (indistinguishable from YouTube by attributes).
 *
 * The stream paused is a non-speech media stream, so the announcement itself is left alone, and
 * it resumes shortly after the extra player stops. [status] reports what was seen and done.
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

    /** How long to hold after two media players overlap (Radarbot's only visible signal). */
    private const val OVERLAP_HOLD_MS = 8_000L

    /** Settle time before resuming once nothing is playing any more. */
    private const val RESUME_AFTER_EMPTY_MS = 800L

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null

    /** Whether the playback watcher is currently registered. */
    @Volatile
    private var watching = false

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

    /** One-line diagnostic for the settings screen. Never null, so the UI always shows the state. */
    val status: String
        get() {
            val state = if (watching) "Watching" else "Idle"
            val sessions = MediaControlService.activeSessionSummary() ?: "no access"
            return "v${BuildConfig.VERSION_NAME} · $state · Audio $lastSeen · " +
                "Sessions $sessions — ${lastAction ?: "no announcement yet"}"
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
        if (lastAction == null) lastAction = Settings.announcementLastAction(ctx)
        if (audioManager != null) return
        audioManager = ctx.getSystemService(AudioManager::class.java)?.also {
            it.registerAudioPlaybackCallback(callback, handler)
            Log.i(TAG, "watching for announcements")
        }
        watching = true
    }

    /** Stops watching and lets any media we paused play again. Idempotent. */
    fun stop() {
        handler.removeCallbacks(resume)
        audioManager?.let { runCatching { it.unregisterAudioPlaybackCallback(callback) } }
        audioManager = null
        appContext = null
        watching = false
        if (pausedByUs) MediaControlService.resumePausedMedia()
        pausedByUs = false
        audioAnnouncing = false
        sessionAnnouncing = false
        mediaPlaying = false
        announcementActive = false
        // lastSeen/lastAction are kept, so the diagnostic still shows the last session's result.
    }

    /**
     * Called by [MediaControlService] when a session playing a speech stream starts or stops.
     */
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
        val mediaCount = attrs.count { it.usage == AudioAttributes.USAGE_MEDIA }
        val nonSpeechMediaCount = attrs.count {
            it.usage == AudioAttributes.USAGE_MEDIA &&
                it.contentType != AudioAttributes.CONTENT_TYPE_SPEECH
        }
        lastSeen = "usages=${usages.sorted().joinToString(",")} media=$mediaCount speech=$speech"
        Log.d(TAG, "active: $lastSeen")

        audioAnnouncing = speech || usages.any { it in ANNOUNCEMENT_USAGES }
        // Media we may pause: an ordinary media stream that is not the speech announcement itself.
        mediaPlaying = nonSpeechMediaCount > 0
        reconcile()

        // Radarbot plays its spoken alerts as plain media with no distinguishing attributes, so
        // the only visible sign is a *second* media player while one is already playing. Pause
        // then; once we have paused, the leftover player is the alert itself, so resume when
        // nothing is playing any more (or after a safety hold in case something else is playing).
        if (nonSpeechMediaCount >= 2) {
            lastSeen = "$lastSeen overlap"
            val action = pauseNow()
            lastAction = action
            Log.i(TAG, action)
            scheduleResume(OVERLAP_HOLD_MS)
        } else if (pausedByUs && nonSpeechMediaCount == 0) {
            scheduleResume(RESUME_AFTER_EMPTY_MS)
        }
    }

    /** Pauses the playing media if we have not already, returning a status line. */
    private fun pauseNow(): String = when {
        !shouldAct() -> "media overlap: not mirroring"
        !MediaControlService.isConnected -> "media overlap: grant notification access"
        pausedByUs -> "media overlap: already paused"
        else -> {
            pausedByUs = MediaControlService.pausePlayingMedia()
            if (pausedByUs) "media overlap: media paused" else "media overlap: nothing playing to pause"
        }
    }

    private fun scheduleResume(delayMs: Long) {
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, delayMs)
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
        appContext?.let { Settings.setAnnouncementLastAction(it, action) }
        Log.i(TAG, action)
    }

    /**
     * Only act while the car is actually showing us. The `files/debugActAlways` marker (only
     * creatable in a debuggable build, e.g. via `adb shell run-as`) relaxes that for desk testing.
     */
    private fun shouldAct(): Boolean {
        val context = appContext ?: return false
        if (java.io.File(context.filesDir, "debugActAlways").exists()) return true
        return CarConnectionWatcher.isProjecting(context)
    }
}
