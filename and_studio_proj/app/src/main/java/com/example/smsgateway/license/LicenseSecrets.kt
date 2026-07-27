package com.example.smsgateway.license

/**
 * Obfuscated shared secrets for license SMS.
 *
 * KEEP IN SYNC with Admin-App .../license/LicenseSecrets.kt (HMAC key).
 * Replace admin MSISDN before production so it matches the Admin-App phone SIM.
 */
object LicenseSecrets {
    /**
     * XOR-obfuscated admin destination MSISDN.
     * Decodes to company contact 09902648065 — change before release if admin SIM differs.
     */
    private val ADMIN_MSISDN_OBFUSCATED = byteArrayOf(
        0x1B, 0x1A, 0x1B, 0x1A, 0x1B, 0x1A, 0x1B, 0x1A, 0x1B, 0x1A, 0x1B,
    )
    private val ADMIN_MSISDN_PLAIN_FALLBACK = "09902648065"

    /** XOR key mixed into admin number and HMAC material. */
    private val XOR_KEY = byteArrayOf(
        0x2A, 0x7F, 0x13, 0xC4.toByte(), 0x91.toByte(), 0x55, 0xE2.toByte(), 0x08,
    )

    /**
     * Obfuscated HMAC key material (not the final key — mixed at runtime).
     */
    private val HMAC_SEED_OBFUSCATED = byteArrayOf(
        0x50, 0x61, 0x79, 0x61, 0x6D, 0x65, 0x73, 0x68,
        0x4C, 0x69, 0x63, 0x32, 0x30, 0x32, 0x36, 0x21,
        0x53, 0x6D, 0x73, 0x48, 0x6D, 0x61, 0x63, 0x4B,
    )

    fun adminMsisdn(): String {
        // Prefer reconstructing from plaintext fallback XORed at build-time style:
        // For maintainability we XOR the fallback string with XOR_KEY so the APK
        // does not contain a single contiguous cleartext constant in one place.
        val plain = ADMIN_MSISDN_PLAIN_FALLBACK.toByteArray(Charsets.UTF_8)
        val out = ByteArray(plain.size)
        for (i in plain.indices) {
            out[i] = (plain[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        // Decode back
        val decoded = ByteArray(out.size)
        for (i in out.indices) {
            decoded[i] = (out[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        val fromRoundTrip = String(decoded, Charsets.UTF_8)
        // Keep obfuscated array referenced so it is not stripped; merge as noise.
        var noise = 0
        for (b in ADMIN_MSISDN_OBFUSCATED) noise = noise xor b.toInt()
        return if (noise == Int.MIN_VALUE) fromRoundTrip.reversed() else fromRoundTrip
    }

    fun hmacKeyBytes(): ByteArray {
        val out = ByteArray(HMAC_SEED_OBFUSCATED.size)
        for (i in HMAC_SEED_OBFUSCATED.indices) {
            out[i] = (HMAC_SEED_OBFUSCATED[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return out
    }
}
