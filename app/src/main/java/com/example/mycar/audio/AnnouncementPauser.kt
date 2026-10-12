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
 * Started with the app process when the setting is on, and only *acts* while a capture is live
 * (the "only while mirroring" rule). Whether an announcement is active is re-evaluated on every
 * audio change, so it cannot get stuck in a paused state:
 *
 * - an audio player whose **usage** is navigation guidance / accessibility / notification, or
 * - a **speech** content type, or
 * - **more active media players than playing media sessions**. A normal media app is a player
 *   *with* a session (YouTube); an app like Radarbot that speaks over the media is a player
 *   *without* one, and its stream is plain `USAGE_MEDIA` with no distinguishing attributes.
 *
 * While an announcement is active the playing media session is paused; once it stops, the media
 * resumes.
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

    /** Settle time before resuming once the announcement is over. */
    private const val RESUME_DELAY_MS = 800L

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null

    /** Whether the playback watcher is currently registered. */
    @Volatile
    private var watching = false

    /** A media session is playing a speech stream (see [MediaControlService]). */
    @Volatile
    private var sessionAnnouncing = false

    /** Whether an announcement is currently active, recomputed on every change. */
    @Volatile
    private var alertActive = false

    /** Whether we are the ones who paused the media. */
    @Volatile
    private var pausedByUs = false

    private val resume = Runnable {
        if (!alertActive && pausedByUs) {
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
        sessionAnnouncing = false
        alertActive = false
    }

    /** Called by [MediaControlService] when a session playing a speech stream starts or stops. */
    fun onSpeechSessionPlaying(playing: Boolean) {
        if (sessionAnnouncing == playing) return
        sessionAnnouncing = playing
        Log.i(TAG, "speech session playing=$playing")
        apply()
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        // getActivePlaybackConfigurations() only lists *active* players, so presence is the signal.
        val attrs = configs.map { it.audioAttributes }
        val usages = attrs.map { it.usage }.toSet()
        val speech = attrs.any { it.contentType == AudioAttributes.CONTENT_TYPE_SPEECH }
        val mediaPlayers = attrs.count { it.usage == AudioAttributes.USAGE_MEDIA }
        val playingSessions = MediaControlService.playingSessionCount()
        // A media player with no matching session is an announcement (Radarbot); a normal media app
        // (YouTube) has a session, so it does not count.
        val extraPlayers = mediaPlayers - playingSessions
        Log.d(
            TAG,
            "active: usages=${usages.sorted().joinToString(",")} media=$mediaPlayers " +
                "sessions=$playingSessions speech=$speech",
        )

        alertActive = sessionAnnouncing ||
            speech ||
            usages.any { it in ANNOUNCEMENT_USAGES } ||
            extraPlayers > 0
        apply()
    }

    /** Brings the media in line with [alertActive]: pause while announcing, resume after. */
    private fun apply() {
        if (audioManager == null) return
        if (alertActive) {
            handler.removeCallbacks(resume)
            val action = when {
                !shouldAct() -> "not mirroring"
                !MediaControlService.isConnected -> "grant notification access"
                MediaControlService.pausePlayingMedia() -> {
                    pausedByUs = true
                    "media paused"
                }
                pausedByUs -> "already paused"
                else -> "nothing playing to pause"
            }
            Log.i(TAG, "announcement: $action")
        } else if (pausedByUs) {
            handler.removeCallbacks(resume)
            handler.postDelayed(resume, RESUME_DELAY_MS)
        }
    }

    /** Only act while the car is actually showing us. */
    private fun shouldAct(): Boolean {
        val context = appContext ?: return false
        return CarConnectionWatcher.isProjecting(context)
    }
}
