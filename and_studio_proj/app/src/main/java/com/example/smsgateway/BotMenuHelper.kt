package com.example.smsgateway

enum class BotMenuChannel {
    RUBIKA,
    TELEGRAM,
    BALE,
}

enum class BotMenuItem(
    val id: String,
    val label: String,
) {
    REGISTER_WARRANTY("register_warranty", "ثبت گارانتی"),
    WARRANTY_STATUS("warranty_status", "وضعیت گارانتی"),
    SEND_PRODUCT_PHOTO("send_product_photo", "ارسال عکس محصول"),
    INVITE_FRIENDS("invite_friends", "دعوت دوستان"),
    MY_SCORE("my_score", "امتیاز من"),
    SUPPORT("support", "پشتیبانی"),
    HELP("help", "راهنما"),
    ;

    companion object {
        fun fromLabel(text: String): BotMenuItem? {
            val trimmed = text.trim()
            return entries.firstOrNull { it.label == trimmed }
        }
    }
}

data class BotMenuSettings(
    val registerWarranty: Boolean = true,
    val warrantyStatus: Boolean = true,
    val sendProductPhoto: Boolean = true,
    val inviteFriends: Boolean = true,
    val myScore: Boolean = true,
    val support: Boolean = true,
    val help: Boolean = true,
) {
    fun isEnabled(item: BotMenuItem): Boolean = when (item) {
        BotMenuItem.REGISTER_WARRANTY -> registerWarranty
        BotMenuItem.WARRANTY_STATUS -> warrantyStatus
        BotMenuItem.SEND_PRODUCT_PHOTO -> sendProductPhoto
        BotMenuItem.INVITE_FRIENDS -> inviteFriends
        BotMenuItem.MY_SCORE -> myScore
        BotMenuItem.SUPPORT -> support
        BotMenuItem.HELP -> help
    }

    fun enabledItems(): List<BotMenuItem> = BotMenuItem.entries.filter { isEnabled(it) }

    fun enabledLabels(): List<String> = enabledItems().map { it.label }
}

object BotMenuHelper {
    fun settingsFor(settingsStore: SettingsStore, channel: BotMenuChannel): BotMenuSettings {
        return when (channel) {
            BotMenuChannel.RUBIKA -> settingsStore.getRubikaMenuSettings()
            BotMenuChannel.TELEGRAM -> settingsStore.getTelegramMenuSettings()
            BotMenuChannel.BALE -> settingsStore.getBaleMenuSettings()
        }
    }

    fun enabledLabels(settingsStore: SettingsStore, channel: BotMenuChannel): List<String> {
        return settingsFor(settingsStore, channel).enabledLabels()
    }

    /** Layout rows as 2+2+1 when fully enabled. */
    fun keyboardRows(labels: List<String>): List<List<String>> {
        if (labels.isEmpty()) return emptyList()
        val rows = mutableListOf<List<String>>()
        var index = 0
        while (index < labels.size) {
            val remaining = labels.size - index
            val take = when {
                remaining == 1 -> 1
                remaining == 3 && rows.isEmpty() -> 2
                else -> minOf(2, remaining)
            }
            rows.add(labels.subList(index, index + take))
            index += take
        }
        return rows
    }

    fun matchEnabledItem(
        text: String,
        settingsStore: SettingsStore,
        channel: BotMenuChannel,
    ): BotMenuItem? {
        val item = BotMenuItem.fromLabel(text) ?: return null
        return item.takeIf { settingsFor(settingsStore, channel).isEnabled(it) }
    }
}
