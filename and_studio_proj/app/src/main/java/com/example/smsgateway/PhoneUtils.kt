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

    /**
     * Normalizes an Iranian mobile number to WhatsApp international digits-only form
     * ("98XXXXXXXXXX", no leading '+' and no leading zero), guarding against a
     * duplicated country code (e.g. "+98912..." must not become "9898912...").
     *
     * Returns null when the input does not look like a valid Iranian mobile number,
     * so those recipients can be skipped instead of being sent garbage.
     */
    fun normalizeToInternational(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        var digits = normalizeToAsciiDigits(phone.trim())
            .filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // 0098XXXXXXXXXX -> 98XXXXXXXXXX
        if (digits.startsWith("0098")) {
            digits = digits.substring(2)
        }

        val national: String = when {
            // Already international: 98 + 10-digit mobile starting with 9.
            digits.startsWith("98") && digits.length == 12 && digits[2] == '9' ->
                digits.substring(2)
            // Local format: 0 + 10-digit mobile (09XXXXXXXXX).
            digits.startsWith("0") && digits.length == 11 && digits[1] == '9' ->
                digits.substring(1)
            // Bare 10-digit mobile without leading zero (9XXXXXXXXX).
            digits.length == 10 && digits[0] == '9' ->
                digits
            else -> return null
        }

        // national is a 10-digit mobile starting with 9.
        if (national.length != 10 || national[0] != '9') return null
        return "98$national"
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
