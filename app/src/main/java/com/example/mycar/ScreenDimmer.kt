package com.example.mycar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.mycar.auto.CarConnectionWatcher

/**
 * Dims the phone screen to its minimum while Android Auto is connected, when the user enabled
 * "Dim the phone screen while Android Auto is connected".
 *
 * The user's brightness and brightness mode (manual or automatic) are saved once and restored
 * when Android Auto disconnects or the option is switched off. Touching the phone while it is
 * dimmed restores the brightness immediately so the phone is usable; after [dimTimeoutMs] without
 * another touch it dims again.
 *
 * Touch detection comes from [com.example.mycar.touch.TouchInjectorService]'s accessibility
 * events. Without that service the screen still dims and restores on connect/disconnect, but a
 * touch does not wake it.
 *
 * Changing the system brightness needs the same "Modify system settings" access as the rotation
 * lock.
 */
object ScreenDimmer {

    private const val TAG = "ScreenDimmer"

    /** The lowest brightness value that still leaves the screen on. */
    private const val DIM_BRIGHTNESS = 1

    /** How long a touch keeps the screen bright before it dims again. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    var dimTimeoutMs: Long = 10_000L

    private val handler = Handler(Looper.getMainLooper())

    /** The app context of the active session, so the timeout can dim again. */
    private var appContext: Context? = null

    /** True between an Android Auto connect and disconnect. */
    private var active = false

    /** True after a touch restored the brightness, until the timeout dims it again. */
    private var awake = false

    private val dimAgain = Runnable {
        awake = false
        dim()
    }

    /** Android Auto connected: dim if the option is on and the access is granted. */
    fun onConnected(context: Context) {
        appContext = context.applicationContext
        if (!active) {
            awake = false
            handler.removeCallbacks(dimAgain)
        }
        active = true
        dim()
    }

    /** Android Auto disconnected: put the user's brightness back. */
    fun onDisconnected(context: Context) {
        active = false
        awake = false
        handler.removeCallbacks(dimAgain)
        release(context.applicationContext)
    }

    /** A touch while dimmed: show the screen now, dim again after the timeout. */
    fun onUserTouch(context: Context) {
        if (!active || !Settings.dimScreen(context)) return
        if (!hasBackup(context)) return
        appContext = context.applicationContext
        awake = true
        handler.removeCallbacks(dimAgain)
        restoreBrightness(context.applicationContext)
        handler.postDelayed(dimAgain, dimTimeoutMs)
    }

    /** The option changed; turning it off immediately gives the brightness back. */
    fun onSettingChanged(context: Context) {
        if (!Settings.dimScreen(context)) {
            active = false
            awake = false
            handler.removeCallbacks(dimAgain)
            release(context.applicationContext)
            return
        }
        if (CarConnectionWatcher.isProjecting(context)) onConnected(context) else active = false
    }

    /**
     * Restores brightness left dimmed by a process that died before its cleanup (an update, a
     * force stop, a reboot) when Android Auto is not projecting.
     */
    fun restoreIfIdle(context: Context) {
        if (!CarConnectionWatcher.isProjecting(context) && hasBackup(context)) {
            active = false
            awake = false
            handler.removeCallbacks(dimAgain)
            release(context.applicationContext)
        }
    }

    private fun dim() {
        val context = appContext ?: return
        if (!active || awake) return
        if (!Settings.dimScreen(context)) return
        if (!WriteSettings.isAllowed(context)) return
        saveBackup(context)
        try {
            AndroidSettings.System.putInt(
                context.contentResolver,
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            AndroidSettings.System.putInt(
                context.contentResolver,
                AndroidSettings.System.SCREEN_BRIGHTNESS,
                DIM_BRIGHTNESS,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not dim the screen", t)
        }
    }

    private fun saveBackup(context: Context) {
        val prefs = Settings.prefs(context)
        if (prefs.contains(Settings.KEY_BRIGHTNESS_BACKUP)) return
        val resolver = context.contentResolver
        prefs.edit()
            .putInt(
                Settings.KEY_BRIGHTNESS_BACKUP,
                AndroidSettings.System.getInt(
                    resolver,
                    AndroidSettings.System.SCREEN_BRIGHTNESS,
                    128,
                ).takeIf { it >= 0 } ?: 128,
            )
            .putInt(
                Settings.KEY_BRIGHTNESS_MODE_BACKUP,
                AndroidSettings.System.getInt(
                    resolver,
                    AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                    AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                ).coerceIn(0, 1),
            )
            .apply()
    }

    /** Puts the saved brightness back, keeping the backup so the timeout can dim again. */
    private fun restoreBrightness(context: Context) {
        val prefs = Settings.prefs(context)
        if (!prefs.contains(Settings.KEY_BRIGHTNESS_BACKUP)) return
        if (!WriteSettings.isAllowed(context)) return
        val resolver = context.contentResolver
        val brightness = prefs.getInt(Settings.KEY_BRIGHTNESS_BACKUP, 128).takeIf { it >= 0 } ?: 128
        val mode = prefs.getInt(
            Settings.KEY_BRIGHTNESS_MODE_BACKUP,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ).coerceIn(0, 1)
        try {
            // Value first: if the saved mode is automatic, the system takes over afterwards.
            AndroidSettings.System.putInt(
                resolver,
                AndroidSettings.System.SCREEN_BRIGHTNESS,
                brightness,
            )
            AndroidSettings.System.putInt(
                resolver,
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                mode,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not restore the screen brightness", t)
        }
    }

    /** Restores the brightness and forgets the backup (Android Auto went away). */
    private fun release(context: Context) {
        if (!WriteSettings.isAllowed(context)) return
        restoreBrightness(context)
        Settings.prefs(context).edit()
            .remove(Settings.KEY_BRIGHTNESS_BACKUP)
            .remove(Settings.KEY_BRIGHTNESS_MODE_BACKUP)
            .apply()
    }

    private fun hasBackup(context: Context): Boolean =
        Settings.prefs(context).contains(Settings.KEY_BRIGHTNESS_BACKUP)
}
