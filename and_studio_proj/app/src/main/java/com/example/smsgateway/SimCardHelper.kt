package com.example.smsgateway

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

object SimCardHelper {
    private val SUBSCRIPTION_EXTRA_KEYS = listOf(
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        "subscription",
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        "subscription_id",
        "sub_id",
    )

    private val SLOT_EXTRA_KEYS = listOf(
        "android.telephony.extra.SLOT_INDEX",
        "slot",
        "simSlot",
        "simId",
        "phone",
        "slot_id",
    )

    fun getAvailableSims(context: Context): List<SimInfo> {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val infos = subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        if (infos.isNotEmpty()) {
            return infos.map { info ->
                SimInfo(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    displayName = buildSimLabel(info.simSlotIndex, info.displayName?.toString()),
                )
            }.sortedBy { it.slotIndex }
        }

        val telephony = context.getSystemService(TelephonyManager::class.java)
        val phoneCount = telephony?.phoneCount ?: 1
        return (0 until phoneCount).mapNotNull { slot ->
            if (telephony?.getSimState(slot) != TelephonyManager.SIM_STATE_READY) {
                return@mapNotNull null
            }
            val subId = subscriptionIdForSlot(context, slot)
                ?: syntheticIdForSlot(slot)
            SimInfo(
                subscriptionId = subId,
                slotIndex = slot,
                displayName = buildSimLabel(slot, null),
            )
        }
    }

    /** Real subscription IDs and synthetic slot IDs that refer to the same SIM. */
    fun subscriptionMatchKeys(context: Context, subscriptionOrSyntheticId: Int): Set<Int> {
        if (subscriptionOrSyntheticId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return setOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        }

        val keys = mutableSetOf(subscriptionOrSyntheticId)

        if (subscriptionOrSyntheticId < 0) {
            val slot = slotIndexFromSyntheticId(subscriptionOrSyntheticId)
            if (slot != null) {
                subscriptionIdForSlot(context, slot)?.let { keys.add(it) }
            }
        } else {
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            val info = subscriptionManager?.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subscriptionOrSyntheticId }
            if (info != null) {
                keys.add(syntheticIdForSlot(info.simSlotIndex))
            }
        }

        return keys
    }

    fun syntheticIdForSlot(slotIndex: Int): Int = -(slotIndex + 1)

    fun slotIndexFromSyntheticId(id: Int): Int? {
        if (id >= 0) return null
        return (-id) - 1
    }

    fun resolveSubscriptionId(context: Context, subscriptionOrSyntheticId: Int): Int {
        if (subscriptionOrSyntheticId > 0) {
            return subscriptionOrSyntheticId
        }
        if (subscriptionOrSyntheticId < 0) {
            val slot = slotIndexFromSyntheticId(subscriptionOrSyntheticId) ?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
            return subscriptionIdForSlot(context, slot) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    fun extractSubscriptionId(context: Context, intent: Intent): Int {
        for (key in SUBSCRIPTION_EXTRA_KEYS) {
            val subId = intent.getIntExtra(key, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return subId
            }
        }

        val slot = SLOT_EXTRA_KEYS
            .asSequence()
            .map { intent.getIntExtra(it, -1) }
            .firstOrNull { it >= 0 }

        if (slot != null) {
            subscriptionIdForSlot(context, slot)?.let { return it }
            return syntheticIdForSlot(slot)
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val activeSubs = subscriptionManager?.activeSubscriptionInfoList.orEmpty()
        if (activeSubs.size == 1) {
            return activeSubs.first().subscriptionId
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    fun subscriptionIdForSlot(context: Context, slotIndex: Int): Int? {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        return subscriptionManager?.activeSubscriptionInfoList
            ?.firstOrNull { it.simSlotIndex == slotIndex }
            ?.subscriptionId
    }

    fun simLabelForSubscription(context: Context, subscriptionOrSyntheticId: Int): String {
        if (subscriptionOrSyntheticId < 0) {
            val slot = slotIndexFromSyntheticId(subscriptionOrSyntheticId)
            if (slot != null) return buildSimLabel(slot, null)
        }
        if (subscriptionOrSyntheticId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return context.getString(R.string.sim_unknown)
        }
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val info = subscriptionManager?.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subscriptionOrSyntheticId }
        return if (info != null) {
            buildSimLabel(info.simSlotIndex, info.displayName?.toString())
        } else {
            context.getString(R.string.sim_unknown)
        }
    }

    @Suppress("DEPRECATION")
    fun smsManagerForSubscription(context: Context, subscriptionOrSyntheticId: Int): SmsManager {
        val resolvedId = resolveSubscriptionId(context, subscriptionOrSyntheticId)
        if (resolvedId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return defaultSmsManager(context)
        }
        return try {
            SmsManager.getSmsManagerForSubscriptionId(resolvedId)
        } catch (_: Exception) {
            defaultSmsManager(context)
        }
    }

    @Suppress("DEPRECATION")
    private fun defaultSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }

    private fun buildSimLabel(slotIndex: Int, carrierName: String?): String {
        val slotNumber = slotIndex + 1
        return if (!carrierName.isNullOrBlank()) {
            "سیم‌کارت $slotNumber ($carrierName)"
        } else {
            "سیم‌کارت $slotNumber"
        }
    }
}
