package com.example.smsgateway.data

/**
 * Shared SQL fragments for inventory "used" vs "unused".
 *
 * Kept intentionally cheap (no GLOB / nested REPLACE) so COUNT over large tables
 * (hundreds of thousands of rows) stays usable. Matches CRM unused listing:
 * blank / `"0"` phone = unused. CSV import already nulls junk via [PhoneUtils.nullIfUnregistered].
 */
object RegisteredPhoneSql {
    /** Clause: row has a registration phone (inventory "used"). */
    const val IS_REGISTERED =
        "phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'"

    /** Clause: row is unused inventory. */
    const val IS_UNUSED = "NOT ($IS_REGISTERED)"
}
