package com.example.smsgateway

object PhoneUtils {
    /**
     * Converts Iranian international format (+98 / 98) to local 09... for display and SMS.
     */
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

    /** True when input looks like a phone number (digits and phone punctuation only). */
    fun isPhoneLikeInput(input: String): Boolean {
        val trimmed = normalizeToAsciiDigits(input.trim())
        if (trimmed.isEmpty()) return false
        return trimmed.all { it.isDigit() || it in "+-() " }
    }

    /** Converts Persian/Arabic numerals and other unicode digits to ASCII 0-9. */
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

    /**
     * Normalizes partial phone search input to local 09... prefix for LIKE queries.
     */
    fun normalizeSearchPrefix(input: String): String {
        val digits = input.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            digits.startsWith("98") && digits.length > 2 -> "0" + digits.substring(2)
            digits.startsWith("0") -> digits
            digits.startsWith("9") -> "0$digits"
            else -> digits
        }
    }
}
