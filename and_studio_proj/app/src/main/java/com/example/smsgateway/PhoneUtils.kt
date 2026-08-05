package com.example.smsgateway

object PhoneUtils {
    /**
     * Pick a usable SMS address from PDU fields when it is already an Iranian mobile.
     * Does not look up contacts — see [DeviceContactHelper.resolveDeviceSimMobileSender].
     */
    fun resolveSmsSenderAddress(
        originatingAddress: String?,
        displayOriginatingAddress: String?,
    ): String {
        val candidates = listOf(originatingAddress, displayOriginatingAddress)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
        return candidates.firstOrNull { isRegisteredPhone(it) }.orEmpty()
    }

    /**
     * True when [phone] is a real registration (valid-looking Iranian mobile).
     * Unused inventory: null, blank, `"0"`, SQL/CSV null tokens (`NULL`, `\N`, …),
     * or anything that is not a phone number.
     *
     * Short codes (panel/bank/provider lines) never match, since they are not
     * `09…` / `+989…` mobile numbers.
     */
    fun isRegisteredPhone(phone: String?): Boolean {
        val trimmed = phone?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "0") return false
        if (isSqlNullToken(trimmed)) return false
        return normalizeToInternational(trimmed) != null
    }

    /**
     * Nulls out blank / sentinel / non-phone values; otherwise returns display-normalized MSISDN.
     */
    fun nullIfUnregistered(phone: String?): String? {
        val trimmed = phone?.trim().orEmpty()
        if (!isRegisteredPhone(trimmed)) return null
        return formatForDisplay(trimmed)
    }

    /** True for empty or export placeholders that mean SQL NULL in CSV dumps. */
    fun isSqlNullToken(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        return trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("nil", ignoreCase = true) ||
            trimmed == "\\N" ||
            trimmed.equals("n/a", ignoreCase = true) ||
            trimmed == "-" ||
            trimmed == "."
    }

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
        val ascii = normalizeToAsciiDigits(phone.trim()).replace(" ", "")
        var digits = ascii.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // +98 / 0098 is Iran's country prefix: remove it, then judge what is left.
        // "+9820001390" -> "20001390" (short code, rejected below);
        // "+989121234567" -> "9121234567" (real mobile).
        val hadCountryPrefix: Boolean = when {
            ascii.startsWith("+") && digits.startsWith("98") -> {
                digits = digits.substring(2)
                true
            }
            // '+' with a non-Iranian country code: not an Iranian number at all.
            ascii.startsWith("+") -> return null
            digits.startsWith("0098") -> {
                digits = digits.substring(4)
                true
            }
            // International form typed without '+': 98 + full 10-digit number.
            digits.startsWith("98") && digits.length == 12 -> {
                digits = digits.substring(2)
                true
            }
            else -> false
        }

        // What remains must be an Iranian mobile: 9XXXXXXXXX (10 digits),
        // optionally with a leading 0 when no country prefix was used.
        val national: String = when {
            digits.length == 11 && digits.startsWith("09") -> digits.substring(1)
            // Bare "98…" without any country prefix is a country-code artifact
            // (no Iranian mobile prefix 098x exists), so reject it.
            digits.length == 10 && digits[0] == '9' && (hadCountryPrefix || digits[1] != '8') ->
                digits
            else -> return null
        }
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
