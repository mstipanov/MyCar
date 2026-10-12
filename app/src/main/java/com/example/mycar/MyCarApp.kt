package com.example.mycar

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.mycar.audio.AnnouncementPauser

/**
 * Applies the saved theme before any activity is created, so the phone UI opens in the chosen
 * mode instead of flashing the wrong one. Changing the setting in the UI calls
 * [AppCompatDelegate.setDefaultNightMode] again, which recreates the running activity.
 *
 * Also starts the announcement watcher as soon as the process is up (when the setting is on), so
 * it does not depend on an activity being opened.
 */
class MyCarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings.themeMode(this).delegateMode)
        AnnouncementPauser.start(this)
    }
}
