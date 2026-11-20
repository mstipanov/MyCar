package com.example.mycar

import android.provider.Settings as AndroidSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dimming option must lower the screen brightness while Android Auto is connected, bring it
 * back when the user touches the phone (then dim again after the timeout), and restore the user's
 * brightness and mode on disconnect.
 *
 * Needs "Modify system settings" (skipped otherwise) and always restores the device values.
 */
@RunWith(AndroidJUnit4::class)
class ScreenDimmerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedBrightness = 128
    private var savedMode = AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    private var savedTimeout = 10_000L

    @Before
    fun setUp() {
        assumeTrue(
            "Needs the Modify system settings special access",
            WriteSettings.isAllowed(context),
        )
        savedBrightness = brightness()
        savedMode = brightnessMode()
        savedTimeout = ScreenDimmer.dimTimeoutMs
        ScreenDimmer.dimTimeoutMs = 200L

        Settings.setDimScreen(context, false)
        ScreenDimmer.onDisconnected(context)
        clearBackup()

        AndroidSettings.System.putInt(
            context.contentResolver,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        AndroidSettings.System.putInt(
            context.contentResolver,
            AndroidSettings.System.SCREEN_BRIGHTNESS,
            NORMAL_BRIGHTNESS,
        )
    }

    @After
    fun tearDown() {
        ScreenDimmer.dimTimeoutMs = savedTimeout
        Settings.setDimScreen(context, false)
        ScreenDimmer.onDisconnected(context)
        if (WriteSettings.isAllowed(context)) {
            AndroidSettings.System.putInt(
                context.contentResolver,
                AndroidSettings.System.SCREEN_BRIGHTNESS,
                savedBrightness,
            )
            AndroidSettings.System.putInt(
                context.contentResolver,
                AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
                savedMode,
            )
        }
        clearBackup()
    }

    @Test
    fun dimsWhileConnectedAndRestoresOnDisconnect() {
        Settings.setDimScreen(context, true)
        ScreenDimmer.onConnected(context)

        assertEquals(1, brightness())
        assertEquals(
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            brightnessMode(),
        )
        assertTrue(hasBackup())

        ScreenDimmer.onDisconnected(context)

        assertEquals(NORMAL_BRIGHTNESS, brightness())
        assertEquals(
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            brightnessMode(),
        )
        assertFalse(hasBackup())
    }

    @Test
    fun restoresTheUsersAutomaticBrightnessMode() {
        AndroidSettings.System.putInt(
            context.contentResolver,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        )
        Settings.setDimScreen(context, true)
        ScreenDimmer.onConnected(context)

        assertEquals(
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            brightnessMode(),
        )

        ScreenDimmer.onDisconnected(context)

        assertEquals(
            AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            brightnessMode(),
        )
        assertFalse(hasBackup())
    }

    @Test
    fun touchingWakesTheScreenAndTheTimeoutDimsAgain() {
        Settings.setDimScreen(context, true)
        ScreenDimmer.onConnected(context)
        assertEquals(1, brightness())

        ScreenDimmer.onUserTouch(context)
        assertEquals(NORMAL_BRIGHTNESS, brightness())
        assertTrue(hasBackup())

        Thread.sleep(700)
        assertEquals(1, brightness())

        ScreenDimmer.onDisconnected(context)
        assertEquals(NORMAL_BRIGHTNESS, brightness())
        assertFalse(hasBackup())
    }

    private fun brightness(): Int = AndroidSettings.System.getInt(
        context.contentResolver,
        AndroidSettings.System.SCREEN_BRIGHTNESS,
        NORMAL_BRIGHTNESS,
    )

    private fun brightnessMode(): Int = AndroidSettings.System.getInt(
        context.contentResolver,
        AndroidSettings.System.SCREEN_BRIGHTNESS_MODE,
        AndroidSettings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    )

    private fun hasBackup(): Boolean =
        Settings.prefs(context).contains(Settings.KEY_BRIGHTNESS_BACKUP)

    private fun clearBackup() {
        Settings.prefs(context).edit()
            .remove(Settings.KEY_BRIGHTNESS_BACKUP)
            .remove(Settings.KEY_BRIGHTNESS_MODE_BACKUP)
            .apply()
    }

    private companion object {
        const val NORMAL_BRIGHTNESS = 128
    }
}
