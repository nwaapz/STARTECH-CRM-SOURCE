package com.example.smsgateway.license

enum class LicenseState {
    /** No successful PM_LIC_OK yet. Short activation window, then LOCKED. */
    NEVER_ACTIVATED,

    /** Fresh successful allow. */
    ACTIVE,

    /** Was ACTIVE; check sent but no reply yet — still usable until grace ends. */
    GRACE,

    /** Explicit PM_LIC_NO from admin. Immediate operational lock. */
    DENIED,

    /** Activation window or post-OK grace exhausted. */
    LOCKED,
}
