package com.payamesh.licenseadmin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (!com.payamesh.licenseadmin.license.LicenseSmsProtocol.isCheckMessage(body)) return

        val subscriptionId = extractSubscriptionId(intent)
        val pending = goAsync()
        executor.execute {
            try {
                LicenseAutoResponder.handleCheck(context, sender, body, subscriptionId)
            } finally {
                pending.finish()
            }
        }
    }

    private fun extractSubscriptionId(intent: Intent): Int {
        val keys = listOf(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            "subscription",
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "subscription_id",
            "sub_id",
        )
        for (key in keys) {
            val subId = intent.getIntExtra(key, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return subId
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }
}
