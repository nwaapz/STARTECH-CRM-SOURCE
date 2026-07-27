package com.example.smsgateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Editable WhatsApp Web automation config. All selectors are stored as plain strings so the
 * operator can update them from the dashboard whenever WhatsApp Web changes its DOM, without
 * needing to redeploy the PC engine.
 */
@Serializable
data class WhatsAppConfig(
    /** CSS selector for the chat/contact search box in WhatsApp Web. */
    val searchBoxSelector: String = "div[contenteditable='true'][data-tab='3']",
    /** CSS selector for a search result row in the chat list pane. */
    val searchResultSelector: String = "#pane-side div[role='listitem']",
    /** CSS selector for the message compose box. */
    val messageInputSelector: String = "div[contenteditable='true'][data-tab='10']",
    /** CSS selector for the send button. */
    val sendButtonSelector: String = "button[aria-label='Send'], span[data-icon='send']",
    /** How to open a chat: "search" (type in search box) or "link" (navigate send?phone=). */
    val openMode: String = OPEN_MODE_SEARCH,
    /** When true, search by phone number first and fall back to the saved contact name. */
    val searchByPhoneFirst: Boolean = true,
    /** Delay in seconds between each contact to reduce ban risk. */
    val sendDelaySeconds: Int = 8,
    /** Max time in ms to wait for a chat/element per contact before marking it failed. */
    val perContactTimeoutMs: Int = 30000,
) {
    fun normalized(): WhatsAppConfig = copy(
        searchBoxSelector = searchBoxSelector.trim(),
        searchResultSelector = searchResultSelector.trim(),
        messageInputSelector = messageInputSelector.trim(),
        sendButtonSelector = sendButtonSelector.trim(),
        openMode = if (openMode.trim().equals(OPEN_MODE_LINK, ignoreCase = true)) {
            OPEN_MODE_LINK
        } else {
            OPEN_MODE_SEARCH
        },
        sendDelaySeconds = sendDelaySeconds.coerceIn(1, 3600),
        perContactTimeoutMs = perContactTimeoutMs.coerceIn(5000, 300000),
    )

    fun toJsonString(): String = json.encodeToString(serializer(), normalized())

    companion object {
        const val OPEN_MODE_SEARCH = "search"
        const val OPEN_MODE_LINK = "link"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJsonString(raw: String?): WhatsAppConfig {
            if (raw.isNullOrBlank()) return WhatsAppConfig()
            return runCatching { json.decodeFromString(serializer(), raw).normalized() }
                .getOrDefault(WhatsAppConfig())
        }
    }
}
