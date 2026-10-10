package com.example.mycar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Surface

/**
 * Forces the device display into landscape and holds it there.
 *
 * This has to go through [AndroidSettings.System] rather than
 * `Activity.setRequestedOrientation`: the app mirrors the *whole display*, so rotating only its
 * own activity would leave everything else (and therefore most of the mirror) in portrait.
 *
 * Writing these requires the "Modify system settings" special access, which the user grants from
 * Settings; [permissionIntent] opens that screen. `user_rotation` has no public constant, hence
 * the literal key.
 */
object RotationLock {

    private const val TAG = "RotationLock"
    private const val USER_ROTATION = "user_rotation"

    /** Whether the user has granted "Modify system settings". */
    fun isAllowed(context: Context): Boolean = AndroidSettings.System.canWrite(context)

    /** The Settings screen where the user grants that access. */
    fun permissionIntent(context: Context): Intent =
        Intent(
            AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Turns auto-rotate off and rotates to landscape, remembering the previous auto-rotate value
     * so [unlock] can put it back. Returns false (and does nothing) without the required access.
     */
    fun lockLandscape(context: Context): Boolean {
        if (!isAllowed(context)) return false
        val resolver = context.contentResolver
        val prefs = Settings.prefs(context)

        if (!prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)) {
            prefs.edit()
                .putInt(
                    Settings.KEY_AUTOROTATE_BACKUP,
                    AndroidSettings.System.getInt(
                        resolver,
                        AndroidSettings.System.ACCELEROMETER_ROTATION,
                        1,
                    ),
                )
                .apply()
        }

        return try {
            AndroidSettings.System.putInt(
                resolver,
                AndroidSettings.System.ACCELEROMETER_ROTATION,
                0,
            )
            AndroidSettings.System.putInt(resolver, USER_ROTATION, Surface.ROTATION_90)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not force landscape", t)
            false
        }
    }

    /**
     * Forces the display to the opposite orientation (portrait <-> landscape) for the car
     * session, turning auto-rotate off, and remembers the previous auto-rotate value for [unlock].
     * This is what the car launcher's rotate button calls. False without the required access.
     */
    fun togglePortraitLandscape(context: Context): Boolean {
        if (!isAllowed(context)) return false
        val resolver = context.contentResolver
        val prefs = Settings.prefs(context)

        if (!prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)) {
            prefs.edit()
                .putInt(
                    Settings.KEY_AUTOROTATE_BACKUP,
                    AndroidSettings.System.getInt(
                        resolver,
                        AndroidSettings.System.ACCELEROMETER_ROTATION,
                        1,
                    ),
                )
                .apply()
        }

        val current = try {
            AndroidSettings.System.getInt(resolver, USER_ROTATION, Surface.ROTATION_0)
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
        val landscape = current == Surface.ROTATION_90 || current == Surface.ROTATION_270
        val next = if (landscape) Surface.ROTATION_0 else Surface.ROTATION_90

        return try {
            AndroidSettings.System.putInt(
                resolver,
                AndroidSettings.System.ACCELEROMETER_ROTATION,
                0,
            )
            AndroidSettings.System.putInt(resolver, USER_ROTATION, next)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not rotate the display", t)
            false
        }
    }

    /** Restores the auto-rotate setting saved by [lockLandscape]. No-op if we never locked. */
    fun unlock(context: Context) {
        if (!isAllowed(context)) return
        val prefs = Settings.prefs(context)
        if (!prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)) return

        val previous = prefs.getInt(Settings.KEY_AUTOROTATE_BACKUP, 1)
        prefs.edit().remove(Settings.KEY_AUTOROTATE_BACKUP).apply()

        try {
            AndroidSettings.System.putInt(
                context.contentResolver,
                AndroidSettings.System.ACCELEROMETER_ROTATION,
                previous,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not restore auto-rotate", t)
        }
    }
}
