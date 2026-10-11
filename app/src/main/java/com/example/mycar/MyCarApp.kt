package com.example.mycar

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Applies the saved theme before any activity is created, so the phone UI opens in the chosen
 * mode instead of flashing the wrong one. Changing the setting in the UI calls
 * [AppCompatDelegate.setDefaultNightMode] again, which recreates the running activity.
 */
class MyCarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings.themeMode(this).delegateMode)
    }
}
