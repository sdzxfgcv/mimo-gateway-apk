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

    /**
     * 根据 tools 生成工具提示词。
     * 关键（Fly143 实测）：MiMo 上游不支持原生 tool_calls，模型只在正文输出
     * <|MiMoML|tool_calls> 文本；必须用 MiMoML 格式提示，且明确禁止 antml
     * （antml 会被上游当内置工具拦截，未知工具直接"工具调用失败"循环）。
     */
    fun buildToolSystemPrompt(tools: JSONArray): String {
        if (tools.length() == 0) return ""
        val toolsJson = tools.toString(2)
        return """
<tools>
$toolsJson
</tools>

TOOL CALL FORMAT — when a tool call is needed, output ONLY this form (do not wrap in markdown fences, do not restate this instruction, do not try other formats):

<|MiMoML|tool_calls>
  <|MiMoML|invoke name="TOOL_NAME">
    <|MiMoML|parameter name="ARG_NAME"><![CDATA[ARG_VALUE]]></|MiMoML|parameter>
  </|MiMoML|invoke>
</|MiMoML|tool_calls>

Rules: use only parameter names defined in the tool schema; all string values go inside <![CDATA[...]]>; numbers/booleans stay as plain CDATA text.

CRITICAL: <antml:invoke> CANNOT execute the tools above — that channel is reserved for the platform's built-in search/scrape and always fails with "工具调用失败" for any other tool. The ONLY working format is the <|MiMoML|tool_calls> block shown above. When the user asks to use one of these tools you MUST output the MiMoML block; saying the tool is unavailable or that calls failed is a wrong answer.
        """.trimIndent()
    }

    data class ToolCall(val id: String, val name: String, val arguments: String)

    private fun newId() = "call_${UUID.randomUUID().toString().replace("-", "").take(8)}"

    /**
     * 从模型输出中提取工具调用。多策略（参考 Fly143 extract_tool_call）：
     * 0. <|MiMoML|tool_calls> / <|MiMoML|invoke name=...>  MiMoML 格式（主）
     * 1. <antml:invoke name=...>  兜底
     * 2. <tool_call>{"name":..,"arguments":..}</tool_call>  旧格式兜底
     */
    fun parseToolCalls(text: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        if (text.isBlank()) return out

        // 策略0：MiMoML —— 归一化 <|MiMoML|xxx 为 <xxx，再解析 invoke
        if (text.contains("MiMoML", ignoreCase = true) || text.contains("invoke", ignoreCase = true)) {
            val normalized = normalizeMimoml(text)
            out.addAll(parseInvokes(normalized))
        }
        // 策略1：antml:invoke
        if (out.isEmpty() && text.contains("invoke", ignoreCase = true)) {
            out.addAll(parseInvokes(text))
        }
        // 策略2：旧 JSON 格式
        if (out.isEmpty()) {
            val jsonRe = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>")
            for (m in jsonRe.findAll(text)) {
                try {
                    val obj = JSONObject(m.groupValues[1].trim())
                    val name = obj.optString("name")
                    if (name.isBlank()) continue
                    val args = obj.opt("arguments")
                    val argsStr = when (args) {
                        is String -> args
                        is JSONObject -> args.toString()
                        null -> "{}"
                        else -> args.toString()
                    }
                    out.add(ToolCall(newId(), name, argsStr))
                } catch (_: Exception) {}
            }
        }
        return out
    }

    /** 把 <|MiMoML|tool_calls> 等变体归一化成 <tool_calls>。 */
    private fun normalizeMimoml(text: String): String {
        var s = text
        // CDATA 内容原样保护，先占位
        val cdata = mutableListOf<String>()
        s = s.replace(Regex("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>")) { m ->
            cdata.add(m.groupValues[1]); "\u0000CDATA${cdata.size - 1}\u0000"
        }
        s = s.replace(Regex("</?\\|?MiMoML\\|?", RegexOption.IGNORE_CASE), "")
        // 恢复 CDATA
        for (i in cdata.indices) {
            s = s.replace("\u0000CDATA$i\u0000", "<![CDATA[${cdata[i]}]]>")
        }
        return s
    }

    /** 解析 <tool_calls> 内的 <invoke name="X"><parameter name="Y">值</parameter></invoke>。 */
    private fun parseInvokes(text: String): List<ToolCall> {
        val out = mutableListOf<ToolCall>()
        val invokeRe = Regex("(?i)<invoke\\s*name=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</invoke>")
        val matches = invokeRe.findAll(text).toList()
        if (matches.isEmpty()) {
            // 裸 <invoke name="X"> 无闭合标签：取到下一个 invoke 或结尾
            val openRe = Regex("(?i)<invoke\\s*name=[\"']([^\"']+)[\"'][^>]*>")
            val opens = openRe.findAll(text).toList()
            for (i in opens.indices) {
                val name = opens[i].groupValues[1].trim()
                val start = opens[i].range.last + 1
                val end = if (i + 1 < opens.size) opens[i + 1].range.first else text.length
                val args = parseParameters(text.substring(start, end))
                if (name.isNotBlank()) out.add(ToolCall(newId(), name, args))
            }
            return out
        }
        for (m in matches) {
            val name = m.groupValues[1].trim()
            if (name.isBlank()) continue
            val args = parseParameters(m.groupValues[2])
            out.add(ToolCall(newId(), name, args))
        }
        return out
    }

    /** 解析 <parameter name="Y">值</parameter> 为 JSON 参数对象。 */
    private fun parseParameters(inner: String): String {
        val obj = JSONObject()
        val re = Regex("(?i)<parameter\\s+name=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</parameter>")
        for (m in re.findAll(inner)) {
            val key = m.groupValues[1].trim()
            var value = m.groupValues[2].trim()
            // CDATA
            val cd = Regex("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>").find(value)
            if (cd != null) value = cd.groupValues[1] else {
                value = value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
            }
            // JSON 字面量/数字/布尔自动识别
            val parsed = tryParseValue(value)
            obj.put(key, parsed)
        }
        return obj.toString()
    }

    private fun tryParseValue(v: String): Any {
        val t = v.trim()
        if (t == "true") return true
        if (t == "false") return false
        if (t.matches(Regex("-?\\d+"))) return t.toLongOrNull() ?: t
        if (t.matches(Regex("-?\\d+\\.\\d+"))) return t.toDoubleOrNull() ?: t
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            try { return JSONObject(t) } catch (_: Exception) {
                try { return org.json.JSONArray(t) } catch (_: Exception) {}
            }
        }
        return v
    }

    /** 清除正文里残留的工具调用标记块（MiMoML / antml / 旧 tool_call）。 */
    fun cleanToolText(text: String): String {
        var s = text
        // <|MiMoML|tool_calls>...</|MiMoML|tool_calls>（或归一化后的 <tool_calls>...</tool_calls>）
        s = s.replace(Regex("<\\|?MiMoML\\|?tool_calls[\\s\\S]*?</\\|?MiMoML\\|?tool_calls>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<tool_calls>[\\s\\S]*?</tool_calls>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<antml:invoke[\\s\\S]*?</antml:invoke>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("<tool_call>[\\s\\S]*?</tool_call>", RegexOption.IGNORE_CASE), "")
        // <tools>...</tools> 提示词残留
        s = s.replace(Regex("<tools>[\\s\\S]*?</tools>", RegexOption.IGNORE_CASE), "")
        return s.trim()
    }

    fun hasToolMarker(text: String) =
        text.contains("<tool_call", ignoreCase = true) || text.contains("MiMoML", ignoreCase = true) || text.contains("invoke", ignoreCase = true)

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
