package com.payamesh.licenseadmin.license

import android.util.Base64
import com.payamesh.licenseadmin.PhoneUtils
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KEEP IN SYNC with and_studio_proj .../license/LicenseSmsProtocol.kt
 *
 * Customer → admin:  PM_LIC_CHK|appVersion|deviceId|nonce|simFingerprint
 * Admin → customer:  PM_LIC_OK|nonce|msisdnNorm|issuedAt|sig
 * Admin → customer:  PM_LIC_NO|nonce|reason
 */
object LicenseSmsProtocol {
    const val PREFIX_CHK = "PM_LIC_CHK"
    const val PREFIX_OK = "PM_LIC_OK"
    const val PREFIX_NO = "PM_LIC_NO"

    const val REASON_UNREGISTERED = "unregistered"
    const val REASON_REVOKED = "revoked"

    private const val SEPARATOR = "|"

    fun isCheckMessage(body: String): Boolean = body.trim().startsWith(PREFIX_CHK)

    fun parseCheck(body: String): LicenseCheckMessage? {
        val parts = body.trim().split(SEPARATOR)
        if (parts.size < 5 || parts[0] != PREFIX_CHK) return null
        return LicenseCheckMessage(
            appVersion = parts[1],
            deviceId = parts[2],
            nonce = parts[3],
            simFingerprint = parts[4],
        )
    }

    fun buildAllow(
        nonce: String,
        msisdnNorm: String,
        issuedAtEpochMs: Long,
    ): String {
        val msisdn = normalizeMsisdn(msisdnNorm) ?: msisdnNorm
        val sig = signAllow(nonce, msisdn, issuedAtEpochMs)
        return listOf(
            PREFIX_OK,
            sanitizeField(nonce),
            sanitizeField(msisdn),
            issuedAtEpochMs.toString(),
            sig,
        ).joinToString(SEPARATOR)
    }

    fun buildDeny(nonce: String, reason: String): String {
        return listOf(
            PREFIX_NO,
            sanitizeField(nonce),
            sanitizeField(reason),
        ).joinToString(SEPARATOR)
    }

    fun newNonce(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    fun normalizeMsisdn(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        PhoneUtils.normalizeToInternational(phone)?.let { return it }
        val digits = PhoneUtils.normalizeToAsciiDigits(phone).filter { it.isDigit() }
        return digits.takeIf { it.length >= 10 }
    }

    private fun signAllow(nonce: String, msisdnNorm: String, issuedAtEpochMs: Long): String {
        val payload = "$nonce|$msisdnNorm|$issuedAtEpochMs"
        return hmacBase64(payload)
    }

    private fun hmacBase64(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(LicenseSecrets.hmacKeyBytes(), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            .take(22)
    }

    private fun sanitizeField(value: String): String {
        return value.replace(SEPARATOR, "_").replace('\n', ' ').replace('\r', ' ').trim()
    }
}

data class LicenseCheckMessage(
    val appVersion: String,
    val deviceId: String,
    val nonce: String,
    val simFingerprint: String,
)
