package com.example.mycar.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * The screen Android Auto shows. [NavigationTemplate] gives the host's map area the whole
 * screen, and [MirrorSurfaceCallback] is what fills it.
 *
 * The app is declared as a **weather** app (see the service intent filter) so it does not take
 * the car's single navigation slot and Google Maps/Waze can keep navigating; it declares the
 * `NAVIGATION_TEMPLATES` permission because that is what gates [NavigationTemplate].
 * `MapWithContentTemplate` (the weather/POI alternative) always splits the screen with a
 * mandatory content pane, which cost roughly half the mirror.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            // NavigationTemplate refuses to build without a non-empty action strip, but we want
            // no car-side buttons: touches on the mirror drive the phone directly, and a button
            // would sit over the mirror and swallow those taps.
            //
            // Action.PAN is the one action Android Auto drops from the map actions strip unless
            // the app registered a pan-mode delegate (setPanModeListener), which we never do. It
            // satisfies the builder while rendering nothing at all.
            .setActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
}
