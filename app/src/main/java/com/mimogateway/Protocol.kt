package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 纯对话协议转换：OpenAI 请求 → MiMo query 文本；MiMo 输出 → OpenAI SSE 响应。
 * 不做任何工具调用（tools 已在网关层忽略），只保留对话需要的四个方法。
 */
object Protocol {

    /** 把 OpenAI messages 序列化成发往 MiMo 的纯文本 query。 */
    fun serializeMessages(messages: JSONArray): String {
        var systemText = ""
        val rest = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") {
                systemText = contentToString(m.opt("content")).trim()
            } else {
                rest.add(m)
            }
        }
        // 只保留最近 20 条，防止 query 过长
        val toSend = if (rest.size > 20) rest.takeLast(20) else rest.toList()
        val parts = mutableListOf<String>()
        toSend.forEach { parts.add("${it.optString("role")}: ${contentToString(it.opt("content"))}") }
        if (systemText.isNotBlank()) parts.add(0, "system: $systemText")
        return parts.joinToString("\n")
    }

    private fun contentToString(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") sb.append(item.optString("text")).append(" ")
            }
            sb.toString().trim()
        }
        else -> ""
    }

    /** 移除 <think>...</think> / <thinking>...</thinking> 及其内容（strip 模式用）。 */
    fun stripThink(text: String): String =
        text.replace(Regex("<think[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<thinking[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")

    /** 构造一行 SSE data。 */
    fun sseLine(obj: JSONObject): String = "data: $obj\n\n"

    /** 构造一个 chat.completion.chunk。 */
    fun chunkBase(id: String, created: Long, model: String, delta: JSONObject, finishReason: Any?): JSONObject =
        JSONObject().apply {
            put("id", id); put("object", "chat.completion.chunk")
            put("created", created); put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0); put("delta", delta)
                put("finish_reason", finishReason)
            }))
        }
}
