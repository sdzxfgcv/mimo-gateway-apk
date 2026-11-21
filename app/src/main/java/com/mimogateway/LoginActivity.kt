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

    /** 实时提示已检测到哪些 Cookie。 */
    private fun updateTipFromCookies() {
        val cookies = CookieManager.getInstance().getCookie(homeUrl) ?: ""
        val hasSt = cookies.contains("serviceToken=")
        val hasUid = cookies.contains("userId=")
        val hasPh = cookies.contains("xiaomichatbot_ph=")
        tvTip.text = when {
            hasSt && hasUid && hasPh ->
                "✓ 已检测到全部凭证！点上方「登录完成后点这里」即可导入。"
            hasSt && hasUid ->
                "已登录，还缺 xiaomichatbot_ph。请在网页里发一条消息后再提取。"
            hasSt ->
                "已检测到 serviceToken，继续完成登录…"
            else ->
                "请在下方网页中用小米账号登录，登录后随便发一条消息，再点上方按钮。"
        }
    }

    /** 从 CookieManager 提取凭证并保存。 */
    private fun extractCredentials() {
        val raw = CookieManager.getInstance().getCookie(homeUrl) ?: ""
        val st = cookieValue(raw, "serviceToken")
        val uid = cookieValue(raw, "userId")
        val ph = cookieValue(raw, "xiaomichatbot_ph")

        if (st.isNullOrBlank()) {
            Toast.makeText(this, "没找到 serviceToken，请先完成登录", Toast.LENGTH_LONG).show()
            return
        }
        if (ph.isNullOrBlank()) {
            Toast.makeText(this, "还缺 xiaomichatbot_ph，请先在网页里发一条消息", Toast.LENGTH_LONG).show()
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

    private fun cookieValue(cookieHeader: String, name: String): String? {
        for (part in cookieHeader.split(";")) {
            val kv = part.trim()
            if (kv.startsWith("$name=")) {
                return kv.substring(name.length + 1).trim().trim('"')
            }
        }
        return null
    }

    override fun onBackPressed() {
        // 网页内返回优先，退到首页再退出
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
