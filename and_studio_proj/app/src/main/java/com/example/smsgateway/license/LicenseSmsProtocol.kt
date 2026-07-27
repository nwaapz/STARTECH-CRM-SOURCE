package com.example.smsgateway.license

import android.util.Base64
import com.example.smsgateway.PhoneUtils
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SMS license challenge/response protocol.
 *
 * KEEP IN SYNC with Admin-App .../license/LicenseSmsProtocol.kt
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

    fun isLicenseMessage(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith(PREFIX_CHK) ||
            trimmed.startsWith(PREFIX_OK) ||
            trimmed.startsWith(PREFIX_NO)
    }

    fun isCheckMessage(body: String): Boolean = body.trim().startsWith(PREFIX_CHK)

    fun isReplyMessage(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith(PREFIX_OK) || trimmed.startsWith(PREFIX_NO)
    }

    fun buildCheck(
        appVersion: String,
        deviceId: String,
        nonce: String,
        simFingerprint: String,
    ): String {
        return listOf(
            PREFIX_CHK,
            sanitizeField(appVersion),
            sanitizeField(deviceId),
            sanitizeField(nonce),
            sanitizeField(simFingerprint),
        ).joinToString(SEPARATOR)
    }

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

    fun parseReply(body: String): LicenseReply? {
        val parts = body.trim().split(SEPARATOR)
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            PREFIX_OK -> {
                if (parts.size < 5) return null
                val nonce = parts[1]
                val msisdn = parts[2]
                val issuedAt = parts[3].toLongOrNull() ?: return null
                val sig = parts[4]
                if (!verifyAllow(nonce, msisdn, issuedAt, sig)) return null
                LicenseReply.Allow(
                    nonce = nonce,
                    msisdnNorm = normalizeMsisdn(msisdn) ?: msisdn,
                    issuedAtEpochMs = issuedAt,
                )
            }
            PREFIX_NO -> {
                if (parts.size < 3) return null
                LicenseReply.Deny(nonce = parts[1], reason = parts[2])
            }
            else -> null
        }
    }

    fun newNonce(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    /** Canonical form: 98XXXXXXXXXX (Iranian mobile) or digits-only fallback. */
    fun normalizeMsisdn(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        PhoneUtils.normalizeToInternational(phone)?.let { return it }
        val digits = PhoneUtils.normalizeToAsciiDigits(phone).filter { it.isDigit() }
        return digits.takeIf { it.length >= 10 }
    }

    fun msisdnsMatch(a: String?, b: String?): Boolean {
        val na = normalizeMsisdn(a) ?: return false
        val nb = normalizeMsisdn(b) ?: return false
        return na == nb
    }

    private fun signAllow(nonce: String, msisdnNorm: String, issuedAtEpochMs: Long): String {
        val payload = "$nonce|$msisdnNorm|$issuedAtEpochMs"
        return hmacBase64(payload)
    }

    private fun verifyAllow(
        nonce: String,
        msisdnNorm: String,
        issuedAtEpochMs: Long,
        sig: String,
    ): Boolean {
        val expected = signAllow(nonce, msisdnNorm, issuedAtEpochMs)
        return expected == sig
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

sealed class LicenseReply {
    abstract val nonce: String

    data class Allow(
        override val nonce: String,
        val msisdnNorm: String,
        val issuedAtEpochMs: Long,
    ) : LicenseReply()

    data class Deny(
        override val nonce: String,
        val reason: String,
    ) : LicenseReply()
}
