package com.mingh.wifianchor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.mingh.wifianchor.probe.ScanHub

/**
 * 极简前台服务：存在的唯一理由是探针要回答一个问题——
 * 「挂着前台服务时，扫描配额会不会比纯后台宽松」。
 * 这决定了正式版后台常驻能不能秒级判定，所以必须能现场开关对照测。
 */
class AnchorService : Service() {

    companion object {
        const val CH_ID = "anchor_probe"
        const val NOTIF_ID = 42

        @Volatile
        var running: Boolean = false

        fun start(ctx: Context) {
            val i = Intent(ctx, AnchorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { ScanHub.log("启动前台服务失败: ${it.javaClass.simpleName} ${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AnchorService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CH_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CH_ID, "Wi-Fi 探针", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = try {
            Notification.Builder(this, CH_ID)
                .setContentTitle("WiFi 锚点探针")
                .setContentText("前台服务运行中（用于对照扫描配额）")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        } catch (t: Throwable) {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("WiFi 锚点探针")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        }
        try {
            startForeground(NOTIF_ID, n)
            running = true
            ScanHub.log("前台服务已启动（Android 14+ 的 location 类型前台服务需要定位权限）")
        } catch (t: Throwable) {
            running = false
            ScanHub.log("startForeground 失败: ${t.javaClass.simpleName} ${t.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ScanHub.log("前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
