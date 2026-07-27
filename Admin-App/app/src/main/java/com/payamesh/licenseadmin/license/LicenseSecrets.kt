package com.payamesh.licenseadmin.license

/**
 * KEEP IN SYNC with and_studio_proj .../license/LicenseSecrets.kt
 * HMAC key material must match the customer Payamesh app exactly.
 */
object LicenseSecrets {
    private val XOR_KEY = byteArrayOf(
        0x2A, 0x7F, 0x13, 0xC4.toByte(), 0x91.toByte(), 0x55, 0xE2.toByte(), 0x08,
    )

    private val HMAC_SEED_OBFUSCATED = byteArrayOf(
        0x50, 0x61, 0x79, 0x61, 0x6D, 0x65, 0x73, 0x68,
        0x4C, 0x69, 0x63, 0x32, 0x30, 0x32, 0x36, 0x21,
        0x53, 0x6D, 0x73, 0x48, 0x6D, 0x61, 0x63, 0x4B,
    )

    fun hmacKeyBytes(): ByteArray {
        val out = ByteArray(HMAC_SEED_OBFUSCATED.size)
        for (i in HMAC_SEED_OBFUSCATED.indices) {
            out[i] = (HMAC_SEED_OBFUSCATED[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return out
    }
}
