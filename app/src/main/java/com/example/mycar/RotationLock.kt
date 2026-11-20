package com.example.mycar

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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

    /** How long a one-shot rotation is forced before auto-rotate is handed back. */
    private const val ONE_SHOT_HOLD_MS = 700L

    private val handler = Handler(Looper.getMainLooper())

    /** Invalidates a pending one-shot restore when a newer action takes over. */
    private var oneShotGeneration = 0

    /** Whether the user has granted "Modify system settings". */
    fun isAllowed(context: Context): Boolean = WriteSettings.isAllowed(context)

    /** The Settings screen where the user grants that access. */
    fun permissionIntent(context: Context): Intent = WriteSettings.permissionIntent(context)

    /**
     * Turns auto-rotate off and rotates to landscape, remembering the previous auto-rotate value
     * and orientation so [unlock] can put both back. Returns false (and does nothing) without the
     * required access.
     */
    fun lockLandscape(context: Context): Boolean {
        if (!isAllowed(context)) return false
        oneShotGeneration++
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
     * The car launcher's rotate button: it only turns the screen. Whether that orientation stays
     * locked is decided by "Lock landscape", not by the button.
     *
     * - With "Lock landscape" on, the rotation is forced and held for the session (auto-rotate
     *   stays off until [unlock]).
     * - With it off, the button forces the new orientation just long enough for the display to
     *   take it, then hands auto-rotate back. If the user already keeps auto-rotate off
     *   themselves, it simply changes their orientation and leaves their setting alone.
     *
     * False (and nothing changes) without the required access.
     */
    fun togglePortraitLandscape(context: Context): Boolean {
        if (!isAllowed(context)) return false
        val prefs = Settings.prefs(context)
        val resolver = context.contentResolver
        val next = nextOrientation(context)

        if (Settings.lockLandscape(context)) {
            rememberRotation(context, resolver)
            oneShotGeneration++
            return setRotation(resolver, next)
        }

        // A backup means MyCar is (or was) holding the rotation. Its stored auto-rotate value is
        // the user's own: 1 means the rotation is only a one-shot and must be given back; 0 means
        // the user runs their rotation manually and a plain orientation change is all they want.
        val autoRotateBefore = if (prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)) {
            prefs.getInt(Settings.KEY_AUTOROTATE_BACKUP, 1)
        } else {
            AndroidSettings.System.getInt(
                resolver,
                AndroidSettings.System.ACCELEROMETER_ROTATION,
                1,
            )
        }

        if (autoRotateBefore == 0) {
            // Drop any hold left by the lock option, then just change the orientation.
            if (prefs.contains(Settings.KEY_AUTOROTATE_BACKUP)) unlock(context)
            return setUserRotation(resolver, next)
        }

        // Auto-rotate was on: force the new orientation long enough to be applied, then give
        // auto-rotate back. The backup keeps a killed process from leaving the phone pinned, and
        // the generation check means a newer press or `lockLandscape` cancels this restore.
        rememberRotation(context, resolver)
        if (!setRotation(resolver, next)) return false
        oneShotGeneration++
        val generation = oneShotGeneration
        handler.postDelayed({
            if (generation == oneShotGeneration && !Settings.lockLandscape(context)) {
                unlock(context)
            }
        }, ONE_SHOT_HOLD_MS)
        return true
    }

    /** The orientation a rotate press would switch to, based on what the display shows now. */
    private fun nextOrientation(context: Context): Int {
        val current = displayRotation(context)
        val landscape = current == Surface.ROTATION_90 || current == Surface.ROTATION_270
        return if (landscape) Surface.ROTATION_0 else Surface.ROTATION_90
    }

    /** Turns auto-rotate off and pins [next], which is what a held rotation needs. */
    private fun setRotation(resolver: ContentResolver, next: Int): Boolean = try {
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

    /** Changes only the orientation, leaving the user's auto-rotate setting untouched. */
    private fun setUserRotation(resolver: ContentResolver, next: Int): Boolean = try {
        AndroidSettings.System.putInt(resolver, USER_ROTATION, next)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Could not rotate the display", t)
        false
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
        oneShotGeneration++
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
