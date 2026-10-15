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

    /** Recomputes the sessions and tells the pauser whether a speech stream is playing. */
    private fun refreshSessions() {
        val others = controllers.filter { it.packageName != packageName }
        AnnouncementPauser.onSpeechSessionPlaying(
            others.any { it.isPlaying() && it.isSpeech() },
        )
    }

    private fun MediaController.isPlaying() =
        playbackState?.state == PlaybackState.STATE_PLAYING

    private fun MediaController.isSpeech() =
        playbackInfo?.audioAttributes?.contentType == AudioAttributes.CONTENT_TYPE_SPEECH

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

    /**
     * Sends a "stop" action posted by [packageName]'s own notification, if it has one. Radarbot
     * keeps a notification up while it runs whose action stops it; sending that PendingIntent
     * stops the app cleanly — with no Settings app-info screen to drive (so none can be left open)
     * and no need for the phone to be awake and unlocked. False when the app has no live
     * notification, none of its actions looks like a stop, or the send fails.
     */
    private fun stopViaNotification(packageName: String): Boolean {
        val action = activeNotifications.orEmpty()
            .filter { it.packageName == packageName }
            .flatMap { it.notification.actions.orEmpty().asList() }
            .firstOrNull { it.actionIntent != null && it.title.looksLikeStop() }
            ?: return false
        return runCatching {
            action.actionIntent.send()
            Log.i(TAG, "sent \"${action.title}\" to $packageName")
        }.isSuccess
    }

    /** Whether an action's title reads as "stop the app". Radarbot localises its, so match a few. */
    private fun CharSequence?.looksLikeStop(): Boolean {
        val title = this?.toString()?.lowercase() ?: return false
        return STOP_ACTION_WORDS.any { title.contains(it) }
    }

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

        /**
         * Action titles that mean "stop the app". Radarbot shows a "stop navigation" action on its
         * notification and localises it, so match the common English and Croatian wording.
         */
        private val STOP_ACTION_WORDS = listOf(
            "stop", "exit", "end", "quit", "finish", "close", "turn off", "dismiss",
            "zaustavi", "izlaz", "zavr", "zatvori", "prekini", "ugasi", "isklju",
        )

        @Volatile
        private var instance: MediaControlService? = null

        /** Whether Notification access is granted and the listener is bound. */
        val isConnected: Boolean get() = instance != null

        /** How many media sessions (other than MyCar) are playing right now. */
        fun playingSessionCount(): Int = instance?.countPlayingSessions() ?: 0

        fun pausePlayingMedia(): Boolean = instance?.pauseMedia() == true

        /**
         * Sends a "stop" action from one of [packageName]'s notifications, if it has one (see
         * [stopViaNotification]). False when Notification access is off or there is no such action.
         */
        fun stopAppViaNotification(packageName: String): Boolean =
            instance?.stopViaNotification(packageName) == true

        fun resumePausedMedia() {
            instance?.resumeMedia()
        }
    }
}
