package com.mimogateway

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 内嵌浏览器登录：加载 MiMo Studio 官网，用户自己登录，
 * 自动抓取 serviceToken / userId / xiaomichatbot_ph 三个 Cookie 并保存。
 * 实现参考 Fly143/MiMo2API 的 LoginActivity（2026-11 实测可用）。
 */
class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var tvTip: TextView
    private val homeUrl = "https://aistudio.xiaomimimo.com/"
    private val ui = Handler(Looper.getMainLooper())
    private var imported = false
    private var polling = false
    private var lastLen = -1

    // 小米登录 Cookie 会落在多个域，全部读取
    private val cookieDomains = listOf(
        "https://aistudio.xiaomimimo.com/",
        "https://xiaomimimo.com/",
        "https://account.xiaomi.com/",
        "https://mi.com/"
    )
    // 实测小米下发的是 xiaomichatbot_serviceToken（带前缀），优先取它
    private val stNames = listOf("xiaomichatbot_serviceToken", "serviceToken", "service_token")
    private val uidNames = listOf("userId", "xiaomichatbot_userId", "user_id")
    private val phNames = listOf("xiaomichatbot_ph", "xiaomichatbotPh")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        title = "登录 MiMo 获取凭证"

        webView = findViewById(R.id.webview)
        tvTip = findViewById(R.id.tvLoginTip)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 移动端 UA，走小米手机登录流程
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                checkCookies()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                checkCookies()
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(homeUrl)

        findViewById<Button>(R.id.btnExtract).setOnClickListener { extractCredentials(manual = true) }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }

        // 登录常由页面内 JS 跳转完成，不触发 onPageFinished，故定时轮询
        polling = true
        val poller = object : Runnable {
            override fun run() {
                checkCookies()
                if (polling && !imported) ui.postDelayed(this, 1500)
            }
        }
        ui.postDelayed(poller, 2000)
    }

    /** 读取所有域的 Cookie。 */
    private fun allCookies(): String {
        val cmg = CookieManager.getInstance()
        cmg.flush()
        val sb = StringBuilder()
        for (d in cookieDomains) {
            val c = cmg.getCookie(d)
            if (!c.isNullOrEmpty()) sb.append(c).append("; ")
        }
        return sb.toString()
    }

    private fun checkCookies() {
        val raw = allCookies()
        val st = pick(raw, stNames)
        val uid = pick(raw, uidNames)
        val ph = pick(raw, phNames)

        // Cookie 变化时更新诊断提示
        if (raw.length != lastLen) {
            lastLen = raw.length
            CrashLog.write(this, "LOGIN_COOKIES", RuntimeException("names=${cookieNames(raw)} st=${st.take(6)} uid=$uid ph=${ph.take(6)}"))
        }

        val full = st.isNotBlank() && uid.isNotBlank() && ph.isNotBlank()
        if (full) {
            tvTip.text = "✓ 已捕获全部凭证（userId $uid），正在自动导入…"
            if (!imported) extractCredentials(manual = false)
        } else {
            tvTip.text = "请在下方用小米账号登录，登录后发一条消息。" +
                "（已捕获：${if (st.isBlank()) "" else "serviceToken "}${if (uid.isBlank()) "" else "userId "}${if (ph.isBlank()) "" else "xiaomichatbot_ph "}）"
        }
    }

    private fun extractCredentials(manual: Boolean) {
        val raw = allCookies()
        val st = pick(raw, stNames)
        val uid = pick(raw, uidNames)
        val ph = pick(raw, phNames)

        if (st.isBlank()) {
            if (manual) Toast.makeText(this, "还没找到 serviceToken，请先完成登录", Toast.LENGTH_LONG).show()
            return
        }
        if (ph.isBlank()) {
            if (manual) Toast.makeText(this, "还缺 xiaomichatbot_ph，请先在网页发一条消息", Toast.LENGTH_LONG).show()
            return
        }

        val cfg = Config(this)
        cfg.serviceToken = st
        cfg.userId = uid
        cfg.phToken = ph
        CookieManager.getInstance().flush()
        imported = true
        polling = false
        Toast.makeText(this, "凭证已保存，可以启动服务了", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    /** 精确匹配 Cookie 名（避免 xiaomichatbot_serviceToken 被误判成 serviceToken）。 */
    private fun pick(cookies: String, names: List<String>): String {
        for (name in names) {
            val parts = cookies.split(";")
            for (part in parts) {
                val kv = part.trim()
                if (kv.startsWith("$name=", ignoreCase = true)) {
                    val v = kv.substring(name.length + 1).trim().trim('"')
                    if (v.isNotBlank()) return v
                }
            }
        }
        return ""
    }

    private fun cookieNames(cookieHeader: String): String =
        cookieHeader.split(";").map { it.trim().substringBefore("=") }
            .filter { it.isNotBlank() }.distinct().joinToString(", ")

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
