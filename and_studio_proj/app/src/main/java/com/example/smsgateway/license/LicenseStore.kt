package com.example.smsgateway.license

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists customer license attestation state.
 *
 * Commercial entitlement is lifetime; this store only tracks technical allow/deny/grace.
 */
class LicenseStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Bumps whenever license prefs change so Compose can refresh without tab switches. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun getState(): LicenseState {
        recomputeState()
        val raw = prefs.getString(KEY_STATE, LicenseState.NEVER_ACTIVATED.name)
            ?: LicenseState.NEVER_ACTIVATED.name
        return runCatching { LicenseState.valueOf(raw) }.getOrDefault(LicenseState.NEVER_ACTIVATED)
    }

    fun setState(state: LicenseState) {
        val previous = prefs.getString(KEY_STATE, LicenseState.NEVER_ACTIVATED.name)
        if (previous == state.name) return
        prefs.edit().putString(KEY_STATE, state.name).apply()
        notifyChanged()
    }

    private fun notifyChanged() {
        _revision.value = _revision.value + 1
    }

    fun hasEverActivated(): Boolean = prefs.getBoolean(KEY_HAS_ACTIVATED, false)

    fun getLastOkWallClockMs(): Long = prefs.getLong(KEY_LAST_OK_WALL, 0L)

    fun getLastOkElapsedRealtimeMs(): Long = prefs.getLong(KEY_LAST_OK_ELAPSED, 0L)

    fun getFirstSeenWallClockMs(): Long {
        val existing = prefs.getLong(KEY_FIRST_SEEN_WALL, 0L)
        if (existing > 0L) return existing
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_FIRST_SEEN_WALL, now)
            .putLong(KEY_FIRST_SEEN_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
        return now
    }

    fun getFirstSeenElapsedRealtimeMs(): Long {
        getFirstSeenWallClockMs()
        return prefs.getLong(KEY_FIRST_SEEN_ELAPSED, SystemClock.elapsedRealtime())
    }

    fun getPendingNonce(): String? = prefs.getString(KEY_PENDING_NONCE, null)

    fun getPendingNonceCreatedAtElapsedMs(): Long =
        prefs.getLong(KEY_PENDING_NONCE_ELAPSED, 0L)

    fun setPendingCheck(nonce: String) {
        prefs.edit()
            .putString(KEY_PENDING_NONCE, nonce)
            .putLong(KEY_PENDING_NONCE_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_LAST_CHECK_ATTEMPT_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_LAST_CHECK_ATTEMPT_WALL, System.currentTimeMillis())
            .apply()
        notifyChanged()
    }

    fun clearPendingNonce() {
        prefs.edit()
            .remove(KEY_PENDING_NONCE)
            .remove(KEY_PENDING_NONCE_ELAPSED)
            .apply()
        notifyChanged()
    }

    fun getLastCheckAttemptElapsedMs(): Long =
        prefs.getLong(KEY_LAST_CHECK_ATTEMPT_ELAPSED, 0L)

    fun getLastCheckAttemptWallClockMs(): Long =
        prefs.getLong(KEY_LAST_CHECK_ATTEMPT_WALL, 0L)

    fun getLicensedMsisdn(): String? = prefs.getString(KEY_LICENSED_MSISDN, null)

    fun getLicensedSimFingerprint(): String? = prefs.getString(KEY_LICENSED_SIM_FP, null)

    fun getLicensedSubscriptionId(): Int =
        prefs.getInt(KEY_LICENSED_SUB_ID, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID

    fun markAllow(
        msisdnNorm: String,
        simFingerprint: String,
        subscriptionId: Int,
        issuedAtEpochMs: Long,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs.edit()
            .putBoolean(KEY_HAS_ACTIVATED, true)
            .putString(KEY_STATE, LicenseState.ACTIVE.name)
            .putString(KEY_LICENSED_MSISDN, msisdnNorm)
            .putString(KEY_LICENSED_SIM_FP, simFingerprint)
            .putInt(KEY_LICENSED_SUB_ID, subscriptionId)
            .putLong(KEY_LAST_OK_WALL, issuedAtEpochMs.coerceAtLeast(System.currentTimeMillis()))
            .putLong(KEY_LAST_OK_ELAPSED, nowElapsed)
            .remove(KEY_PENDING_NONCE)
            .remove(KEY_PENDING_NONCE_ELAPSED)
            .remove(KEY_DENY_REASON)
            .apply()
        notifyChanged()
    }

    fun markDeny(reason: String) {
        prefs.edit()
            .putString(KEY_STATE, LicenseState.DENIED.name)
            .putString(KEY_DENY_REASON, reason)
            .remove(KEY_PENDING_NONCE)
            .remove(KEY_PENDING_NONCE_ELAPSED)
            .apply()
        notifyChanged()
    }

    fun getDenyReason(): String? = prefs.getString(KEY_DENY_REASON, null)

    fun markGrace() {
        if (!hasEverActivated()) return
        setState(LicenseState.GRACE)
    }

    fun markLocked() {
        setState(LicenseState.LOCKED)
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty().ifBlank { "unknown" }
        prefs.edit().putString(KEY_DEVICE_ID, androidId).apply()
        return androidId
    }

    /**
     * Recomputes ACTIVE/GRACE/LOCKED/NEVER_ACTIVATED based on elapsedRealtime anchors
     * so wall-clock rollback cannot extend grace forever.
     */
    fun recomputeState() {
        getFirstSeenWallClockMs()
        val stored = prefs.getString(KEY_STATE, LicenseState.NEVER_ACTIVATED.name)
        if (stored == LicenseState.DENIED.name) return

        val nowElapsed = SystemClock.elapsedRealtime()

        if (!hasEverActivated()) {
            val firstElapsed = getFirstSeenElapsedRealtimeMs()
            val age = nowElapsed - firstElapsed
            if (age > NEVER_ACTIVATED_WINDOW_MS) {
                setState(LicenseState.LOCKED)
            } else {
                setState(LicenseState.NEVER_ACTIVATED)
            }
            return
        }

        val lastOkElapsed = getLastOkElapsedRealtimeMs()
        if (lastOkElapsed <= 0L) {
            setState(LicenseState.LOCKED)
            return
        }

        val sinceOk = nowElapsed - lastOkElapsed
        when {
            sinceOk <= ACTIVE_FRESH_WINDOW_MS -> setState(LicenseState.ACTIVE)
            sinceOk <= GRACE_WINDOW_MS -> setState(LicenseState.GRACE)
            else -> setState(LicenseState.LOCKED)
        }
    }

    fun statusSummary(): LicenseStatusSummary {
        recomputeState()
        return LicenseStatusSummary(
            state = getState(),
            hasEverActivated = hasEverActivated(),
            licensedMsisdn = getLicensedMsisdn(),
            lastOkWallClockMs = getLastOkWallClockMs(),
            denyReason = getDenyReason(),
            pendingNonce = getPendingNonce(),
            graceRemainingMs = graceRemainingMs(),
            activationWindowRemainingMs = activationWindowRemainingMs(),
        )
    }

    fun graceRemainingMs(): Long {
        if (!hasEverActivated()) return 0L
        val lastOk = getLastOkElapsedRealtimeMs()
        if (lastOk <= 0L) return 0L
        val remaining = GRACE_WINDOW_MS - (SystemClock.elapsedRealtime() - lastOk)
        return remaining.coerceAtLeast(0L)
    }

    fun activationWindowRemainingMs(): Long {
        if (hasEverActivated()) return 0L
        val first = getFirstSeenElapsedRealtimeMs()
        val remaining = NEVER_ACTIVATED_WINDOW_MS - (SystemClock.elapsedRealtime() - first)
        return remaining.coerceAtLeast(0L)
    }

    fun shouldSendPeriodicCheck(): Boolean {
        recomputeState()
        val state = getState()
        if (state == LicenseState.DENIED) {
            // Still allow periodic re-check so a newly registered number can unlock.
        }
        val lastAttempt = getLastCheckAttemptElapsedMs()
        if (lastAttempt <= 0L) return true
        return SystemClock.elapsedRealtime() - lastAttempt >= CHECK_INTERVAL_MS
    }

    companion object {
        private const val PREFS_NAME = "license_store"
        private const val KEY_STATE = "state"
        private const val KEY_HAS_ACTIVATED = "has_activated"
        private const val KEY_LAST_OK_WALL = "last_ok_wall"
        private const val KEY_LAST_OK_ELAPSED = "last_ok_elapsed"
        private const val KEY_FIRST_SEEN_WALL = "first_seen_wall"
        private const val KEY_FIRST_SEEN_ELAPSED = "first_seen_elapsed"
        private const val KEY_PENDING_NONCE = "pending_nonce"
        private const val KEY_PENDING_NONCE_ELAPSED = "pending_nonce_elapsed"
        private const val KEY_LAST_CHECK_ATTEMPT_ELAPSED = "last_check_elapsed"
        private const val KEY_LAST_CHECK_ATTEMPT_WALL = "last_check_wall"
        private const val KEY_LICENSED_MSISDN = "licensed_msisdn"
        private const val KEY_LICENSED_SIM_FP = "licensed_sim_fp"
        private const val KEY_LICENSED_SUB_ID = "licensed_sub_id"
        private const val KEY_DENY_REASON = "deny_reason"
        private const val KEY_DEVICE_ID = "device_id"

        /** Short window for first activation (not the 30-day grace). */
        const val NEVER_ACTIVATED_WINDOW_MS: Long = 72L * 60L * 60L * 1000L

        /** After last OK, treat as ACTIVE for one day then GRACE until 30 days. */
        const val ACTIVE_FRESH_WINDOW_MS: Long = 24L * 60L * 60L * 1000L

        /** Max time since last successful OK before LOCKED (includes ACTIVE period). */
        const val GRACE_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L

        const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

        /** How long to wait for a reply before treating as "no reply". */
        const val REPLY_TIMEOUT_MS: Long = 15L * 60L * 1000L

        @Volatile
        private var instance: LicenseStore? = null

        fun getInstance(context: Context): LicenseStore {
            return instance ?: synchronized(this) {
                instance ?: LicenseStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

data class LicenseStatusSummary(
    val state: LicenseState,
    val hasEverActivated: Boolean,
    val licensedMsisdn: String?,
    val lastOkWallClockMs: Long,
    val denyReason: String?,
    val pendingNonce: String?,
    val graceRemainingMs: Long,
    val activationWindowRemainingMs: Long,
)
