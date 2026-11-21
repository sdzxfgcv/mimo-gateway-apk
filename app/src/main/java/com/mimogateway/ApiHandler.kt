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

    private fun buildQuery(req: JSONObject): Pair<String, Boolean> {
        val messages = req.optJSONArray("messages") ?: JSONArray()
        var query = Protocol.serializeMessages(messages)
        val tools = req.optJSONArray("tools")
        val enableThinking = req.has("reasoning_effort") && req.opt("reasoning_effort") != null
        if (tools != null && tools.length() > 0) {
            query = "${Protocol.buildToolSystemPrompt(tools)}\n\n$query"
        }
        return Pair(query, enableThinking)
    }

    private fun getConversationId(req: JSONObject): String {
        val clientSid = req.optString("user", "").ifBlank {
            // 用最后一条 user 消息的前 80 字符作为会话键的补充
            val msgs = req.optJSONArray("messages") ?: JSONArray()
            msgs.toString().take(80)
        }
        return sessions.getOrPut(clientSid) { UUID.randomUUID().toString().replace("-", "") }
    }

    private fun handleNonStream(req: JSONObject, out: OutputStream) {
        val (query, thinking) = buildQuery(req)
        val convId = getConversationId(req)
        val model = mimo.resolveModel(req.optString("model", cfg.model))
        val fullText = StringBuilder()
        var usage: MimoChunk.Usage? = null
        mimo.chat(convId, query, model, thinking) { chunk ->
            when (chunk) {
                is MimoChunk.Text -> fullText.append(chunk.content)
                is MimoChunk.Usage -> usage = chunk
                is MimoChunk.DialogId -> {} // 保持原 conversationId 即可
                is MimoChunk.Finish -> {}
            }
        }
        var text = fullText.toString()
        val tools = req.optJSONArray("tools")
        val calls = if (tools != null && tools.length() > 0) Protocol.parseToolCalls(text) else emptyList()
        if (calls.isNotEmpty()) text = Protocol.stripThink(text)

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
        val (query, thinking) = buildQuery(req)
        val convId = getConversationId(req)
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

        try {
            mimo.chat(convId, query, model, thinking) { chunk ->
                when (chunk) {
                    is MimoChunk.Text -> {
                        var t = chunk.content
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
                                // 流式输出：若还没出现工具标记，先输出安全部分（保留尾部 12 字符防跨块标记）
                                if (!buf.contains("<tool_call")) {
                                    val safe = buf.length - 12
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
                if (buf.isNotBlank()) sendDelta(out, responseId, created, model, JSONObject().put("content", buf.toString()))
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
                sendDelta(out, responseId, created, model, JSONObject().put("content", buf.toString()))
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
