package com.bilalbogday.carmirror.car

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDestroyed = false
    private var lastIsMirroring = MirrorState.state.value.isMirroring
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
            // Screen.invalidate() MUST always be called on the main thread
            mainHandler.post {
                if (!isDestroyed) {
                    try {
                        invalidate()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                isDestroyed = false
                MirrorState.addListener(stateListener)
                try {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@CarMirrorScreen)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register SurfaceCallback", t)
                }
                try {
                    carContext.getCarService(NavigationManager::class.java)
                        .setNavigationManagerCallback(object : NavigationManagerCallback {
                            override fun onStopNavigation() {
                                // Keep mirroring surface active
                            }
                        })
                } catch (t: Throwable) {
                    Log.w(TAG, "NavigationManager callback registration skipped", t)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                isDestroyed = true
                MirrorState.removeListener(stateListener)
                CarSurfaceRegistry.onCarSurfaceDestroyed()
                try {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                } catch (_: Throwable) {
                }
                try {
                    carContext.getCarService(NavigationManager::class.java).clearNavigationManagerCallback()
                } catch (_: Throwable) {
                }
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
        val scaleIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_scale_mode)
        ).build()
        val dimIcon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, R.drawable.ic_dim_mode)
        ).build()

        val scaleAction = Action.Builder()
            .setIcon(scaleIcon)
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

        val dimIconAction = Action.Builder()
            .setIcon(dimIcon)
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
            .addAction(scaleAction)
            .addAction(dimIconAction)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}
