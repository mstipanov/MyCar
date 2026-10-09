package com.example.mycar.launch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log

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
 * The list is deliberately hard-coded for now; making it editable and reorderable is the natural
 * next step.
 */
object QuickLaunch {

    private const val TAG = "QuickLaunch"

    /** Package names, in the order they appear in the menu. */
    private val PACKAGES = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.youtube",
    )

    /** Decoded icon edge, in pixels. The drawer scales it to its row height when drawing. */
    private const val ICON_PX = 128

    /** Icons and labels are resolved once per process and reused for every frame. */
    private val cache = HashMap<String, LaunchTarget>()

    /** The installed apps among [PACKAGES], with labels and icons. Cheap after the first call. */
    fun targets(context: Context): List<LaunchTarget> =
        PACKAGES.mapNotNull { pkg ->
            cache[pkg] ?: resolve(context, pkg)?.also { cache[pkg] = it }
        }

    /** Opens the app on the phone. False when it has no launcher entry or the launch was blocked. */
    fun open(context: Context, packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Log.w(TAG, "$packageName has no launcher entry")
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
