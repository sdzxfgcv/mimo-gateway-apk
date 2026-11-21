package com.mimogateway

import android.content.Context
import org.json.JSONObject

/**
 * 极简配置存储。凭据用 SharedPreferences 保存（私有目录，其他 App 读不到）。
 */
class Config(ctx: Context) {
    val appContext: Context = ctx.applicationContext
    private val sp = appContext.getSharedPreferences("mimo_gateway", Context.MODE_PRIVATE)

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
        get() = sp.getString("model", "mimo-v2.6-pro") ?: "mimo-v2.6-pro"
        set(v) = sp.edit().putString("model", v).apply()

    var thinkMode: String
        get() = sp.getString("thinkMode", "separate") ?: "separate"
        set(v) = sp.edit().putString("thinkMode", v).apply()

    fun isConfigured(): Boolean = serviceToken.isNotBlank() && userId.isNotBlank() && phToken.isNotBlank()

    /** 从浏览器复制的 cURL 命令解析三个凭据，方便一键导入。 */
    fun importFromCurl(curl: String): Boolean {
        try {
            val st = Regex("""serviceToken[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            val uid = Regex("""userId[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            val ph = Regex("""xiaomichatbot_ph[=:]\s*["']?([^;"'\s&]+)""").find(curl)?.groupValues?.get(1)
            if (st != null && uid != null && ph != null) {
                serviceToken = st; userId = uid; phToken = ph
                return true
            }
            // 也支持直接粘贴 JSON：{"service_token":..,"user_id":..,"ph_token":..}
            val j = JSONObject(curl)
            serviceToken = j.optString("service_token", j.optString("serviceToken", serviceToken))
            userId = j.optString("user_id", j.optString("userId", userId))
            phToken = j.optString("ph_token", j.optString("xiaomichatbot_ph", phToken))
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
