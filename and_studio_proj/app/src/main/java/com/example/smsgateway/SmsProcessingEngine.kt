package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SmsSessionEntity
import com.example.smsgateway.lan.LanTraceLog
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
        registrationSource: String = RegistrationSource.SMS,
    ): GatewayApi.GatewayResponse {
        val config = settingsStore.getSmsProcessingConfig()
        val normalizedPhone = PhoneUtils.formatForDisplay(sender.trim()) ?: sender.trim()
        val body = message.trim()
        if (body.isEmpty()) {
            return response(replyInvalid())
        }
        val source = RegistrationSource.normalizeOrDefault(registrationSource)

        return runBlocking {
            val resolvedSubscriptionId = resolveOutboundSubscriptionId(
                phone = normalizedPhone,
                inboundSubscriptionId = subscriptionId,
                config = config,
            )
            val result = when (config.mode) {
                SmsProcessingMode.SERIAL_ONLY ->
                    processSerialOnly(normalizedPhone, body, resolvedSubscriptionId, source)
                SmsProcessingMode.COMBINED_PATTERN ->
                    processCombined(normalizedPhone, body, config, resolvedSubscriptionId, source)
                SmsProcessingMode.STEP_BY_STEP ->
                    processStepByStep(normalizedPhone, body, config, resolvedSubscriptionId, source)
            }
            if (result.replySubscriptionId != null) {
                result
            } else {
                result.copy(replySubscriptionId = resolvedSubscriptionId)
            }
        }
    }

    private suspend fun resolveOutboundSubscriptionId(
        phone: String,
        inboundSubscriptionId: Int,
        config: SmsProcessingConfig,
    ): Int {
        val sticky = findStickySubscriptionId(phone, config)
        if (sticky != null) {
            return sticky
        }
        val budget = SimQuotaRouter.estimateConversationBudget(appContext)
        return SimQuotaRouter.pickForBudget(appContext, inboundSubscriptionId, budget)
            ?: inboundSubscriptionId
    }

    private suspend fun findStickySubscriptionId(
        phone: String,
        config: SmsProcessingConfig,
    ): Int? {
        val session = sessionStore.findValidSession(phone, config.sessionTimeoutMinutes)
        session?.subscriptionId?.let { return it }

        val incomplete = database.serialDao().findIncompleteByPhone(phone) ?: return null
        val regSub = incomplete.registrationSubscriptionId
        return if (regSub != null && regSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            regSub
        } else {
            null
        }
    }

    private suspend fun processSerialOnly(
        phone: String,
        serial: String,
        subscriptionId: Int,
        registrationSource: String,
    ): GatewayApi.GatewayResponse {
        return registerIfValid(
            phone = phone,
            serial = serial,
            city = null,
            km = null,
            useFields = false,
            subscriptionId = subscriptionId,
            registrationSource = registrationSource,
        )
    }

    private suspend fun processCombined(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
        registrationSource: String,
    ): GatewayApi.GatewayResponse {
        tryCompleteIncompleteRegistration(phone, body, config)?.let { return it }

        tryRegisterPartialSerialOnly(phone, body, config, subscriptionId, registrationSource)?.let { return it }

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
                registrationSource = registrationSource,
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
        registrationSource: String,
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
                return startStepSession(phone, body, config, subscriptionId, registrationSource)
            }
            return continueStepSession(phone, body, config, session)
        }

        tryCompleteIncompleteRegistration(phone, body, config)?.let { return it }

        return startStepSession(phone, body, config, subscriptionId, registrationSource)
    }

    private suspend fun startStepSession(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
        registrationSource: String,
    ): GatewayApi.GatewayResponse {
        val serial = SerialNormalizer.normalizeSerial(body)
        if (serial.isEmpty()) return response(replyInvalid())

        val dao = database.serialDao()
        val row = dao.findBySerial(serial)
            ?: return rejectSerial(phone, serial, SerialRegistrationErrorReason.NOT_FOUND)

        val remaining = config.stepFieldsAfterSerial()

        when {
            row.phone.isNullOrBlank() -> {
                if (remaining.isEmpty()) {
                    return registerIfValid(
                        phone, serial, null, null, useFields = false, subscriptionId, registrationSource,
                    )
                }
                if (!registerPartialSerial(phone, serial, subscriptionId, registrationSource)) {
                    return rejectSerial(phone, serial, SerialRegistrationErrorReason.ALREADY_USED)
                }
            }
            row.phone.trim() == phone.trim() -> {
                if (!row.needsCompletion()) {
                    val firstReg = onFullRegistrationSuccess(phone, row.serial)
                    val needsPhoto = isImageVerificationRequired(row.serial)
                    return response(
                        replyValid(row.serial),
                        firstRegistration = firstReg,
                        registrationSucceeded = false,
                        imageVerificationRequired = needsPhoto,
                        registeredSerial = row.serial,
                    )
                }
            }
            else -> return rejectSerial(phone, serial, SerialRegistrationErrorReason.ALREADY_USED)
        }

        if (remaining.isEmpty()) {
            val firstReg = onFullRegistrationSuccess(phone, row.serial)
            val needsPhoto = isImageVerificationRequired(row.serial)
            return response(
                replyValid(row.serial),
                firstRegistration = firstReg,
                registrationSucceeded = true,
                imageVerificationRequired = needsPhoto,
                registeredSerial = row.serial,
            )
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
                subscriptionId = subscriptionId.takeIf {
                    it != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                },
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
        val incompleteRaw = dao.findIncompleteByPhone(phone) ?: return null
        val incomplete = applyKnownCityAndMaybeComplete(phone, incompleteRaw.serial) ?: incompleteRaw
        val category = categoryFor(incomplete)
        val known = knownCityFor(phone)
        if (!RegistrationFieldRules.needsCompletion(
                phone,
                incomplete.city,
                incomplete.km,
                category,
                known,
            )
        ) {
            // Flag cleared by known-city / seller rules; nothing left to collect.
            return null
        }

        if (body.trim().equals(incomplete.serial, ignoreCase = true)) {
            return response(partialMissingFieldsReply(incomplete, category, known))
        }

        val missingFields = RegistrationFieldRules.missingFields(
            incomplete.city,
            incomplete.km,
            category,
            known,
        ).toSmsFields()
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
        registrationSource: String,
    ): GatewayApi.GatewayResponse? {
        if (!requiresCityKm(config)) return null

        val serial = SerialNormalizer.normalizeSerial(body)
        if (serial.isEmpty()) return null

        val dao = database.serialDao()
        val row = dao.findBySerial(serial) ?: return null

        when {
            row.phone.isNullOrBlank() -> {
                if (!registerPartialSerial(phone, serial, subscriptionId, registrationSource)) return null
            }
            row.phone.trim() == phone.trim() -> {
                val category = categoryFor(row)
                val known = knownCityFor(phone)
                if (!RegistrationFieldRules.needsCompletion(phone, row.city, row.km, category, known) &&
                    row.registrationFieldsComplete
                ) {
                    return null
                }
                database.serialDao().markRegistrationIncomplete(
                    serial,
                    phone,
                    storedSubscriptionId(subscriptionId),
                )
            }
            else -> return null
        }

        val updated = applyKnownCityAndMaybeComplete(phone, serial) ?: return null
        val category = categoryFor(updated)
        val known = knownCityFor(phone)
        if (!RegistrationFieldRules.needsCompletion(phone, updated.city, updated.km, category, known)) {
            sessionStore.clearSession(phone)
            val firstReg = onFullRegistrationSuccess(phone, updated.serial)
            val needsPhoto = isImageVerificationRequired(updated.serial)
            return response(
                replyValid(updated.serial),
                firstRegistration = firstReg,
                registrationSucceeded = true,
                imageVerificationRequired = needsPhoto,
                registeredSerial = updated.serial,
            )
        }
        return response(partialMissingFieldsReply(updated, category, known))
    }

    private suspend fun registerPartialSerial(
        phone: String,
        serial: String,
        subscriptionId: Int,
        registrationSource: String,
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
            RegistrationSource.normalizeOrDefault(registrationSource),
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

        val category = categoryFor(row)
        val known = knownCityFor(phone)
        val effectiveCity = RegistrationFieldRules.effectiveCity(city, known)
        val stillMissing = RegistrationFieldRules.missingFields(effectiveCity, km, category, null)
        if (!stillMissing.isEmpty()) {
            // Persist whatever we have, keep incomplete, ask for remaining fields.
            dao.updateRegistrationFields(
                phone = phone,
                serial = normalizedSerial,
                city = effectiveCity,
                km = km,
                fieldsComplete = false,
            )
            val updated = dao.findBySerial(normalizedSerial) ?: return response(replyInvalid())
            return response(partialMissingFieldsReply(updated, category, known))
        }

        val affected = dao.updateRegistrationFields(
            phone = phone,
            serial = normalizedSerial,
            city = effectiveCity,
            km = km,
            fieldsComplete = true,
        )
        if (affected != 1) {
            return response(replyInvalid())
        }

        sessionStore.clearSession(phone)
        syncWriter.recordRegistrationUpdated(
            serial = row.serial,
            phone = phone,
            date = row.date ?: JalaliHelper.nowJalaliDateTime(),
            city = effectiveCity,
            km = km,
        )
        val firstReg = onFullRegistrationSuccess(phone, normalizedSerial)
        val needsPhoto = isImageVerificationRequired(normalizedSerial)
        return response(
            replyValid(normalizedSerial),
            firstRegistration = firstReg,
            registrationSucceeded = true,
            imageVerificationRequired = needsPhoto,
            registeredSerial = normalizedSerial,
        )
    }

    private suspend fun registerIfValid(
        phone: String,
        serial: String,
        city: String?,
        km: Int?,
        useFields: Boolean,
        subscriptionId: Int,
        registrationSource: String,
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
        val source = RegistrationSource.normalizeOrDefault(registrationSource)
        val category = categoryFor(row)
        val known = knownCityFor(phone)
        val effectiveCity = if (useFields) {
            RegistrationFieldRules.effectiveCity(city, known)
        } else {
            city
        }
        val requiresKmFlag = if (RegistrationFieldRules.requiresKm(category)) 1 else 0
        val affected = if (useFields) {
            dao.registerSerialWithFields(
                phone,
                jalaliDateTime,
                normalizedSerial,
                effectiveCity,
                km,
                storedSubscriptionId(subscriptionId),
                source,
                requiresKmFlag,
            )
        } else {
            dao.registerSerial(
                phone,
                jalaliDateTime,
                normalizedSerial,
                storedSubscriptionId(subscriptionId),
                source,
            )
        }

        if (affected != 1) {
            return rejectSerial(phone, normalizedSerial, SerialRegistrationErrorReason.ALREADY_USED)
        }

        syncWriter.recordRegistered(
            serial = row.serial,
            phone = phone,
            date = jalaliDateTime,
            city = effectiveCity,
            km = km,
        )
        if (useFields) {
            val updated = applyKnownCityAndMaybeComplete(phone, normalizedSerial)
                ?: return response(replyInvalid())
            val updatedCategory = categoryFor(updated)
            val updatedKnown = knownCityFor(phone)
            if (RegistrationFieldRules.needsCompletion(
                    phone,
                    updated.city,
                    updated.km,
                    updatedCategory,
                    updatedKnown,
                )
            ) {
                return response(partialMissingFieldsReply(updated, updatedCategory, updatedKnown))
            }
            val firstReg = onFullRegistrationSuccess(phone, normalizedSerial)
            val needsPhoto = isImageVerificationRequired(normalizedSerial)
            return response(
                replyValid(normalizedSerial),
                firstRegistration = firstReg,
                registrationSucceeded = true,
                imageVerificationRequired = needsPhoto,
                registeredSerial = normalizedSerial,
            )
        }
        val firstReg = onFullRegistrationSuccess(phone, normalizedSerial)
        val needsPhoto = isImageVerificationRequired(normalizedSerial)
        return response(
            replyValid(normalizedSerial),
            firstRegistration = firstReg,
            registrationSucceeded = true,
            imageVerificationRequired = needsPhoto,
            registeredSerial = normalizedSerial,
        )
    }

    fun isPhoneRegisteredWithMCode(phone: String): Boolean {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: return false
        return runBlocking {
            database.serialDao().countRegisteredMCode(normalized) > 0
        }
    }

    private suspend fun onFullRegistrationSuccess(phone: String, serial: String): Boolean {
        val row = database.serialDao().findBySerial(serial) ?: return false
        DeviceContactHelper.ensureContact(appContext, phone, row.id)
        awardClubScoreIfEligible(phone, row)
        awardReferralForPurchase(phone, row.id)
        return database.serialDao().countCompletedRegistrationsByPhone(phone) == 1
    }

    /**
     * Awards the serial group's club score to the buyer when image verification is not required.
     * Photo-gated groups award score later in [SerialPhotoVerificationService.approve].
     */
    private suspend fun awardClubScoreIfEligible(phone: String, row: SerialEntity) {
        val groupId = row.groupId ?: return
        val group = database.serialGroupDao().findById(groupId) ?: return
        if (group.imageVerificationRequired) return
        val score = group.score.coerceAtLeast(0)
        if (score < 1) return
        try {
            ReferralService.getInstance(appContext).awardOwnPoints(
                phone = phone,
                points = score,
                sourceType = com.example.smsgateway.data.CustomerScoreSourceType.SERIAL,
                sourceId = row.id,
            )
        } catch (error: Exception) {
            LanTraceLog.e("SmsProcessingEngine", "Club score award failed for $phone", error)
        }
    }

    private suspend fun awardReferralForPurchase(phone: String, serialRowId: Long) {
        try {
            ReferralService.getInstance(appContext).onPurchase(
                buyerPhone = phone,
                sourceType = com.example.smsgateway.data.CustomerScoreSourceType.SERIAL,
                sourceId = serialRowId,
            )
        } catch (error: Exception) {
            LanTraceLog.e("SmsProcessingEngine", "Referral award failed for $phone", error)
        }
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

    private suspend fun categoryFor(row: SerialEntity): String {
        val groupId = row.groupId ?: return SerialGroupCategory.DEFAULT
        return SerialGroupCategory.normalizeOrDefault(
            database.serialGroupDao().findById(groupId)?.category,
        )
    }

    private suspend fun knownCityFor(phone: String): String? {
        return database.serialDao().findLatestCityByPhone(phone)?.takeIf { it.isNotBlank() }
    }

    /**
     * Copy a previously known city onto the row when blank, and mark complete when
     * seller/known-city rules say nothing else is required.
     */
    private suspend fun applyKnownCityAndMaybeComplete(phone: String, serial: String): SerialEntity? {
        val dao = database.serialDao()
        val row = dao.findBySerial(serial) ?: return null
        if (row.phone?.trim() != phone.trim()) return row

        val category = categoryFor(row)
        val known = knownCityFor(phone)
        val effectiveCity = RegistrationFieldRules.effectiveCity(row.city, known)
        val complete = RegistrationFieldRules.fieldsComplete(effectiveCity, row.km, category, null)
        val cityNeedsWrite = row.city.isNullOrBlank() && effectiveCity != null
        val flagNeedsWrite = complete != row.registrationFieldsComplete

        if (!cityNeedsWrite && !flagNeedsWrite) {
            return row
        }

        dao.updateRegistrationFields(
            phone = phone,
            serial = serial,
            city = effectiveCity,
            km = row.km,
            fieldsComplete = complete,
        )
        val updated = dao.findBySerial(serial) ?: return null
        if (cityNeedsWrite || (complete && !row.registrationFieldsComplete)) {
            syncWriter.recordRegistrationUpdated(
                serial = updated.serial,
                phone = phone,
                date = updated.date ?: JalaliHelper.nowJalaliDateTime(),
                city = updated.city,
                km = updated.km,
            )
        }
        return updated
    }

    private suspend fun partialMissingFieldsReply(
        row: SerialEntity,
        category: String? = null,
        knownCity: String? = null,
    ): String {
        val resolvedCategory = category ?: categoryFor(row)
        val known = knownCity ?: row.phone?.let { knownCityFor(it) }
        val missing = RegistrationFieldRules.missingFields(row.city, row.km, resolvedCategory, known)
        val example = when {
            missing.city && missing.km ->
                "${row.serial} ${SmsMessageCatalog.EXAMPLE_CITY} ${SmsMessageCatalog.EXAMPLE_KM}"
            missing.city -> "${row.serial} ${SmsMessageCatalog.EXAMPLE_CITY}"
            missing.km -> "${row.serial} ${SmsMessageCatalog.EXAMPLE_KM}"
            else -> row.serial
        }
        return when {
            missing.city && missing.km ->
                templates.resolve(SmsMessageId.PARTIAL_MISSING_CITY_KM, mapOf("@1" to example))
            missing.city ->
                templates.resolve(SmsMessageId.PARTIAL_MISSING_CITY, mapOf("@1" to example))
            missing.km ->
                templates.resolve(SmsMessageId.KM_INVALID_WITH_EXAMPLE, mapOf("@1" to example))
            else -> templates.resolve(SmsMessageId.REPLY_VALID, mapOf("@1" to "طبق شرایط محصول"))
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

    private suspend fun replyValid(serial: String? = null): String {
        val warrantyLabel = resolveWarrantyLabel(serial)
        val base = templates.resolve(
            SmsMessageId.REPLY_VALID,
            mapOf("@1" to warrantyLabel),
        )
        if (serial.isNullOrBlank() || !isImageVerificationRequired(serial)) {
            return base
        }
        val photoHint = appContext.getString(
            R.string.bot_photo_verify_after_register,
            SerialNormalizer.normalizeSerial(serial),
        )
        return "$base\n\n$photoHint"
    }

    private suspend fun resolveWarrantyLabel(serial: String?): String {
        if (serial.isNullOrBlank()) return "طبق شرایط محصول"
        val row = database.serialDao().findBySerial(SerialNormalizer.normalizeSerial(serial))
            ?: return "طبق شرایط محصول"
        val groupId = row.groupId ?: return "طبق شرایط محصول"
        val group = database.serialGroupDao().findById(groupId) ?: return "طبق شرایط محصول"
        return SerialGroupWarranty.formatFa(group.warrantyMonths, group.warrantyKm)
            ?: "طبق شرایط محصول"
    }

    suspend fun isImageVerificationRequired(serial: String?): Boolean {
        if (serial.isNullOrBlank()) return false
        val row = database.serialDao().findBySerial(SerialNormalizer.normalizeSerial(serial))
            ?: return false
        val groupId = row.groupId ?: return false
        val group = database.serialGroupDao().findById(groupId) ?: return false
        return group.imageVerificationRequired
    }

    private fun replyInvalid(): String = templates.resolve(SmsMessageId.REPLY_INVALID)

    private fun response(
        reply: String,
        firstRegistration: Boolean = false,
        registrationSucceeded: Boolean = false,
        imageVerificationRequired: Boolean = false,
        registeredSerial: String? = null,
        replySubscriptionId: Int? = null,
    ): GatewayApi.GatewayResponse {
        return GatewayApi.GatewayResponse(
            status = REPLY_STATUS,
            reply = reply,
            firstRegistration = firstRegistration,
            registrationSucceeded = registrationSucceeded,
            imageVerificationRequired = imageVerificationRequired,
            registeredSerial = registeredSerial,
            replySubscriptionId = replySubscriptionId,
        )
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
