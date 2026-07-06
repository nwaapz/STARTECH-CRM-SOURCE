package com.example.smsgateway

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

class SmsProcessor(private val context: Context) {
    private val settingsStore = SettingsStore.getInstance(context)
    private val communicationLog = CommunicationLog.getInstance(context)
    private val verifierFactory = SerialVerifierFactory.getInstance(context)

    private val recentMessages = ConcurrentHashMap<String, Long>()

    fun process(sender: String, body: String, subscriptionId: Int) {
        if (sender.isBlank() || body.isBlank()) return

        val dedupKey = "$subscriptionId::$sender::$body"
        val now = System.currentTimeMillis()
        val lastSeen = recentMessages[dedupKey]
        if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
            return
        }
        recentMessages[dedupKey] = now
        pruneDedupCache(now)

        val simLabel = SimCardHelper.simLabelForSubscription(context, subscriptionId)

        try {
            val response = verifierFactory.get().verify(sender, body, subscriptionId)
            SmsOutbound.send(context, subscriptionId, sender, response.reply)
            communicationLog.logFromProcessor(
                sender = sender,
                inbound = body,
                reply = response.reply,
                status = CommunicationLog.STATUS_SENT,
                error = null,
                simLabel = simLabel,
                timestamp = now,
            )
        } catch (error: GatewayApi.GatewayException) {
            communicationLog.logFromProcessor(
                sender = sender,
                inbound = body,
                reply = null,
                status = CommunicationLog.STATUS_GATEWAY_ERROR,
                error = error.message,
                simLabel = simLabel,
                timestamp = now,
            )
        } catch (error: Exception) {
            communicationLog.logFromProcessor(
                sender = sender,
                inbound = body,
                reply = null,
                status = CommunicationLog.STATUS_FAILED,
                error = error.message,
                simLabel = simLabel,
                timestamp = now,
            )
        }
    }

    private fun pruneDedupCache(now: Long) {
        recentMessages.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
    }

    companion object {
        private const val DEDUP_WINDOW_MS = 10_000L

        @Volatile
        private var instance: SmsProcessor? = null

        fun getInstance(context: Context): SmsProcessor {
            return instance ?: synchronized(this) {
                instance ?: SmsProcessor(context.applicationContext).also { instance = it }
            }
        }
    }
}
