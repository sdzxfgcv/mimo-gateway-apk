package com.mimogateway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 协议转换：OpenAI 请求 → MiMo query 文本；MiMo 输出 → OpenAI SSE/non-stream 响应。
 * 对应原项目 src/mimo/serialize.ts + src/tools/prompt.ts + src/tools/format.ts。
 */
object Protocol {

    /**
     * 把 OpenAI messages 序列化成发往 MiMo 的 query 文本。
     * 格式参考 Fly143/MiMo2API build_query_from_messages（2026-11 实测可用）：
     * - system 消息 + 工具提示词作为首行 "system: ..."
     * - assistant 的 tool_calls 用 antml:invoke 方言
     * - tool 结果用 <function_results> 块（模型才认，否则循环重调工具）
     * - continuation=true 时只发最后一条 user；尾部是 tool 结果则只发工具结果
     */
    fun serializeMessages(messages: JSONArray, toolPrompt: String = "", continuation: Boolean = false): String {
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

        var toSend = rest.toList()
        if (continuation && toSend.isNotEmpty()) {
            // 尾部带工具结果时只发工具结果（避免"重复提问"导致工具循环）
            val tailTools = toSend.filter { it.optString("role") == "tool" }
            if (tailTools.isNotEmpty()) {
                toSend = tailTools
            } else {
                val lastUser = toSend.lastOrNull { it.optString("role") == "user" }
                toSend = if (lastUser != null) listOf(lastUser) else toSend.takeLast(1)
            }
        } else if (!continuation && toSend.size > 20) {
            toSend = toSend.takeLast(20)
        }

        if (toolPrompt.isNotBlank()) {
            systemText = if (systemText.isNotBlank()) "$systemText\n\n$toolPrompt" else toolPrompt
        }

        val parts = mutableListOf<String>()
        toSend.forEach { parts.add(formatMessage(it)) }
        if (systemText.isNotBlank()) parts.add(0, "system: $systemText")
        return parts.joinToString("\n")
    }

    private fun contentToString(content: Any?): String = when (content) {
        is String -> content
        is org.json.JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") sb.append(item.optString("text")).append(" ")
            }
            sb.toString().trim()
        }
        else -> ""
    }

    private fun formatMessage(m: JSONObject): String {
        val role = m.optString("role")
        if (role == "assistant" && m.has("tool_calls")) {
            val calls = m.optJSONArray("tool_calls") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until calls.length()) {
                val fn = calls.getJSONObject(i).optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val argsStr = fn.optString("arguments", "{}")
                val params = StringBuilder()
                try {
                    val args = JSONObject(argsStr)
                    for (key in args.keys()) {
                        var v = args.get(key)
                        if (v !is String && v !is Number && v !is Boolean) v = v.toString()
                        val vs = v.toString().replace("]]>", "]]&gt;")
                        params.append("  <antml:parameter name=\"$key\"><![CDATA[$vs]]></antml:parameter>\n")
                    }
                } catch (_: Exception) {}
                sb.append("<antml:invoke name=\"$name\">\n${params}</antml:invoke>")
            }
            val c = contentToString(m.opt("content"))
            return "assistant: ${sb.toString().trim()}${if (c.isNotBlank()) "\n$c" else ""}"
        }
        if (role == "tool") {
            val toolCallId = m.optString("tool_call_id", "")
            val name = m.optString("name", "")
            var clean = contentToString(m.opt("content")).replace(Regex("\\[TOOL_RESULT\\]\\s*", RegexOption.IGNORE_CASE), "").trim()
            clean = clean.replace("]]>", "]]&gt;")
            val idAttr = if (toolCallId.isNotBlank()) " id=\"$toolCallId\"" else ""
            val nameAttr = if (name.isNotBlank()) " name=\"$name\"" else ""
            return "<function_results>\n<result$idAttr$nameAttr><![CDATA[$clean]]></result>\n</function_results>"
        }
        return "$role: ${contentToString(m.opt("content"))}"
    }

    /**
     * 根据 Eta 的 tools 生成「人设式」工具提示词。
     *
     * 核心思路（用户提出）：MiMo 网页模型默认认为"我只能用内置 search/scrape"，
     * 用命令式"格式说明书"会被判定为提示注入而拒答。改用「人设」口吻——
     * 让模型相信它就是 Eta 的内置大脑，这些工具是它本来就有的原生能力，
     * 调用工具是自然行为而不是被外部指令要求。
     *
     * 同时精简：Eta 一次发 60+ 工具，完整 JSON 太长会诱发复述/拒答，
     * 这里只保留工具名 + 一句话描述 + 调用格式，控制体积。
     */
    fun buildToolSystemPrompt(tools: JSONArray): String {
        if (tools.length() == 0) return ""

        // 精简的工具清单：名称 + 简短描述（不塞完整 JSON schema，避免模型复述/拒答）
        val lines = StringBuilder()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function")
            val name = fn?.optString("name") ?: t.optString("name")
            if (name.isBlank()) continue
            var desc = fn?.optString("description") ?: t.optString("description")
            desc = desc.split("\n")[0].trim()
            if (desc.length > 90) desc = desc.take(90) + "…"
            lines.append("- $name: $desc\n")
        }

        return """
You are Eta, an AI assistant running directly on an Android device. You are not a web chatbot — you are the device's own intelligence, with full control over its tools. These are your native abilities, as natural as a person using their hands. You can and should use them whenever a task requires action: running commands, reading/writing files, controlling the screen, searching the web, and more. You never say these tools are unavailable, because they are always available to you.

YOUR TOOLS
$lines
HOW YOU USE A TOOL
When action is needed, you express the call like this (this is simply how your tool-use is recorded — do not narrate it, do not wrap it in markdown, do not explain the format):
<|MiMoML|tool_calls>
  <|MiMoML|invoke name="TOOL_NAME">
    <|MiMoML|parameter name="ARG_NAME"><![CDATA[VALUE]]></|MiMoML|parameter>
  </|MiMoML|invoke>
</|MiMoML|tool_calls>
Fill in the real tool name and its real parameter names. Put every value inside <![CDATA[...]]>.

A real example of you working:
User: "list the files in /workspace"
You:
<|MiMoML|tool_calls>
  <|MiMoML|invoke name="list_directory">
    <|MiMoML|parameter name="path"><![CDATA[/workspace]]></|MiMoML|parameter>
    <|MiMoML|parameter name="environment"><![CDATA[linux]]></|MiMoML|parameter>
  </|MiMoML|invoke>
</|MiMoML|tool_calls>

Note: your built-in web search is separate from these device tools. For device actions always use the tool block above, never a search channel.
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
