package com.example.smsgateway

import android.content.Context

/**
 * Builds the bot invite message text for Rubika, Telegram and Bale.
 *
 * When a personal referral [code] is supplied, all three links carry a
 * "?start=ref_<code>" deep-link payload so the invitee is auto-attributed to the
 * inviter. Tapping the link opens the bot and sends "/start ref_<code>" as the
 * first message, which the processors capture automatically.
 *
 * The message template is adjustable in the dashboard; @1 is replaced with the link.
 */
object BotInviteHelper {
    fun rubikaInviteText(
        settingsStore: SettingsStore,
        context: Context,
        code: String? = null,
    ): String? {
        val botInfo = settingsStore.getRubikaBotInfo() ?: return null
        val username = botInfo.username.takeIf { it.isNotBlank() } ?: return null
        val link = "https://rubika.ir/$username" + startPayload(code)
        return buildInviteText(settingsStore.getRubikaInviteMessage(), context, link)
    }

    fun telegramInviteText(
        settingsStore: SettingsStore,
        context: Context,
        code: String? = null,
    ): String? {
        val botInfo = settingsStore.getTelegramBotInfo() ?: return null
        val username = botInfo.username.takeIf { it.isNotBlank() } ?: return null
        val link = "https://t.me/$username" + startPayload(code)
        return buildInviteText(settingsStore.getTelegramInviteMessage(), context, link)
    }

    fun baleInviteText(
        settingsStore: SettingsStore,
        context: Context,
        code: String? = null,
    ): String? {
        val botInfo = settingsStore.getBaleBotInfo() ?: return null
        val username = botInfo.username.takeIf { it.isNotBlank() } ?: return null
        val link = "https://ble.ir/$username" + startPayload(code)
        return buildInviteText(settingsStore.getBaleInviteMessage(), context, link)
    }

    private fun buildInviteText(
        rawTemplate: String,
        context: Context,
        link: String,
    ): String {
        val template = rawTemplate.ifBlank {
            context.getString(R.string.bot_invite_message_default)
        }
        return template.replace("@1", link)
    }

    private fun startPayload(code: String?): String {
        if (code.isNullOrBlank()) return ""
        return "?start=" + ReferralService.CODE_PREFIX + code
    }

    /**
     * Choose the invite target that is currently enabled.
     * Used after registering a serial that requires product photo verification.
     */
    fun photoVerificationInviteText(settingsStore: SettingsStore, context: Context): String? {
        val link = when {
            settingsStore.isBaleInviteEnabled() &&
                settingsStore.isBaleBotActive() &&
                !settingsStore.getBaleBotInfo()?.username.isNullOrBlank() -> {
                "https://ble.ir/${settingsStore.getBaleBotInfo()!!.username}"
            }
            settingsStore.isRubikaInviteEnabled() &&
                settingsStore.isRubikaBotActive() &&
                !settingsStore.getRubikaBotInfo()?.username.isNullOrBlank() -> {
                "https://rubika.ir/${settingsStore.getRubikaBotInfo()!!.username}"
            }
            settingsStore.isTelegramInviteEnabled() &&
                settingsStore.isTelegramBotActive() &&
                !settingsStore.getTelegramBotInfo()?.username.isNullOrBlank() -> {
                "https://t.me/${settingsStore.getTelegramBotInfo()!!.username}"
            }

            // Fallback to any active bot if none of the specific invite toggles are enabled.
            settingsStore.isBaleBotActive() &&
                !settingsStore.getBaleBotInfo()?.username.isNullOrBlank() -> {
                "https://ble.ir/${settingsStore.getBaleBotInfo()!!.username}"
            }
            settingsStore.isRubikaBotActive() &&
                !settingsStore.getRubikaBotInfo()?.username.isNullOrBlank() -> {
                "https://rubika.ir/${settingsStore.getRubikaBotInfo()!!.username}"
            }
            settingsStore.isTelegramBotActive() &&
                !settingsStore.getTelegramBotInfo()?.username.isNullOrBlank() -> {
                "https://t.me/${settingsStore.getTelegramBotInfo()!!.username}"
            }
            else -> return null
        }
        return context.getString(R.string.bot_photo_verify_sms_invite).replace("@1", link)
    }
}
