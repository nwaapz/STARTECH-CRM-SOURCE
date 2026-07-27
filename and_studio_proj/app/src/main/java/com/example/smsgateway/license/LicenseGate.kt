package com.example.smsgateway.license

import android.content.Context

/**
 * Central gate for operational features. License replies must bypass this gate
 * (handled earlier in SmsReceiver).
 */
object LicenseGate {
    fun allowsOperationalUse(context: Context): Boolean {
        val store = LicenseStore.getInstance(context)
        return when (store.getState()) {
            LicenseState.ACTIVE,
            LicenseState.GRACE,
            LicenseState.NEVER_ACTIVATED,
            -> true
            LicenseState.DENIED,
            LicenseState.LOCKED,
            -> false
        }
    }

    /** Gateway service may start so checks/replies can still flow. */
    fun allowsGatewayService(context: Context): Boolean = true

    fun blockReasonResId(context: Context): Int? {
        return when (LicenseStore.getInstance(context).getState()) {
            LicenseState.DENIED -> com.example.smsgateway.R.string.license_blocked_denied
            LicenseState.LOCKED -> com.example.smsgateway.R.string.license_blocked_locked
            else -> null
        }
    }
}
