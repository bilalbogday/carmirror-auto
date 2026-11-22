package com.bilalbogday.carmirror

import com.bilalbogday.carmirror.web.LocalWebMirrorServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MirrorCoreTest {

    @Test
    fun scaleModeCyclesCleanly() {
        assertEquals(ScaleMode.FIT, ScaleMode.FILL.next())
        assertEquals(ScaleMode.CROP, ScaleMode.FIT.next())
        assertEquals(ScaleMode.ROTATE_90, ScaleMode.CROP.next())
        assertEquals(ScaleMode.FILL, ScaleMode.ROTATE_90.next())
    }

    @Test
    fun mirrorStateTransitionsProperly() {
        MirrorState.updateMirroring(active = true, localWebUrl = "http://192.168.1.5:8080", sourceWidth = 1280, sourceHeight = 720)
        assertTrue(MirrorState.state.value.isMirroring)
        assertEquals(1280, MirrorState.state.value.sourceWidth)
        assertEquals(720, MirrorState.state.value.sourceHeight)

        MirrorState.updateMirroring(active = false)
        assertFalse(MirrorState.state.value.isMirroring)
        assertEquals(0, MirrorState.state.value.sourceWidth)
        assertEquals(null, MirrorState.state.value.localWebUrl)
    }

    @Test
    fun localWebMirrorServerServesHealthHtmlAndMjpegStream() {
        val freePort = ServerSocket(0).use { it.localPort }
        val connectedLatch = CountDownLatch(1)
        val server = LocalWebMirrorServer(port = freePort) { count ->
            if (count > 0) connectedLatch.countDown()
        }
        server.start()
        Thread.sleep(150)

        try {
            // 1. Verify /health endpoint
            val healthConn = URL("http://127.0.0.1:$freePort/health").openConnection() as HttpURLConnection
            healthConn.connectTimeout = 2000
            healthConn.readTimeout = 2000
            assertEquals(200, healthConn.responseCode)
            val healthBody = healthConn.inputStream.bufferedReader().readText()
            assertTrue(healthBody.contains("\"status\":\"ok\""))
            healthConn.disconnect()

            // 2. Verify HTML root page
            val htmlConn = URL("http://127.0.0.1:$freePort/").openConnection() as HttpURLConnection
            htmlConn.connectTimeout = 2000
            htmlConn.readTimeout = 2000
            assertEquals(200, htmlConn.responseCode)
            val htmlBody = htmlConn.inputStream.bufferedReader().readText()
            assertTrue(htmlBody.contains("/stream.mjpg"))
            htmlConn.disconnect()

            // 3. Verify live MJPEG stream delivers pushed frames independently of Android Auto
            val mjpegConn = URL("http://127.0.0.1:$freePort/stream.mjpg").openConnection() as HttpURLConnection
            mjpegConn.connectTimeout = 2000
            mjpegConn.readTimeout = 3000
            val streamThread = Thread {
                Thread.sleep(150)
                server.pushJpegFrame(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte()))
            }
            streamThread.start()

            assertEquals(200, mjpegConn.responseCode)
            assertTrue(connectedLatch.await(2, TimeUnit.SECONDS))
            val reader = BufferedReader(InputStreamReader(mjpegConn.inputStream))
            val boundaryLine = reader.readLine()
            val contentTypeLine = reader.readLine()
            assertEquals("--frame", boundaryLine)
            assertEquals("Content-Type: image/jpeg", contentTypeLine)
            mjpegConn.disconnect()
            streamThread.join(1000)
        } finally {
            server.stop()
        }
    }
}
