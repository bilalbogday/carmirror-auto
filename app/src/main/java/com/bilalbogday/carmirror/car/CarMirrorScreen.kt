package com.bilalbogday.carmirror.car

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bilalbogday.carmirror.MainActivity
import com.bilalbogday.carmirror.MirrorState
import com.bilalbogday.carmirror.MirrorUiState
import com.bilalbogday.carmirror.R
import com.bilalbogday.carmirror.gl.CarSurfaceRegistry

class CarMirrorScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    companion object {
        private const val TAG = "CarMirrorScreen"
    }

    private var lastIsMirroring = false
    private var lastScaleMode = MirrorState.state.value.scaleMode
    private var lastDimMode = MirrorState.state.value.dimPhoneScreen

    private val stateListener: (MirrorUiState) -> Unit = { state ->
        if (state.isMirroring != lastIsMirroring ||
            state.scaleMode != lastScaleMode ||
            state.dimPhoneScreen != lastDimMode
        ) {
            lastIsMirroring = state.isMirroring
            lastScaleMode = state.scaleMode
            lastDimMode = state.dimPhoneScreen
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                MirrorState.addListener(stateListener)
                try {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@CarMirrorScreen)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register SurfaceCallback", t)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                MirrorState.removeListener(stateListener)
                CarSurfaceRegistry.onCarSurfaceDestroyed()
            }
        })
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        Log.i(TAG, "onSurfaceAvailable: ${width}x${height} dpi=${surfaceContainer.dpi}")
        if (surface != null && width > 0 && height > 0) {
            CarSurfaceRegistry.onCarSurfaceAvailable(surface, width, height)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "onSurfaceDestroyed")
        CarSurfaceRegistry.onCarSurfaceDestroyed()
    }

    override fun onGetTemplate(): Template {
        val state = MirrorState.state.value
        val icon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_car_mirror)
        ).build()

        // NavigationTemplate allows max 1 action with a custom text title in ActionStrip
        val primaryTitle = if (state.isMirroring) {
            state.scaleMode.shortCarLabel
        } else {
            "Telefondan Başlat"
        }

        val primaryAction = Action.Builder()
            .setTitle(primaryTitle)
            .setOnClickListener {
                if (!MirrorState.state.value.isMirroring) {
                    try {
                        val phoneIntent = Intent(carContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        carContext.startActivity(phoneIntent)
                    } catch (_: Throwable) {
                    }
                    CarToast.makeText(
                        carContext,
                        "Telefon ekranından 'Yansıtmayı Başlat' butonuna dokunun",
                        CarToast.LENGTH_LONG
                    ).show()
                } else {
                    val nextMode = MirrorState.cycleScaleMode()
                    CarToast.makeText(
                        carContext,
                        "Görünüm: ${nextMode.labelTr}",
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            }
            .build()

        // Second action uses icon-only to comply with NavigationTemplate ActionStrip constraints
        val dimIconAction = Action.Builder()
            .setIcon(icon)
            .setBackgroundColor(if (state.dimPhoneScreen) CarColor.GREEN else CarColor.DEFAULT)
            .setOnClickListener {
                val dimmed = MirrorState.toggleDimPhoneScreen()
                val msg = if (dimmed) {
                    "Telefon ekranı karartıldı (Cep Modu Aktif)"
                } else {
                    "Telefon ekran parlaklığı normale döndü"
                }
                CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(primaryAction)
            .addAction(dimIconAction)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}
