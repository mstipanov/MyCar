package com.example.mycar.launch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import com.example.mycar.Settings
import com.example.mycar.touch.TouchInjectorService

/** One app offered by the car's quick launch menu. */
data class LaunchTarget(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
)

/**
 * The fixed short list of apps the car's quick launch menu offers, and the single place that
 * starts them.
 *
 * The menu is drawn on the car surface by [com.example.mycar.car.MirrorSurfaceCallback]. The car
 * app runs inside this app's own process, so "launch" is an ordinary [Context.startActivity] on
 * the phone: the chosen app opens on the phone and the mirror shows it. That background launch
 * needs "Display over other apps", the same permission auto-start already relies on.
 *
 * The apps shown are user-managed (`Settings.launcherApps`); when that has never been set,
 * [DEFAULT_PACKAGES] is used. Only the installed ones are offered.
 */
object QuickLaunch {

    private const val TAG = "QuickLaunch"

    /** Package names shown when the user has not chosen their own launcher apps. */
    private val DEFAULT_PACKAGES = Settings.DEFAULT_LAUNCHER_APPS

    /** Decoded icon edge, in pixels. The drawer scales it to its row height when drawing. */
    private const val ICON_PX = 128

    /** Icons and labels are resolved once per process and reused for every frame. */
    private val cache = HashMap<String, LaunchTarget>()

    /** The installed apps among the user's chosen launcher apps (or the defaults). Cheap after
     * the first call. */
    fun targets(context: Context): List<LaunchTarget> {
        val packages = Settings.launcherApps(context) ?: DEFAULT_PACKAGES
        return packages.mapNotNull { pkg ->
            cache[pkg] ?: resolve(context, pkg)?.also { cache[pkg] = it }
        }
    }

    /**
     * Opens the app on the phone. False when it has no launcher entry or the launch was blocked.
     *
     * A launch from the background is only allowed because MyCar holds "Display over other apps"
     * (the SYSTEM_ALERT_WINDOW background-activity-start exemption), or because its accessibility
     * service is bound. Without one of those, Android silently drops the launch — no exception —
     * so this also logs why when the overlay permission is missing.
     */
    fun open(context: Context, packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            Log.w(TAG, "$packageName has no launcher entry")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // Try the accessibility service first: when it is enabled its process may be allowed to
        // start activities from the background even without the overlay permission.
        if (TouchInjectorService.launch(launch)) {
            Log.i(TAG, "Opened $packageName through the accessibility service")
            return true
        }

        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(
                TAG,
                "Opening $packageName may be blocked: grant \"Display over other apps\" to " +
                    "MyCar, or enable the MyCar accessibility service",
            )
        }
        return runCatching {
            context.startActivity(launch)
        }.onFailure {
            Log.w(TAG, "Could not open $packageName", it)
        }.isSuccess
    }

    /** Label + icon for one package, or null when it is not installed or not visible to us. */
    private fun resolve(context: Context, packageName: String): LaunchTarget? = runCatching {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return null
        val label = launch.resolveActivityInfo(pm, 0)?.loadLabel(pm)?.toString() ?: packageName
        LaunchTarget(packageName, label, rasterize(pm.getApplicationIcon(packageName)))
    }.getOrNull()

    private fun rasterize(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        return bitmap
    }
}
