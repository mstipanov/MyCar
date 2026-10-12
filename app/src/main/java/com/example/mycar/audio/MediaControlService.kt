package com.example.mycar.audio

import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.KeyEvent

/**
 * A notification listener whose only job is to let MyCar see and control the phone's media
 * sessions. Enabling it in Settings -> Notification access is what unlocks both
 * [MediaSessionManager.getActiveSessions] (so we can find the stream to pause, and spot a speech
 * announcement) and a [MediaController] to pause/resume another app's playback.
 */
class MediaControlService : NotificationListenerService() {

    /** The session we last paused, so [resumeMedia] puts back only what we took. */
    private var paused: MediaController? = null

    /** Active sessions, each with a callback so we hear their playback-state changes. */
    private var controllers: List<MediaController> = emptyList()

    /** A snapshot of the sessions for the settings-screen diagnostic, or null when not connected. */
    private var sessionSummary: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshSessions()
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> bindControllers(controllers) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "connected")
        runCatching {
            mediaSessionManager()?.addOnActiveSessionsChangedListener(sessionsChanged, componentName())
        }
        bindControllers(runCatching { mediaSessionManager()?.getActiveSessions(componentName()) }.getOrNull())
    }

    override fun onListenerDisconnected() {
        unbindControllers()
        runCatching {
            mediaSessionManager()?.removeOnActiveSessionsChangedListener(sessionsChanged)
        }
        paused = null
        sessionSummary = null
        if (instance === this) instance = null
        Log.i(TAG, "disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unbindControllers()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun mediaSessionManager(): MediaSessionManager? =
        getSystemService(MediaSessionManager::class.java)

    private fun componentName() = ComponentName(this, MediaControlService::class.java)

    private fun bindControllers(list: List<MediaController>?) {
        unbindControllers()
        controllers = list.orEmpty()
        controllers.forEach { runCatching { it.registerCallback(controllerCallback) } }
        refreshSessions()
    }

    private fun unbindControllers() {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
    }

    /** Recomputes the snapshot and tells the pauser whether a speech stream is playing. */
    private fun refreshSessions() {
        val others = controllers.filter { it.packageName != packageName }
        sessionSummary = others.joinToString("; ") {
            "${it.packageName}: type=${it.contentType()}, state=${it.playbackState?.state}"
        }.ifEmpty { "none" }

        AnnouncementPauser.onSpeechSessionPlaying(
            others.any { it.isPlaying() && it.isSpeech() },
        )
    }

    private fun MediaController.isPlaying() =
        playbackState?.state == PlaybackState.STATE_PLAYING

    private fun MediaController.isSpeech() =
        playbackInfo?.audioAttributes?.contentType == AudioAttributes.CONTENT_TYPE_SPEECH

    private fun MediaController.contentType(): Int =
        playbackInfo?.audioAttributes?.contentType ?: -1

    /** Pauses the playing media session, skipping MyCar and any speech announcement. */
    private fun pauseMedia(): Boolean {
        val controller = (controllers + activeSessions()).firstOrNull {
            it.packageName != packageName && it.isPlaying() && !it.isSpeech()
        } ?: return false
        paused = controller
        controller.transportControls.pause()
        Log.i(TAG, "paused ${controller.packageName}")
        return true
    }

    private fun activeSessions(): List<MediaController> =
        runCatching { mediaSessionManager()?.getActiveSessions(componentName()) }.getOrNull().orEmpty()

    /** Number of media sessions currently playing, excluding MyCar. */
    private fun countPlayingSessions(): Int =
        (controllers + activeSessions())
            .filter {
                it.packageName != packageName &&
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            .distinctBy { it.sessionToken }
            .size

    /** Resumes the session [pauseMedia] stopped, unless it is already playing again. */
    private fun resumeMedia() {
        val controller = paused ?: return
        paused = null
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) return

        controller.transportControls.play()
        Log.i(TAG, "resumed ${controller.packageName} (play)")

        // Some players ignore play() from a foreign controller, so nudge the media button a beat
        // later if nothing started.
        Handler(Looper.getMainLooper()).postDelayed({
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
                val audio = getSystemService(AudioManager::class.java) ?: return@postDelayed
                audio.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
                )
                audio.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY),
                )
                Log.i(TAG, "resume fallback: media key PLAY")
            }
        }, RESUME_FALLBACK_MS)
    }

    companion object {
        private const val TAG = "MediaControl"
        private const val RESUME_FALLBACK_MS = 1_000L

        @Volatile
        private var instance: MediaControlService? = null

        /** Whether Notification access is granted and the listener is bound. */
        val isConnected: Boolean get() = instance != null

        /** A one-line list of the active media sessions, for the settings-screen diagnostic. */
        fun activeSessionSummary(): String? = instance?.sessionSummary

        /** How many media sessions (other than MyCar) are playing right now. */
        fun playingSessionCount(): Int = instance?.countPlayingSessions() ?: 0

        fun pausePlayingMedia(): Boolean = instance?.pauseMedia() == true

        fun resumePausedMedia() {
            instance?.resumeMedia()
        }
    }
}
