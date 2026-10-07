package com.example.mycar

import android.content.Context

/** Small, app-wide user settings. */
object Settings {

    const val PREFS_NAME = "settings"
    const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    const val KEY_LOCK_LANDSCAPE = "lockLandscape"
    const val KEY_TOUCH_CONTROL = "touchControl"
    const val KEY_AUTO_START = "autoStart"
    const val KEY_START_APP_PACKAGE = "startAppPackage"
    const val KEY_START_APP_LABEL = "startAppLabel"

    /** Remembers the user's auto-rotate setting while we force landscape. */
    const val KEY_AUTOROTATE_BACKUP = "autoRotateBackup"

    /**
     * Whether the phone screen should stay awake.
     *
     * Enforced in two places, because either alone is not enough:
     * - `MainActivity` sets `FLAG_KEEP_SCREEN_ON` while its UI is visible.
     * - `ScreenMirrorService` holds a screen wake lock while mirroring, which is the case that
     *   actually matters here: once the car shows the mirror the phone is usually put down and
     *   its screen would otherwise sleep.
     */
    fun keepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    /**
     * Whether the phone should be forced into landscape while mirroring. The mirror is shown in
     * a landscape car area, so a portrait phone only wastes space (and auto-rotate would flip it
     * back as soon as the mount moves).
     */
    fun lockLandscape(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_LANDSCAPE, false)

    fun setLockLandscape(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_LANDSCAPE, enabled).apply()
    }

    /**
     * Whether taps on the car touchscreen are forwarded to the phone. Requires the app's
     * accessibility service to be enabled; this flag only says the user wants it.
     */
    fun touchControl(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TOUCH_CONTROL, false)

    fun setTouchControl(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TOUCH_CONTROL, enabled).apply()
    }

    /**
     * Whether mirroring should start by itself the moment Android Auto connects. On by default:
     * the whole point of the app is to be hands-free. Requires "Display over other apps" so the
     * watcher can bring the consent screen up from the background, and the accessibility service
     * so that consent can be accepted without a tap.
     */
    fun autoStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    /**
     * Package of the app to open once mirroring starts, or blank for none. A label is stored
     * alongside it purely so the settings button can show what is selected.
     */
    fun startAppPackage(context: Context): String =
        prefs(context).getString(KEY_START_APP_PACKAGE, "").orEmpty()

    fun startAppLabel(context: Context): String =
        prefs(context).getString(KEY_START_APP_LABEL, "").orEmpty()

    fun setStartApp(context: Context, packageName: String, label: String) {
        prefs(context).edit()
            .putString(KEY_START_APP_PACKAGE, packageName)
            .putString(KEY_START_APP_LABEL, label)
            .apply()
    }

    /** The prefs instance, so the mirror service can listen for changes live. */
    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
