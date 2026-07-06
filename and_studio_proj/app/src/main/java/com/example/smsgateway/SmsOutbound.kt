package com.example.smsgateway

import android.content.Context
import android.telephony.SubscriptionManager

object SmsOutbound {
    fun resolveSubscriptionId(context: Context, preferred: Int?): Int {
        val settingsStore = SettingsStore.getInstance(context)
        val enabled = settingsStore.getEnabledSubscriptionIds().sorted()

        if (preferred != null && preferred != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val preferredKeys = SimCardHelper.subscriptionMatchKeys(context, preferred)
            val enabledMatch = enabled.firstOrNull { enabledId ->
                SimCardHelper.subscriptionMatchKeys(context, enabledId).any { it in preferredKeys }
            }
            if (enabledMatch != null) {
                return enabledMatch
            }
            if (enabled.isEmpty()) {
                return preferred
            }
        }

        return enabled.firstOrNull()
            ?: SimCardHelper.getAvailableSims(context).firstOrNull()?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    fun send(context: Context, subscriptionId: Int, phone: String, message: String) {
        val destination = PhoneUtils.normalizeForSmsSend(phone)
        require(destination.isNotEmpty()) { "Phone number is empty" }

        val resolvedSubscription = resolveSubscriptionId(context, subscriptionId)
        val smsManager = SimCardHelper.smsManagerForSubscription(context, resolvedSubscription)
        val parts = smsManager.divideMessage(message)

        try {
            if (parts.size <= 1) {
                smsManager.sendTextMessage(destination, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
            }
        } catch (error: SecurityException) {
            throw IllegalStateException("SMS permission denied", error)
        } catch (error: Exception) {
            throw IllegalStateException("Failed to send SMS: ${error.message}", error)
        }
    }
}
