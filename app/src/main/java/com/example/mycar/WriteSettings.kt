package com.example.mycar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings

/**
 * The "Modify system settings" special access, shared by the rotation lock and screen dimming.
 * Writing system values (rotation, brightness) requires it; the user grants it from Settings.
 */
object WriteSettings {

    fun isAllowed(context: Context): Boolean = AndroidSettings.System.canWrite(context)

    fun permissionIntent(context: Context): Intent =
        Intent(
            AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
