package com.example.smsgateway

data class ParsedPanelInbound(
    val message: String,
    val customerPhone: String,
)

object PanelInboundParser {
    /**
     * Inbound is accepted only from a line configured in settings. Carriers may deliver
     * the same code with a country prefix (`+98…`) or extra leading zeros, so both sides
     * are reduced to bare digits before comparing.
     */
    fun isPanelSender(sender: String, shortCodes: Set<String>): Boolean {
        val normalizedSender = normalizeSender(sender)
        if (normalizedSender.isEmpty()) return false
        return shortCodes.any { code ->
            val normalizedCode = normalizeSender(code)
            normalizedCode.isNotEmpty() && normalizedCode == normalizedSender
        }
    }

    fun parse(body: String): ParsedPanelInbound? {
        val strippedBody = stripMelipayamakFooter(body).trim()
        if (strippedBody.isEmpty()) return null

        val semicolonIdx = strippedBody.lastIndexOf(';')
        val colonIdx = strippedBody.lastIndexOf(':')
        val splitIdx = maxOf(semicolonIdx, colonIdx)
        if (splitIdx <= 0 || splitIdx >= strippedBody.length - 1) return null

        val left = strippedBody.substring(0, splitIdx).trim()
        val right = strippedBody.substring(splitIdx + 1).trim()
        if (left.isEmpty() || right.isEmpty()) return null

        val leftIsPhone = isClientPhoneToken(left)
        val rightIsPhone = isClientPhoneToken(right)
        if (leftIsPhone == rightIsPhone) return null

        val phoneRaw = if (leftIsPhone) left else right
        val messageRaw = if (leftIsPhone) right else left
        val message = stripMelipayamakFooter(messageRaw).trim()
        if (message.isEmpty()) return null

        val customerPhone = PhoneUtils.normalizeForSmsSend(phoneRaw)
        if (customerPhone.isEmpty() || !isClientPhoneToken(customerPhone)) return null

        return ParsedPanelInbound(message = message, customerPhone = customerPhone)
    }

    /** Removes Melipayamak regulatory opt-out footer (لغو 11 / variants). */
    fun stripMelipayamakFooter(text: String): String {
        var result = text.trimEnd()
        var previous: String
        do {
            previous = result
            result = FOOTER_REGEX.replace(result, "").trimEnd()
        } while (result != previous)
        return result
    }

    private fun isClientPhoneToken(token: String): Boolean =
        PhoneUtils.isRegisteredPhone(token)

    private fun normalizeSender(sender: String): String {
        val digits = PhoneUtils.normalizeToAsciiDigits(sender.trim()).filter { it.isDigit() }
        if (digits.isEmpty()) return sender.trim()
        var bare = digits
        if (bare.startsWith("0098")) {
            bare = bare.substring(4)
        } else if (bare.startsWith("98") && bare.length > 2) {
            bare = bare.substring(2)
        }
        return bare.trimStart('0').ifEmpty { digits }
    }

    // Trailing "لغو 11" / "لغو11" with ASCII or Persian/Arabic digits, optional newlines.
    private val FOOTER_REGEX = Regex(
        """(?:\r?\n|\s)*لغو\s*[0-9۰-۹٠-٩]{1,3}\s*$""",
    )
}
