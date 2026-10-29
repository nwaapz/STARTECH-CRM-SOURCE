package com.example.smsgateway

/** Optional second SMS sent after successful serial registration (باشگاه مشتریان). */
data class ClubWelcomeSettings(
    val enabled: Boolean = false,
    val message: String = "",
) {
    fun normalized(): ClubWelcomeSettings = copy(message = message.trim())
}
