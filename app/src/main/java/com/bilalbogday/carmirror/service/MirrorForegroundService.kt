package com.bilalbogday.carmirror.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.bilalbogday.carmirror.MainActivity
import com.bilalbogday.carmirror.MirrorState
import com.bilalbogday.carmirror.MirrorUiState
import com.bilalbogday.carmirror.R
import com.bilalbogday.carmirror.gl.MirrorGlEngine
import com.bilalbogday.carmirror.overlay.ScreenControlOverlay
import com.bilalbogday.carmirror.web.LocalWebMirrorServer
import kotlin.math.max
import kotlin.math.roundToInt

class MirrorForegroundService : Service() {

    companion object {
        private const val TAG = "MirrorFgService"
        private const val CHANNEL_ID = "carmirror_projection_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.bilalbogday.carmirror.action.START"
        const val ACTION_STOP = "com.bilalbogday.carmirror.action.STOP"
        const val ACTION_CYCLE_SCALE = "com.bilalbogday.carmirror.action.CYCLE_SCALE"
        const val ACTION_TOGGLE_DIM = "com.bilalbogday.carmirror.action.TOGGLE_DIM"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var glEngine: MirrorGlEngine? = null
    private var screenOverlay: ScreenControlOverlay? = null
    private var webServer: LocalWebMirrorServer? = null
    private var displayManager: DisplayManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lastCaptureW: Int = 0
    private var lastCaptureH: Int = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && MirrorState.state.value.isMirroring) {
                scheduleCaptureResize()
            }
        }
    }

    private val resizeRunnable = Runnable {
        if (!MirrorState.state.value.isMirroring) return@Runnable
        try {
            val (newW, newH, dpi) = getOptimalCaptureSize()
            if (newW != lastCaptureW || newH != lastCaptureH) {
                lastCaptureW = newW
                lastCaptureH = newH
                glEngine?.updateSourceSize(newW, newH)
                virtualDisplay?.resize(newW, newH, dpi)
                MirrorState.updateSourceSize(newW, newH)
                Log.i(TAG, "Resized capture for display rotation: ${newW}x${newH}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resize capture on display change", t)
        }
    }

    private val stateListener: (MirrorUiState) -> Unit = { state ->
        screenOverlay?.applyState(
            isMirroring = state.isMirroring,
            autoLandscape = state.autoLandscape,
            dimPhoneScreen = state.dimPhoneScreen
        )
        glEngine?.requestRedraw()
        if (state.isMirroring) {
            updateNotification(state)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopMirroringInternal()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        screenOverlay = ScreenControlOverlay(applicationContext)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        MirrorState.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startMirroring(resultCode, resultData)
                } else {
                    MirrorState.setError("Ekran yakalama verisi alınamadı.")
                    stopSelf()
                }
            }
            ACTION_CYCLE_SCALE -> {
                MirrorState.cycleScaleMode()
            }
            ACTION_TOGGLE_DIM -> {
                MirrorState.toggleDimPhoneScreen()
            }
            ACTION_STOP -> {
                stopMirroringInternal()
            }
        }
        return START_NOT_STICKY
    }

    private fun startMirroring(resultCode: Int, resultData: Intent) {
        MirrorState.setError(null)
        // 1. MUST startForeground with MEDIA_PROJECTION type before getMediaProjection() on Android 10-16
        try {
            val initialNotif = buildNotification(MirrorState.state.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotif)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            MirrorState.setError("Ön plan servisi başlatılamadı: ${t.message}")
            stopSelf()
            return
        }

        cleanupCaptureResources()
        acquireLocks()

        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "getMediaProjection returned null")
                MirrorState.setError("MediaProjection izni başlatılamadı.")
                stopMirroringInternal()
                return
            }

            // 2. MUST register callback before createVirtualDisplay() on Android 14+ (API 34+)
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            // 3. Start local zero-cost Wi-Fi fallback server
            val server = LocalWebMirrorServer(port = 8080) { clientCount ->
                MirrorState.updateWebClients(clientCount)
                glEngine?.setWebClientsActive(clientCount > 0)
            }
            val localUrl = server.start()
            webServer = server

            // 4. Calculate optimal capture dimensions matching current phone screen aspect ratio
            val (captureW, captureH, dpi) = getOptimalCaptureSize()
            lastCaptureW = captureW
            lastCaptureH = captureH

            val engine = MirrorGlEngine(
                initialSourceWidth = captureW,
                initialSourceHeight = captureH,
                onJpegFrameAvailable = { jpeg ->
                    webServer?.pushJpegFrame(jpeg)
                }
            )
            glEngine = engine

            engine.start { inputSurface ->
                mainHandler.post {
                    try {
                        val activeProj = mediaProjection ?: return@post
                        virtualDisplay = activeProj.createVirtualDisplay(
                            "CarMirrorAutoVirtualDisplay",
                            captureW,
                            captureH,
                            dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            inputSurface,
                            null,
                            mainHandler
                        )
                        MirrorState.updateMirroring(
                            active = true,
                            localWebUrl = localUrl,
                            sourceWidth = captureW,
                            sourceHeight = captureH
                        )
                        Log.i(TAG, "VirtualDisplay started: ${captureW}x${captureH} @ ${dpi}dpi")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to createVirtualDisplay", t)
                        MirrorState.setError("Sanal ekran oluşturulamadı: ${t.message}")
                        stopMirroringInternal()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start mirroring", t)
            MirrorState.setError("Yansıtma başlatma hatası: ${t.message}")
            stopMirroringInternal()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (MirrorState.state.value.isMirroring) {
            scheduleCaptureResize()
        }
    }

    private fun scheduleCaptureResize() {
        mainHandler.removeCallbacks(resizeRunnable)
        mainHandler.postDelayed(resizeRunnable, 100L)
    }

    private fun getOptimalCaptureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rawW: Int
        val rawH: Int
        val dpi: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            rawW = max(2, bounds.width())
            rawH = max(2, bounds.height())
            dpi = max(120, resources.configuration.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            rawW = max(2, metrics.widthPixels)
            rawH = max(2, metrics.heightPixels)
            dpi = max(120, metrics.densityDpi)
        }

        val maxEdge = max(rawW, rawH)
        val scale = if (maxEdge > 1280) 1280f / maxEdge.toFloat() else 1.0f

        val alignedW = max(64, ((rawW * scale).roundToInt() / 2) * 2)
        val alignedH = max(64, ((rawH * scale).roundToInt() / 2) * 2)
        return Triple(alignedW, alignedH, dpi)
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "CarMirrorAuto::MirrorWakeLock"
            )
            wl.setReferenceCounted(false)
            wl.acquire(6 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (_: Throwable) {
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wLock = wm.createWifiLock(lockMode, "CarMirrorAuto::LowLatencyWifiLock")
            wLock.setReferenceCounted(false)
            wLock.acquire()
            wifiLock = wLock
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    private fun cleanupCaptureResources() {
        mainHandler.removeCallbacks(resizeRunnable)
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null

        try {
            glEngine?.stop()
        } catch (_: Throwable) {
        }
        glEngine = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        try {
            webServer?.stop()
        } catch (_: Throwable) {
        }
        webServer = null
        lastCaptureW = 0
        lastCaptureH = 0
    }

    private fun stopMirroringInternal() {
        MirrorState.updateMirroring(active = false, localWebUrl = null)
        screenOverlay?.release()
        cleanupCaptureResources()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        MirrorState.removeListener(stateListener)
        screenOverlay?.release()
        cleanupCaptureResources()
        releaseLocks()
        MirrorState.updateMirroring(active = false, localWebUrl = null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Araç ekranı yansıtma servisi bildirimi"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: MirrorUiState) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(state: MirrorUiState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val scaleIntent = Intent(this, MirrorForegroundService::class.java).apply {
            action = ACTION_CYCLE_SCALE
        }
        val scalePending = PendingIntent.getService(
            this,
            1,
            scaleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dimIntent = Intent(this, MirrorForegroundService::class.java).apply {
            action = ACTION_TOGGLE_DIM
        }
        val dimPending = PendingIntent.getService(
            this,
            2,
            dimIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MirrorForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val carText = when {
            state.isCarSurfaceConnected -> "Araç Ekranı Bağlı (${state.carWidth}x${state.carHeight})"
            state.webClientsCount > 0 -> "Wi-Fi Tarayıcı Bağlı (${state.webClientsCount} ekran)"
            else -> "Yansıtma aktif (${state.sourceWidth}x${state.sourceHeight})"
        }

        val dimLabel = if (state.dimPhoneScreen) "Ekranı Aç" else "Ekranı Karart"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car_mirror)
            .setContentTitle("CarMirror Auto: ${state.scaleMode.shortCarLabel}")
            .setContentText(carText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "Görünüm: ${state.scaleMode.shortCarLabel}", scalePending)
            .addAction(0, dimLabel, dimPending)
            .addAction(0, "Durdur", stopPending)
            .build()
    }
}
