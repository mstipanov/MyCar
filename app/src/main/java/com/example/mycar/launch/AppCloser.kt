package com.example.mycar.launch

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.example.mycar.Settings
import com.example.mycar.touch.TouchInjectorService

/**
 * Closes the app the user chose for "close when Android Auto disconnects".
 *
 * Android does not let an ordinary app kill another app's process: `killBackgroundProcesses` is a
 * no-op for other packages from Android 14 on, and there is no public "force stop" API. So this
 * works in two ways:
 *
 * - Soft (the default): ask the system to reap the app's background process where it still may
 *   (`killBackgroundProcesses`, effective up to Android 13), and when the app is the one on
 *   screen, leave it by going home through the accessibility service. Invisible and reliable, but
 *   it does not stop background media playback.
 * - Hard (opt-in): let the accessibility service drive the app-info screen's "Force stop", which
 *   really stops the process (media and background work included). This needs the phone awake and
 *   unlocked; when it is not, or the service is off, it falls back to the soft path.
 */
object AppCloser {

    private const val TAG = "AppCloser"

    /** Closes the app from [Settings], if one is chosen and the feature is on. */
    fun closeChosenApp(context: Context) {
        if (!Settings.closeAppOnDisconnect(context)) return
        val packageName = Settings.closeAppPackage(context)
        if (packageName.isBlank()) return
        close(context, packageName, hard = Settings.closeAppHard(context))
    }

    private fun close(context: Context, packageName: String, hard: Boolean) {
        if (hard && canDriveUi(context) && TouchInjectorService.forceStop(packageName)) {
            Log.i(TAG, "force stopping $packageName")
            return
        }
        softClose(context, packageName)
    }

    /** Reaps the app's background process where allowed, and leaves it when it is on screen. */
    private fun softClose(context: Context, packageName: String) {
        // A no-op for other apps from Android 14 on, but the right thing up to Android 13.
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(packageName)
        }.onFailure { Log.w(TAG, "could not reap $packageName", it) }

        if (TouchInjectorService.foregroundPackage() == packageName) {
            Log.i(TAG, "leaving foreground app $packageName")
            TouchInjectorService.goHome()
        }
    }

    /** Whether the app-info screen could actually be shown and driven: awake and unlocked. */
    private fun canDriveUi(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val interactive = power?.isInteractive ?: true
        val locked = keyguard?.isKeyguardLocked ?: false
        return interactive && !locked
    }
}
