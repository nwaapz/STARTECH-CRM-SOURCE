package com.example.smsgateway

import android.content.Context
import android.telephony.SubscriptionManager

enum class VerificationMode {
    REMOTE,
    INTERNAL,
}

enum class SmsEngineMode {
    DEVICE_SIM,
    MELIPAYAMAK_PANEL,
}

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGatewayUrl(): String = GATEWAY_URL

    fun getVerificationMode(): VerificationMode {
        val stored = prefs.getString(KEY_VERIFICATION_MODE, VerificationMode.REMOTE.name)
        return runCatching { VerificationMode.valueOf(stored ?: VerificationMode.REMOTE.name) }
            .getOrDefault(VerificationMode.REMOTE)
    }

    fun setVerificationMode(mode: VerificationMode) {
        prefs.edit().putString(KEY_VERIFICATION_MODE, mode.name).apply()
    }

    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isAutoStart(): Boolean = prefs.getBoolean(KEY_AUTO_START, true)

    fun getEnabledSubscriptionIds(): Set<Int> {
        val stored = prefs.getStringSet(KEY_ENABLED_SUBSCRIPTIONS, null)
        if (stored == null) {
            return SimCardHelper.getAvailableSims(appContext).map { it.subscriptionId }.toSet()
        }
        return stored.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setEnabledSubscriptionIds(ids: Set<Int>) {
        prefs.edit()
            .putStringSet(KEY_ENABLED_SUBSCRIPTIONS, ids.map { it.toString() }.toSet())
            .apply()
    }

    fun isSubscriptionEnabled(subscriptionId: Int): Boolean {
        val enabled = getEnabledSubscriptionIds()
        if (enabled.isEmpty()) return true
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return true
        }

        val incomingKeys = SimCardHelper.subscriptionMatchKeys(appContext, subscriptionId)
        return enabled.any { enabledId ->
            SimCardHelper.subscriptionMatchKeys(appContext, enabledId).any { it in incomingKeys }
        }
    }

    /** Primary (enabled) SIMs, or any discovered SIM when backup mode is on. */
    fun isSubscriptionAllowedForInbound(subscriptionId: Int): Boolean {
        if (isSubscriptionEnabled(subscriptionId)) return true
        if (!isBackupSimsEnabled()) return false
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return true
        return SimCardHelper.getAvailableSims(appContext).any { sim ->
            SimCardHelper.subscriptionMatchKeys(appContext, sim.subscriptionId).any { key ->
                key in SimCardHelper.subscriptionMatchKeys(appContext, subscriptionId)
            }
        }
    }

    fun getSmsDailyLimit(): Int =
        prefs.getInt(KEY_SMS_DAILY_LIMIT, SimSmsQuotaStore.DEFAULT_DAILY_LIMIT)
            .coerceIn(1, 100_000)

    fun setSmsDailyLimit(limit: Int) {
        prefs.edit()
            .putInt(KEY_SMS_DAILY_LIMIT, limit.coerceIn(1, 100_000))
            .apply()
    }

    fun isBackupSimsEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_SIMS_ENABLED, false)

    fun setBackupSimsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKUP_SIMS_ENABLED, enabled).apply()
    }

    fun reconcileEnabledSubscriptionIds() {
        val available = SimCardHelper.getAvailableSims(appContext).map { it.subscriptionId }.toSet()
        if (available.isEmpty()) return

        val reconciled = getEnabledSubscriptionIds()
            .flatMap { SimCardHelper.subscriptionMatchKeys(appContext, it) }
            .filter { key ->
                available.any { availableId ->
                    SimCardHelper.subscriptionMatchKeys(appContext, availableId).contains(key)
                }
            }
            .toSet()

        val normalized = available.filter { simId ->
            reconciled.any { key ->
                key in SimCardHelper.subscriptionMatchKeys(appContext, simId)
            }
        }.toSet()

        if (normalized.isNotEmpty()) {
            setEnabledSubscriptionIds(normalized)
        } else {
            setEnabledSubscriptionIds(available)
        }
    }

    fun ensureDefaultSimSelection() {
        if (!prefs.contains(KEY_ENABLED_SUBSCRIPTIONS)) {
            val all = SimCardHelper.getAvailableSims(appContext).map { it.subscriptionId }.toSet()
            if (all.isNotEmpty()) {
                setEnabledSubscriptionIds(all)
            }
        }
    }

    fun isLanServerEnabled(): Boolean = prefs.getBoolean(KEY_LAN_SERVER_ENABLED, false)

    fun setLanServerEnabled(enabled: Boolean) {
        if (enabled) {
            ensureLanPin()
            if (getVerificationMode() != VerificationMode.INTERNAL) {
                setVerificationMode(VerificationMode.INTERNAL)
            }
        }
        prefs.edit().putBoolean(KEY_LAN_SERVER_ENABLED, enabled).apply()
    }

    fun getLanPort(): Int = prefs.getInt(KEY_LAN_PORT, DEFAULT_LAN_PORT)

    fun setLanPort(port: Int) {
        prefs.edit().putInt(KEY_LAN_PORT, port.coerceIn(1024, 65535)).apply()
    }

    fun getLanPin(): String {
        val stored = prefs.getString(KEY_LAN_PIN, null)
        if (!stored.isNullOrBlank()) return stored
        return ensureLanPin()
    }

    fun setLanPin(pin: String) {
        prefs.edit().putString(KEY_LAN_PIN, pin.trim()).apply()
    }

    /** Custom host/IP for CRM URL display (empty = auto-detect from network). */
    fun getLanHostOverride(): String = prefs.getString(KEY_LAN_HOST_OVERRIDE, "").orEmpty().trim()

    fun setLanHostOverride(host: String) {
        prefs.edit().putString(KEY_LAN_HOST_OVERRIDE, host.trim()).apply()
    }

    fun clearLanHostOverride() {
        prefs.edit().remove(KEY_LAN_HOST_OVERRIDE).apply()
    }

    fun hasLanHostOverride(): Boolean = getLanHostOverride().isNotBlank()

    fun getSmsProcessingConfig(): SmsProcessingConfig {
        val raw = prefs.getString(KEY_SMS_PROCESSING_CONFIG, null)
        return SmsProcessingConfig.fromJsonString(raw)
    }

    fun setSmsProcessingConfig(config: SmsProcessingConfig) {
        val normalized = config.normalized()
        val error = normalized.validate()
        require(error == null) { error ?: "Invalid SMS processing config" }
        prefs.edit()
            .putString(KEY_SMS_PROCESSING_CONFIG, normalized.toJsonObject().toString())
            .apply()
    }

    fun getSmsMessageOverrides(): Map<SmsMessageId, String> {
        val raw = prefs.getString(KEY_SMS_MESSAGE_OVERRIDES, null)
        return SmsMessageTemplates.overridesFromJson(raw)
    }

    fun setSmsMessageOverrides(overrides: Map<SmsMessageId, String>) {
        prefs.edit()
            .putString(KEY_SMS_MESSAGE_OVERRIDES, SmsMessageTemplates.overridesToJson(overrides))
            .apply()
    }

    private fun ensureLanPin(): String {
        val existing = prefs.getString(KEY_LAN_PIN, null)
        if (!existing.isNullOrBlank()) return existing
        val pin = (100000..999999).random().toString()
        prefs.edit().putString(KEY_LAN_PIN, pin).apply()
        return pin
    }

    fun getRubikaToken(): String = prefs.getString(KEY_RUBIKA_BOT_TOKEN, "").orEmpty()

    fun setRubikaToken(token: String) {
        prefs.edit().putString(KEY_RUBIKA_BOT_TOKEN, token.trim()).apply()
    }

    fun isRubikaBotActive(): Boolean = prefs.getBoolean(KEY_RUBIKA_BOT_ACTIVE, false)

    fun getRubikaBotInfo(): RubikaBotInfo? {
        if (!isRubikaBotActive()) return null
        val botId = prefs.getString(KEY_RUBIKA_BOT_ID, null) ?: return null
        return RubikaBotInfo(
            botId = botId,
            title = prefs.getString(KEY_RUBIKA_BOT_TITLE, "").orEmpty(),
            username = prefs.getString(KEY_RUBIKA_BOT_USERNAME, "").orEmpty(),
        )
    }

    fun setRubikaBotActive(info: RubikaBotInfo, token: String) {
        prefs.edit()
            .putString(KEY_RUBIKA_BOT_TOKEN, token.trim())
            .putBoolean(KEY_RUBIKA_BOT_ACTIVE, true)
            .putString(KEY_RUBIKA_BOT_ID, info.botId)
            .putString(KEY_RUBIKA_BOT_TITLE, info.title)
            .putString(KEY_RUBIKA_BOT_USERNAME, info.username)
            .apply()
    }

    fun getRubikaNextOffsetId(): String? {
        val stored = prefs.getString(KEY_RUBIKA_NEXT_OFFSET_ID, null)
        return stored?.takeIf { it.isNotBlank() }
    }

    fun setRubikaNextOffsetId(offsetId: String?) {
        prefs.edit()
            .putString(KEY_RUBIKA_NEXT_OFFSET_ID, offsetId?.trim().orEmpty())
            .apply()
    }

    fun clearRubikaBot() {
        prefs.edit()
            .remove(KEY_RUBIKA_BOT_TOKEN)
            .putBoolean(KEY_RUBIKA_BOT_ACTIVE, false)
            .remove(KEY_RUBIKA_BOT_ID)
            .remove(KEY_RUBIKA_BOT_TITLE)
            .remove(KEY_RUBIKA_BOT_USERNAME)
            .apply()
    }

    fun isRubikaSerialEnabled(): Boolean = prefs.getBoolean(KEY_RUBIKA_SERIAL_ENABLED, false)

    fun setRubikaSerialEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RUBIKA_SERIAL_ENABLED, enabled).apply()
    }

    fun isRubikaInviteEnabled(): Boolean = prefs.getBoolean(KEY_RUBIKA_INVITE_ENABLED, false)

    fun setRubikaInviteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RUBIKA_INVITE_ENABLED, enabled).apply()
    }

    fun getRubikaInviteMessage(): String = prefs.getString(KEY_RUBIKA_INVITE_MESSAGE, "").orEmpty()

    fun setRubikaInviteMessage(message: String) {
        prefs.edit().putString(KEY_RUBIKA_INVITE_MESSAGE, message).apply()
    }

    fun getRubikaMenuSettings(): BotMenuSettings = BotMenuSettings(
        registerWarranty = prefs.getBoolean(KEY_RUBIKA_MENU_REGISTER_WARRANTY, true),
        warrantyStatus = prefs.getBoolean(KEY_RUBIKA_MENU_WARRANTY_STATUS, true),
        sendProductPhoto = prefs.getBoolean(KEY_RUBIKA_MENU_SEND_PRODUCT_PHOTO, true),
        inviteFriends = prefs.getBoolean(KEY_RUBIKA_MENU_INVITE_FRIENDS, true),
        myScore = prefs.getBoolean(KEY_RUBIKA_MENU_MY_SCORE, true),
        support = prefs.getBoolean(KEY_RUBIKA_MENU_SUPPORT, true),
        help = prefs.getBoolean(KEY_RUBIKA_MENU_HELP, true),
    )

    fun setRubikaMenuSettings(settings: BotMenuSettings) {
        prefs.edit()
            .putBoolean(KEY_RUBIKA_MENU_REGISTER_WARRANTY, settings.registerWarranty)
            .putBoolean(KEY_RUBIKA_MENU_WARRANTY_STATUS, settings.warrantyStatus)
            .putBoolean(KEY_RUBIKA_MENU_SEND_PRODUCT_PHOTO, settings.sendProductPhoto)
            .putBoolean(KEY_RUBIKA_MENU_INVITE_FRIENDS, settings.inviteFriends)
            .putBoolean(KEY_RUBIKA_MENU_MY_SCORE, settings.myScore)
            .putBoolean(KEY_RUBIKA_MENU_SUPPORT, settings.support)
            .putBoolean(KEY_RUBIKA_MENU_HELP, settings.help)
            .apply()
    }

    fun wasRubikaInviteSent(phone: String): Boolean {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        if (normalized.isBlank()) return false
        return prefs.getStringSet(KEY_RUBIKA_INVITE_SENT_PHONES, emptySet())
            ?.contains(normalized) == true
    }

    fun markRubikaInviteSent(phone: String) {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        if (normalized.isBlank()) return
        val updated = prefs.getStringSet(KEY_RUBIKA_INVITE_SENT_PHONES, emptySet())
            .orEmpty()
            .toMutableSet()
        updated.add(normalized)
        prefs.edit().putStringSet(KEY_RUBIKA_INVITE_SENT_PHONES, updated).apply()
    }

    fun getWhatsAppConfig(): WhatsAppConfig {
        val raw = prefs.getString(KEY_WHATSAPP_CONFIG, null)
        return WhatsAppConfig.fromJsonString(raw)
    }

    fun setWhatsAppConfig(config: WhatsAppConfig) {
        prefs.edit().putString(KEY_WHATSAPP_CONFIG, config.toJsonString()).apply()
    }

    fun getWhatsAppEngineLastSeen(): Long = prefs.getLong(KEY_WHATSAPP_ENGINE_LAST_SEEN, 0L)

    fun isWhatsAppEngineLoggedIn(): Boolean = prefs.getBoolean(KEY_WHATSAPP_ENGINE_LOGGED_IN, false)

    fun recordWhatsAppEngineHeartbeat(loggedIn: Boolean) {
        prefs.edit()
            .putLong(KEY_WHATSAPP_ENGINE_LAST_SEEN, System.currentTimeMillis())
            .putBoolean(KEY_WHATSAPP_ENGINE_LOGGED_IN, loggedIn)
            .apply()
    }

    fun getTelegramToken(): String = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "").orEmpty()

    fun setTelegramToken(token: String) {
        prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, token.trim()).apply()
    }

    fun isTelegramBotActive(): Boolean = prefs.getBoolean(KEY_TELEGRAM_BOT_ACTIVE, false)

    fun getTelegramBotInfo(): TelegramBotInfo? {
        if (!isTelegramBotActive()) return null
        val botId = prefs.getString(KEY_TELEGRAM_BOT_ID, null) ?: return null
        return TelegramBotInfo(
            botId = botId,
            title = prefs.getString(KEY_TELEGRAM_BOT_TITLE, "").orEmpty(),
            username = prefs.getString(KEY_TELEGRAM_BOT_USERNAME, "").orEmpty(),
        )
    }

    fun setTelegramBotActive(info: TelegramBotInfo, token: String) {
        prefs.edit()
            .putString(KEY_TELEGRAM_BOT_TOKEN, token.trim())
            .putBoolean(KEY_TELEGRAM_BOT_ACTIVE, true)
            .putString(KEY_TELEGRAM_BOT_ID, info.botId)
            .putString(KEY_TELEGRAM_BOT_TITLE, info.title)
            .putString(KEY_TELEGRAM_BOT_USERNAME, info.username)
            .apply()
    }

    fun getTelegramNextOffsetId(): String? {
        val stored = prefs.getString(KEY_TELEGRAM_NEXT_OFFSET_ID, null)
        return stored?.takeIf { it.isNotBlank() }
    }

    fun setTelegramNextOffsetId(offsetId: String?) {
        prefs.edit()
            .putString(KEY_TELEGRAM_NEXT_OFFSET_ID, offsetId?.trim().orEmpty())
            .apply()
    }

    fun clearTelegramBot() {
        prefs.edit()
            .remove(KEY_TELEGRAM_BOT_TOKEN)
            .putBoolean(KEY_TELEGRAM_BOT_ACTIVE, false)
            .remove(KEY_TELEGRAM_BOT_ID)
            .remove(KEY_TELEGRAM_BOT_TITLE)
            .remove(KEY_TELEGRAM_BOT_USERNAME)
            .apply()
    }

    fun isTelegramSerialEnabled(): Boolean = prefs.getBoolean(KEY_TELEGRAM_SERIAL_ENABLED, false)

    fun setTelegramSerialEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGRAM_SERIAL_ENABLED, enabled).apply()
    }

    fun isTelegramInviteEnabled(): Boolean = prefs.getBoolean(KEY_TELEGRAM_INVITE_ENABLED, false)

    fun setTelegramInviteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGRAM_INVITE_ENABLED, enabled).apply()
    }

    fun getTelegramInviteMessage(): String = prefs.getString(KEY_TELEGRAM_INVITE_MESSAGE, "").orEmpty()

    fun setTelegramInviteMessage(message: String) {
        prefs.edit().putString(KEY_TELEGRAM_INVITE_MESSAGE, message).apply()
    }

    fun getTelegramMenuSettings(): BotMenuSettings = BotMenuSettings(
        registerWarranty = prefs.getBoolean(KEY_TELEGRAM_MENU_REGISTER_WARRANTY, true),
        warrantyStatus = prefs.getBoolean(KEY_TELEGRAM_MENU_WARRANTY_STATUS, true),
        sendProductPhoto = prefs.getBoolean(KEY_TELEGRAM_MENU_SEND_PRODUCT_PHOTO, true),
        inviteFriends = prefs.getBoolean(KEY_TELEGRAM_MENU_INVITE_FRIENDS, true),
        myScore = prefs.getBoolean(KEY_TELEGRAM_MENU_MY_SCORE, true),
        support = prefs.getBoolean(KEY_TELEGRAM_MENU_SUPPORT, true),
        help = prefs.getBoolean(KEY_TELEGRAM_MENU_HELP, true),
    )

    fun setTelegramMenuSettings(settings: BotMenuSettings) {
        prefs.edit()
            .putBoolean(KEY_TELEGRAM_MENU_REGISTER_WARRANTY, settings.registerWarranty)
            .putBoolean(KEY_TELEGRAM_MENU_WARRANTY_STATUS, settings.warrantyStatus)
            .putBoolean(KEY_TELEGRAM_MENU_SEND_PRODUCT_PHOTO, settings.sendProductPhoto)
            .putBoolean(KEY_TELEGRAM_MENU_INVITE_FRIENDS, settings.inviteFriends)
            .putBoolean(KEY_TELEGRAM_MENU_MY_SCORE, settings.myScore)
            .putBoolean(KEY_TELEGRAM_MENU_SUPPORT, settings.support)
            .putBoolean(KEY_TELEGRAM_MENU_HELP, settings.help)
            .apply()
    }

    fun getBaleToken(): String = prefs.getString(KEY_BALE_BOT_TOKEN, "").orEmpty()

    fun setBaleToken(token: String) {
        prefs.edit().putString(KEY_BALE_BOT_TOKEN, token.trim()).apply()
    }

    fun isBaleBotActive(): Boolean = prefs.getBoolean(KEY_BALE_BOT_ACTIVE, false)

    fun getBaleBotInfo(): BaleBotInfo? {
        if (!isBaleBotActive()) return null
        val botId = prefs.getString(KEY_BALE_BOT_ID, null) ?: return null
        return BaleBotInfo(
            botId = botId,
            title = prefs.getString(KEY_BALE_BOT_TITLE, "").orEmpty(),
            username = prefs.getString(KEY_BALE_BOT_USERNAME, "").orEmpty(),
        )
    }

    fun setBaleBotActive(info: BaleBotInfo, token: String) {
        prefs.edit()
            .putString(KEY_BALE_BOT_TOKEN, token.trim())
            .putBoolean(KEY_BALE_BOT_ACTIVE, true)
            .putString(KEY_BALE_BOT_ID, info.botId)
            .putString(KEY_BALE_BOT_TITLE, info.title)
            .putString(KEY_BALE_BOT_USERNAME, info.username)
            .apply()
    }

    fun getBaleNextOffsetId(): String? {
        val stored = prefs.getString(KEY_BALE_NEXT_OFFSET_ID, null)
        return stored?.takeIf { it.isNotBlank() }
    }

    fun setBaleNextOffsetId(offsetId: String?) {
        prefs.edit()
            .putString(KEY_BALE_NEXT_OFFSET_ID, offsetId?.trim().orEmpty())
            .apply()
    }

    fun clearBaleBot() {
        prefs.edit()
            .remove(KEY_BALE_BOT_TOKEN)
            .putBoolean(KEY_BALE_BOT_ACTIVE, false)
            .remove(KEY_BALE_BOT_ID)
            .remove(KEY_BALE_BOT_TITLE)
            .remove(KEY_BALE_BOT_USERNAME)
            .apply()
    }

    fun isBaleSerialEnabled(): Boolean = prefs.getBoolean(KEY_BALE_SERIAL_ENABLED, false)

    fun setBaleSerialEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BALE_SERIAL_ENABLED, enabled).apply()
    }

    fun isBaleInviteEnabled(): Boolean = prefs.getBoolean(KEY_BALE_INVITE_ENABLED, false)

    fun setBaleInviteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BALE_INVITE_ENABLED, enabled).apply()
    }

    fun getBaleInviteMessage(): String = prefs.getString(KEY_BALE_INVITE_MESSAGE, "").orEmpty()

    fun setBaleInviteMessage(message: String) {
        prefs.edit().putString(KEY_BALE_INVITE_MESSAGE, message).apply()
    }

    fun getBaleMenuSettings(): BotMenuSettings = BotMenuSettings(
        registerWarranty = prefs.getBoolean(KEY_BALE_MENU_REGISTER_WARRANTY, true),
        warrantyStatus = prefs.getBoolean(KEY_BALE_MENU_WARRANTY_STATUS, true),
        sendProductPhoto = prefs.getBoolean(KEY_BALE_MENU_SEND_PRODUCT_PHOTO, true),
        inviteFriends = prefs.getBoolean(KEY_BALE_MENU_INVITE_FRIENDS, true),
        myScore = prefs.getBoolean(KEY_BALE_MENU_MY_SCORE, true),
        support = prefs.getBoolean(KEY_BALE_MENU_SUPPORT, true),
        help = prefs.getBoolean(KEY_BALE_MENU_HELP, true),
    )

    fun setBaleMenuSettings(settings: BotMenuSettings) {
        prefs.edit()
            .putBoolean(KEY_BALE_MENU_REGISTER_WARRANTY, settings.registerWarranty)
            .putBoolean(KEY_BALE_MENU_WARRANTY_STATUS, settings.warrantyStatus)
            .putBoolean(KEY_BALE_MENU_SEND_PRODUCT_PHOTO, settings.sendProductPhoto)
            .putBoolean(KEY_BALE_MENU_INVITE_FRIENDS, settings.inviteFriends)
            .putBoolean(KEY_BALE_MENU_MY_SCORE, settings.myScore)
            .putBoolean(KEY_BALE_MENU_SUPPORT, settings.support)
            .putBoolean(KEY_BALE_MENU_HELP, settings.help)
            .apply()
    }

    fun getReferralSettings(): ReferralSettings = ReferralSettings(
        enabled = prefs.getBoolean(KEY_REFERRAL_ENABLED, false),
        basePoints = prefs.getInt(KEY_REFERRAL_BASE_POINTS, ReferralSettings.DEFAULT_BASE_POINTS),
        decayPercent = prefs.getInt(KEY_REFERRAL_DECAY_PERCENT, ReferralSettings.DEFAULT_DECAY_PERCENT),
        maxDepth = prefs.getInt(KEY_REFERRAL_MAX_DEPTH, ReferralSettings.DEFAULT_MAX_DEPTH),
    ).normalized()

    fun setReferralSettings(settings: ReferralSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putBoolean(KEY_REFERRAL_ENABLED, normalized.enabled)
            .putInt(KEY_REFERRAL_BASE_POINTS, normalized.basePoints)
            .putInt(KEY_REFERRAL_DECAY_PERCENT, normalized.decayPercent)
            .putInt(KEY_REFERRAL_MAX_DEPTH, normalized.maxDepth)
            .apply()
    }

    fun isReferralEnabled(): Boolean = prefs.getBoolean(KEY_REFERRAL_ENABLED, false)

    fun getLotteryPresetPhone(): String =
        prefs.getString(KEY_LOTTERY_PRESET_PHONE, "").orEmpty().trim()

    fun setLotteryPresetPhone(phone: String) {
        prefs.edit().putString(KEY_LOTTERY_PRESET_PHONE, phone.trim()).apply()
    }

    fun clearLotteryPresetPhone() {
        prefs.edit().remove(KEY_LOTTERY_PRESET_PHONE).apply()
    }

    fun getCloudSyncUrl(): String =
        prefs.getString(KEY_CLOUD_SYNC_URL, "").orEmpty().trim()

    fun setCloudSyncUrl(url: String) {
        prefs.edit().putString(KEY_CLOUD_SYNC_URL, url.trim()).apply()
    }

    fun getCloudSyncToken(): String =
        prefs.getString(KEY_CLOUD_SYNC_TOKEN, "").orEmpty().trim()

    fun setCloudSyncToken(token: String) {
        prefs.edit().putString(KEY_CLOUD_SYNC_TOKEN, token.trim()).apply()
    }

    fun getCloudSyncLastPullMs(): Long = prefs.getLong(KEY_CLOUD_SYNC_LAST_PULL_MS, 0L)

    fun setCloudSyncLastPullMs(ms: Long) {
        prefs.edit().putLong(KEY_CLOUD_SYNC_LAST_PULL_MS, ms).apply()
    }

    fun getCloudSyncLastPushMs(): Long = prefs.getLong(KEY_CLOUD_SYNC_LAST_PUSH_MS, 0L)

    fun setCloudSyncLastPushMs(ms: Long) {
        prefs.edit().putLong(KEY_CLOUD_SYNC_LAST_PUSH_MS, ms).apply()
    }

    fun getSmsEngineMode(): SmsEngineMode {
        val stored = prefs.getString(KEY_SMS_ENGINE_MODE, SmsEngineMode.DEVICE_SIM.name)
        return runCatching { SmsEngineMode.valueOf(stored ?: SmsEngineMode.DEVICE_SIM.name) }
            .getOrDefault(SmsEngineMode.DEVICE_SIM)
    }

    fun setSmsEngineMode(mode: SmsEngineMode) {
        prefs.edit().putString(KEY_SMS_ENGINE_MODE, mode.name).apply()
    }

    fun isMelipayamakPanelMode(): Boolean = getSmsEngineMode() == SmsEngineMode.MELIPAYAMAK_PANEL

    fun getMelipayamakUsername(): String =
        prefs.getString(KEY_MELIPAYAMAK_USERNAME, "").orEmpty().trim()

    fun setMelipayamakUsername(username: String) {
        prefs.edit().putString(KEY_MELIPAYAMAK_USERNAME, username.trim()).apply()
    }

    fun getMelipayamakPassword(): String =
        prefs.getString(KEY_MELIPAYAMAK_PASSWORD, "").orEmpty()

    fun setMelipayamakPassword(password: String) {
        prefs.edit().putString(KEY_MELIPAYAMAK_PASSWORD, password).apply()
    }

    fun getMelipayamakSenderLine(): String =
        prefs.getString(KEY_MELIPAYAMAK_SENDER_LINE, DEFAULT_MELIPAYAMAK_SENDER_LINE)
            .orEmpty()
            .trim()
            .ifBlank { DEFAULT_MELIPAYAMAK_SENDER_LINE }

    fun setMelipayamakSenderLine(line: String) {
        prefs.edit().putString(KEY_MELIPAYAMAK_SENDER_LINE, line.trim()).apply()
    }

    fun getMelipayamakInboundShortCodes(): Set<String> {
        val raw = prefs.getString(KEY_MELIPAYAMAK_INBOUND_SHORT_CODES, DEFAULT_MELIPAYAMAK_INBOUND_SHORT_CODES)
            .orEmpty()
        return raw.split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { DEFAULT_MELIPAYAMAK_INBOUND_SHORT_CODES.split(',').map { it.trim() }.toSet() }
    }

    fun setMelipayamakInboundShortCodes(codes: String) {
        prefs.edit().putString(KEY_MELIPAYAMAK_INBOUND_SHORT_CODES, codes.trim()).apply()
    }

    companion object {
        const val DEFAULT_MELIPAYAMAK_SENDER_LINE = "20001390"
        private const val DEFAULT_MELIPAYAMAK_INBOUND_SHORT_CODES = "20001390,2001390,+9820001390"
        const val GATEWAY_URL = "https://startechgroup.ir/gateway/Gateway.php"
        const val DEFAULT_LAN_PORT = 8765
        private const val PREFS_NAME = "gateway_settings"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_ENABLED_SUBSCRIPTIONS = "enabled_subscriptions"
        private const val KEY_SMS_DAILY_LIMIT = "sms_daily_limit"
        private const val KEY_BACKUP_SIMS_ENABLED = "backup_sims_enabled"
        private const val KEY_VERIFICATION_MODE = "verification_mode"
        private const val KEY_LAN_SERVER_ENABLED = "lan_server_enabled"
        private const val KEY_LAN_PORT = "lan_port"
        private const val KEY_LAN_PIN = "lan_pin"
        private const val KEY_LAN_HOST_OVERRIDE = "lan_host_override"
        private const val KEY_SMS_PROCESSING_CONFIG = "sms_processing_config"
        private const val KEY_SMS_MESSAGE_OVERRIDES = "sms_message_overrides"
        private const val KEY_RUBIKA_BOT_TOKEN = "rubika_bot_token"
        private const val KEY_RUBIKA_BOT_ACTIVE = "rubika_bot_active"
        private const val KEY_RUBIKA_BOT_ID = "rubika_bot_id"
        private const val KEY_RUBIKA_BOT_TITLE = "rubika_bot_title"
        private const val KEY_RUBIKA_BOT_USERNAME = "rubika_bot_username"
        private const val KEY_RUBIKA_NEXT_OFFSET_ID = "rubika_next_offset_id"
        private const val KEY_RUBIKA_SERIAL_ENABLED = "rubika_serial_enabled"
        private const val KEY_RUBIKA_INVITE_ENABLED = "rubika_invite_enabled"
        private const val KEY_RUBIKA_INVITE_MESSAGE = "rubika_invite_message"
        private const val KEY_RUBIKA_INVITE_SENT_PHONES = "rubika_invite_sent_phones"
        private const val KEY_RUBIKA_MENU_REGISTER_WARRANTY = "rubika_menu_register_warranty"
        private const val KEY_RUBIKA_MENU_WARRANTY_STATUS = "rubika_menu_warranty_status"
        private const val KEY_RUBIKA_MENU_SEND_PRODUCT_PHOTO = "rubika_menu_send_product_photo"
        private const val KEY_RUBIKA_MENU_INVITE_FRIENDS = "rubika_menu_invite_friends"
        private const val KEY_RUBIKA_MENU_MY_SCORE = "rubika_menu_my_score"
        private const val KEY_RUBIKA_MENU_SUPPORT = "rubika_menu_support"
        private const val KEY_RUBIKA_MENU_HELP = "rubika_menu_help"
        private const val KEY_WHATSAPP_CONFIG = "whatsapp_config"
        private const val KEY_WHATSAPP_ENGINE_LAST_SEEN = "whatsapp_engine_last_seen"
        private const val KEY_WHATSAPP_ENGINE_LOGGED_IN = "whatsapp_engine_logged_in"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_BOT_ACTIVE = "telegram_bot_active"
        private const val KEY_TELEGRAM_BOT_ID = "telegram_bot_id"
        private const val KEY_TELEGRAM_BOT_TITLE = "telegram_bot_title"
        private const val KEY_TELEGRAM_BOT_USERNAME = "telegram_bot_username"
        private const val KEY_TELEGRAM_NEXT_OFFSET_ID = "telegram_next_offset_id"
        private const val KEY_TELEGRAM_SERIAL_ENABLED = "telegram_serial_enabled"
        private const val KEY_TELEGRAM_INVITE_ENABLED = "telegram_invite_enabled"
        private const val KEY_TELEGRAM_INVITE_MESSAGE = "telegram_invite_message"
        private const val KEY_TELEGRAM_MENU_REGISTER_WARRANTY = "telegram_menu_register_warranty"
        private const val KEY_TELEGRAM_MENU_WARRANTY_STATUS = "telegram_menu_warranty_status"
        private const val KEY_TELEGRAM_MENU_SEND_PRODUCT_PHOTO = "telegram_menu_send_product_photo"
        private const val KEY_TELEGRAM_MENU_INVITE_FRIENDS = "telegram_menu_invite_friends"
        private const val KEY_TELEGRAM_MENU_MY_SCORE = "telegram_menu_my_score"
        private const val KEY_TELEGRAM_MENU_SUPPORT = "telegram_menu_support"
        private const val KEY_TELEGRAM_MENU_HELP = "telegram_menu_help"
        private const val KEY_BALE_BOT_TOKEN = "bale_bot_token"
        private const val KEY_BALE_BOT_ACTIVE = "bale_bot_active"
        private const val KEY_BALE_BOT_ID = "bale_bot_id"
        private const val KEY_BALE_BOT_TITLE = "bale_bot_title"
        private const val KEY_BALE_BOT_USERNAME = "bale_bot_username"
        private const val KEY_BALE_NEXT_OFFSET_ID = "bale_next_offset_id"
        private const val KEY_BALE_SERIAL_ENABLED = "bale_serial_enabled"
        private const val KEY_BALE_INVITE_ENABLED = "bale_invite_enabled"
        private const val KEY_BALE_INVITE_MESSAGE = "bale_invite_message"
        private const val KEY_BALE_MENU_REGISTER_WARRANTY = "bale_menu_register_warranty"
        private const val KEY_BALE_MENU_WARRANTY_STATUS = "bale_menu_warranty_status"
        private const val KEY_BALE_MENU_SEND_PRODUCT_PHOTO = "bale_menu_send_product_photo"
        private const val KEY_BALE_MENU_INVITE_FRIENDS = "bale_menu_invite_friends"
        private const val KEY_BALE_MENU_MY_SCORE = "bale_menu_my_score"
        private const val KEY_BALE_MENU_SUPPORT = "bale_menu_support"
        private const val KEY_BALE_MENU_HELP = "bale_menu_help"
        private const val KEY_REFERRAL_ENABLED = "referral_enabled"
        private const val KEY_REFERRAL_BASE_POINTS = "referral_base_points"
        private const val KEY_REFERRAL_DECAY_PERCENT = "referral_decay_percent"
        private const val KEY_REFERRAL_MAX_DEPTH = "referral_max_depth"
        private const val KEY_LOTTERY_PRESET_PHONE = "lottery_preset_phone"
        private const val KEY_CLOUD_SYNC_URL = "cloud_sync_url"
        private const val KEY_CLOUD_SYNC_TOKEN = "cloud_sync_token"
        private const val KEY_CLOUD_SYNC_LAST_PULL_MS = "cloud_sync_last_pull_ms"
        private const val KEY_CLOUD_SYNC_LAST_PUSH_MS = "cloud_sync_last_push_ms"
        private const val KEY_SMS_ENGINE_MODE = "sms_engine_mode"
        private const val KEY_MELIPAYAMAK_USERNAME = "melipayamak_username"
        private const val KEY_MELIPAYAMAK_PASSWORD = "melipayamak_password"
        private const val KEY_MELIPAYAMAK_SENDER_LINE = "melipayamak_sender_line"
        private const val KEY_MELIPAYAMAK_INBOUND_SHORT_CODES = "melipayamak_inbound_short_codes"

        @Volatile
        private var instance: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
