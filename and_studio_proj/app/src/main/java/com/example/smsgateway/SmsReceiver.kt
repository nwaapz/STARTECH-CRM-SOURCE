package com.example.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return
        if (!SettingsStore.getInstance(context).isServiceEnabled()) return

        val subscriptionId = SimCardHelper.extractSubscriptionId(context, intent)
        if (!SettingsStore.getInstance(context).isSubscriptionEnabled(subscriptionId)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }

        val pendingResult = goAsync()
        executor.execute {
            try {
                SmsProcessor.getInstance(context).process(
                    sender = sender,
                    body = body,
                    subscriptionId = subscriptionId,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
