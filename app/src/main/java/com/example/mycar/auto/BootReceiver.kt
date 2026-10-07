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
        if (Settings.autoStart(context) && android.provider.Settings.canDrawOverlays(context)) {
            CarConnectionWatcher.start(context)
        }
    }
}
