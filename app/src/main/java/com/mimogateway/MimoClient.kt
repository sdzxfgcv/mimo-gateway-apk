package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 上游返回的块：文本 / 用量 / 会话ID / 结束 */
sealed class MimoChunk {
    data class Text(val content: String) : MimoChunk()
    data class Usage(val prompt: Int, val completion: Int, val total: Int, val reasoning: Int) : MimoChunk()
    data class DialogId(val content: String) : MimoChunk()
    object Finish : MimoChunk()
}

/**
 * 直接调用 aistudio.xiaomimimo.com 的 bot/chat 接口，解析其 SSE 流。
 * 完全对应原项目 src/mimo/client.ts 的逻辑，零第三方依赖。
 */
class MimoClient(private val cfg: Config) {
    private val apiUrl = "https://aistudio.xiaomimimo.com/open-apis/bot/chat"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * 发起流式请求。onChunk 逐块回调；本函数运行在调用线程，异常向上抛。
     */
    fun chat(
        conversationId: String,
        query: String,
        model: String,
        enableThinking: Boolean,
        onChunk: (MimoChunk) -> Unit
    ) {
        val msgId = UUID.randomUUID().toString().replace("-", "").take(32)
        val body = JSONObject().apply {
            put("msgId", msgId)
            put("conversationId", conversationId)
            put("query", query)
            put("modelConfig", JSONObject().apply {
                put("model", model)
                put("enableThinking", enableThinking)
                put("webSearchStatus", "disabled")
            })
            put("multiMedias", JSONArray())
        }

        val url = URL("$apiUrl?xiaomichatbot_ph=${encode(cfg.phToken)}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 300000
        conn.doInput = true
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Cookie",
            "serviceToken=${cfg.serviceToken}; userId=${cfg.userId}; xiaomichatbot_ph=${cfg.phToken}")
        conn.setRequestProperty("Origin", "https://aistudio.xiaomimimo.com")
        conn.setRequestProperty("Referer", "https://aistudio.xiaomimimo.com/")
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("x-timezone", "Asia/Shanghai")
        conn.setRequestProperty("Accept", "text/event-stream")

        val os: OutputStream = conn.outputStream
        os.write(body.toString().toByteArray(Charsets.UTF_8))
        os.flush()
        os.close()

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()?.take(500) ?: ""
            conn.disconnect()
            throw RuntimeException("MiMo HTTP $code: $err")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var event = ""
        var line: String?
        try {
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                when {
                    l.startsWith("event:") -> event = l.removePrefix("event:").trim()
                    l.startsWith("data:") -> {
                        val dataStr = l.removePrefix("data:").trim()
                        if (dataStr.isEmpty()) continue
                        try {
                            val d = JSONObject(dataStr)
                            when (event) {
                                "message" -> onChunk(MimoChunk.Text(d.optString("content", "")))
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
                            }
                        } catch (e: Exception) { /* 忽略无法解析的 data 行 */ }
                    }
                }
            }
        } finally {
            reader.close()
            conn.disconnect()
        }
    }

    // 模型名 → 上游真实模型名（redirectTo）映射，来自 bot/config，带缓存
    private var modelRedirects: Map<String, String>? = null
    private var cachedModels: List<String>? = null

    /** 拉取模型配置，构建「可用模型列表」和「redirectTo 映射」。 */
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
                // 只把 isNew 的当推荐模型列出；其余仍作为可解析别名
                if (m.optBoolean("isNew")) models.add(name)
            }
            modelRedirects = redirects
            cachedModels = if (models.isEmpty()) listOf("mimo-v2.6-pro", "mimo-v2.6-flash") else models
        } catch (e: Exception) {
            modelRedirects = emptyMap()
            cachedModels = listOf("mimo-v2.6-pro", "mimo-v2.6-flash")
        }
    }

    /** 拉取可用模型列表（对应 fetchBotConfig），失败返回默认列表。 */
    fun fetchModels(): List<String> {
        ensureModels()
        return cachedModels ?: listOf("mimo-v2.6-pro", "mimo-v2.6-flash")
    }

    /**
     * 把客户端传来的模型名解析成上游真实接受的模型名。
     * 例如 mimo-v2-pro -> mimo-v2.6-pro（redirectTo）。
     * 若已是最终模型名则原样返回；完全未知则退回默认。
     */
    fun resolveModel(requested: String): String {
        ensureModels()
        val map = modelRedirects ?: emptyMap()
        var name = requested.ifBlank { cfg.model }
        // 跟随 redirectTo 链（最多 3 跳，防环）
        repeat(3) {
            val next = map[name]
            if (next.isNullOrBlank() || next == name) return@repeat
            name = next
        }
        // 若解析后不在已知模型集合里，也不是任何别名，退回默认
        val allKnown = map.keys + (cachedModels ?: emptyList()) + map.values
        return if (name in allKnown) name else (cachedModels?.firstOrNull() ?: "mimo-v2.6-pro")
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
