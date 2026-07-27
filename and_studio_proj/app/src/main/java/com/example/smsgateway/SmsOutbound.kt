package com.example.smsgateway

import android.content.Context
import android.telephony.SubscriptionManager

class SmsOutboundQuotaExceededException(
    message: String = "Daily SMS limit reached for all available SIMs",
) : IllegalStateException(message)

object SmsOutbound {
    fun resolveSubscriptionId(context: Context, preferred: Int?): Int {
        val picked = SimQuotaRouter.pickForSingleSend(context, preferred)
        if (picked != null) {
            return picked
        }

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

    /**
     * Resolves a SIM that still has quota. Throws [SmsOutboundQuotaExceededException] when none.
     * In Melipayamak panel mode, quota is not enforced (returns any enabled SIM for logging).
     */
    fun resolveSubscriptionIdOrThrow(
        context: Context,
        preferred: Int?,
        budget: Int = 1,
        forceDeviceSim: Boolean = false,
    ): Int {
        if (!forceDeviceSim && SettingsStore.getInstance(context).isMelipayamakPanelMode()) {
            val resolved = resolveSubscriptionId(context, preferred)
            if (resolved != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return resolved
            }
            return SimCardHelper.getAvailableSims(context).firstOrNull()?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }

        val picked = SimQuotaRouter.pickForBudget(context, preferred, budget)
            ?: throw SmsOutboundQuotaExceededException()
        val remaining = SimSmsQuotaStore.getInstance(context).remainingBlocking(picked)
        if (remaining <= 0) {
            throw SmsOutboundQuotaExceededException()
        }
        return picked
    }

    fun send(
        context: Context,
        subscriptionId: Int,
        phone: String,
        message: String,
        forceDeviceSim: Boolean = false,
    ) {
        val destination = PhoneUtils.normalizeForSmsSend(phone)
        require(destination.isNotEmpty()) { "Phone number is empty" }

        val settingsStore = SettingsStore.getInstance(context)
        if (!forceDeviceSim && settingsStore.isMelipayamakPanelMode()) {
            MelipayamakApi.send(
                username = settingsStore.getMelipayamakUsername(),
                password = settingsStore.getMelipayamakPassword(),
                from = settingsStore.getMelipayamakSenderLine(),
                to = destination,
                text = message,
            )
            return
        }

        val quotaStore = SimSmsQuotaStore.getInstance(context)
        val resolvedSubscription = resolveSubscriptionId(context, subscriptionId)
        if (quotaStore.remainingBlocking(resolvedSubscription) <= 0) {
            val failover = SimQuotaRouter.pickForSingleSend(context, preferred = null)
                ?: throw SmsOutboundQuotaExceededException()
            sendOnSubscription(context, failover, destination, message)
            return
        }

        sendOnSubscription(context, resolvedSubscription, destination, message)
    }

    private fun sendOnSubscription(
        context: Context,
        subscriptionId: Int,
        destination: String,
        message: String,
    ) {
        val remaining = SimSmsQuotaStore.getInstance(context).remainingBlocking(subscriptionId)
        if (remaining <= 0) {
            throw SmsOutboundQuotaExceededException(
                "Daily SMS limit reached for SIM $subscriptionId",
            )
        }

        val smsManager = SimCardHelper.smsManagerForSubscription(context, subscriptionId)
        val parts = smsManager.divideMessage(message)

        try {
            if (parts.size <= 1) {
                smsManager.sendTextMessage(destination, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
            }
        } catch (error: SecurityException) {
            throw IllegalStateException("SMS permission denied", error)
        } catch (error: SmsOutboundQuotaExceededException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("Failed to send SMS: ${error.message}", error)
        }

        SimSmsQuotaStore.getInstance(context).recordSendBlocking(subscriptionId)
    }
}
