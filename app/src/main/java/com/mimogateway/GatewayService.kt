package com.mimogateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

class GatewayService : Service() {
    private var server: TinyHttpServer? = null
    private val binder = LocalBinder()
    private lateinit var cfg: Config

    companion object {
        @Volatile var isRunning = false
        private const val CHANNEL_ID = "mimo_gateway"
    }

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        createChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server?.running == true) return START_STICKY
        try {
            server = TinyHttpServer(cfg.port, ApiHandler(cfg)).also { it.start() }
            isRunning = true
            Log.i("MiMoGateway", "Server started on port ${cfg.port}")
        } catch (e: Exception) {
            Log.e("MiMoGateway", "Failed to start server", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MiMo Gateway", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }.apply {
            setSmallIcon(android.R.drawable.stat_sys_upload)
            setContentTitle("MiMo Gateway")
            setContentText("本地 API 网关运行中 · 端口 ${cfg.port}")
            setContentIntent(pi)
            setOngoing(true)
        }.build()
    }
}
