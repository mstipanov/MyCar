package com.example.mycar.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * One Android Auto session. Registering the surface callback here is what makes the host
 * hand over the raw map Surface.
 *
 * [Session] is no-arg in the released Car App Library; the [CarContext] only becomes
 * available once the host has attached this session.
 */
class MirrorSession : Session() {

    private var surfaceCallback: MirrorSurfaceCallback? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val callback = MirrorSurfaceCallback(carContext)
        surfaceCallback = callback
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
        return MirrorScreen(carContext)
    }

    /** Called from [MirrorCarAppService.onDestroy] to make sure the render loop is dead. */
    fun release() {
        surfaceCallback?.stop()
        surfaceCallback = null
    }
}
