package com.bilalbogday.carmirror.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.bilalbogday.carmirror.MirrorState
import com.bilalbogday.carmirror.ScaleMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared registry so Android Auto CarAppService and MirrorForegroundService
 * can connect in ANY order (Phone first or Car first).
 */
object CarSurfaceRegistry {
    private var currentSurface: Surface? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var activeEngine: MirrorGlEngine? = null

    @Synchronized
    fun onCarSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        currentSurface = surface
        currentWidth = width
        currentHeight = height
        MirrorState.updateCarSurface(true, width, height)
        activeEngine?.attachCarSurface(surface, width, height)
    }

    @Synchronized
    fun onCarSurfaceDestroyed() {
        currentSurface = null
        currentWidth = 0
        currentHeight = 0
        MirrorState.updateCarSurface(false)
        activeEngine?.detachCarSurface()
    }

    @Synchronized
    fun registerEngine(engine: MirrorGlEngine) {
        activeEngine = engine
        val surface = currentSurface
        if (surface != null && surface.isValid && currentWidth > 0 && currentHeight > 0) {
            engine.attachCarSurface(surface, currentWidth, currentHeight)
        }
    }

    @Synchronized
    fun unregisterEngine(engine: MirrorGlEngine) {
        if (activeEngine === engine) {
            activeEngine = null
        }
    }
}

/**
 * Hardware-accelerated OpenGL ES 2.0 + EGL14 zero-latency pipeline.
 * - Renders MediaProjection VirtualDisplay (GL_TEXTURE_EXTERNAL_OES) directly onto
 *   Android Auto's SurfaceContainer surface in GPU memory (< 1ms).
 * - Independently supports right-side-up offscreen FBO capture for Local Wi-Fi Browser
 *   streaming even when Android Auto is not connected.
 */
