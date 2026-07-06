package com.example.smsgateway

import com.example.smsgateway.lan.SmsMessageDefinitionResponse
import com.example.smsgateway.lan.SmsMessagePlaceholderHintResponse
import com.example.smsgateway.lan.SmsMessagesSettingsResponse
import org.json.JSONObject

class SmsMessageTemplates(
    private val settingsStore: SettingsStore,
) {
    private val overrides: Map<SmsMessageId, String>
        get() = settingsStore.getSmsMessageOverrides()

    fun templateFor(id: SmsMessageId): String {
        return overrides[id] ?: SmsMessageCatalog.definitionFor(id).defaultTemplate
    }

    fun resolve(id: SmsMessageId, values: Map<String, String> = emptyMap()): String {
        var text = templateFor(id)
        values.forEach { (token, value) ->
            text = text.replace(token, value)
        }
        return text
    }

    fun resolvePreview(template: String, definition: SmsMessageDefinition): String {
        var text = template
        definition.sampleValues.forEach { (token, value) ->
            text = text.replace(token, value)
        }
        return text
    }

    fun allDefinitionsWithTemplates(): List<SmsMessageDefinitionWithTemplate> {
        return SmsMessageCatalog.definitions.map { def ->
            SmsMessageDefinitionWithTemplate(
                definition = def,
                template = templateFor(def.id),
            )
        }
    }

    fun setOverrides(templates: Map<SmsMessageId, String>) {
        templates.forEach { (id, template) ->
            validateTemplate(id, template)
        }
        settingsStore.setSmsMessageOverrides(templates)
    }

    fun resetToDefaults() {
        settingsStore.setSmsMessageOverrides(emptyMap())
    }

    fun resetMessage(id: SmsMessageId) {
        val updated = overrides.toMutableMap()
        updated.remove(id)
        settingsStore.setSmsMessageOverrides(updated)
    }

    fun validateTemplate(id: SmsMessageId, template: String): String? {
        val trimmed = template.trim()
        if (trimmed.isEmpty()) {
            return "متن پیام «${SmsMessageCatalog.definitionFor(id).titleFa}» نمی‌تواند خالی باشد."
        }

        val def = SmsMessageCatalog.definitionFor(id)
        def.placeholders.forEach { hint ->
            if (!trimmed.contains(hint.token)) {
                return "متن پیام «${def.titleFa}» باید شامل ${hint.token} باشد."
            }
        }

        val declaredTokens = def.placeholders.map { it.token }.toSet()
        PLACEHOLDER_REGEX.findAll(trimmed).forEach { match ->
            val token = match.value
            if (token !in declaredTokens) {
                return "متن پیام «${def.titleFa}» شامل ${token} غیرمجاز است."
            }
        }

        return null
    }

    fun validateAll(templates: Map<SmsMessageId, String>): String? {
        SmsMessageId.entries.forEach { id ->
            val template = templates[id] ?: templateFor(id)
            validateTemplate(id, template)?.let { return it }
        }
        return null
    }

    fun toApiResponse(): SmsMessagesSettingsResponse {
        return SmsMessagesSettingsResponse(
            messages = allDefinitionsWithTemplates().map { it.toResponse() },
        )
    }

    fun applyFromApi(messages: List<SmsMessageDefinitionResponse>) {
        val updates = mutableMapOf<SmsMessageId, String>()
        messages.forEach { msg ->
            val id = SmsMessageId.fromKey(msg.id)
                ?: throw IllegalArgumentException("Unknown message id: ${msg.id}")
            validateTemplate(id, msg.template)?.let { throw IllegalArgumentException(it) }
            val default = SmsMessageCatalog.definitionFor(id).defaultTemplate
            if (msg.template.trim() != default) {
                updates[id] = msg.template.trim()
            }
        }
        settingsStore.setSmsMessageOverrides(updates)
    }

    data class SmsMessageDefinitionWithTemplate(
        val definition: SmsMessageDefinition,
        val template: String,
    ) {
        fun toResponse(): SmsMessageDefinitionResponse {
            return SmsMessageDefinitionResponse(
                id = definition.id.key,
                categoryFa = definition.categoryFa,
                titleFa = definition.titleFa,
                descriptionFa = definition.descriptionFa,
                placeholders = definition.placeholders.map {
                    SmsMessagePlaceholderHintResponse(token = it.token, labelFa = it.labelFa)
                },
                template = template,
            )
        }
    }

    companion object {
        private val PLACEHOLDER_REGEX = Regex("@\\d+")

        @Volatile
        private var instance: SmsMessageTemplates? = null

        fun getInstance(settingsStore: SettingsStore): SmsMessageTemplates {
            return instance ?: synchronized(this) {
                instance ?: SmsMessageTemplates(settingsStore).also { instance = it }
            }
        }

        fun overridesFromJson(raw: String?): Map<SmsMessageId, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching {
                val json = JSONObject(raw)
                buildMap {
                    json.keys().forEach { key ->
                        val id = SmsMessageId.fromKey(key) ?: return@forEach
                        val value = json.optString(key, "").trim()
                        if (value.isNotEmpty()) {
                            put(id, value)
                        }
                    }
                }
            }.getOrDefault(emptyMap())
        }

        fun overridesToJson(overrides: Map<SmsMessageId, String>): String {
            val json = JSONObject()
            overrides.forEach { (id, template) ->
                json.put(id.key, template)
            }
            return json.toString()
        }
    }
}
