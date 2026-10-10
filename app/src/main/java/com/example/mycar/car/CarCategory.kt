package com.example.mycar.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Which Android Auto category MyCar registers under.
 *
 * The category is a manifest intent-filter, so it cannot be changed at runtime. Instead there are
 * two `CarAppService` components, one per category ([MirrorCarAppService] for navigation and
 * [MirrorWeatherCarAppService] for weather), and [register] enables exactly one of them with
 * [PackageManager.setComponentEnabledSetting]. Android Auto sees only the enabled one when it next
 * scans for car apps, which is why a change takes effect on reconnect.
 */
enum class CarCategory {
    /** Listed in the navigation launcher; can be auto-opened as the default navigation app. */
    NAVIGATION,

    /** Listed as a weather app, leaving the car's single navigation slot to Maps/Waze. */
    WEATHER;

    companion object {
        /** The stored value, or [WEATHER] when it is missing or unknown. */
        fun fromStored(value: String?): CarCategory =
            entries.firstOrNull { it.name == value } ?: WEATHER

        /** Enables the service for [category] and disables the other one. Idempotent. */
        fun register(context: Context, category: CarCategory) {
            setEnabled(context, MirrorCarAppService::class.java, category == NAVIGATION)
            setEnabled(context, MirrorWeatherCarAppService::class.java, category == WEATHER)
        }
    }
}

private fun setEnabled(context: Context, service: Class<*>, enabled: Boolean) {
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    try {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, service),
            state,
            PackageManager.DONT_KILL_APP,
        )
    } catch (t: Throwable) {
        val verb = if (enabled) "enable" else "disable"
        Log.w(TAG, "Could not $verb ${service.simpleName}", t)
    }
}

private const val TAG = "CarCategory"
