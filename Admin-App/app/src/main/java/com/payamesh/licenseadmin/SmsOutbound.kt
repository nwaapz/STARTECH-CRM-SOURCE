package com.payamesh.licenseadmin

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

object SmsOutbound {
    fun send(context: Context, subscriptionId: Int, phone: String, message: String) {
        val destination = PhoneUtils.normalizeForSmsSend(phone)
        require(destination.isNotEmpty()) { "Phone number is empty" }

        val smsManager = smsManagerForSubscription(context, subscriptionId)
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

    @Suppress("DEPRECATION")
    private fun smsManagerForSubscription(context: Context, subscriptionId: Int): SmsManager {
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return try {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } catch (_: Exception) {
                defaultSmsManager(context)
            }
        }
        return defaultSmsManager(context)
    }

    @Suppress("DEPRECATION")
    private fun defaultSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}
