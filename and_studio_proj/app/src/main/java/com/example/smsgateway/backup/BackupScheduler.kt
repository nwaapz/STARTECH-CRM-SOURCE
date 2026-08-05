package com.example.smsgateway.backup

import android.content.Context
import android.util.Log
import com.example.smsgateway.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs a daily backup while the gateway foreground service is active.
 * Checks about once an hour; creates a backup if today's calendar day has none yet.
 */
class BackupScheduler private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            Log.i(TAG, "Backup scheduler started")
            delay(15_000L)
            while (isActive) {
                try {
                    runDailyIfNeeded()
                } catch (e: Exception) {
                    Log.w(TAG, "Daily backup check failed", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun runDailyIfNeeded() {
        val settings = SettingsStore.getInstance(appContext)
        if (!settings.isBackupAutoEnabled()) return

        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (settings.getBackupLastDailyDay() == today) return

        val target = GatewayBackupManager.preferredAutoTarget(
            appContext,
            settings.getBackupAutoTargetId().ifBlank { null },
        ) ?: return

        Log.i(TAG, "Creating daily backup on ${target.id}")
        val result = GatewayBackupManager.createBackup(
            context = appContext,
            target = target,
            reason = BackupReason.DAILY,
        )
        if (result.ok) {
            settings.setBackupLastDailyDay(today)
            settings.setBackupLastSuccessMs(System.currentTimeMillis())
            Log.i(TAG, "Daily backup ok: ${result.file?.name}")
        } else {
            Log.w(TAG, "Daily backup failed: ${result.message}")
        }
    }

    companion object {
        private const val TAG = "BackupScheduler"
        private const val CHECK_INTERVAL_MS = 60L * 60 * 1000 // 1 hour

        @Volatile
        private var instance: BackupScheduler? = null

        fun getInstance(context: Context): BackupScheduler {
            return instance ?: synchronized(this) {
                instance ?: BackupScheduler(context.applicationContext).also { instance = it }
            }
        }
    }
}
