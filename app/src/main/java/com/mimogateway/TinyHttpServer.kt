package com.mimogateway

import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 极简 HTTP/1.1 服务器（基于 ServerSocket，零第三方依赖）。
 * 支持 SSE 流式响应。路由交给 handler。
 */
class TinyHttpServer(
    private val port: Int,
    private val handler: RequestHandler
) {
    interface RequestHandler {
        /** 返回 true 表示已自行处理并写入 response；path 为请求路径。 */
        fun handle(method: String, path: String, headers: Map<String, String>, body: String, out: OutputStream): Boolean
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var running = false

    fun start() {
        serverSocket = ServerSocket()
        serverSocket?.reuseAddress = true
        serverSocket?.bind(InetSocketAddress(port))
        running = true
        pool.execute {
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    pool.execute { handleConnection(socket) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handleConnection(socket: Socket) {
        var out: OutputStream? = null
        try {
            socket.soTimeout = 300000
            val `in` = BufferedInputStream(socket.getInputStream())
            out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(`in`) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = readLine(`in`) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
            }

            var body = ""
            if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = `in`.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read, Charsets.UTF_8)
            }

            val handled = handler.handle(method, path, headers, body, out)
            if (!handled) {
                writeJson(out, 404, JSONObject().put("error", "not found"))
            }
            out.flush()
        } catch (e: Exception) {
            try {
                if (out != null) writeJson(out, 500, JSONObject().put("error", e.message ?: "error"))
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(`in`: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = `in`.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        fun writeJson(out: OutputStream, status: Int, obj: JSONObject) {
            val bytes = obj.toString().toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
            sb.append("Content-Type: application/json; charset=utf-8\r\n")
            sb.append("Content-Length: ${bytes.size}\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }

        fun writeSseHeaders(out: OutputStream) {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: text/event-stream; charset=utf-8\r\n")
            sb.append("Cache-Control: no-cache\r\n")
            sb.append("X-Accel-Buffering: no\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        fun writeSse(out: OutputStream, text: String) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun statusText(status: Int) = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "OK"
        }
    }
}
