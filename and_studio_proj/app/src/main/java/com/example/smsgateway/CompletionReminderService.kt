package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.SerialEntity

object CompletionReminderService {
    fun buildMessage(row: SerialEntity, config: SmsProcessingConfig, templates: SmsMessageTemplates): String {
        val serial = row.serial
        val missingCity = row.city.isNullOrBlank()
        val missingKm = row.km == null

        return when (config.mode) {
            SmsProcessingMode.COMBINED_PATTERN -> buildCombinedModeMessage(serial, missingCity, missingKm, templates)
            SmsProcessingMode.STEP_BY_STEP -> buildStepModeMessage(serial, config, missingCity, missingKm, templates)
            SmsProcessingMode.SERIAL_ONLY -> buildCombinedModeMessage(serial, missingCity, missingKm, templates)
        }
    }

    fun sendReminder(
        context: Context,
        row: SerialEntity,
        config: SmsProcessingConfig,
        templates: SmsMessageTemplates,
    ) {
        val phone = row.phone?.trim().orEmpty()
        require(phone.isNotEmpty()) { "Serial has no phone number" }

        val message = buildMessage(row, config, templates)
        val subscriptionId = SmsOutbound.resolveSubscriptionId(context, row.registrationSubscriptionId)
        val simLabel = SimCardHelper.simLabelForSubscription(context, subscriptionId)

        SmsOutbound.send(context, subscriptionId, phone, message)

        CommunicationLog.getInstance(context).logOutgoing(
            phone = phone,
            body = message,
            status = CommunicationLog.STATUS_SENT,
            simLabel = simLabel,
        )
    }

    private fun buildCombinedModeMessage(
        serial: String,
        missingCity: Boolean,
        missingKm: Boolean,
        templates: SmsMessageTemplates,
    ): String {
        val example = when {
            missingCity && missingKm ->
                "$serial ${SmsMessageCatalog.EXAMPLE_CITY} ${SmsMessageCatalog.EXAMPLE_KM}"
            missingCity -> "$serial ${SmsMessageCatalog.EXAMPLE_CITY}"
            missingKm -> "$serial ${SmsMessageCatalog.EXAMPLE_KM}"
            else -> serial
        }
        val missingText = when {
            missingCity && missingKm -> "شهر و کیلومتر"
            missingCity -> "شهر"
            missingKm -> "کیلومتر"
            else -> "اطلاعات"
        }
        return templates.resolve(
            SmsMessageId.COMPLETION_REMINDER,
            mapOf("@1" to missingText, "@2" to example),
        )
    }

    private fun buildStepModeMessage(
        serial: String,
        config: SmsProcessingConfig,
        missingCity: Boolean,
        missingKm: Boolean,
        templates: SmsMessageTemplates,
    ): String {
        return when (config.stepFlowType) {
            StepFlowType.COMBINED_SECOND -> {
                templates.resolve(
                    SmsMessageId.COMPLETION_REMINDER_STEP_COMBINED,
                    mapOf(
                        "@1" to serial,
                        "@2" to SmsMessageCatalog.EXAMPLE_CITY,
                        "@3" to SmsMessageCatalog.EXAMPLE_KM,
                    ),
                )
            }
            StepFlowType.SEQUENTIAL -> {
                val steps = buildList {
                    add("سریال: $serial")
                    if (missingCity) add("شهر: ${SmsMessageCatalog.EXAMPLE_CITY}")
                    if (missingKm) add("کیلومتر: ${SmsMessageCatalog.EXAMPLE_KM}")
                }
                templates.resolve(
                    SmsMessageId.COMPLETION_REMINDER_STEP_SEQUENTIAL,
                    mapOf("@1" to steps.joinToString("، ")),
                )
            }
        }
    }
}
