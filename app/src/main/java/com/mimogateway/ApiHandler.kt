package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

/**
 * 纯对话 OpenAI 兼容路由（不做工具调用）。
 * - GET  /health
 * - GET  /v1/models
 * - POST /v1/chat/completions  (stream / non-stream)
 *
 * 设计：完全忽略客户端传来的 tools/tool_choice，只做文本对话。
 * 每次响应都保证 choices[0].message.content 有正文（思考走 reasoning_content 字段），
 * 避免客户端报"模型未返回正文或工具调用"。
 */
class ApiHandler(private val cfg: Config) : TinyHttpServer.RequestHandler {
    private val mimo = MimoClient(cfg)
    private var cachedModels: List<String>? = null

    override fun handle(method: String, path: String, headers: Map<String, String>, body: String, out: OutputStream): Boolean {
        try {
            if (method == "GET" && path == "/health") {
                TinyHttpServer.writeJson(out, 200, JSONObject().put("status", "ok"))
                return true
            }
            if (method == "GET" && path == "/v1/models") {
                val models = cachedModels ?: mimo.fetchModels().also { cachedModels = it }
                val arr = JSONArray()
                for (m in models) {
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
                val req = JSONObject(body)
                val isStream = req.optBoolean("stream", false)
                if (isStream) handleStream(req, out) else handleNonStream(req, out)
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

    /** 构造 query；思考默认开启，直到客户端明确要求关闭。 */
    private fun buildQuery(req: JSONObject): Pair<String, Boolean> {
        val messages = req.optJSONArray("messages") ?: JSONArray()
        val query = Protocol.serializeMessages(messages) // 纯对话，不注入工具提示词
        val effort = req.optString("reasoning_effort", "").ifBlank {
            req.optJSONObject("reasoning")?.optString("effort", "") ?: ""
        }
        val enableThinking = effort.lowercase() !in setOf("none", "off", "0", "false")
        return Pair(query, enableThinking)
    }

    private fun convId(req: JSONObject): String {
        val key = req.optString("user", "").ifBlank {
            val msgs = req.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (m.optString("role") == "user") {
                    return "u:" + m.optString("content", "").take(120)
                }
            }
            "fallback"
        }
        return UUID.nameUUIDFromBytes(key.toByteArray()).toString().replace("-", "")
    }

    private fun handleNonStream(req: JSONObject, out: OutputStream) {
        val cid = convId(req)
        val (query, thinking) = buildQuery(req)
        val requestedModel = req.optString("model", cfg.model)
        val model = mimo.resolveModel(requestedModel)
        val text = StringBuilder()
        val reason = StringBuilder()
        var usage: MimoChunk.Usage? = null
        mimo.chat(cid, query, model, thinking) { chunk ->
            when (chunk) {
                is MimoChunk.Text -> text.append(chunk.content)
                is MimoChunk.Reasoning -> reason.append(chunk.content)
                is MimoChunk.Usage -> usage = chunk
                is MimoChunk.DialogId, is MimoChunk.Finish -> {}
            }
        }
        var body = Protocol.stripThink(text.toString()).trim()
        if (body.isBlank()) {
            val diag = CrashLog.read(cfg.appContext).takeLast(400)
            body = if (diag.isNotBlank()) "[MiMo Gateway] 上游未返回正文。诊断：$diag"
                   else "[MiMo Gateway] 上游未返回正文，请稍后重试或检查登录凭证。"
        }
        val responseId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", body)
            if (cfg.thinkMode == "separate" && reason.isNotBlank()) {
                put("reasoning_content", reason.toString())
            }
        }
        val resp = JSONObject().apply {
            put("id", responseId); put("object", "chat.completion")
            put("created", created); put("model", requestedModel)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0); put("message", message); put("finish_reason", "stop")
            }))
            if (usage != null) put("usage", JSONObject().apply {
                put("prompt_tokens", usage!!.prompt); put("completion_tokens", usage!!.completion)
                put("total_tokens", usage!!.total)
            })
        }
        TinyHttpServer.writeJson(out, 200, resp)
    }

    private fun handleStream(req: JSONObject, out: OutputStream) {
        val cid = convId(req)
        val (query, thinking) = buildQuery(req)
        val requestedModel = req.optString("model", cfg.model)
        val model = mimo.resolveModel(requestedModel)
        val echoModel = requestedModel.ifBlank { model }

        TinyHttpServer.writeSseHeaders(out)
        val responseId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        var usage: MimoChunk.Usage? = null
        var inThink = false
        var gotContent = false

        try {
            mimo.chat(cid, query, model, thinking) { chunk ->
                when (chunk) {
                    is MimoChunk.Reasoning -> {
                        if (cfg.thinkMode == "strip") return@chat
                        val r = chunk.content
                        // 纯文本思考，Eta 读 reasoning_content 字段直接渲染成思考块，不带 <think> 标签
                        if (r.isNotEmpty()) {
                            inThink = true
                            sendDelta(out, responseId, created, echoModel, JSONObject().put("reasoning_content", r))
                        }
                    }
                    is MimoChunk.Text -> {
                        var t = chunk.content
                        inThink = false
                        if (cfg.thinkMode == "strip") t = Protocol.stripThink(t)
                        if (t.isNotEmpty()) {
                            gotContent = true
                            sendDelta(out, responseId, created, echoModel, JSONObject().put("content", t))
                        }
                    }
                    is MimoChunk.Usage -> usage = chunk
                    is MimoChunk.DialogId, is MimoChunk.Finish -> {}
                }
            }
        } catch (e: Exception) {
            gotContent = true
            sendDelta(out, responseId, created, echoModel,
                JSONObject().put("content", "[MiMo Gateway 错误] ${e.message ?: e.javaClass.simpleName}"))
        }

        if (!gotContent) {
            val diag = CrashLog.read(cfg.appContext).takeLast(400)
            val msg = if (diag.isNotBlank()) "[MiMo Gateway] 上游未返回正文。诊断：$diag"
                      else "[MiMo Gateway] 上游未返回正文，请稍后重试或检查登录凭证。"
            sendDelta(out, responseId, created, echoModel, JSONObject().put("content", msg))
        }

        val endChunk = Protocol.chunkBase(responseId, created, echoModel, JSONObject(), "stop")
        if (usage != null) {
            endChunk.put("usage", JSONObject().apply {
                put("prompt_tokens", usage!!.prompt); put("completion_tokens", usage!!.completion)
                put("total_tokens", usage!!.total)
            })
        }
        TinyHttpServer.writeSse(out, Protocol.sseLine(endChunk))
        TinyHttpServer.writeSse(out, "data: [DONE]\n\n")
    }

    private fun sendDelta(out: OutputStream, id: String, created: Long, model: String, delta: JSONObject) {
        TinyHttpServer.writeSse(out, Protocol.sseLine(Protocol.chunkBase(id, created, model, delta, null)))
    }
}
