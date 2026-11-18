package com.example.mycar

import android.content.ContentResolver
import android.provider.Settings as AndroidSettings
import android.view.Surface
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
 * The car launcher's rotate button only turns the screen; "Lock landscape" decides whether the
 * orientation is held. With the lock off, a press must hand auto-rotate (and the previous
 * orientation) back on its own. With the lock on, the hold stays for the whole session.
 *
 * Runs only where "Modify system settings" has been granted (skip otherwise) and always restores
 * the device's values afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RotationLockTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var resolver: ContentResolver
    private var savedAutoRotate = 1
    private var savedUserRotation = 0

    @Before
    fun setUp() {
        assumeTrue(
            "Needs the Modify system settings special access",
            AndroidSettings.System.canWrite(context),
        )
        resolver = context.contentResolver
        savedAutoRotate = AndroidSettings.System.getInt(
            resolver,
            AndroidSettings.System.ACCELEROMETER_ROTATION,
            1,
        )
        savedUserRotation = AndroidSettings.System.getInt(resolver, USER_ROTATION, 0)
        clearHold()
    }

    @After
    fun tearDown() {
        if (!AndroidSettings.System.canWrite(context)) return
        AndroidSettings.System.putInt(
            resolver,
            AndroidSettings.System.ACCELEROMETER_ROTATION,
            savedAutoRotate,
        )
        AndroidSettings.System.putInt(resolver, USER_ROTATION, savedUserRotation)
        clearHold()
    }

    @Test
    fun lockOffHandsAutoRotateBackAfterTheTurn() {
        Settings.setLockLandscape(context, false)
        AndroidSettings.System.putInt(resolver, AndroidSettings.System.ACCELEROMETER_ROTATION, 1)
        AndroidSettings.System.putInt(resolver, USER_ROTATION, Surface.ROTATION_0)

        assertTrue(RotationLock.togglePortraitLandscape(context))
        assertEquals(0, autoRotate())
        assertTrue(hasHold())

        // The one-shot hold is short: auto-rotate must come back on its own.
        Thread.sleep(1200)
        assertEquals(1, autoRotate())
        assertEquals(Surface.ROTATION_0, userRotation())
        assertFalse(hasHold())
    }

    @Test
    fun theUsersOwnAutoRotateOffIsLeftAlone() {
        Settings.setLockLandscape(context, false)
        AndroidSettings.System.putInt(resolver, AndroidSettings.System.ACCELEROMETER_ROTATION, 0)
        AndroidSettings.System.putInt(resolver, USER_ROTATION, Surface.ROTATION_0)

        assertTrue(RotationLock.togglePortraitLandscape(context))

        assertEquals(0, autoRotate())
        assertEquals(Surface.ROTATION_90, userRotation())
        assertFalse(hasHold())
    }

    @Test
    fun landscapeLockKeepsTheHoldAcrossPresses() {
        Settings.setLockLandscape(context, true)
        AndroidSettings.System.putInt(resolver, AndroidSettings.System.ACCELEROMETER_ROTATION, 1)

        assertTrue(RotationLock.togglePortraitLandscape(context))
        assertTrue(RotationLock.togglePortraitLandscape(context))

        assertEquals(0, autoRotate())
        assertTrue(hasHold())
    }

    private fun autoRotate(): Int = AndroidSettings.System.getInt(
        resolver,
        AndroidSettings.System.ACCELEROMETER_ROTATION,
        1,
    )

    private fun userRotation(): Int = AndroidSettings.System.getInt(
        resolver,
        USER_ROTATION,
        Surface.ROTATION_0,
    )

    private fun hasHold(): Boolean =
        Settings.prefs(context).contains(Settings.KEY_AUTOROTATE_BACKUP)

    private fun clearHold() {
        Settings.setLockLandscape(context, false)
        Settings.prefs(context).edit()
            .remove(Settings.KEY_AUTOROTATE_BACKUP)
            .remove(Settings.KEY_USER_ROTATION_BACKUP)
            .apply()
    }

    private companion object {
        const val USER_ROTATION = "user_rotation"
    }
}
