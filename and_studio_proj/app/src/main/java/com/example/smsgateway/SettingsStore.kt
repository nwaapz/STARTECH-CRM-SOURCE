package com.example.smsgateway

import android.content.Context
import android.telephony.SubscriptionManager

enum class VerificationMode {
    REMOTE,
    INTERNAL,
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

    companion object {
        const val GATEWAY_URL = "https://startechgroup.ir/gateway/Gateway.php"
        const val DEFAULT_LAN_PORT = 8765
        private const val PREFS_NAME = "gateway_settings"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_ENABLED_SUBSCRIPTIONS = "enabled_subscriptions"
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
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_BOT_ACTIVE = "telegram_bot_active"
        private const val KEY_TELEGRAM_BOT_ID = "telegram_bot_id"
        private const val KEY_TELEGRAM_BOT_TITLE = "telegram_bot_title"
        private const val KEY_TELEGRAM_BOT_USERNAME = "telegram_bot_username"
        private const val KEY_TELEGRAM_NEXT_OFFSET_ID = "telegram_next_offset_id"
        private const val KEY_TELEGRAM_SERIAL_ENABLED = "telegram_serial_enabled"

        @Volatile
        private var instance: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
