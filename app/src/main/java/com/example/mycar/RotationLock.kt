package com.example.mycar

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Display
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
     * and orientation so [unlock] can put both back. Returns false (and does nothing) without the
     * required access.
     */
    fun lockLandscape(context: Context): Boolean {
        if (!isAllowed(context)) return false
        val resolver = context.contentResolver
        rememberRotation(context, resolver)

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
     * The car launcher's rotate button.
     *
     * The first press forces the display to the opposite orientation, which requires turning
     * auto-rotate off so the phone does not immediately flip back; [unlock] restores the user's
     * own setting when mirroring stops. While "Lock landscape" is off, pressing the button again
     * *releases* that hold instead of pinning the other orientation, so the button never leaves
     * the phone's rotation locked behind the user's back. With "Lock landscape" on, every press
     * only flips between portrait and landscape, because the user asked for the lock to last the
     * whole session. False without the required access.
     */
    fun togglePortraitLandscape(context: Context): Boolean {
        if (!isAllowed(context)) return false
        val prefs = Settings.prefs(context)
        if (prefs.contains(Settings.KEY_AUTOROTATE_BACKUP) && !Settings.lockLandscape(context)) {
            unlock(context)
            return true
        }

        val resolver = context.contentResolver
        rememberRotation(context, resolver)

        // Base the toggle on the display's *actual* rotation, not `user_rotation`: with auto-rotate
        // on (the default, since "lock landscape" is off) `user_rotation` goes stale, so the first
        // tap would pick the orientation the phone already has and look like it did nothing.
        val current = displayRotation(context)
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

    /**
     * Saves the rotation settings once, before MyCar changes them. Both values matter: unlocking
     * only auto-rotate is not enough for a user who keeps auto-rotate off, because MyCar would
     * have left their `user_rotation` pointing at the landscape orientation it forced.
     */
    private fun rememberRotation(context: Context, resolver: ContentResolver) {
        val prefs = Settings.prefs(context)
        if (prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)) return
        prefs.edit()
            .putInt(
                Settings.KEY_AUTOROTATE_BACKUP,
                AndroidSettings.System.getInt(
                    resolver,
                    AndroidSettings.System.ACCELEROMETER_ROTATION,
                    1,
                ),
            )
            .putInt(
                Settings.KEY_USER_ROTATION_BACKUP,
                AndroidSettings.System.getInt(
                    resolver,
                    USER_ROTATION,
                    Surface.ROTATION_0,
                ),
            )
            .apply()
    }

    /**
     * The display's current rotation. Read live from the display because `user_rotation` is only
     * meaningful while auto-rotate is off; falls back to that setting if the display is unavailable.
     */
    private fun displayRotation(context: Context): Int {
        val actual = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.rotation
        }.getOrNull()
        if (actual != null) return actual
        return runCatching {
            AndroidSettings.System.getInt(
                context.contentResolver,
                USER_ROTATION,
                Surface.ROTATION_0,
            )
        }.getOrDefault(Surface.ROTATION_0)
    }

    /**
     * Restores the rotation settings saved by [lockLandscape] or [togglePortraitLandscape]:
     * both the auto-rotate mode and the user's own orientation. No-op if we never changed them.
     */
    fun unlock(context: Context) {
        if (!isAllowed(context)) return
        val prefs = Settings.prefs(context)
        val hadAutoRotate = prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)
        val hadUserRotation = prefs.contains(Settings.KEY_USER_ROTATION_BACKUP)
        if (!hadAutoRotate && !hadUserRotation) return

        val previousAutoRotate = prefs.getInt(Settings.KEY_AUTOROTATE_BACKUP, 1)
        val previousUserRotation = prefs.getInt(
            Settings.KEY_USER_ROTATION_BACKUP,
            Surface.ROTATION_0,
        )
        prefs.edit()
            .remove(Settings.KEY_AUTOROTATE_BACKUP)
            .remove(Settings.KEY_USER_ROTATION_BACKUP)
            .apply()

        val resolver = context.contentResolver
        // Restore the orientation before re-enabling auto-rotate: while auto-rotate is still off,
        // the display immediately follows this value, so the phone does not end up pinned to the
        // landscape orientation MyCar forced.
        if (hadUserRotation) {
            runCatching {
                AndroidSettings.System.putInt(resolver, USER_ROTATION, previousUserRotation)
            }.onFailure { Log.w(TAG, "Could not restore user rotation", it) }
        }
        if (hadAutoRotate) {
            runCatching {
                AndroidSettings.System.putInt(
                    resolver,
                    AndroidSettings.System.ACCELEROMETER_ROTATION,
                    previousAutoRotate,
                )
            }.onFailure { Log.w(TAG, "Could not restore auto-rotate", it) }
        }
    }
}
