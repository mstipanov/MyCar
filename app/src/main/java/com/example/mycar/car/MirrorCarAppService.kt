package com.example.mycar.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Entry point Android Auto binds to. The `NAVIGATION` category on this service's intent filter is
 * what makes the app appear in the car's navigation launcher.
 */
class MirrorCarAppService : CarAppService() {

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
