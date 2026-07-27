package com.example.smsgateway

data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    /** Stable-ish SIM fingerprint (ICCID when available). */
    val fingerprint: String = "",
    /** Line number if Telephony exposes it (often empty). */
    val lineNumber: String? = null,
)
