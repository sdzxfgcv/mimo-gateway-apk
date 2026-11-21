package com.mimogateway

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 内嵌浏览器登录：加载 MiMo Studio 官网，用户自己登录，
 * 然后从 CookieManager 提取 serviceToken / userId / xiaomichatbot_ph。
 */
class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var tvTip: TextView
    private val homeUrl = "https://aistudio.xiaomimimo.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        title = "登录 MiMo 获取凭证"

        webView = findViewById(R.id.webview)
        tvTip = findViewById(R.id.tvLoginTip)

        // 关键：允许第三方 Cookie（小米登录会跨 account.xiaomi.com 域跳转）
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // 用桌面 UA，避免官网把 WebView 识别为不支持的浏览器
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                updateTipFromCookies()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                updateTipFromCookies()
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(homeUrl)

        findViewById<Button>(R.id.btnExtract).setOnClickListener { extractCredentials() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    companion object {
        // 小米登录可能用不同的 Cookie 名，全部兼容（检测与提取用同一套规则，避免误判）
        private val ST_NAMES = listOf("serviceToken", "xiaomichatbot_serviceToken", "service_token")
        private val UID_NAMES = listOf("userId", "xiaomichatbot_userId", "user_id")
        private val PH_NAMES = listOf("xiaomichatbot_ph", "xiaomichatbotPh")
    }

    /** 实时提示已检测到哪些 Cookie。 */
    private fun updateTipFromCookies() {
        val cookies = CookieManager.getInstance().getCookie(homeUrl) ?: ""
        val st = findCookie(cookies, ST_NAMES)
        val uid = findCookie(cookies, UID_NAMES)
        val ph = findCookie(cookies, PH_NAMES)
        tvTip.text = when {
            !st.isNullOrBlank() && !uid.isNullOrBlank() && !ph.isNullOrBlank() ->
                "✓ 已检测到全部凭证！点上方「登录完成后点这里」即可导入。"
            !st.isNullOrBlank() && !uid.isNullOrBlank() ->
                "已登录，还缺 xiaomichatbot_ph。请在网页里发一条消息后再提取。"
            !st.isNullOrBlank() ->
                "已检测到 serviceToken，继续完成登录…"
            cookies.isNotBlank() ->
                "已打开网页，正在等待登录…（Cookie: ${cookieNames(cookies)}）"
            else ->
                "请在下方网页中用小米账号登录，登录后随便发一条消息，再点上方按钮。"
        }
    }

    /** 从 CookieManager 提取凭证并保存。 */
    private fun extractCredentials() {
        // 同时读取多个域，serviceToken 常落在父域 .xiaomimimo.com
        val raw = listOf(
            CookieManager.getInstance().getCookie(homeUrl) ?: "",
            CookieManager.getInstance().getCookie("https://xiaomimimo.com") ?: "",
            CookieManager.getInstance().getCookie("https://www.xiaomimimo.com") ?: ""
        ).joinToString("; ")

        val st = findCookie(raw, ST_NAMES)
        val uid = findCookie(raw, UID_NAMES)
        val ph = findCookie(raw, PH_NAMES)

        if (st.isNullOrBlank()) {
            val names = cookieNames(raw)
            tvTip.text = "没找到 serviceToken。当前检测到的 Cookie 名：$names\n（请完成登录后重试，或把此提示截图给我）"
            Toast.makeText(this, "没找到 serviceToken，请把提示截图", Toast.LENGTH_LONG).show()
            return
        }
        if (ph.isNullOrBlank()) {
            val names = cookieNames(raw)
            tvTip.text = "还缺 xiaomichatbot_ph，请在网页里发一条消息。当前 Cookie：$names"
            Toast.makeText(this, "还缺 xiaomichatbot_ph，请先发一条消息", Toast.LENGTH_LONG).show()
            return
        }

        val cfg = Config(this)
        cfg.serviceToken = st
        cfg.userId = uid ?: ""
        cfg.phToken = ph
        Toast.makeText(this, "凭证已保存，可以启动服务了", Toast.LENGTH_SHORT).show()

        // 同步 Cookie 到磁盘，防止重启丢失
        CookieManager.getInstance().flush()
        setResult(RESULT_OK)
        finish()
    }

    /** 按候选名精确提取 Cookie 值（忽略大小写），避免 xiaomichatbot_serviceToken 被误判成 serviceToken。 */
    private fun findCookie(cookieHeader: String, names: List<String>): String? {
        val parts = cookieHeader.split(";").map { it.trim() }
        for (name in names) {
            for (part in parts) {
                if (part.startsWith("$name=", ignoreCase = true)) {
                    val v = part.substring(name.length + 1).trim().trim('"')
                    if (v.isNotBlank()) return v
                }
            }
        }
        return null
    }

    /** 仅返回 Cookie 名称（不含敏感值），用于调试提示。 */
    private fun cookieNames(cookieHeader: String): String =
        cookieHeader.split(";").map { it.trim().substringBefore("=") }
            .filter { it.isNotBlank() }.distinct().joinToString(", ")

    override fun onBackPressed() {
        // 网页内返回优先，退到首页再退出
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
