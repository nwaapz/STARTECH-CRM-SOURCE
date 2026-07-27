package com.example.smsgateway.cloud

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

/**
 * Periodic cloud hybrid sync while the gateway foreground service is running.
 * Runs immediately on start, then every [INTERVAL_MS] when URL+token are configured.
 */
class CloudSyncScheduler private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            Log.i(TAG, "Cloud sync scheduler started")
            // Initial sync shortly after service start
            delay(5_000L)
            while (isActive) {
                try {
                    runIfConfigured()
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic sync failed", e)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Debounced nudge after local inventory/registration writes. */
    fun requestSyncSoon() {
        start()
        scope.launch {
            delay(2_000L)
            try {
                runIfConfigured()
            } catch (e: Exception) {
                Log.w(TAG, "requestSyncSoon failed", e)
            }
        }
    }

    private suspend fun runIfConfigured() {
        val settings = SettingsStore.getInstance(appContext)
        if (settings.getCloudSyncUrl().isBlank() || settings.getCloudSyncToken().isBlank()) {
            return
        }
        val result = CloudSyncService.getInstance(appContext).syncOnce()
        if (!result.ok) {
            Log.w(TAG, "syncOnce: ${result.message}")
        }
    }

    companion object {
        private const val TAG = "CloudSyncScheduler"
        private const val INTERVAL_MS = 10L * 60 * 1000 // 10 minutes

        @Volatile
        private var instance: CloudSyncScheduler? = null

        fun getInstance(context: Context): CloudSyncScheduler {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncScheduler(context.applicationContext).also { instance = it }
            }
        }
    }
}
