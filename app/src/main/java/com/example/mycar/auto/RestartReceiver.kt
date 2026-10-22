package com.example.mycar.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mycar.Settings
import com.example.mycar.audio.AnnouncementPauser
import com.example.mycar.car.CarCategory

/**
 * Brings the app's always-on pieces back after the system kills the process out from under them,
 * without waiting for the user to open MyCar: after a reboot (`BOOT_COMPLETED`) or after an app
 * update (`MY_PACKAGE_REPLACED`, which kills the process the moment the new APK is installed).
 *
 * The update case matters because nothing is restarted after a package replacement: without it,
 * auto-start, close-on-disconnect and announcement pausing silently stayed off until MyCar was
 * opened by hand or the phone was rebooted. Starting a foreground service from either broadcast
 * is permitted.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> restoreWatcher(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // An app update resets the CarAppService components to the manifest default, so
                // re-apply the stored Android Auto category instead of waiting for the phone UI.
                CarCategory.register(context, Settings.carCategory(context))
                AnnouncementPauser.start(context)
                restoreWatcher(context)
            }
        }
    }

    private fun restoreWatcher(context: Context) {
        if (CarConnectionWatcher.wanted(context)) CarConnectionWatcher.start(context)
    }
}
