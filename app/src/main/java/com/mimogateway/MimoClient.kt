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

    /** 拉取可用模型列表（对应 fetchBotConfig），失败返回默认列表。 */
    fun fetchModels(): List<String> {
        return try {
            val conn = URL("https://aistudio.xiaomimimo.com/open-apis/bot/config").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", ua)
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = JSONObject(text).getJSONObject("data").getJSONArray("modelConfigListNg")
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optString("pageType") == "chat" && m.optBoolean("isNew")) {
                    val name = m.optString("model")
                    if (name.isNotBlank()) out.add(name)
                }
            }
            if (out.isEmpty()) listOf("mimo-v2-pro", "mimo-v2-flash", "mimo-v2-omni") else out
        } catch (e: Exception) {
            listOf("mimo-v2-pro", "mimo-v2-flash", "mimo-v2-omni")
        }
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
