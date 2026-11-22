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
import java.util.UUID

/**
 * 内嵌浏览器登录：加载 MiMo 官网并跳转小米账号登录，抓 passToken / userId / cUserId。
 * passToken 用来走 MiMo App 的 OpenAI 兼容通道（/api/route/chat/completions），
 * 该通道原生支持 tools / tool_calls，是 Eta 工具调用能工作的前提。
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
    // App 通道核心：passToken（小米账号长期凭证）
    private val passNames = listOf("passToken", "pass_token")
    private val uidNames = listOf("userId", "xiaomichatbot_userId", "user_id")
    private val cuidNames = listOf("cUserId", "cuser_id")

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
        val pass = pick(raw, passNames)
        val uid = pick(raw, uidNames)
        val cuid = pick(raw, cuidNames)

        if (raw.length != lastLen) {
            lastLen = raw.length
            CrashLog.write(this, "LOGIN_COOKIES", RuntimeException("names=${cookieNames(raw)} pass=${pass.take(6)} uid=$uid cuid=$cuid"))
        }

        val full = pass.isNotBlank() && uid.isNotBlank()
        if (full) {
            tvTip.text = "✓ 已捕获 App 通道凭证（userId $uid），正在自动导入…"
            if (!imported) extractCredentials(manual = false)
        } else {
            tvTip.text = "请用小米账号登录（不要只扫码看页面）。" +
                "App 通道需要 passToken。（已捕获：${if (pass.isBlank()) "" else "passToken "}${if (uid.isBlank()) "" else "userId "}）"
        }
    }

    private fun extractCredentials(manual: Boolean) {
        val raw = allCookies()
        val pass = pick(raw, passNames)
        val uid = pick(raw, uidNames)
        val cuid = pick(raw, cuidNames)

        if (pass.isBlank()) {
            if (manual) Toast.makeText(this, "还没找到 passToken：请在页面里点登录，用小米账号完成一次登录", Toast.LENGTH_LONG).show()
            return
        }

        val cfg = Config(this)
        cfg.passToken = pass
        if (uid.isNotBlank()) cfg.userId = uid
        if (cuid.isNotBlank()) cfg.cUserId = cuid
        if (cfg.deviceId.isBlank()) cfg.deviceId = "pc_" + UUID.randomUUID().toString().replace("-", "")
        cfg.appCookies = ""   // 作废旧会话，下次请求重新换取
        cfg.appCookiesAt = 0L
        CookieManager.getInstance().flush()
        imported = true
        polling = false
        Toast.makeText(this, "凭证已保存，可以启动服务了", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    /** 精确匹配 Cookie 名，避免前缀误判。 */
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
