package com.bilalbogday.carmirror.web

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in zero-server, 100% free Local Wi-Fi / Hotspot HTTP MJPEG Server.
 * Remains completely idle (0% CPU/GPU usage) unless a browser client connects.
 */
class LocalWebMirrorServer(
    private val port: Int = 8080,
    private val onClientCountChanged: (Boolean) -> Unit
) {

    companion object {
        private const val TAG = "LocalWebMirrorServer"

        fun findLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                val candidates = mutableListOf<String>()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                candidates.add(ip)
                            }
                        }
                    }
                }
                // Prefer hotspot subnet (192.168.43.x or 192.168.x.x)
                return candidates.firstOrNull { it.startsWith("192.168.") }
                    ?: candidates.firstOrNull()
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
                val socket = ServerSocket(port)
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
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            val out = BufferedOutputStream(socket.getOutputStream())
            if (requestLine.contains("/stream.mjpg")) {
                val count = activeClients.incrementAndGet()
                onClientCountChanged(count > 0)
                try {
                    val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                            "Pragma: no-cache\r\n" +
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
                    val rem = activeClients.decrementAndGet()
                    onClientCountChanged(rem > 0)
                }
            } else {
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8"/>
                        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
                        <title>CarMirror Auto</title>
                        <style>
                            html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; display:flex; align-items:center; justify-content:center; }
                            img { width:100vw; height:100vh; object-fit:fill; }
                        </style>
                    </head>
                    <body onclick="document.documentElement.requestFullscreen && document.documentElement.requestFullscreen()">
                        <img src="/stream.mjpg" alt="CarMirror Live Stream"/>
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
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }
}
