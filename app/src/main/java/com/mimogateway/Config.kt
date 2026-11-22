package com.mimogateway

import android.content.Context
import org.json.JSONObject

/**
 * 极简配置存储。凭据用 SharedPreferences 保存（私有目录，其他 App 读不到）。
 * eta 版：使用 MiMo App 的 OpenAI 兼容通道，核心凭据是 passToken（换 session cookies）。
 */
class Config(ctx: Context) {
    val appContext: Context = ctx.applicationContext
    private val sp = appContext.getSharedPreferences("mimo_gateway", Context.MODE_PRIVATE)

    /** App 通道：小米账号长期凭证，用来换 mimo-server 的 session cookies */
    var passToken: String
        get() = sp.getString("passToken", "") ?: ""
        set(v) = sp.edit().putString("passToken", v).apply()

    var cUserId: String
        get() = sp.getString("cUserId", "") ?: ""
        set(v) = sp.edit().putString("cUserId", v).apply()

    var deviceId: String
        get() = sp.getString("deviceId", "") ?: ""
        set(v) = sp.edit().putString("deviceId", v).apply()

    /** App 通道换到的 session cookies（带过期时间，24h） */
    var appCookies: String
        get() = sp.getString("appCookies", "") ?: ""
        set(v) = sp.edit().putString("appCookies", v).apply()

    var appCookiesAt: Long
        get() = sp.getLong("appCookiesAt", 0L)
        set(v) = sp.edit().putLong("appCookiesAt", v).apply()

    var region: String
        get() = sp.getString("region", "SGP") ?: "SGP"
        set(v) = sp.edit().putString("region", v).apply()

    // 网页端凭据（保留，便于诊断/回退）
    var serviceToken: String
        get() = sp.getString("serviceToken", "") ?: ""
        set(v) = sp.edit().putString("serviceToken", v).apply()

    var userId: String
        get() = sp.getString("userId", "") ?: ""
        set(v) = sp.edit().putString("userId", v).apply()

    var phToken: String
        get() = sp.getString("phToken", "") ?: ""
        set(v) = sp.edit().putString("phToken", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    var model: String
        get() = sp.getString("model", "mimo-flash") ?: "mimo-flash"
        set(v) = sp.edit().putString("model", v).apply()

    var thinkMode: String
        get() = sp.getString("thinkMode", "separate") ?: "separate"
        set(v) = sp.edit().putString("thinkMode", v).apply()

    /** App 通道只需要 passToken；userId 用来标注账号。 */
    fun isConfigured(): Boolean = passToken.isNotBlank()

    /** 从浏览器复制的 cURL / JSON 导入凭据（兼容旧格式，也支持 passToken）。 */
    fun importFromCurl(curl: String): Boolean {
        try {
            val pass = Regex("""passToken[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            if (!pass.isNullOrBlank()) {
                passToken = pass
                Regex("""userId[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)?.let { userId = it }
                Regex("""cUserId[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)?.let { cUserId = it }
                return true
            }
            val st = Regex("""serviceToken[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            val uid = Regex("""userId[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            val ph = Regex("""xiaomichatbot_ph[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            if (st != null && uid != null && ph != null) {
                serviceToken = st; userId = uid; phToken = ph
                return true
            }
            val j = JSONObject(curl)
            j.optString("passToken", "").takeIf { it.isNotBlank() }?.let { passToken = it }
            j.optString("userId", "").takeIf { it.isNotBlank() }?.let { userId = it }
            j.optString("cUserId", "").takeIf { it.isNotBlank() }?.let { cUserId = it }
            return passToken.isNotBlank()
        } catch (e: Exception) {
            return false
        }
    }
}
