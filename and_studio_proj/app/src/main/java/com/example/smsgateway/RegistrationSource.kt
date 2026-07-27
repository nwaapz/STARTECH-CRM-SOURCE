package com.example.smsgateway

object RegistrationSource {
    const val SMS = "sms"
    const val RUBIKA = "rubika"
    const val TELEGRAM = "telegram"
    const val BALE = "bale"

    val ALL = setOf(SMS, RUBIKA, TELEGRAM, BALE)

    fun normalizeOrDefault(value: String?): String {
        val trimmed = value?.trim()?.lowercase().orEmpty()
        return if (trimmed in ALL) trimmed else SMS
    }

    fun normalizeOrNull(value: String?): String? {
        val trimmed = value?.trim()?.lowercase().orEmpty()
        return trimmed.takeIf { it in ALL }
    }
}
