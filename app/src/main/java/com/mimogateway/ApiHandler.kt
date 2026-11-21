package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenAI 兼容路由处理器。
 * - GET  /health
 * - GET  /v1/models
 * - POST /v1/chat/completions  (stream / non-stream)
 */
class ApiHandler(private val cfg: Config) : TinyHttpServer.RequestHandler {
    private val mimo = MimoClient(cfg)
    // 客户端会话 id → MiMo conversationId（简单会话保持；进程内有效）
    private val sessions = ConcurrentHashMap<String, String>()
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
                // 鉴权
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

    private fun clientSessionKey(req: JSONObject): String {
        val explicit = req.optString("user", "")
        if (explicit.isNotBlank()) return explicit
        // 无 user 字段时，用最早一条 user 消息作为会话锚点（同一对话首条消息不变）
        val msgs = req.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") {
                return "u:" + contentStr(m.opt("content")).take(120)
            }
        }
        return "fallback"
    }

    private fun contentStr(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val o = c.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("text"))
            }
            sb.toString()
        }
        else -> ""
    }

    private fun buildQuery(req: JSONObject, continuation: Boolean): Pair<String, Boolean> {
        val messages = req.optJSONArray("messages") ?: JSONArray()
        val tools = req.optJSONArray("tools")
        val toolPrompt = if (tools != null && tools.length() > 0) Protocol.buildToolSystemPrompt(tools) else ""
        val query = Protocol.serializeMessages(messages, toolPrompt, continuation)
        val enableThinking = req.has("reasoning_effort") && req.opt("reasoning_effort") != null
        return Pair(query, enableThinking)
    }

    private fun getConversationId(req: JSONObject): Pair<String, Boolean> {
        val key = clientSessionKey(req)
        val existing = sessions[key]
        if (existing != null) return Pair(existing, false) // 续接
        val conv = UUID.randomUUID().toString().replace("-", "")
        sessions[key] = conv
        return Pair(conv, true) // 新会话
    }

    private fun handleNonStream(req: JSONObject, out: OutputStream) {
        val (convId, isNew) = getConversationId(req)
        val (query, thinking) = buildQuery(req, continuation = !isNew)
        val model = mimo.resolveModel(req.optString("model", cfg.model))
        val fullText = StringBuilder()
        var usage: MimoChunk.Usage? = null
        mimo.chat(convId, query, model, thinking) { chunk ->
            when (chunk) {
                is MimoChunk.Text -> fullText.append(chunk.content)
                is MimoChunk.Reasoning -> {} // 非流式：思考已包含在 <think> 中或按 thinkMode 处理
                is MimoChunk.Usage -> usage = chunk
                is MimoChunk.DialogId -> {} // 保持原 conversationId 即可
                is MimoChunk.Finish -> {}
            }
        }
        var text = fullText.toString()
        val tools = req.optJSONArray("tools")
        val calls = if (tools != null && tools.length() > 0) Protocol.parseToolCalls(text) else emptyList()
        // 有工具调用时，正文里的 MiMoML 块不算内容；始终清掉残留的 think/工具标记
        text = Protocol.stripThink(text)
        if (calls.isNotEmpty()) text = Protocol.cleanToolText(text)
        // 上游未返回任何正文时，把诊断日志回显给客户端，避免"空内容"难以排查
        if (text.isBlank() && calls.isEmpty()) {
            val diag = CrashLog.read(cfg.appContext)
            text = "[MiMo Gateway] 上游未返回正文。诊断：\n${diag.takeLast(600)}"
        }

        val responseId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        val message = JSONObject().apply {
            put("role", "assistant")
            if (calls.isNotEmpty()) {
                put("content", null)
                val tcArr = JSONArray()
                for (c in calls) {
                    tcArr.put(JSONObject().apply {
                        put("id", c.id); put("type", "function")
                        put("function", JSONObject().apply { put("name", c.name); put("arguments", c.arguments) })
                    })
                }
                put("tool_calls", tcArr)
            } else {
                put("content", if (cfg.thinkMode == "strip") Protocol.stripThink(text) else text)
            }
        }
        val choice = JSONObject().apply {
            put("index", 0); put("message", message)
            put("finish_reason", if (calls.isNotEmpty()) "tool_calls" else "stop")
        }
        val resp = JSONObject().apply {
            put("id", responseId); put("object", "chat.completion")
            put("created", created); put("model", model)
            put("choices", JSONArray().put(choice))
            if (usage != null) put("usage", JSONObject().apply {
                put("prompt_tokens", usage!!.prompt); put("completion_tokens", usage!!.completion)
                put("total_tokens", usage!!.total)
                put("completion_tokens_details", JSONObject().put("reasoning_tokens", usage!!.reasoning))
            })
        }
        TinyHttpServer.writeJson(out, 200, resp)
    }

    private fun handleStream(req: JSONObject, out: OutputStream) {
        val (convId, isNew) = getConversationId(req)
        val (query, thinking) = buildQuery(req, continuation = !isNew)
        val model = mimo.resolveModel(req.optString("model", cfg.model))
        val tools = req.optJSONArray("tools")
        val wantTools = tools != null && tools.length() > 0

        TinyHttpServer.writeSseHeaders(out)
        val responseId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        val buf = StringBuilder()
        var thinkingBuf = StringBuilder()
        var inThink = false
        var usage: MimoChunk.Usage? = null
        var finishReason = "stop"
        var thinkOpened = false

        try {
            mimo.chat(convId, query, model, thinking) { chunk ->
                when (chunk) {
                    is MimoChunk.Reasoning -> {
                        if (cfg.thinkMode == "strip") return@chat
                        val r = chunk.content
                        if (r.isNotEmpty()) {
                            val piece = (if (!thinkOpened) { thinkOpened = true; "<think>" } else "") + r
                            if (wantTools) buf.append(piece)
                            else sendDelta(out, responseId, created, model, JSONObject().put("reasoning_content", piece))
                        }
                    }
                    is MimoChunk.Text -> {
                        var t = chunk.content
                        // 闭合之前打开的 think 段（separate 模式用 reasoning_content 字段已单独发，这里补尾巴）
                        if (thinkOpened && cfg.thinkMode != "strip") {
                            thinkOpened = false
                            if (!wantTools) sendDelta(out, responseId, created, model, JSONObject().put("reasoning_content", "</think>"))
                        }
                        if (cfg.thinkMode == "strip") {
                            // 逐块剔除 <think>...</think>
                            val sb = StringBuilder()
                            var i = 0
                            while (i < t.length) {
                                if (!inThink && t.startsWith("<think>", i)) { inThink = true; i += 7; continue }
                                if (!inThink && t.startsWith("<thinking>", i)) { inThink = true; i += 10; continue }
                                if (inThink && t.startsWith("</think>", i)) { inThink = false; i += 8; continue }
                                if (inThink && t.startsWith("</thinking>", i)) { inThink = false; i += 11; continue }
                                if (!inThink) sb.append(t[i])
                                i++
                            }
                            t = if (thinkingBuf.isNotEmpty()) {
                                val merged = thinkingBuf.toString() + sb.toString()
                                thinkingBuf.clear(); merged
                            } else sb.toString()
                        }
                        if (t.isNotEmpty()) {
                            if (wantTools) {
                                buf.append(t)
                                // 流式输出：若还没出现工具标记，先输出安全部分
                                // 保留尾部 20 字符防跨块标记（MiMoML 标记较长）
                                if (!Protocol.hasToolMarker(buf.toString())) {
                                    val safe = buf.length - 20
                                    if (safe > 0) {
                                        val emit = buf.substring(0, safe)
                                        buf.delete(0, safe)
                                        sendDelta(out, responseId, created, model, JSONObject().put("content", emit))
                                    }
                                }
                            } else {
                                sendDelta(out, responseId, created, model, JSONObject().put("content", t))
                            }
                        }
                    }
                    is MimoChunk.Usage -> usage = chunk
                    is MimoChunk.DialogId -> {}
                    is MimoChunk.Finish -> {}
                }
            }
        } catch (e: Exception) {
            // SSE 头已发出，不能再写 JSON 响应头；用 error chunk 告知客户端
            val errDelta = JSONObject().put("content", "[MiMo Gateway 错误] ${e.message ?: e.javaClass.simpleName}")
            sendDelta(out, responseId, created, model, errDelta)
            TinyHttpServer.writeSse(out, Protocol.sseLine(Protocol.chunkBase(responseId, created, model, JSONObject(), "stop")))
            TinyHttpServer.writeSse(out, "data: [DONE]\n\n")
            return
        }

        if (wantTools) {
            val calls = Protocol.parseToolCalls(buf.toString())
            if (calls.isNotEmpty()) {
                finishReason = "tool_calls"
                // 只发清理掉 MiMoML 块后的残留正文（一般为空），不要把工具标记当内容
                val leftover = Protocol.cleanToolText(buf.toString())
                if (leftover.isNotBlank()) sendDelta(out, responseId, created, model, JSONObject().put("content", leftover))
                val tcArr = JSONArray()
                for ((idx, c) in calls.withIndex()) {
                    tcArr.put(JSONObject().apply {
                        put("index", idx); put("id", c.id); put("type", "function")
                        put("function", JSONObject().apply { put("name", c.name); put("arguments", "") })
                    })
                }
                sendDelta(out, responseId, created, model, JSONObject().put("tool_calls", tcArr))
                for ((idx, c) in calls.withIndex()) {
                    val argsArr = JSONArray().put(JSONObject().apply {
                        put("index", idx)
                        put("function", JSONObject().put("arguments", c.arguments))
                    })
                    sendDelta(out, responseId, created, model, JSONObject().put("tool_calls", argsArr))
                }
            } else if (buf.isNotBlank()) {
                val leftover = Protocol.stripThink(Protocol.cleanToolText(buf.toString()))
                if (leftover.isNotBlank()) sendDelta(out, responseId, created, model, JSONObject().put("content", leftover))
            }
        }

        val endChunk = Protocol.chunkBase(responseId, created, model, JSONObject(), finishReason)
        if (usage != null) {
            endChunk.put("usage", JSONObject().apply {
                put("prompt_tokens", usage!!.prompt); put("completion_tokens", usage!!.completion)
                put("total_tokens", usage!!.total)
                put("completion_tokens_details", JSONObject().put("reasoning_tokens", usage!!.reasoning))
            })
        }
        TinyHttpServer.writeSse(out, Protocol.sseLine(endChunk))
        TinyHttpServer.writeSse(out, "data: [DONE]\n\n")
    }

    private fun sendDelta(out: OutputStream, id: String, created: Long, model: String, delta: JSONObject) {
        TinyHttpServer.writeSse(out, Protocol.sseLine(Protocol.chunkBase(id, created, model, delta, null)))
    }
}
