package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase

/**
 * Resolves a bot chat id for a phone number across the different bot platforms.
 * Shared by the club auto-message scheduler and mass-message sending engine so a phone
 * that has never started a given bot can be detected (and skipped) consistently.
 */
object BotChatIdResolver {
    fun resolveRubika(database: GatewayDatabase, phone: String): String? {
        val dao = database.rubikaChatContactDao()
        for (candidate in candidates(phone)) {
            dao.findByPhone(candidate)?.chatId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun resolveTelegram(database: GatewayDatabase, phone: String): String? {
        val dao = database.telegramChatContactDao()
        for (candidate in candidates(phone)) {
            dao.findByPhone(candidate)?.chatId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun resolveBale(database: GatewayDatabase, phone: String): String? {
        val dao = database.baleChatContactDao()
        for (candidate in candidates(phone)) {
            dao.findByPhone(candidate)?.chatId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun candidates(phone: String): List<String> {
        val normalized = PhoneUtils.normalizeForSmsSend(phone)
        return listOfNotNull(normalized, phone, phone.trim()).distinct().filter { it.isNotBlank() }
    }
}
