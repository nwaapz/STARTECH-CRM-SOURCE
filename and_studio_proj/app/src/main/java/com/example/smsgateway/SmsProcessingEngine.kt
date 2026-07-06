package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SmsSessionEntity
import android.telephony.SubscriptionManager
import kotlinx.coroutines.runBlocking

class SmsProcessingEngine(
    private val appContext: android.content.Context,
    private val database: GatewayDatabase,
    private val settingsStore: SettingsStore,
) {
    private val sessionStore = SmsSessionStore.getInstance(database)
    private val syncWriter = SyncEventWriter.getInstance(database)
    private val errorWriter = SerialRegistrationErrorWriter.getInstance(database)
    private val templates = SmsMessageTemplates.getInstance(settingsStore)

    fun process(
        sender: String,
        message: String,
        subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ): GatewayApi.GatewayResponse {
        val config = settingsStore.getSmsProcessingConfig()
        val normalizedPhone = PhoneUtils.formatForDisplay(sender.trim()) ?: sender.trim()
        val body = message.trim()
        if (body.isEmpty()) {
            return response(replyInvalid())
        }

        return runBlocking {
            when (config.mode) {
                SmsProcessingMode.SERIAL_ONLY -> processSerialOnly(normalizedPhone, body, subscriptionId)
                SmsProcessingMode.COMBINED_PATTERN -> processCombined(normalizedPhone, body, config, subscriptionId)
                SmsProcessingMode.STEP_BY_STEP -> processStepByStep(normalizedPhone, body, config, subscriptionId)
            }
        }
    }

    private suspend fun processSerialOnly(
        phone: String,
        serial: String,
        subscriptionId: Int,
    ): GatewayApi.GatewayResponse {
        return registerIfValid(
            phone = phone,
            serial = serial,
            city = null,
            km = null,
            useFields = false,
            subscriptionId = subscriptionId,
        )
    }

    private suspend fun processCombined(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
    ): GatewayApi.GatewayResponse {
        tryCompleteIncompleteRegistration(phone, body, config)?.let { return it }

        tryRegisterPartialSerialOnly(phone, body, config, subscriptionId)?.let { return it }

        val result = SmsPatternParser.parseCombinedFlexible(
            message = body,
            separator = config.separator,
            requiredFields = config.combinedPattern,
            isValidSerial = { serial -> isUnusedSerialInDb(serial) },
        )
        val parsed = result.fields
        if (parsed != null) {
            return registerIfValid(
                phone = phone,
                serial = parsed.serial,
                city = parsed.city,
                km = parsed.km,
                useFields = true,
                subscriptionId = subscriptionId,
            )
        }

        if (result.error == SmsPatternParser.CombinedParseError.SERIAL_NOT_FOUND) {
            return rejectSerial(
                phone = phone,
                serial = guessSerialFromMessage(body),
                reason = SerialRegistrationErrorReason.NOT_FOUND,
            )
        }

        return response(combinedModeErrorReply(result.error))
    }

    private suspend fun processStepByStep(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
    ): GatewayApi.GatewayResponse {
        if (sessionStore.isSessionExpired(phone, config.sessionTimeoutMinutes)) {
            sessionStore.clearSession(phone)
            if (!isUnusedSerial(body)) {
                return response(templates.resolve(SmsMessageId.SESSION_EXPIRED))
            }
        }

        val session = sessionStore.findValidSession(phone, config.sessionTimeoutMinutes)

        if (session != null) {
            if (isUnusedSerial(body)) {
                sessionStore.clearSession(phone)
                return startStepSession(phone, body, config, subscriptionId)
            }
            return continueStepSession(phone, body, config, session)
        }

        tryCompleteIncompleteRegistration(phone, body, config)?.let { return it }

        return startStepSession(phone, body, config, subscriptionId)
    }

    private suspend fun startStepSession(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
    ): GatewayApi.GatewayResponse {
        val serial = SerialNormalizer.normalizeSerial(body)
        if (serial.isEmpty()) return response(replyInvalid())

        val dao = database.serialDao()
        val row = dao.findBySerial(serial)
            ?: return rejectSerial(phone, serial, SerialRegistrationErrorReason.NOT_FOUND)

        when {
            row.phone.isNullOrBlank() -> {
                if (!registerPartialSerial(phone, serial, subscriptionId)) {
                    return rejectSerial(phone, serial, SerialRegistrationErrorReason.ALREADY_USED)
                }
            }
            row.phone.trim() == phone.trim() -> {
                if (!row.needsCompletion()) {
                    onFullRegistrationSuccess(phone, row.serial)
                    return response(replyValid())
                }
            }
            else -> return rejectSerial(phone, serial, SerialRegistrationErrorReason.ALREADY_USED)
        }

        val remaining = config.stepFieldsAfterSerial()
        if (remaining.isEmpty()) {
            onFullRegistrationSuccess(phone, row.serial)
            return response(replyValid())
        }

        val now = System.currentTimeMillis()
        val nextField = when (config.stepFlowType) {
            StepFlowType.SEQUENTIAL -> remaining.first().name
            StepFlowType.COMBINED_SECOND -> SmsProcessingConfig.SESSION_COMBINED
        }

        sessionStore.saveSession(
            SmsSessionEntity(
                phone = phone,
                serial = row.serial,
                nextField = nextField,
                collectedCity = null,
                collectedKm = null,
                updatedAt = now,
            ),
        )

        return response(nextStepPrompt(config, nextField))
    }

    private suspend fun continueStepSession(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        session: SmsSessionEntity,
    ): GatewayApi.GatewayResponse {
        val now = System.currentTimeMillis()

        if (session.nextField == SmsProcessingConfig.SESSION_COMBINED) {
            val parseResult = SmsPatternParser.parseCityKmAuto(body)
            val parsed = parseResult.fields
                ?: return response(combinedSecondStepErrorReply(parseResult.error))

            sessionStore.clearSession(phone)
            return completeRegistrationFields(
                phone = phone,
                serial = session.serial,
                city = parsed.city,
                km = parsed.km,
            )
        }

        val expectedField = runCatching {
            SmsField.valueOf(session.nextField ?: return response(replyInvalid()))
        }.getOrElse { return response(replyInvalid()) }

        val parsed = SmsPatternParser.parseSingleField(body, expectedField)
            ?: return when (expectedField) {
                SmsField.KM -> response(templates.resolve(SmsMessageId.KM_INVALID))
                else -> response(
                    templates.resolve(
                        SmsMessageId.STEP_PROMPT_FIELD,
                        mapOf("@1" to SmsProcessingConfig.fieldPromptLabel(expectedField)),
                    ),
                )
            }

        val collectedCity = parsed.city ?: session.collectedCity
        val collectedKm = parsed.km ?: session.collectedKm
        val remaining = config.stepFieldsAfterSerial()
        val currentIndex = remaining.indexOf(expectedField)
        if (currentIndex < 0) {
            sessionStore.clearSession(phone)
            return response(replyInvalid())
        }

        val nextRemaining = remaining.drop(currentIndex + 1)
        if (nextRemaining.isEmpty()) {
            sessionStore.clearSession(phone)
            return completeRegistrationFields(
                phone = phone,
                serial = session.serial,
                city = collectedCity,
                km = collectedKm,
            )
        }

        sessionStore.saveSession(
            session.copy(
                nextField = nextRemaining.first().name,
                collectedCity = collectedCity,
                collectedKm = collectedKm,
                updatedAt = now,
            ),
        )

        return response(nextStepPrompt(config, nextRemaining.first().name))
    }

    private suspend fun tryCompleteIncompleteRegistration(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
    ): GatewayApi.GatewayResponse? {
        if (!requiresCityKm(config)) return null

        val dao = database.serialDao()
        val incomplete = dao.findIncompleteByPhone(phone) ?: return null

        if (body.trim().equals(incomplete.serial, ignoreCase = true)) {
            return response(partialMissingFieldsReply(incomplete))
        }

        val missingFields = missingFieldsFor(incomplete)
        if (missingFields.isEmpty()) return null

        val requiredFields = buildList {
            add(SmsField.SERIAL)
            addAll(missingFields)
        }

        val parsedFromCombined = SmsPatternParser.parseCombinedFlexible(
            message = body,
            separator = config.separator,
            requiredFields = requiredFields,
            isValidSerial = { serial ->
                serial.trim().equals(incomplete.serial, ignoreCase = true)
            },
        )
        parsedFromCombined.fields?.let { parsed ->
            if (!parsed.serial.equals(incomplete.serial, ignoreCase = true)) {
                return null
            }
            return completeRegistrationFields(
                phone = phone,
                serial = incomplete.serial,
                city = parsed.city ?: incomplete.city,
                km = parsed.km ?: incomplete.km,
            )
        }

        var city = incomplete.city?.takeIf { it.isNotBlank() }
        var km = incomplete.km

        if (missingFields.contains(SmsField.CITY) && city.isNullOrBlank()) {
            city = SmsPatternParser.parseSingleField(body, SmsField.CITY)?.city
        }
        if (missingFields.contains(SmsField.KM) && km == null) {
            km = SmsPatternParser.parseSingleField(body, SmsField.KM)?.km
        }

        if (missingFields.size == 2 && city.isNullOrBlank() && km == null) {
            val auto = SmsPatternParser.parseCityKmAuto(body)
            auto.fields?.let { autoFields ->
                return completeRegistrationFields(
                    phone = phone,
                    serial = incomplete.serial,
                    city = autoFields.city,
                    km = autoFields.km,
                )
            }
        }

        if (missingFields.contains(SmsField.CITY) && city.isNullOrBlank()) {
            return null
        }
        if (missingFields.contains(SmsField.KM) && km == null) {
            return null
        }

        return completeRegistrationFields(
            phone = phone,
            serial = incomplete.serial,
            city = city,
            km = km,
        )
    }

    private suspend fun tryRegisterPartialSerialOnly(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
    ): GatewayApi.GatewayResponse? {
        if (!requiresCityKm(config)) return null

        val serial = SerialNormalizer.normalizeSerial(body)
        if (serial.isEmpty()) return null

        val dao = database.serialDao()
        val row = dao.findBySerial(serial) ?: return null

        when {
            row.phone.isNullOrBlank() -> {
                if (!registerPartialSerial(phone, serial, subscriptionId)) return null
            }
            row.phone.trim() == phone.trim() && row.hasMissingRegistrationFields() -> {
                database.serialDao().markRegistrationIncomplete(
                    serial,
                    phone,
                    storedSubscriptionId(subscriptionId),
                )
            }
            else -> return null
        }

        val updated = dao.findBySerial(serial) ?: return null
        return response(partialMissingFieldsReply(updated))
    }

    private suspend fun registerPartialSerial(
        phone: String,
        serial: String,
        subscriptionId: Int,
    ): Boolean {
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        val dao = database.serialDao()
        val row = dao.findBySerial(normalizedSerial) ?: return false
        if (!row.phone.isNullOrBlank()) {
            return row.phone.trim() == phone.trim()
        }

        val jalaliDateTime = JalaliHelper.nowJalaliDateTime()
        val affected = dao.registerPartialSerial(
            phone,
            jalaliDateTime,
            normalizedSerial,
            storedSubscriptionId(subscriptionId),
        )
        if (affected == 1) {
            syncWriter.recordRegistered(
                serial = row.serial,
                phone = phone,
                date = jalaliDateTime,
                city = null,
                km = null,
            )
            return true
        }
        return false
    }

    private suspend fun completeRegistrationFields(
        phone: String,
        serial: String,
        city: String?,
        km: Int?,
    ): GatewayApi.GatewayResponse {
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        val dao = database.serialDao()
        val row = dao.findBySerial(normalizedSerial) ?: return response(replyInvalid())
        if (row.phone?.trim() != phone.trim()) {
            return response(replyInvalid())
        }

        val affected = dao.updateRegistrationFields(phone, normalizedSerial, city, km)
        if (affected != 1) {
            return response(replyInvalid())
        }

        sessionStore.clearSession(phone)
        syncWriter.recordRegistrationUpdated(
            serial = row.serial,
            phone = phone,
            date = row.date ?: JalaliHelper.nowJalaliDateTime(),
            city = city,
            km = km,
        )
        onFullRegistrationSuccess(phone, normalizedSerial)
        return response(replyValid())
    }

    private suspend fun registerIfValid(
        phone: String,
        serial: String,
        city: String?,
        km: Int?,
        useFields: Boolean,
        subscriptionId: Int,
    ): GatewayApi.GatewayResponse {
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        val dao = database.serialDao()
        val row = dao.findBySerial(normalizedSerial)
        if (row == null) {
            return rejectSerial(phone, normalizedSerial, SerialRegistrationErrorReason.NOT_FOUND)
        }
        if (isSerialUsed(row.phone)) {
            return rejectSerial(phone, normalizedSerial, SerialRegistrationErrorReason.ALREADY_USED)
        }

        val jalaliDateTime = JalaliHelper.nowJalaliDateTime()
        val affected = if (useFields) {
            dao.registerSerialWithFields(
                phone,
                jalaliDateTime,
                normalizedSerial,
                city,
                km,
                storedSubscriptionId(subscriptionId),
            )
        } else {
            dao.registerSerial(phone, jalaliDateTime, normalizedSerial, storedSubscriptionId(subscriptionId))
        }

        if (affected != 1) {
            return rejectSerial(phone, normalizedSerial, SerialRegistrationErrorReason.ALREADY_USED)
        }

        syncWriter.recordRegistered(
            serial = row.serial,
            phone = phone,
            date = jalaliDateTime,
            city = city,
            km = km,
        )
        if (useFields && (city.isNullOrBlank() || km == null)) {
            val updated = dao.findBySerial(normalizedSerial) ?: return response(replyInvalid())
            return response(partialMissingFieldsReply(updated))
        }
        onFullRegistrationSuccess(phone, normalizedSerial)
        return response(replyValid())
    }

    private suspend fun onFullRegistrationSuccess(phone: String, serial: String) {
        val row = database.serialDao().findBySerial(serial) ?: return
        DeviceContactHelper.ensureContact(appContext, phone, row.id)
    }

    private suspend fun isUnusedSerial(body: String): Boolean {
        return isUnusedSerialInDb(body)
    }

    private suspend fun isUnusedSerialInDb(serial: String): Boolean {
        val normalized = SerialNormalizer.normalizeSerial(serial)
        if (normalized.isEmpty()) return false
        val row = database.serialDao().findBySerial(normalized) ?: return false
        return !isSerialUsed(row.phone)
    }

    private fun isSerialUsed(phone: String?): Boolean {
        return phone != null && phone.trim().isNotEmpty()
    }

    private fun requiresCityKm(config: SmsProcessingConfig): Boolean {
        return config.mode == SmsProcessingMode.COMBINED_PATTERN ||
            config.mode == SmsProcessingMode.STEP_BY_STEP
    }

    private fun storedSubscriptionId(subscriptionId: Int): Int? {
        return if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            null
        } else {
            subscriptionId
        }
    }

    private fun missingFieldsFor(row: SerialEntity): List<SmsField> {
        val fields = mutableListOf<SmsField>()
        if (row.city.isNullOrBlank()) fields.add(SmsField.CITY)
        if (row.km == null) fields.add(SmsField.KM)
        return fields
    }

    private fun partialMissingFieldsReply(row: SerialEntity): String {
        val example = when {
            row.city.isNullOrBlank() && row.km == null ->
                "${row.serial} ${SmsMessageCatalog.EXAMPLE_CITY} ${SmsMessageCatalog.EXAMPLE_KM}"
            row.city.isNullOrBlank() -> "${row.serial} ${SmsMessageCatalog.EXAMPLE_CITY}"
            row.km == null -> "${row.serial} ${SmsMessageCatalog.EXAMPLE_KM}"
            else -> row.serial
        }
        return when {
            row.city.isNullOrBlank() && row.km == null ->
                templates.resolve(SmsMessageId.PARTIAL_MISSING_CITY_KM, mapOf("@1" to example))
            row.city.isNullOrBlank() ->
                templates.resolve(SmsMessageId.PARTIAL_MISSING_CITY, mapOf("@1" to example))
            row.km == null ->
                templates.resolve(SmsMessageId.KM_INVALID_WITH_EXAMPLE, mapOf("@1" to example))
            else -> replyValid()
        }
    }

    private fun nextStepPrompt(config: SmsProcessingConfig, nextField: String): String {
        if (nextField == SmsProcessingConfig.SESSION_COMBINED) {
            return combinedSecondStepPrompt()
        }
        val field = runCatching { SmsField.valueOf(nextField) }.getOrNull()
            ?: return replyInvalid()
        return templates.resolve(
            SmsMessageId.STEP_PROMPT_FIELD,
            mapOf("@1" to SmsProcessingConfig.fieldPromptLabel(field)),
        )
    }

    private fun combinedSecondStepPrompt(): String {
        return templates.resolve(
            SmsMessageId.STEP_PROMPT_CITY_KM,
            mapOf(
                "@1" to SmsMessageCatalog.EXAMPLE_CITY,
                "@2" to SmsMessageCatalog.EXAMPLE_KM,
            ),
        )
    }

    private fun combinedSecondStepErrorReply(error: SmsPatternParser.CityKmAutoParseError?): String {
        return when (error) {
            SmsPatternParser.CityKmAutoParseError.MISSING_CITY ->
                templates.resolve(SmsMessageId.COMBINED_MISSING_CITY)
            SmsPatternParser.CityKmAutoParseError.MISSING_KM,
            SmsPatternParser.CityKmAutoParseError.INVALID_KM,
            ->
                templates.resolve(SmsMessageId.COMBINED_MISSING_KM)
            else -> combinedSecondStepPrompt()
        }
    }

    private fun combinedModeErrorReply(error: SmsPatternParser.CombinedParseError?): String {
        return when (error) {
            SmsPatternParser.CombinedParseError.MISSING_CITY ->
                templates.resolve(SmsMessageId.COMBINED_MISSING_CITY)
            SmsPatternParser.CombinedParseError.MISSING_KM,
            SmsPatternParser.CombinedParseError.INVALID_KM,
            ->
                templates.resolve(SmsMessageId.COMBINED_MISSING_KM)
            SmsPatternParser.CombinedParseError.SERIAL_NOT_FOUND ->
                replyInvalid()
            else ->
                templates.resolve(
                    SmsMessageId.COMBINED_FORMAT_ERROR,
                    mapOf(
                        "@1" to SmsMessageCatalog.EXAMPLE_SERIAL,
                        "@2" to SmsMessageCatalog.EXAMPLE_CITY,
                        "@3" to SmsMessageCatalog.EXAMPLE_KM,
                    ),
                )
        }
    }

    private suspend fun rejectSerial(
        phone: String,
        serial: String,
        reason: String,
    ): GatewayApi.GatewayResponse {
        errorWriter.record(phone, serial, reason)
        return response(replyInvalid())
    }

    private fun guessSerialFromMessage(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val firstToken = trimmed.split(Regex("\\s+")).firstOrNull() ?: trimmed
        return SerialNormalizer.normalizeSerial(firstToken)
    }

    private fun replyValid(): String = templates.resolve(SmsMessageId.REPLY_VALID)

    private fun replyInvalid(): String = templates.resolve(SmsMessageId.REPLY_INVALID)

    private fun response(reply: String): GatewayApi.GatewayResponse {
        return GatewayApi.GatewayResponse(REPLY_STATUS, reply)
    }

    companion object {
        private const val REPLY_STATUS = "success"

        @Volatile
        private var instance: SmsProcessingEngine? = null

        fun getInstance(context: android.content.Context): SmsProcessingEngine {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: SmsProcessingEngine(
                    appContext,
                    GatewayDatabase.getInstance(appContext),
                    SettingsStore.getInstance(appContext),
                ).also { instance = it }
            }
        }
    }
}
