package com.mimogateway

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class LapsedError(message: String) : RuntimeException(message)

/**
 * MiMo App（桌面端/CLI）通道。
 *
 * 端点：https://mimo-server-<region>.xiaomimimo.com/api/route/chat/completions
 * 这是 OpenAI 兼容接口，原生支持 tools / tool_calls / reasoning_content / usage，
 * 不需要任何 MiMoML 提示词转换。会话由 passToken 经 /user/xiaomi/me 的重定向换取。
 *
 * 参考：magpie-community/plugins packages/mimo/index.mjs（2026-11）
 */
class AppClient(private val cfg: Config) {
    private val hosts = linkedMapOf(
        "SGP" to "https://mimo-server-sgp.xiaomimimo.com/api",
        "RU" to "https://mimo-server-ru.xiaomimimo.com/api",
        "IN" to "https://mimo-server-in.xiaomimimo.com/api"
    )
    private val appVersion = "26.929.292248"
    private val source = "mimocode-cli-free"
    private val ua = "miNative PC/Normal Windows_NT/10.0.26100 SDKV/1.0.0 DEVT/PC DEVS/Windows APP/miaccount_desktop APPV/0.1.0"
    private val lifetime = 23 * 60 * 60 * 1000L

    private data class Ck(val value: String, val domain: String)

    private fun log(m: String) {
        try { CrashLog.write(cfg.appContext, "APPCH", RuntimeException(m)) } catch (_: Exception) {}
    }

    private fun base(): String = hosts[cfg.region] ?: hosts["SGP"]!!

    /** 用 passToken 走服务端 /user/xiaomi/me → 小米登录 → /sts，换取会话 cookies。 */
    private fun renew(depth: Int = 0): String {
        if (cfg.passToken.isBlank()) {
            throw RuntimeException("未抓到 passToken：请重新打开 APK 的登录页，用小米账号完成一次登录")
        }
        if (cfg.deviceId.isBlank()) cfg.deviceId = "pc_" + UUID.randomUUID().toString().replace("-", "")

        val jar = LinkedHashMap<String, Ck>()
        for ((k, v) in listOf(
            "userId" to cfg.userId,
            "passToken" to cfg.passToken,
            "cUserId" to cfg.cUserId,
            "deviceId" to cfg.deviceId,
            "pass_ua" to "pc",
            "uLocale" to "zh_CN"
        )) {
            if (v.isNotBlank()) jar[k] = Ck(v, "account.xiaomi.com")
        }

        var currentBase = base()
        var url = currentBase.trimEnd('/') + "/user/xiaomi/me"
        var text = ""
        for (hop in 0..10) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("X-Client-Version", appVersion)
            val cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value.value}" }
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)

            val code = conn.responseCode
            for ((k, vs) in conn.headerFields) {
                if (k?.equals("Set-Cookie", ignoreCase = true) != true || vs == null) continue
                for (line in vs) {
                    val pair = line.substringBefore(";")
                    val eq = pair.indexOf('=')
                    if (eq <= 0) continue
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim().trim('"')
                    var domain = URL(url).host.lowercase()
                    Regex("""(?i)domain=([^;]+)""").find(line)?.let {
                        domain = it.groupValues[1].trim().trimStart('.').lowercase()
                    }
                    val gone = Regex("""(?i)(max-age=0|expires=[^;]*(1970|01 Jan 1970))""").containsMatchIn(line)
                    if (gone) jar.remove(name) else jar[name] = Ck(value, domain)
                }
            }
            val loc = conn.getHeaderField("Location")
            text = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }
            conn.disconnect()

            if (code in 300..399 && !loc.isNullOrBlank()) {
                var next = URL(URL(url), loc).toString()
                val host = URL(currentBase).host
                if (next.startsWith("http://") && next.contains("://$host")) {
                    next = "https://" + next.removePrefix("http://")
                }
                url = next
                continue
            }
            if (code !in 200..299) throw RuntimeException("MiMo 会话换取失败 HTTP $code")
            break
        }

        val me = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw LapsedError("小米登录已失效，请重新在 APK 内登录")
        }
        if (me.optInt("code", -1) != 0) throw LapsedError("小米登录已失效：code=${me.optInt("code")}")
        val data = me.optJSONObject("data")
            ?: throw LapsedError("小米登录已失效：未返回账号信息")
        if (data.optString("userId").isBlank()) throw LapsedError("小米登录已失效：未返回 userId")

        val region = data.optString("region", "").trim().uppercase()
        if (region.isNotBlank() && hosts.containsKey(region) && region != cfg.region && depth < 2) {
            cfg.region = region
            return renew(depth + 1) // 换到账号所属区域的服务器再换取一次
        }

        val host = URL(currentBase).host.lowercase()
        val cookies = jar.entries
            .filter { it.value.domain == host || it.value.domain.endsWith(".xiaomimimo.com") }
            .filter { it.key !in setOf("passToken", "deviceId", "pass_ua", "uLocale") }
            .joinToString("; ") { "${it.key}=${it.value.value}" }
        if (cookies.isBlank()) throw LapsedError("未换到 MiMo 会话 Cookie，请重新登录")

        cfg.appCookies = cookies
        cfg.appCookiesAt = System.currentTimeMillis()
        log("renew ok region=${cfg.region} cookies=${cookies.take(160)}")
        return cookies
    }

    fun cookies(force: Boolean = false): String {
        if (!force && cfg.appCookies.isNotBlank() && System.currentTimeMillis() - cfg.appCookiesAt < lifetime) {
            return cfg.appCookies
        }
        return renew()
    }

    /** 把 Eta 里常见模型名映射到 App 通道的名字 */
    private fun rewriteBody(body: String): String {
        val j = try { JSONObject(body) } catch (_: Exception) { return body }
        val m = j.optString("model", "")
        val mapped = when (m) {
            "mimo-v2.6-flash", "mimo-flash", "flash" -> "mimo-flash"
            "mimo-v2.6-pro", "mimo-pro", "mimo-auto", "pro", "" -> "mimo-pro"
            else -> m
        }
        j.put("model", mapped)
        return j.toString()
    }

    /**
     * 调 App 的 OpenAI 兼容端点，逐行回调上游原始 SSE / JSON 行。
     * 不解析、不改写：tools / tool_calls / reasoning_content / usage 全部原生透传。
     */
    fun chat(body: String, stream: Boolean, onLine: (String) -> Unit) {
        var attempt = 0
        while (true) {
            val cookie = cookies(force = attempt > 0)
            val url = URL(base().trimEnd('/') + "/route/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 0
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("X-Mimo-Source", source)
            conn.setRequestProperty("X-Client-Version", appVersion)
            conn.setRequestProperty("User-Agent", ua)

            val out: OutputStream = conn.outputStream
            out.use { it.write(rewriteBody(body).toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream.bufferedReader().readText().take(600) } catch (_: Exception) { "" }
                conn.disconnect()
                log("chat HTTP $code ${err.take(200)}")
                if ((code == 401 || code == 403) && attempt == 0) {
                    attempt++
                    continue // 会话过期，强制重换一次
                }
                throw RuntimeException("MiMo App HTTP $code: $err")
            }
            try {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) onLine(line!!)
                }
            } finally {
                conn.disconnect()
            }
            return
        }
    }

    fun models(): List<String> = listOf("mimo-flash", "mimo-pro", "mimo-v2.6-flash", "mimo-v2.6-pro")
}
