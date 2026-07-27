package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BalePoller private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = BaleApi.getInstance()
    private val chatStore = BaleChatStore.getInstance(appContext)
    private val processor = BaleProcessor.getInstance(appContext)
    private var pollingJob: Job? = null

    fun isPolling(): Boolean = pollingJob?.isActive == true

    fun start() {
        if (pollingJob?.isActive == true) return
        val settings = SettingsStore.getInstance(appContext)
        if (!settings.isBaleBotActive()) return

        pollingJob = scope.launch {
            LanTraceLog.i("BalePoller", "Polling started")
            while (isActive) {
                val currentSettings = SettingsStore.getInstance(appContext)
                if (!currentSettings.isBaleBotActive()) {
                    LanTraceLog.i("BalePoller", "Bot inactive — stopping poll loop")
                    break
                }
                if (!com.example.smsgateway.license.LicenseGate.allowsOperationalUse(appContext)) {
                    delay(30_000)
                    continue
                }

                val token = currentSettings.getBaleToken()
                if (token.isBlank()) {
                    LanTraceLog.w("BalePoller", "Missing token — stopping poll loop")
                    break
                }

                try {
                    val result = api.getUpdates(
                        token = token,
                        offsetId = chatStore.getNextOffsetId(currentSettings),
                    )
                    val added = chatStore.appendUpdates(result.updates)
                    added.forEach { update ->
                        processor.processIncoming(update)
                    }
                    result.nextOffsetId?.let { offset ->
                        chatStore.setNextOffsetId(currentSettings, offset)
                    }
                } catch (e: Exception) {
                    LanTraceLog.e("BalePoller", "getUpdates failed", e)
                }

                delay(POLL_INTERVAL_MS)
            }
            LanTraceLog.i("BalePoller", "Polling stopped")
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L

        @Volatile
        private var instance: BalePoller? = null

        fun getInstance(context: Context): BalePoller {
            return instance ?: synchronized(this) {
                instance ?: BalePoller(context.applicationContext).also { instance = it }
            }
        }
    }
}
