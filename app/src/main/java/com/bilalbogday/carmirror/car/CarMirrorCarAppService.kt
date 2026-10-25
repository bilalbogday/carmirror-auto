package com.bilalbogday.carmirror.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CarMirrorCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allow all Android Auto head units and versions unconditionally
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return CarMirrorSession()
    }
}

class CarMirrorSession : Session() {
    override fun onCreateScreen(intent: Intent) = CarMirrorScreen(carContext)
}
