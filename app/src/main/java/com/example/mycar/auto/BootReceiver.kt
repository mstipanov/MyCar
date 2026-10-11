package com.example.mycar.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mycar.Settings

/**
 * Brings the [CarConnectionWatcher] back after a reboot, so auto-start works on the first drive
 * without opening the app. Starting a foreground service from `BOOT_COMPLETED` is permitted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Auto-start needs the overlay permission for its background activity start; closing an
        // app on disconnect does not, so either feature keeps the watcher alive.
        val forAutoStart =
            Settings.autoStart(context) && android.provider.Settings.canDrawOverlays(context)
        if (forAutoStart || Settings.closeAppOnDisconnect(context)) {
            CarConnectionWatcher.start(context)
        }
    }
}
