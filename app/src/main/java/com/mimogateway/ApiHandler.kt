package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * OpenAI 兼容路由（eta 版）。
 *
 * 关键区别：不再做 MiMoML 协议翻译、不再注入"人设"，而是把客户端（Eta）发来的
 * 请求原样转发给 MiMo App 的 OpenAI 兼容端点，并把上游 SSE 原样回传。
 * 这样 Eta 的 tools / tool_calls / reasoning_content / usage 都是原生字段。
 *
 * - GET  /health
 * - GET  /v1/models
 * - POST /v1/chat/completions  (stream / non-stream)
 */
class ApiHandler(private val cfg: Config) : TinyHttpServer.RequestHandler {
    private val app = AppClient(cfg)

    override fun handle(method: String, path: String, headers: Map<String, String>, body: String, out: OutputStream): Boolean {
        try {
            if (method == "GET" && path == "/health") {
                TinyHttpServer.writeJson(out, 200, JSONObject().put("status", "ok"))
                return true
            }
            if (method == "GET" && path == "/v1/models") {
                val arr = JSONArray()
                for (m in app.models()) {
                    arr.put(JSONObject().apply {
                        put("id", m); put("object", "model"); put("owned_by", "mimo"); put("created", 0)
                    })
                }
                TinyHttpServer.writeJson(out, 200, JSONObject().apply {
                    put("object", "list"); put("data", arr)
                })
                return true
            }
            if (method == "POST" && path == "/v1/chat/completions") {
                val expected = cfg.apiKey
                if (expected.isNotBlank()) {
                    val auth = headers["authorization"] ?: ""
                    val provided = auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
                    if (provided != expected) {
                        TinyHttpServer.writeJson(out, 401, JSONObject().put("error",
                            JSONObject().put("message", "Invalid API key").put("type", "invalid_request_error")))
                        return true
                    }
                }
                // 丢弃可能为空的 body，避免上游拿到坏 JSON
                if (body.isBlank()) {
                    TinyHttpServer.writeJson(out, 400, JSONObject().put("error",
                        JSONObject().put("message", "empty request body").put("type", "invalid_request_error")))
                    return true
                }
                val stream = try { JSONObject(body).optBoolean("stream", false) } catch (_: Exception) { false }
                if (stream) handleStream(body, out) else handleNonStream(body, out)
                return true
            }
            return false
        } catch (e: Exception) {
            try {
                TinyHttpServer.writeJson(out, 502, JSONObject().put("error",
                    JSONObject().put("message", e.message ?: "error").put("type", "api_error")))
            } catch (_: Exception) {}
            return true
        }
    }

    private fun handleNonStream(body: String, out: OutputStream) {
        val sb = StringBuilder()
        app.chat(body, false) { line -> if (line.isNotBlank()) sb.append(line).append('\n') }
        val raw = sb.toString().trim()
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw RuntimeException("上游返回非 JSON：${raw.take(200)}")
        }
        TinyHttpServer.writeJson(out, 200, obj)
    }

    private fun handleStream(body: String, out: OutputStream) {
        TinyHttpServer.writeSseHeaders(out)
        var sawDone = false
        try {
            app.chat(body, true) { line ->
                if (line == "[DONE]" || line.trim() == "data: [DONE]") sawDone = true
                TinyHttpServer.writeSse(out, line + "\n")
            }
        } catch (e: Exception) {
            val msg = (e.message ?: "error").replace("\n", " ")
            val err = JSONObject().put("error", JSONObject()
                .put("message", msg)
                .put("type", "api_error"))
            TinyHttpServer.writeSse(out, "data: $err\n\n")
        }
        if (!sawDone) TinyHttpServer.writeSse(out, "data: [DONE]\n\n")
    }
}
