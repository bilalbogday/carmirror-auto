package com.bilalbogday.carmirror.overlay

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Hardware Backlight Dimmer & Auto-Landscape Controller.
 * Uses a 1x1 transparent TYPE_APPLICATION_OVERLAY window so:
 * 1) Phone screen brightness is lowered at the hardware panel level (screenBrightness = 0.001f)
 *    WITHOUT darkening the SurfaceFlinger framebuffer captured by MediaProjection!
 * 2) Forces system-wide Landscape orientation (SCREEN_ORIENTATION_SENSOR_LANDSCAPE) so
 *    all phone apps fill the wide car display naturally.
 */
class ScreenControlOverlay(private val context: Context) {

    companion object {
        private const val TAG = "ScreenControlOverlay"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun applyState(
        isMirroring: Boolean,
        autoLandscape: Boolean,
        dimPhoneScreen: Boolean
    ) {
        mainHandler.post {
            if (!isMirroring || !Settings.canDrawOverlays(context)) {
                removeOverlayInternal()
                return@post
            }

            try {
                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_Not_TOUCHABLE_SAFE() or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                val orientation = if (autoLandscape) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }

                val brightness = if (dimPhoneScreen) {
                    0.001f // Minimum hardware panel backlight while keeping VirtualDisplay 100% bright
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }

                if (overlayView == null) {
                    val view = View(context).apply {
                        alpha = 0f
                    }
                    val params = WindowManager.LayoutParams(
                        1,
                        1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        flags,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 0
                        y = 0
                        screenOrientation = orientation
                        screenBrightness = brightness
                    }
                    windowManager.addView(view, params)
                    overlayView = view
                    layoutParams = params
                } else {
                    val params = layoutParams ?: return@post
                    params.screenOrientation = orientation
                    params.screenBrightness = brightness
                    windowManager.updateViewLayout(overlayView, params)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update ScreenControlOverlay", t)
            }
        }
    }

    fun release() {
        mainHandler.post {
            removeOverlayInternal()
        }
    }

    private fun removeOverlayInternal() {
        val view = overlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
        overlayView = null
        layoutParams = null
    }

    private fun Int.FLAG_Not_TOUCHABLE_SAFE(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
