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

class RubikaPoller private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = RubikaApi.getInstance()
    private val chatStore = RubikaChatStore.getInstance(appContext)
    private val processor = RubikaProcessor.getInstance(appContext)
    private var pollingJob: Job? = null

    fun isPolling(): Boolean = pollingJob?.isActive == true

    fun start() {
        if (pollingJob?.isActive == true) return
        val settings = SettingsStore.getInstance(appContext)
        if (!settings.isRubikaBotActive()) return

        pollingJob = scope.launch {
            LanTraceLog.i("RubikaPoller", "Polling started")
            while (isActive) {
                val currentSettings = SettingsStore.getInstance(appContext)
                if (!currentSettings.isRubikaBotActive()) {
                    LanTraceLog.i("RubikaPoller", "Bot inactive — stopping poll loop")
                    break
                }

                val token = currentSettings.getRubikaToken()
                if (token.isBlank()) {
                    LanTraceLog.w("RubikaPoller", "Missing token — stopping poll loop")
                    break
                }

                try {
                    val result = api.getUpdates(
                        token = token,
                        offsetId = chatStore.getNextOffsetId(currentSettings),
                    )
                    val added = chatStore.appendUpdates(result.updates, token)
                    added.forEach { update ->
                        processor.processIncoming(update)
                    }
                    result.nextOffsetId?.let { offset ->
                        chatStore.setNextOffsetId(currentSettings, offset)
                    }
                } catch (e: Exception) {
                    LanTraceLog.e("RubikaPoller", "getUpdates failed", e)
                }

                delay(POLL_INTERVAL_MS)
            }
            LanTraceLog.i("RubikaPoller", "Polling stopped")
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L

        @Volatile
        private var instance: RubikaPoller? = null

        fun getInstance(context: Context): RubikaPoller {
            return instance ?: synchronized(this) {
                instance ?: RubikaPoller(context.applicationContext).also { instance = it }
            }
        }
    }
}
