package com.example.smsgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsMessageTemplatesTest {
    @Test
    fun overridesFromJson_parsesStoredTemplates() {
        val json = """{"reply_valid":"گارانتی شما فعال شد."}"""
        val overrides = SmsMessageTemplates.overridesFromJson(json)
        assertEquals("گارانتی شما فعال شد.", overrides[SmsMessageId.REPLY_VALID])
    }

    @Test
    fun overridesToJson_roundTrips() {
        val original = mapOf(SmsMessageId.REPLY_VALID to "گارانتی شما فعال شد.")
        val json = SmsMessageTemplates.overridesToJson(original)
        val parsed = SmsMessageTemplates.overridesFromJson(json)
        assertEquals(original, parsed)
    }

    @Test
    fun catalog_defaultTemplates_includeRequiredPlaceholders() {
        SmsMessageCatalog.definitions.forEach { definition ->
            definition.placeholders.forEach { hint ->
                assertTrue(
                    "Missing ${hint.token} in ${definition.id.key}",
                    definition.defaultTemplate.contains(hint.token),
                )
            }
        }
    }

    @Test
    fun placeholderReplacement_replacesInOrder() {
        val template = "ثبت ناقص. @1 — مثال: @2"
        var text = template
        mapOf("@1" to "شهر", "@2" to "M100 تبریز").forEach { (token, value) ->
            text = text.replace(token, value)
        }
        assertEquals("ثبت ناقص. شهر — مثال: M100 تبریز", text)
    }

    @Test
    fun validateLogic_rejectsMissingPlaceholder() {
        val definition = SmsMessageCatalog.definitionFor(SmsMessageId.STEP_PROMPT_FIELD)
        val template = "لطفاً شهر را ارسال کنید."
        val missing = definition.placeholders.any { hint -> !template.contains(hint.token) }
        assertTrue(missing)
    }

    @Test
    fun validateLogic_rejectsUnknownPlaceholder() {
        val definition = SmsMessageCatalog.definitionFor(SmsMessageId.REPLY_VALID)
        val template = "پیام @1"
        val declared = definition.placeholders.map { it.token }.toSet()
        val unknown = Regex("@\\d+").findAll(template).any { it.value !in declared }
        assertTrue(unknown)
    }

    @Test
    fun validateLogic_acceptsValidCompletionReminderTemplate() {
        val definition = SmsMessageCatalog.definitionFor(SmsMessageId.COMPLETION_REMINDER)
        val template = "ثبت ناقص. @1 — مثال: @2"
        val hasAll = definition.placeholders.all { hint -> template.contains(hint.token) }
        val declared = definition.placeholders.map { it.token }.toSet()
        val unknown = Regex("@\\d+").findAll(template).any { it.value !in declared }
        assertTrue(hasAll)
        assertNull(if (unknown) "unknown" else null)
    }
}
