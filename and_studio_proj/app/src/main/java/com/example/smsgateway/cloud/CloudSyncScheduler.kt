package com.example.smsgateway.cloud

import android.content.Context
import android.util.Log
import com.example.smsgateway.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced auto-push trigger after local writes. There is no periodic/background
 * sync loop anymore — pulling is a manual "Get from server" action, and pushing is
 * either a manual "Send to server" action or this short debounce right after a
 * local registration/inventory write. Silently no-ops if the server isn't
 * configured or unreachable, leaving the change pending for the manual button.
 */
class CloudSyncScheduler private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    /** Debounced nudge after local registration/inventory writes: coalesces rapid writes into one push. */
    fun pushSoon() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(1_500L)
            try {
                val settings = SettingsStore.getInstance(appContext)
                if (settings.getCloudSyncUrl().isBlank() || settings.getCloudSyncToken().isBlank()) {
                    return@launch
                }
                val result = CloudSyncService.getInstance(appContext).pushOnce()
                if (!result.ok) {
                    Log.w(TAG, "pushSoon: ${result.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pushSoon failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "CloudSyncScheduler"

        @Volatile
        private var instance: CloudSyncScheduler? = null

        fun getInstance(context: Context): CloudSyncScheduler {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncScheduler(context.applicationContext).also { instance = it }
            }
        }
    }
}
