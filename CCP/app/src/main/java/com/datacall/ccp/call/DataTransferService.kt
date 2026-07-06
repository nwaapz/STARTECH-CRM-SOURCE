package com.datacall.ccp.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.datacall.ccp.MainActivity
import com.datacall.ccp.R
import com.datacall.ccp.modem.FskConfig
import com.datacall.ccp.session.TransferProgress
import com.datacall.ccp.session.TransferSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DataTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                receiveJob?.cancel()
                receiveJob = null
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(R.string.notification_listening_title),
                        getString(R.string.notification_listening_text)
                    )
                )
            }
            ACTION_INCOMING -> {
                val number = intent.getStringExtra(EXTRA_NUMBER)
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(R.string.notification_incoming_title),
                        getString(R.string.notification_incoming_text) + (number?.let { " ($it)" } ?: "")
                    )
                )
            }
            ACTION_RECEIVE -> {
                if (receiveJob?.isActive == true) return START_STICKY
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(R.string.notification_transfer_title),
                        "Receiving image data..."
                    )
                )
                startReceiveTransfer()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startReceiveTransfer() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            try {
                val session = TransferSession(
                    context = applicationContext,
                    config = FskConfig.forCall(),
                    onProgress = { updateProgressNotification(it) },
                    onLog = { }
                )
                val (file, metrics) = session.receiveImage()
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        "Transfer complete",
                        "Saved ${file.name} (${metrics.bytesTransferred} bytes)"
                    )
                )
            } catch (_: Exception) {
                if (CallSessionState.isListening) {
                    armListening(applicationContext)
                }
            } finally {
                CallSessionState.isReceiving = false
            }
        }
    }

    private fun updateProgressNotification(progress: TransferProgress) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.notification_transfer_title),
                progress.message.ifBlank { progress.phase.name }
            )
        )
    }

    override fun onDestroy() {
        receiveJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(title: String, text: String): Notification {
        createChannel()
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_transfer_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_transfer_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "data_transfer"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_ARM = "arm"
        private const val ACTION_INCOMING = "incoming"
        private const val ACTION_RECEIVE = "receive"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_NUMBER = "number"

        fun armListening(context: Context) {
            val intent = Intent(context, DataTransferService::class.java).apply { action = ACTION_ARM }
            context.startForegroundService(intent)
        }

        fun startIncomingAlert(context: Context, number: String?) {
            val intent = Intent(context, DataTransferService::class.java).apply {
                action = ACTION_INCOMING
                putExtra(EXTRA_NUMBER, number)
            }
            context.startForegroundService(intent)
        }

        fun startReceiveMode(context: Context) {
            val intent = Intent(context, DataTransferService::class.java).apply { action = ACTION_RECEIVE }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DataTransferService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
