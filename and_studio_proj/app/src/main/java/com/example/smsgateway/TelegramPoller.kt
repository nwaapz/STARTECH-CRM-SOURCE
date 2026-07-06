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

class TelegramPoller private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = TelegramApi.getInstance()
    private val chatStore = TelegramChatStore.getInstance(appContext)
    private val processor = TelegramProcessor.getInstance(appContext)
    private var pollingJob: Job? = null

    fun isPolling(): Boolean = pollingJob?.isActive == true

    fun start() {
        if (pollingJob?.isActive == true) return
        val settings = SettingsStore.getInstance(appContext)
        if (!settings.isTelegramBotActive()) return

        pollingJob = scope.launch {
            LanTraceLog.i("TelegramPoller", "Polling started")
            while (isActive) {
                val currentSettings = SettingsStore.getInstance(appContext)
                if (!currentSettings.isTelegramBotActive()) {
                    LanTraceLog.i("TelegramPoller", "Bot inactive — stopping poll loop")
                    break
                }

                val token = currentSettings.getTelegramToken()
                if (token.isBlank()) {
                    LanTraceLog.w("TelegramPoller", "Missing token — stopping poll loop")
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
                    LanTraceLog.e("TelegramPoller", "getUpdates failed", e)
                }

                delay(POLL_INTERVAL_MS)
            }
            LanTraceLog.i("TelegramPoller", "Polling stopped")
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L

        @Volatile
        private var instance: TelegramPoller? = null

        fun getInstance(context: Context): TelegramPoller {
            return instance ?: synchronized(this) {
                instance ?: TelegramPoller(context.applicationContext).also { instance = it }
            }
        }
    }
}
