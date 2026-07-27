package com.example.smsgateway

data class ParsedPanelInbound(
    val message: String,
    val customerPhone: String,
)

object PanelInboundParser {
    fun isPanelSender(sender: String, shortCodes: Set<String>): Boolean {
        val normalizedSender = normalizeSender(sender)
        if (normalizedSender.isEmpty()) return false
        return shortCodes.any { normalizeSender(it) == normalizedSender }
    }

    fun parse(body: String): ParsedPanelInbound? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        val semicolonIdx = trimmed.lastIndexOf(';')
        val colonIdx = trimmed.lastIndexOf(':')
        val splitIdx = maxOf(semicolonIdx, colonIdx)
        if (splitIdx <= 0 || splitIdx >= trimmed.length - 1) return null

        val message = trimmed.substring(0, splitIdx).trim()
        val phoneRaw = trimmed.substring(splitIdx + 1).trim()
        if (message.isEmpty() || phoneRaw.isEmpty()) return null

        val customerPhone = PhoneUtils.normalizeForSmsSend(phoneRaw)
        if (customerPhone.isEmpty()) return null

        return ParsedPanelInbound(message = message, customerPhone = customerPhone)
    }

    private fun normalizeSender(sender: String): String {
        val digits = sender.filter { it.isDigit() }
        if (digits.isEmpty()) return sender.trim()
        return when {
            digits.length >= 10 && digits.endsWith("20001390") -> "20001390"
            digits.length >= 9 && digits.endsWith("2001390") -> "2001390"
            else -> digits.trimStart('0')
        }
    }
}
