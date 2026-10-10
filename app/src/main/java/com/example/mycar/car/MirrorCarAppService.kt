package com.example.mycar.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Entry point Android Auto binds to. Its intent filter — declared in the manifest — carries the
 * category Android Auto classifies the app under. [MirrorWeatherCarAppService] is the weather
 * twin; [CarCategory.register] enables exactly one of the two, since the category is a manifest
 * declaration and cannot change at runtime.
 */
open class MirrorCarAppService : CarAppService() {

    private var session: MirrorSession? = null

    /**
     * Normally a Car App is built and signed by a host that Android Auto already trusts. A
     * sideloaded build has no such trust, and Android Auto only runs apps that came from a
     * trusted store, so this accepts any host. It is also why this app can never be shipped
     * through Google Play.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        MirrorSession().also { session = it }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
