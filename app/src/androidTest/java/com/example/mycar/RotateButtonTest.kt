package com.example.mycar

import android.graphics.SurfaceTexture
import android.provider.Settings as AndroidSettings
import android.view.Surface
import androidx.car.app.SurfaceContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycar.car.MirrorSurfaceCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real car-strip rotate cell (not [RotationLock] directly) the way the host does, and
 * checks the phone's system rotation settings afterwards. This is the closest reproduction of
 * tapping the button on a head unit without a car.
 *
 * Needs "Modify system settings" (skipped otherwise) and always restores the device values.
 */
@RunWith(AndroidJUnit4::class)
class RotateButtonTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedAutoRotate = 1
    private var savedUserRotation = 0

    @Before
    fun setUp() {
        assumeTrue(
            "Needs the Modify system settings special access",
            AndroidSettings.System.canWrite(context),
        )
        savedAutoRotate = autoRotate()
        savedUserRotation = userRotation()
        Settings.setLockLandscape(context, false)
        Settings.setQuickLaunch(context, true)
        clearHold()
    }

    @After
    fun tearDown() {
        if (!AndroidSettings.System.canWrite(context)) return
        AndroidSettings.System.putInt(
            context.contentResolver,
            AndroidSettings.System.ACCELEROMETER_ROTATION,
            savedAutoRotate,
        )
        AndroidSettings.System.putInt(context.contentResolver, USER_ROTATION, savedUserRotation)
        Settings.setLockLandscape(context, false)
        clearHold()
    }

    @Test
    fun rotateCellForcesThenReleasesThePhoneOrientation() {
        AndroidSettings.System.putInt(
            context.contentResolver,
            AndroidSettings.System.ACCELEROMETER_ROTATION,
            1,
        )
        AndroidSettings.System.putInt(context.contentResolver, USER_ROTATION, Surface.ROTATION_0)

        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
        val surface = Surface(texture)
        val callback = MirrorSurfaceCallback(context)
        try {
            callback.onSurfaceAvailable(SurfaceContainer(surface, WIDTH, HEIGHT, 160))

            // The strip is a right-hand column; rotate is its bottom-most cell. One tap is all it
            // takes: it turns the screen, and with "Lock landscape" off auto-rotate comes back.
            callback.onClick(WIDTH - THICKNESS / 2f, HEIGHT - THICKNESS / 2f)
            assertEquals(0, autoRotate())
            assertEquals(Surface.ROTATION_90, userRotation())

            Thread.sleep(1200)
            assertEquals(1, autoRotate())
            assertEquals(Surface.ROTATION_0, userRotation())
        } finally {
            callback.stop()
            surface.release()
            texture.release()
        }
    }

    private fun autoRotate(): Int = AndroidSettings.System.getInt(
        context.contentResolver,
        AndroidSettings.System.ACCELEROMETER_ROTATION,
        1,
    )

    private fun userRotation(): Int = AndroidSettings.System.getInt(
        context.contentResolver,
        USER_ROTATION,
        Surface.ROTATION_0,
    )

    private fun clearHold() {
        Settings.prefs(context).edit()
            .remove(Settings.KEY_AUTOROTATE_BACKUP)
            .remove(Settings.KEY_USER_ROTATION_BACKUP)
            .apply()
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        /** Matches MirrorSurfaceCallback.LAUNCHER_THICKNESS_DP at mdpi (dpi 160). */
        const val THICKNESS = 78f

        const val USER_ROTATION = "user_rotation"
    }
}
