package com.example.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.smsgateway.lan.LanHttpServer
import com.example.smsgateway.lan.LanNetworkUtils
import com.example.smsgateway.lan.LanTraceLog

class GatewayForegroundService : Service() {
    private var lanServer: LanHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LanTraceLog.i("Service", "Foreground service stop requested")
                ClubAutoMessageScheduler.getInstance(this).stop()
                com.example.smsgateway.backup.BackupScheduler.getInstance(this).stop()
                com.example.smsgateway.license.LicenseChecker.getInstance(this).stop()
                stopLanServer()
                SettingsStore.getInstance(this).setServiceEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                SettingsStore.getInstance(this).setServiceEnabled(true)
                LanTraceLog.i("Service", "Foreground service started")
                SyncEventWriter.getInstance(this).attachContext(this)
                startLanServerIfEnabled()
                ClubAutoMessageScheduler.getInstance(this).start()
                com.example.smsgateway.backup.BackupScheduler.getInstance(this).start()
                com.example.smsgateway.license.LicenseChecker.getInstance(this).start()
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        ClubAutoMessageScheduler.getInstance(this).stop()
        com.example.smsgateway.backup.BackupScheduler.getInstance(this).stop()
        stopLanServer()
        super.onDestroy()
    }

    private fun startLanServerIfEnabled() {
        val settings = SettingsStore.getInstance(this)
        if (!settings.isLanServerEnabled()) {
            LanTraceLog.i("Service", "LAN server not started — LAN CRM disabled")
            stopLanServer()
            return
        }
        LanTraceLog.i("Service", "Starting LAN server (service running)")
        lanServer = LanHttpServer.getInstance(this).also { it.start() }
    }

    private fun stopLanServer() {
        LanTraceLog.i("Service", "Stopping LAN server")
        lanServer?.stop()
        lanServer = null
        LanHttpServer.getInstance(this).stop()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val settings = SettingsStore.getInstance(this)
        val contentText = if (settings.isLanServerEnabled()) {
            val url = LanNetworkUtils.buildLanUrl(this, settings.getLanPort())
            getString(R.string.notification_text_lan, url)
        } else {
            getString(R.string.notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    fun refreshNotification() {
        if (SettingsStore.getInstance(this).isServiceEnabled()) {
            startLanServerIfEnabled()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val CHANNEL_ID = "sms_gateway_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.smsgateway.STOP"

        fun start(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRunning(context: Context): Boolean {
            return SettingsStore.getInstance(context).isServiceEnabled()
        }

        fun refreshLanServer(context: Context) {
            val settings = SettingsStore.getInstance(context)
            LanTraceLog.i("Service", "refreshLanServer serviceEnabled=${settings.isServiceEnabled()} lanEnabled=${settings.isLanServerEnabled()}")
            if (!settings.isServiceEnabled()) {
                LanTraceLog.w("Service", "refreshLanServer skipped — gateway service not running")
                return
            }
            val server = LanHttpServer.getInstance(context)
            if (settings.isLanServerEnabled()) {
                server.restartIfNeeded()
            } else {
                server.stop()
            }
            val intent = Intent(context, GatewayForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }
}
