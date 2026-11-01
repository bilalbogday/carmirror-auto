package com.bilalbogday.carmirror.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class CarMirrorCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return CarMirrorSession()
    }

    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        return CarMirrorSession()
    }
}

class CarMirrorSession : Session() {
    override fun onCreateScreen(intent: Intent) = CarMirrorScreen(carContext)
}
