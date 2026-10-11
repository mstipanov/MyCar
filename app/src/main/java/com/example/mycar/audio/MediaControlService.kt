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
 * A notification listener whose only job is to let MyCar control the phone's current media
 * session — through a [MediaController], which is the only way a normal app can pause and resume
 * another app's playback. The user enables it by hand in Settings -> Notification access; the
 * service does nothing else with notifications.
 */
class MediaControlService : NotificationListenerService() {

    /** The session we last paused, so [resumeMedia] puts back only what we took. */
    private var paused: MediaController? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        paused = null
        Log.i(TAG, "disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The session that is playing media right now, ignoring MyCar and any speech announcement. */
    private fun playingController(): MediaController? = runCatching {
        val manager = getSystemService(MediaSessionManager::class.java)
        manager.getActiveSessions(ComponentName(this, MediaControlService::class.java))
    }.getOrDefault(emptyList())
        .firstOrNull { controller ->
            controller.packageName != packageName &&
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                // Never pause the announcement itself (Radarbot plays as a speech stream).
                controller.playbackInfo?.audioAttributes?.contentType !=
                    AudioAttributes.CONTENT_TYPE_SPEECH
        }

    /** Pauses the playing media. True when there was something to pause. */
    private fun pauseMedia(): Boolean {
        val controller = playingController() ?: return false
        paused = controller
        controller.transportControls.pause()
        Log.i(TAG, "paused ${controller.packageName}")
        return true
    }

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

        fun pausePlayingMedia(): Boolean = instance?.pauseMedia() == true

        fun resumePausedMedia() {
            instance?.resumeMedia()
        }
    }
}
