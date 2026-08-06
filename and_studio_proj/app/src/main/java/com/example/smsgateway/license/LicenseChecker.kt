package com.example.smsgateway.license

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.SubscriptionManager
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.SimCardHelper
import com.example.smsgateway.SmsOutbound
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends license check SMSes to the admin number and processes allow/deny replies.
 *
 * After a successful OK, no automatic re-check is sent for [LicenseStore.ACTIVE_FRESH_WINDOW_MS]
 * (24 hours). When that window expires, exactly one check is attempted (then spaced by
 * [LicenseStore.CHECK_INTERVAL_MS] until the next OK). Manual [requestCheckNow] and SIM
 * changes still force an immediate check.
 */
class LicenseChecker private constructor(
    private val appContext: Context,
) {
    private val store = LicenseStore.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loopJob: Job? = null
    private var subscriptionCallback: SubscriptionManager.OnSubscriptionsChangedListener? = null

    fun start() {
        store.getFirstSeenWallClockMs()
        store.recomputeState()
        if (loopJob?.isActive == true) return
        registerSubscriptionListener()
        loopJob = scope.launch {
            LanTraceLog.i(TAG, "License checker started")
            while (isActive) {
                try {
                    tick()
                } catch (error: Exception) {
                    LanTraceLog.e(TAG, "License tick failed", error)
                }
                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        unregisterSubscriptionListener()
    }

    fun requestCheckNow() {
        if (loopJob?.isActive != true) {
            start()
        }
        scope.launch {
            runCatching { maybeSendCheck(force = true) }
        }
    }

    /** Ensure periodic loop is running (e.g. after manual check from Settings). */
    fun ensureStarted() {
        start()
    }

    private suspend fun tick() {
        store.recomputeState()
        expirePendingIfNeeded()
        if (store.shouldSendPeriodicCheck()) {
            maybeSendCheck(force = false)
        }
    }

    private fun expirePendingIfNeeded() {
        val pendingAt = store.getPendingNonceCreatedAtElapsedMs()
        if (pendingAt <= 0L || store.getPendingNonce().isNullOrBlank()) return
        if (SystemClock.elapsedRealtime() - pendingAt < LicenseStore.REPLY_TIMEOUT_MS) return

        store.clearPendingNonce()
        if (store.hasEverActivated()) {
            store.markGrace()
            LanTraceLog.i(TAG, "License reply timeout — entering GRACE")
        }
        store.recomputeState()
    }

    private suspend fun maybeSendCheck(force: Boolean) = mutex.withLock {
        store.recomputeState()
        if (!force && !store.shouldSendPeriodicCheck()) return

        val subscriptionId = resolveCheckSubscriptionId()
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            LanTraceLog.w(TAG, "No SIM available for license check")
            return
        }

        val fingerprint = SimCardHelper.fingerprintForSubscription(appContext, subscriptionId)
        val licensedFp = store.getLicensedSimFingerprint()
        if (!licensedFp.isNullOrBlank() && licensedFp != fingerprint && store.hasEverActivated()) {
            LanTraceLog.w(TAG, "SIM fingerprint changed — forcing license re-check")
        }

        val nonce = LicenseSmsProtocol.newNonce()
        val body = LicenseSmsProtocol.buildCheck(
            appVersion = appVersionName(),
            deviceId = store.deviceId(),
            nonce = nonce,
            simFingerprint = fingerprint,
        )
        val admin = PhoneUtils.normalizeForSmsSend(LicenseSecrets.adminMsisdn())
        try {
            SmsOutbound.send(appContext, subscriptionId, admin, body, forceDeviceSim = true)
            store.setPendingCheck(nonce)
            // Remember which sub sent the check so OK can bind to it.
            prefsPutLastCheckSub(subscriptionId, fingerprint)
            LanTraceLog.i(TAG, "License check SMS sent via sub=$subscriptionId")
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "Failed to send license check SMS", error)
            // Count as attempt so we do not spam; grace/lock still based on last OK.
            store.setPendingCheck(nonce)
            store.clearPendingNonce()
            if (store.hasEverActivated()) {
                store.markGrace()
            }
        }
    }

    fun handleInboundReply(sender: String, body: String, subscriptionId: Int): Boolean {
        if (!LicenseSmsProtocol.isReplyMessage(body)) return false
        if (!LicenseSmsProtocol.msisdnsMatch(sender, LicenseSecrets.adminMsisdn())) {
            LanTraceLog.w(TAG, "Ignoring license-like SMS from non-admin sender")
            return false
        }
        val reply = LicenseSmsProtocol.parseReply(body) ?: return true
        val pending = store.getPendingNonce()
        when (reply) {
            is LicenseReply.Allow -> {
                if (pending.isNullOrBlank() || pending != reply.nonce) {
                    LanTraceLog.w(TAG, "Ignoring allow with mismatched/stale nonce")
                    return true
                }
                val fingerprint = lastCheckFingerprint()
                    ?: SimCardHelper.fingerprintForSubscription(appContext, subscriptionId)
                val subId = lastCheckSubscriptionId().takeIf {
                    it != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                } ?: subscriptionId
                store.markAllow(
                    msisdnNorm = reply.msisdnNorm,
                    simFingerprint = fingerprint,
                    subscriptionId = subId,
                    issuedAtEpochMs = reply.issuedAtEpochMs,
                )
                LanTraceLog.i(TAG, "License ALLOW for ${reply.msisdnNorm}")
            }
            is LicenseReply.Deny -> {
                if (!pending.isNullOrBlank() && pending != reply.nonce) {
                    LanTraceLog.w(TAG, "Ignoring deny with mismatched nonce")
                    return true
                }
                store.markDeny(reply.reason)
                LanTraceLog.i(TAG, "License DENY reason=${reply.reason}")
            }
        }
        return true
    }

    private fun resolveCheckSubscriptionId(): Int {
        val licensed = store.getLicensedSubscriptionId()
        if (licensed != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val available = SimCardHelper.getAvailableSims(appContext)
            val licensedFp = store.getLicensedSimFingerprint()
            val match = available.firstOrNull { sim ->
                SimCardHelper.subscriptionMatchKeys(appContext, sim.subscriptionId)
                    .contains(licensed) ||
                    (!licensedFp.isNullOrBlank() && sim.fingerprint == licensedFp)
            }
            if (match != null) return match.subscriptionId
        }
        return SmsOutbound.resolveSubscriptionId(appContext, preferred = null)
    }

    private fun registerSubscriptionListener() {
        if (subscriptionCallback != null) return
        val sm = appContext.getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                LanTraceLog.i(TAG, "Subscriptions changed — license re-check")
                requestCheckNow()
            }
        }
        try {
            sm.addOnSubscriptionsChangedListener(listener)
            subscriptionCallback = listener
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "Failed to register subscription listener", error)
        }
    }

    private fun unregisterSubscriptionListener() {
        val listener = subscriptionCallback ?: return
        val sm = appContext.getSystemService(SubscriptionManager::class.java)
        try {
            sm?.removeOnSubscriptionsChangedListener(listener)
        } catch (_: Exception) {
        }
        subscriptionCallback = null
    }

    private fun appVersionName(): String {
        return try {
            val pm = appContext.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(appContext.packageName, 0)
            }
            info.versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
    }

    private fun prefsPutLastCheckSub(subscriptionId: Int, fingerprint: String) {
        appContext.getSharedPreferences(CHECK_META_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_CHECK_SUB, subscriptionId)
            .putString(KEY_LAST_CHECK_FP, fingerprint)
            .apply()
    }

    private fun lastCheckSubscriptionId(): Int {
        return appContext.getSharedPreferences(CHECK_META_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_CHECK_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    }

    private fun lastCheckFingerprint(): String? {
        return appContext.getSharedPreferences(CHECK_META_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CHECK_FP, null)
    }

    companion object {
        private const val TAG = "License"
        private const val LOOP_INTERVAL_MS = 15L * 60L * 1000L
        private const val CHECK_META_PREFS = "license_check_meta"
        private const val KEY_LAST_CHECK_SUB = "last_check_sub"
        private const val KEY_LAST_CHECK_FP = "last_check_fp"

        @Volatile
        private var instance: LicenseChecker? = null

        fun getInstance(context: Context): LicenseChecker {
            return instance ?: synchronized(this) {
                instance ?: LicenseChecker(context.applicationContext).also { instance = it }
            }
        }
    }
}
