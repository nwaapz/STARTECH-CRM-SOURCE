package com.payamesh.licenseadmin

/**
 * Phone normalization — must stay aligned with Payamesh PhoneUtils / LicenseSmsProtocol.
 */
object PhoneUtils {
    fun normalizeForSmsSend(phone: String): String {
        val trimmed = normalizeToAsciiDigits(phone.trim()).replace(" ", "")
        if (trimmed.startsWith("+98") && trimmed.length >= 13) {
            return "0" + trimmed.substring(3)
        }
        if (trimmed.startsWith("98") && trimmed.length >= 12 && trimmed[2].isDigit()) {
            return "0" + trimmed.substring(2)
        }
        if (trimmed.length == 10 && trimmed.startsWith('9') && trimmed.all(Char::isDigit)) {
            return "0$trimmed"
        }
        return trimmed
    }

    fun formatForDisplay(phone: String?): String? {
        if (phone.isNullOrBlank()) return phone
        return normalizeForSmsSend(phone)
    }

    fun normalizeToInternational(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        var digits = normalizeToAsciiDigits(phone.trim()).filter { it.isDigit() }
        if (digits.isEmpty()) return null
        if (digits.startsWith("0098")) {
            digits = digits.substring(2)
        }
        val national: String = when {
            digits.startsWith("98") && digits.length == 12 && digits[2] == '9' ->
                digits.substring(2)
            digits.startsWith("0") && digits.length == 11 && digits[1] == '9' ->
                digits.substring(1)
            digits.length == 10 && digits[0] == '9' ->
                digits
            else -> return null
        }
        if (national.length != 10 || national[0] != '9') return null
        return "98$national"
    }

    fun normalizeToAsciiDigits(input: String): String {
        return buildString(input.length) {
            input.forEach { ch ->
                when {
                    ch in '0'..'9' -> append(ch)
                    ch.isDigit() -> {
                        val digit = Character.digit(ch, 10)
                        if (digit >= 0) append('0' + digit)
                    }
                    else -> append(ch)
                }
            }
        }
    }
}
