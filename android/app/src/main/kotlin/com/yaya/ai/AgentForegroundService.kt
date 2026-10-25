package com.yaya.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class AgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "yaya_agent"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                val intent = Intent(context, AgentForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 前台服务启动失败不阻断任务（任务 44：保活只是加速器，任务状态在 loop_state 检查点）。
                android.util.Log.w("AgentFGS", "前台服务启动失败：${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("YAYai Agent")
            .setContentText("Agent 运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 dataSync 前台服务 24h 累积 6h 用尽时系统回调（API 33+）：
    // 必须几秒内 stopSelf（否则系统抛 RemoteServiceException 崩溃）；
    // 此路径不做任何阻塞操作——任务状态已由 loop_state 检查点（任务 43）承载，
    // 6h 用尽后需用户切前台才能重启 dataSync，恢复靠检查点续跑。
    // 注：容器桩 android.jar 只有 1 参 onTimeout（裁剪），真机/CI 完整 SDK 是 (startId, fgsType)
    // 两参（API 33+ 真实签名），故压制桩 jar 误报；运行时由系统调用两参版本。
    @Suppress("NOTHING_TO_OVERRIDE")
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
