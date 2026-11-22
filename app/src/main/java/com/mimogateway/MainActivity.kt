package com.mimogateway

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.*

class MainActivity : Activity() {
    private lateinit var cfg: Config
    private var bound = false
    private var service: GatewayService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as GatewayService.LocalBinder).getService()
            bound = true
            refreshStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false; service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cfg = Config(this)

        // 捕获主线程未处理异常，写文件而不是静默闪退
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            CrashLog.write(this, "MainActivity.uncaught", e)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        val etCurl = findViewById<EditText>(R.id.etCurl)
        val etSt = findViewById<EditText>(R.id.etServiceToken)
        val etUid = findViewById<EditText>(R.id.etUserId)
        val etPh = findViewById<EditText>(R.id.etPhToken)
        val etKey = findViewById<EditText>(R.id.etApiKey)
        val etPort = findViewById<EditText>(R.id.etPort)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvHint = findViewById<TextView>(R.id.tvHint)

        etSt.setText(cfg.passToken)
        etUid.setText(cfg.userId)
        etPh.setText(cfg.cUserId)
        etKey.setText(cfg.apiKey)
        etPort.setText(if (cfg.port != 8080) cfg.port.toString() else "")

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            startActivityForResult(Intent(this, LoginActivity::class.java), 2001)
        }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val ok = cfg.importFromCurl(etCurl.text.toString().trim())
            Toast.makeText(this, if (ok) "导入成功" else "解析失败，请检查 cURL 内容", Toast.LENGTH_SHORT).show()
            if (ok) {
                etSt.setText(cfg.passToken)
                etUid.setText(cfg.userId)
                etPh.setText(cfg.cUserId)
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            cfg.passToken = etSt.text.toString().trim()
            cfg.userId = etUid.text.toString().trim()
            cfg.cUserId = etPh.text.toString().trim()
            cfg.appCookies = ""   // 凭据变更，作废旧会话
            cfg.appCookiesAt = 0L
            cfg.apiKey = etKey.text.toString().trim()
            val p = etPort.text.toString().trim().toIntOrNull()
            if (p != null && p in 1..65535) cfg.port = p
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            if (GatewayService.isRunning) {
                stopService(Intent(this, GatewayService::class.java))
                Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
                tvStatus.text = "状态：未启动"
                tvHint.text = ""
            } else {
                if (!cfg.isConfigured()) {
                    Toast.makeText(this, "请先登录获取 passToken", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                requestNotifPermission()
                CrashLog.clear(this)
                startGateway()
                // 延迟检查是否启动成功，失败则显示崩溃日志
                findViewById<Button>(R.id.btnToggle).postDelayed({
                    if (!GatewayService.isRunning) {
                        val err = CrashLog.read(this@MainActivity)
                        if (err.isNotBlank()) {
                            findViewById<TextView>(R.id.tvStatus).text = "状态：启动失败"
                            findViewById<TextView>(R.id.tvHint).text = "错误日志：\n$err"
                        }
                    }
                }, 1500)
            }
        }

        refreshStatus()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun startGateway() {
        val intent = Intent(this, GatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        findViewById<TextView>(R.id.tvStatus).text = "状态：启动中…"
    }

    private fun refreshStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvHint = findViewById<TextView>(R.id.tvHint)
        val running = GatewayService.isRunning
        tvStatus.text = "状态：${if (running) "运行中 ✓" else "未启动"}"
        val key = cfg.apiKey.ifBlank { "(未设置密钥，将不校验)" }
        tvHint.text = if (running) {
            "本机地址：http://127.0.0.1:${cfg.port}\n" +
            "局域网：http://手机IP:${cfg.port}\n" +
            "OpenAI base_url：http://手机IP:${cfg.port}/v1\n" +
            "apiKey：$key\n" +
            "模型：${cfg.model}"
        } else {
            "启动后：把客户端 base_url 设为 http://手机IP:${cfg.port}/v1"
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bindService(Intent(this, GatewayService::class.java), conn, 0)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(conn); bound = false }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            // 登录后刷新输入框
            findViewById<EditText>(R.id.etServiceToken).setText(cfg.passToken)
            findViewById<EditText>(R.id.etUserId).setText(cfg.userId)
            findViewById<EditText>(R.id.etPhToken).setText(cfg.cUserId)
            Toast.makeText(this, "凭证已就绪，点「启动服务」即可", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    override fun onResume() { super.onResume(); refreshStatus() }
}
