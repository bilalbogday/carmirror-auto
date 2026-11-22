package com.bilalbogday.carmirror.web

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in zero-server, 100% free Local Wi-Fi / Hotspot HTTP MJPEG Server.
 * Works completely independently of Android Auto (can be viewed from any car browser,
 * tablet, or laptop over local Wi-Fi/Hotspot with 0 internet usage).
 */
class LocalWebMirrorServer(
    private val port: Int = 8080,
    private val onClientCountChanged: (Int) -> Unit
) {

    companion object {
        private const val TAG = "LocalWebMirrorServer"

        fun findLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                val hotspotCandidates = mutableListOf<String>()
                val lanCandidates = mutableListOf<String>()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val name = intf.name.lowercase()
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                if (name.contains("ap") || name.contains("swlan") || ip.startsWith("192.168.43.")) {
                                    hotspotCandidates.add(ip)
                                } else {
                                    lanCandidates.add(ip)
                                }
                            }
                        }
                    }
                }
                return hotspotCandidates.firstOrNull()
                    ?: lanCandidates.firstOrNull { it.startsWith("192.168.") }
                    ?: lanCandidates.firstOrNull()
                    ?: "127.0.0.1"
            } catch (_: Throwable) {
                return "127.0.0.1"
            }
        }
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val activeClients = AtomicInteger(0)

    @Volatile
    private var latestJpeg: ByteArray? = null
    private val frameLock = Object()

    fun start(): String {
        if (running) return "http://${findLocalIpAddress()}:$port"
        running = true
        executor.execute {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = socket
                while (running) {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                }
            } catch (t: Throwable) {
                if (running) {
                    Log.w(TAG, "LocalWebMirrorServer stopped: ${t.message}")
                }
            }
        }
        return "http://${findLocalIpAddress()}:$port"
    }

    fun pushJpegFrame(jpeg: ByteArray) {
        synchronized(frameLock) {
            latestJpeg = jpeg
            frameLock.notifyAll()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        activeClients.set(0)
        onClientCountChanged(0)
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            val out = BufferedOutputStream(socket.getOutputStream(), 65536)
            when {
                requestLine.contains("/stream.mjpg") -> {
                    val count = activeClients.incrementAndGet()
                    onClientCountChanged(count)
                    try {
                        val headers = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                                "Pragma: no-cache\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(headers.toByteArray())
                        out.flush()

                        var lastSent: ByteArray? = null
                        while (running && !socket.isClosed) {
                            val frame = synchronized(frameLock) {
                                if (latestJpeg === lastSent) {
                                    frameLock.wait(250L)
                                }
                                latestJpeg
                            }
                            if (frame != null && frame !== lastSent) {
                                lastSent = frame
                                val partHeader = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                                out.write(partHeader.toByteArray())
                                out.write(frame)
                                out.write("\r\n".toByteArray())
                                out.flush()
                            }
                        }
                    } finally {
                        val rem = maxOf(0, activeClients.decrementAndGet())
                        onClientCountChanged(rem)
                    }
                }
                requestLine.contains("/health") -> {
                    val body = "{\"status\":\"ok\",\"clients\":${activeClients.get()}}".toByteArray()
                    val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(headers.toByteArray())
                    out.write(body)
                    out.flush()
                }
                else -> {
                    val html = """
                        <!DOCTYPE html>
                        <html lang="tr">
                        <head>
                            <meta charset="utf-8"/>
                            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
                            <title>CarMirror Auto — Canlı Ekran</title>
                            <style>
                                html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; font-family:system-ui,sans-serif; }
                                #stream { width:100vw; height:100vh; object-fit:contain; display:block; background:#000; }
                                #stream.fill { object-fit:fill; }
                                #bar { position:fixed; bottom:14px; right:14px; display:flex; gap:8px; opacity:0.85; z-index:10; }
                                button { background:rgba(15,23,42,0.85); color:#fff; border:1px solid #334155; border-radius:10px; padding:10px 14px; font-size:14px; cursor:pointer; }
                            </style>
                        </head>
                        <body>
                            <img id="stream" class="fill" src="/stream.mjpg" alt="CarMirror Live Stream"/>
                            <div id="bar">
                                <button onclick="toggleFit()">Tam Ekran / Orantılı</button>
                                <button onclick="toggleFs()">⛶ Tam Pencere</button>
                            </div>
                            <script>
                                const img = document.getElementById('stream');
                                function toggleFit() { img.classList.toggle('fill'); }
                                function toggleFs() {
                                    if (!document.fullscreenElement) {
                                        document.documentElement.requestFullscreen && document.documentElement.requestFullscreen();
                                    } else {
                                        document.exitFullscreen && document.exitFullscreen();
                                    }
                                }
                                img.onerror = () => { setTimeout(() => { img.src = '/stream.mjpg?t=' + Date.now(); }, 1000); };
                            </script>
                        </body>
                        </html>
                    """.trimIndent()
                    val body = html.toByteArray()
                    val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(headers.toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }
}
