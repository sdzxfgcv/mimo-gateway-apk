package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 上游返回的块：文本 / 推理 / 用量 / 会话ID / 结束 */
sealed class MimoChunk {
    data class Text(val content: String) : MimoChunk()
    data class Reasoning(val content: String) : MimoChunk()
    data class Usage(val prompt: Int, val completion: Int, val total: Int, val reasoning: Int) : MimoChunk()
    data class DialogId(val content: String) : MimoChunk()
    object Finish : MimoChunk()
}

/**
 * 调用 aistudio.xiaomimimo.com 的 v3 (react/chat) 接口，解析其 SSE 流。
 * 协议参考 Fly143/MiMo2API：https://github.com/Fly143/MiMo2API (2026-11 实测可用)
 * 关键：旧 /open-apis/bot/chat 已废弃，固定回 event:error；必须用 /open-apis/react/chat。
 */
class MimoClient(private val cfg: Config) {
    private val apiUrl = "https://aistudio.xiaomimimo.com/open-apis/react/chat"
    private val ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

    private fun netLog(msg: String) { CrashLog.write(cfg.appContext, "NET", RuntimeException(msg)) }

    /**
     * 发起流式请求。onChunk 逐块回调；异常向上抛。
     */
    fun chat(
        conversationId: String,
        query: String,
        model: String,
        enableThinking: Boolean,
        onChunk: (MimoChunk) -> Unit
    ) {
        val msgId = UUID.randomUUID().toString().replace("-", "").take(32)
        val modelConfig = JSONObject().apply {
            put("enableThinking", enableThinking)
            put("webSearchStatus", "disabled")
            put("model", model)
            if (enableThinking) put("reasoning_effort", "high")
        }
        val body = JSONObject().apply {
            put("msgId", msgId)
            put("conversationId", conversationId)
            put("query", query)
            put("modelConfig", modelConfig)
            put("multiMedias", JSONArray())
            put("attachments", JSONArray())
        }

        val url = URL("$apiUrl?xiaomichatbot_ph=${encode(cfg.phToken)}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 0 // 不超时：思考+输出整条流纯透传
        conn.doInput = true
        conn.doOutput = true
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Cookie",
            "serviceToken=${cfg.serviceToken}; userId=${cfg.userId}; xiaomichatbot_ph=${cfg.phToken}")
        conn.setRequestProperty("Origin", "https://aistudio.xiaomimimo.com")
        conn.setRequestProperty("Referer", "https://aistudio.xiaomimimo.com/")
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("x-timezone", "Asia/Shanghai")

        val os: OutputStream = conn.outputStream
        os.write(body.toString().toByteArray(Charsets.UTF_8))
        os.flush()
        os.close()

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()?.take(500) ?: ""
            netLog("HTTP $code model=$model body=$err")
            conn.disconnect()
            throw RuntimeException("MiMo HTTP $code: $err")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var event: String? = null
        var line: String?
        var lineCount = 0
        var gotText = false
        try {
            while (reader.readLine().also { line = it } != null) {
                val l = (line ?: break).trim()
                if (l.isEmpty()) continue
                when {
                    l.startsWith("event:") -> { event = l.removePrefix("event:").trim(); continue }
                    l.startsWith("id:") -> continue
                    !l.startsWith("data:") -> continue
                }
                val dataStr = l.removePrefix("data:").trim()
                if (dataStr.isEmpty()) continue
                if (dataStr == "[DONE]") break
                if (lineCount < 8) netLog("event=$event data=${dataStr.take(300)}")
                lineCount++
                val d = try { JSONObject(dataStr) } catch (e: Exception) {
                    netLog("PARSE_FAIL raw=${dataStr.take(200)}"); continue
                }
                when (event) {
                    "error" -> {
                        netLog("UPSTREAM_ERROR model=$model data=${dataStr.take(300)}")
                        throw RuntimeException("上游错误: ${d.optString("content", d.optString("msg", dataStr.take(120)))}")
                    }
                    "message" -> {
                        val reasoning = d.optString("reasoningContent", "")
                        val text = d.optString("content", "")
                        if (reasoning.isNotEmpty()) onChunk(MimoChunk.Reasoning(reasoning))
                        if (text.isNotEmpty()) { gotText = true; onChunk(MimoChunk.Text(text)) }
                    }
                    "dialogId" -> onChunk(MimoChunk.DialogId(d.optString("content", "")))
                    "usage" -> {
                        val native = d.optJSONObject("nativeUsage")
                        val details = native?.optJSONObject("completion_tokens_details")
                        val reasoning = details?.optInt("reasoning_tokens", 0) ?: 0
                        onChunk(MimoChunk.Usage(
                            d.optInt("promptTokens", 0),
                            d.optInt("completionTokens", 0),
                            d.optInt("totalTokens", 0),
                            reasoning))
                    }
                    "finish" -> onChunk(MimoChunk.Finish)
                    else -> netLog("UNKNOWN event=$event data=${dataStr.take(200)}")
                }
                event = null
            }
            if (!gotText) netLog("NO_TEXT model=$model lines=$lineCount")
        } finally {
            reader.close()
            conn.disconnect()
        }
    }

    // 模型名 → 上游真实模型名（redirectTo）映射，来自 bot/config，带缓存
    private var modelRedirects: Map<String, String>? = null
    private var cachedModels: List<String>? = null

    private fun ensureModels() {
        if (modelRedirects != null) return
        try {
            val conn = URL("https://aistudio.xiaomimimo.com/open-apis/bot/config").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", ua)
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = JSONObject(text).getJSONObject("data").getJSONArray("modelConfigListNg")
            val redirects = mutableMapOf<String, String>()
            val models = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optString("pageType") != "chat") continue
                val name = m.optString("model")
                if (name.isBlank()) continue
                val redirect = m.optString("redirectTo", "")
                if (redirect.isNotBlank()) redirects[name] = redirect
                if (m.optBoolean("isNew")) models.add(name)
            }
            modelRedirects = redirects
            cachedModels = if (models.isEmpty()) listOf("mimo-v2.6-pro", "mimo-v2.6-flash") else models
        } catch (e: Exception) {
            modelRedirects = emptyMap()
            cachedModels = listOf("mimo-v2.6-pro", "mimo-v2.6-flash")
        }
    }

    fun fetchModels(): List<String> {
        ensureModels()
        return cachedModels ?: listOf("mimo-v2.6-pro", "mimo-v2.6-flash")
    }

    fun resolveModel(requested: String): String {
        ensureModels()
        val map = modelRedirects ?: emptyMap()
        var name = requested.ifBlank { cfg.model }
        repeat(3) {
            val next = map[name]
            if (next.isNullOrBlank() || next == name) return@repeat
            name = next
        }
        val allKnown = map.keys + (cachedModels ?: emptyList()) + map.values
        return if (name in allKnown) name else (cachedModels?.firstOrNull() ?: "mimo-v2.6-pro")
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