class MirrorGlEngine(
    initialSourceWidth: Int,
    initialSourceHeight: Int,
    private val onJpegFrameAvailable: ((ByteArray) -> Unit)? = null
) : SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "MirrorGlEngine"

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        fun computeMvpMatrix(
            srcW: Int,
            srcH: Int,
            dstW: Int,
            dstH: Int,
            mode: ScaleMode,
            outMatrix: FloatArray,
            flipY: Boolean = false
        ) {
            Matrix.setIdentityM(outMatrix, 0)
            if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
                if (flipY) Matrix.scaleM(outMatrix, 0, 1.0f, -1.0f, 1.0f)
                return
            }

            if (flipY) {
                Matrix.scaleM(outMatrix, 0, 1.0f, -1.0f, 1.0f)
            }

            when (mode) {
                ScaleMode.FILL -> {
                    // Fill 100% of destination with zero black bars
                }
                ScaleMode.FIT -> {
                    val srcAspect = srcW.toFloat() / srcH.toFloat()
                    val dstAspect = dstW.toFloat() / dstH.toFloat()
                    if (srcAspect > dstAspect) {
                        val scaleY = dstAspect / srcAspect
                        Matrix.scaleM(outMatrix, 0, 1.0f, scaleY, 1.0f)
                    } else {
                        val scaleX = srcAspect / dstAspect
                        Matrix.scaleM(outMatrix, 0, scaleX, 1.0f, 1.0f)
                    }
                }
                ScaleMode.CROP -> {
                    val srcAspect = srcW.toFloat() / srcH.toFloat()
                    val dstAspect = dstW.toFloat() / dstH.toFloat()
                    if (srcAspect > dstAspect) {
                        val scaleX = srcAspect / dstAspect
                        Matrix.scaleM(outMatrix, 0, scaleX, 1.0f, 1.0f)
                    } else {
                        val scaleY = dstAspect / srcAspect
                        Matrix.scaleM(outMatrix, 0, 1.0f, scaleY, 1.0f)
                    }
                }
                ScaleMode.ROTATE_90 -> {
                    Matrix.rotateM(outMatrix, 0, -90f, 0f, 0f, 1f)
                }
            }
        }
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var carEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var programId: Int = 0
    private var aPositionLoc: Int = 0
    private var aTextureCoordLoc: Int = 0
    private var uMVPMatrixLoc: Int = 0
    private var uSTMatrixLoc: Int = 0
    private var uTextureLoc: Int = 0

    // Offscreen FBO resources for independent Wi-Fi browser streaming
    private var webFboId: Int = 0
    private var webColorTexId: Int = 0
    private var webFboWidth: Int = 0
    private var webFboHeight: Int = 0
    private var webPixelBuf: ByteBuffer? = null
    private var webBitmap: Bitmap? = null
    private val webJpegOut = ByteArrayOutputStream(65536)

    @Volatile
    private var sourceWidth: Int = max(2, initialSourceWidth)

    @Volatile
    private var sourceHeight: Int = max(2, initialSourceHeight)

    @Volatile
    private var carWidth: Int = 0

    @Volatile
    private var carHeight: Int = 0

    @Volatile
    private var hasPendingFrame: Boolean = false

    @Volatile
    private var hasReceivedAnyFrame: Boolean = false

    @Volatile
    private var webClientsActive: Boolean = false

    private var lastWebCaptureMs: Long = 0L

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val webMvpMatrix = FloatArray(16)

    private val vertexData = floatArrayOf(
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 0.0f, 1.0f, 1.0f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertexData)
            position(0)
        }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            drawFrame(isHeartbeat = true)
            val delayMs = if (webClientsActive) 33L else 50L
            glHandler?.postDelayed(this, delayMs)
        }
    }

    fun start(onInputSurfaceReady: (Surface) -> Unit) {
        val thread = HandlerThread("CarMirrorGlThread").apply { start() }
        val handler = Handler(thread.looper)
        glThread = thread
        glHandler = handler

        handler.post {
            try {
                initEgl()
                initGlProgram()
                initInputSurface()
                Matrix.setIdentityM(stMatrix, 0)
                Matrix.setIdentityM(mvpMatrix, 0)
                Matrix.setIdentityM(webMvpMatrix, 0)
                CarSurfaceRegistry.registerEngine(this)
                inputSurface?.let { onInputSurfaceReady(it) }
                handler.postDelayed(heartbeatRunnable, 50L)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MirrorGlEngine", t)
                MirrorState.setError("Grafik motoru başlatılamadı: ${t.message}")
            }
        }
    }

    fun updateSourceSize(newWidth: Int, newHeight: Int) {
        val w = max(2, newWidth)
        val h = max(2, newHeight)
        glHandler?.post {
            sourceWidth = w
            sourceHeight = h
            surfaceTexture?.setDefaultBufferSize(w, h)
            drawFrame(isHeartbeat = true)
        }
    }

    fun setWebClientsActive(active: Boolean) {
        webClientsActive = active
        glHandler?.post {
            if (!active) {
                releaseWebFbo()
            } else {
                drawFrame(isHeartbeat = true)
            }
        }
    }

    fun requestRedraw() {
        glHandler?.post {
            drawFrame(isHeartbeat = true)
        }
    }

    fun attachCarSurface(surface: Surface, width: Int, height: Int) {
        glHandler?.post {
            releaseCarEglSurface()
            if (!surface.isValid || eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) {
                return@post
            }
            try {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                val newSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    eglConfig,
                    surface,
                    surfaceAttribs,
                    0
                )
                if (newSurface == null || newSurface == EGL14.EGL_NO_SURFACE) {
                    val err = EGL14.eglGetError()
                    Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(err)}")
                    MirrorState.setError("Araç yüzeyi bağlanamadı (EGL 0x${Integer.toHexString(err)})")
                    return@post
                }
                carEglSurface = newSurface
                carWidth = max(2, width)
                carHeight = max(2, height)
                Log.i(TAG, "Attached car surface: ${carWidth}x${carHeight}")
                drawFrame(isHeartbeat = true)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to attach car surface", t)
                carEglSurface = EGL14.EGL_NO_SURFACE
                MirrorState.setError("Araç yüzeyi hatası: ${t.message}")
            }
        }
    }

    fun detachCarSurface() {
        glHandler?.post {
            releaseCarEglSurface()
        }
    }

    fun stop() {
        CarSurfaceRegistry.unregisterEngine(this)
        val handler = glHandler
        val thread = glThread
        if (handler != null && thread != null) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                releaseAllGl()
                thread.quitSafely()
            }
        }
        glHandler = null
        glThread = null
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        hasPendingFrame = true
        hasReceivedAnyFrame = true
        drawFrame(isHeartbeat = false)
    }

    private fun drawFrame(isHeartbeat: Boolean) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return
        val st = surfaceTexture ?: return

        val hasCar = carEglSurface != EGL14.EGL_NO_SURFACE && carWidth > 0 && carHeight > 0
        val targetSurface = if (hasCar) carEglSurface else pbufferSurface
        if (targetSurface == EGL14.EGL_NO_SURFACE) return

        if (!EGL14.eglMakeCurrent(eglDisplay, targetSurface, targetSurface, eglContext)) {
            if (targetSurface == carEglSurface) {
                releaseCarEglSurface()
                if (pbufferSurface == EGL14.EGL_NO_SURFACE ||
                    !EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                ) {
                    return
                }
            } else {
                return
            }
        }

        if (hasPendingFrame || !isHeartbeat) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                hasPendingFrame = false
                hasReceivedAnyFrame = true
            } catch (_: Throwable) {
                return
            }
        }

        if (!hasReceivedAnyFrame) return

        // 1. Direct GPU-to-GPU render to Android Auto car display surface
        if (carEglSurface != EGL14.EGL_NO_SURFACE && carWidth > 0 && carHeight > 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, carWidth, carHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            computeMvpMatrix(
                srcW = sourceWidth,
                srcH = sourceHeight,
                dstW = carWidth,
                dstH = carHeight,
                mode = MirrorState.state.value.scaleMode,
                outMatrix = mvpMatrix,
                flipY = false
            )

            renderQuad(mvpMatrix)
            EGL14.eglSwapBuffers(eglDisplay, carEglSurface)
        }

        // 2. Independent offscreen FBO capture for Local Wi-Fi Browser streaming
        if (webClientsActive && onJpegFrameAvailable != null) {
            val now = System.currentTimeMillis()
            if (now - lastWebCaptureMs >= 33L) {
                lastWebCaptureMs = now
                captureWebJpegViaFbo()
            }
        }
    }

    private fun renderQuad(matrix: FloatArray) {
        GLES20.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun ensureWebFbo(targetW: Int, targetH: Int): Boolean {
        if (webFboId != 0 && webFboWidth == targetW && webFboHeight == targetH) {
            return true
        }
        releaseWebFbo()

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            targetW,
            targetH,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texId,
            0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            Log.e(TAG, "Web FBO incomplete: 0x${Integer.toHexString(status)}")
            return false
        }

        webFboId = fboId
        webColorTexId = texId
        webFboWidth = targetW
        webFboHeight = targetH
        webPixelBuf = ByteBuffer.allocateDirect(targetW * targetH * 4).order(ByteOrder.nativeOrder())
        webBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        return true
    }

    private fun captureWebJpegViaFbo() {
        try {
            val mode = MirrorState.state.value.scaleMode
            val rawW = if (mode == ScaleMode.ROTATE_90) sourceHeight else sourceWidth
            val rawH = if (mode == ScaleMode.ROTATE_90) sourceWidth else sourceHeight
            val maxEdge = max(rawW, rawH)
            val scale = if (maxEdge > 960) 960f / maxEdge.toFloat() else 1.0f
            val targetW = max(64, ((rawW * scale).roundToInt() / 2) * 2)
            val targetH = max(64, ((rawH * scale).roundToInt() / 2) * 2)

            if (!ensureWebFbo(targetW, targetH)) return
            val buf = webPixelBuf ?: return
            val bmp = webBitmap ?: return

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, webFboId)
            GLES20.glViewport(0, 0, targetW, targetH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Flip Y so glReadPixels produces a right-side-up Android Bitmap
            computeMvpMatrix(
                srcW = sourceWidth,
                srcH = sourceHeight,
                dstW = targetW,
                dstH = targetH,
                mode = if (mode == ScaleMode.ROTATE_90) ScaleMode.ROTATE_90 else ScaleMode.FILL,
                outMatrix = webMvpMatrix,
                flipY = true
            )
            renderQuad(webMvpMatrix)

            buf.rewind()
            GLES20.glReadPixels(0, 0, targetW, targetH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            webJpegOut.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, webJpegOut)
            onJpegFrameAvailable?.invoke(webJpegOut.toByteArray())
        } catch (t: Throwable) {
            Log.w(TAG, "captureWebJpegViaFbo failed: ${t.message}")
        }
    }

    private fun releaseWebFbo() {
        if (webFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(webFboId), 0)
            webFboId = 0
        }
        if (webColorTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(webColorTexId), 0)
            webColorTexId = 0
        }
        webFboWidth = 0
        webFboHeight = 0
        webPixelBuf = null
        try {
            webBitmap?.recycle()
        } catch (_: Throwable) {
        }
        webBitmap = null
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("EGL_NO_DISPLAY")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val primaryAttribs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, primaryAttribs, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] <= 0) {
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] <= 0) {
                throw RuntimeException("No matching EGLConfig found")
            }
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 16,
            EGL14.EGL_HEIGHT, 16,
            EGL14.EGL_NONE
        )
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreatePbufferSurface failed")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun initGlProgram() {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vShader)
        GLES20.glAttachShader(programId, fShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("GL program link failed: $info")
        }

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixLoc = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "sTexture")
    }

    private fun initInputSurface() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(sourceWidth, sourceHeight)
        st.setOnFrameAvailableListener(this, glHandler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed ($type): $info")
        }
        return shader
    }

    private fun releaseCarEglSurface() {
        if (carEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            EGL14.eglDestroySurface(eglDisplay, carEglSurface)
            carEglSurface = EGL14.EGL_NO_SURFACE
        }
        carWidth = 0
        carHeight = 0
    }

    private fun releaseAllGl() {
        releaseWebFbo()
        releaseCarEglSurface()
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null

        try {
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                pbufferSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
