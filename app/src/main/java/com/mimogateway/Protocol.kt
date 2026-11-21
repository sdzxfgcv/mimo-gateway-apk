package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 协议转换：OpenAI 请求 → MiMo query 文本；MiMo 输出 → OpenAI SSE/non-stream 响应。
 * 对应原项目 src/mimo/serialize.ts + src/tools/prompt.ts + src/tools/format.ts。
 */
object Protocol {

    /** 把 OpenAI messages 序列化成发往 MiMo 的 query 文本。 */
    fun serializeMessages(messages: JSONArray): String {
        val system = mutableListOf<String>()
        val rest = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system" -> system.add(m.optString("content", ""))
                else -> rest.add(m)
            }
        }
        // 只保留最近 20 条非系统消息
        val trimmed = if (rest.size > 20) rest.takeLast(20) else rest
        val parts = mutableListOf<String>()
        if (system.isNotEmpty()) {
            parts.add("[System Instruction]\n${system.joinToString("\n")}")
        }
        if (trimmed.size > 1) {
            val history = trimmed.dropLast(1).joinToString("\n") { formatMessage(it) }
            parts.add("[Conversation History]\n$history")
        }
        if (trimmed.isNotEmpty()) {
            parts.add("[Current Query]\n${formatMessage(trimmed.last())}")
        }
        return parts.joinToString("\n\n")
    }

    private fun formatMessage(m: JSONObject): String {
        val role = m.optString("role")
        if (role == "assistant" && m.has("tool_calls")) {
            val calls = m.getJSONArray("tool_calls")
            val sb = StringBuilder("assistant: [Tool Calls]\n")
            for (i in 0 until calls.length()) {
                val fn = calls.getJSONObject(i).optJSONObject("function") ?: continue
                sb.append("${fn.optString("name")}(${fn.optString("arguments")})\n")
            }
            val c = m.optString("content", "")
            if (c.isNotBlank()) sb.append(c)
            return sb.toString().trimEnd()
        }
        if (role == "tool") {
            val name = m.optString("name", "unknown")
            return "[Tool Result] $name:\n${m.optString("content", "")}"
        }
        return "$role: ${m.optString("content", "")}"
    }

    /** 根据 tools 生成注入到 system 的工具调用提示词。 */
    fun buildToolSystemPrompt(tools: JSONArray): String {
        val descs = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function")
            val name: String
            val desc: String
            val params: JSONObject?
            if (fn != null) {
                name = fn.optString("name"); desc = fn.optString("description"); params = fn.optJSONObject("parameters")
            } else {
                name = t.optString("name"); desc = t.optString("description"); params = t.optJSONObject("input_schema")
            }
            val sb = StringBuilder("## $name")
            if (desc.isNotBlank()) sb.append(" // ${desc.split("\n")[0].take(80)}")
            val props = params?.optJSONObject("properties")
            if (props != null) {
                val required = params.optJSONArray("required")
                val reqSet = mutableSetOf<String>()
                if (required != null) for (k in 0 until required.length()) reqSet.add(required.optString(k))
                sb.append("\n")
                for (key in props.keys()) {
                    val p = props.getJSONObject(key)
                    val star = if (reqSet.contains(key)) "*" else ""
                    val ptype = p.optString("type", "any")
                    val pdesc = if (p.has("description")) " // ${p.optString("description")}" else ""
                    sb.append("  $key$star: $ptype$pdesc\n")
                }
            }
            descs.add(sb.toString().trimEnd())
        }
        return """
[Tool Call Format - Strictly Enforced]
<tool_call>
{"name": "tool_name", "arguments": {"param": "value"}}
</tool_call>
Requirements:
• Must wrap JSON with <tool_call> tags
• JSON must include "name" and "arguments" fields
• Do not output bash commands or markdown code blocks
• Do not output system tags such as <toolcall_status>, <toolcall_result>
• Use English only for all tags and labels
Available Tools: ${descs.joinToString("\n\n")}
        """.trimIndent()
    }

    data class ToolCall(val id: String, val name: String, val arguments: String)

    /** 从模型输出中解析 <tool_call>...</tool_call> 块。 */
    fun parseToolCalls(text: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        val regex = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>")
        for (match in regex.findAll(text)) {
            try {
                val obj = JSONObject(match.groupValues[1].trim())
                val name = obj.optString("name")
                if (name.isBlank()) continue
                val args = obj.opt("arguments")
                val argsStr = when (args) {
                    is String -> args
                    is JSONObject -> args.toString()
                    null -> "{}"
                    else -> args.toString()
                }
                out.add(ToolCall("call_${UUID.randomUUID().toString().replace("-", "").take(8)}", name, argsStr))
            } catch (e: Exception) { /* 忽略解析失败的块 */ }
        }
        return out
    }

    fun hasToolMarker(text: String) = text.contains("<tool_call>")

    /** 移除 <think>...</think> / <thinking>...</thinking> 标签及其内容（strip 模式）。 */
    fun stripThink(text: String): String =
        text.replace(Regex("<think[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<thinking[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")

    /** 构造 OpenAI SSE 数据行。 */
    fun sseLine(obj: JSONObject): String = "data: $obj\n\n"

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
