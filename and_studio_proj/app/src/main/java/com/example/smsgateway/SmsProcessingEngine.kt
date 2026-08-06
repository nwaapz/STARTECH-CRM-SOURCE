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
    private val activeTrace = ThreadLocal<ProcTrace?>()

    private data class ProcTrace(
        val id: String,
        val phone: String,
        val mode: String,
    )

    /** Append one line to [SmsProcessingDebugLog] for the current process() call. */
    private fun dbg(message: String) {
        val t = activeTrace.get()
        SmsProcessingDebugLog.log(
            correlationId = t?.id ?: "?",
            phone = t?.phone.orEmpty(),
            mode = t?.mode.orEmpty(),
            message = message,
        )
    }

    private fun clip(text: String?, max: Int = 160): String {
        val raw = text?.replace('\n', ' ')?.trim().orEmpty()
        if (raw.length <= max) return raw
        return raw.take(max) + "…"
    }

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
        val correlationId = "%04x".format((System.currentTimeMillis() and 0xFFFF).toInt())

        return runBlocking {
            activeTrace.set(ProcTrace(correlationId, normalizedPhone, config.mode.name))
            try {
                dbg(
                    "IN source=$source body=\"${clip(body, 200)}\" " +
                        "subIn=$subscriptionId sep='${config.separator}' " +
                        "stepFlow=${config.stepFlowType} " +
                        "stepOrder=${config.stepFieldOrder.joinToString("+")} " +
                        "combinedPattern=${config.combinedPattern.joinToString("+")} " +
                        "timeoutMin=${config.sessionTimeoutMinutes}",
                )
                val resolvedSubscriptionId = resolveOutboundSubscriptionId(
                    phone = normalizedPhone,
                    inboundSubscriptionId = subscriptionId,
                    config = config,
                )
                dbg("outboundSub=$resolvedSubscriptionId")
                val result = when (config.mode) {
                    SmsProcessingMode.SERIAL_ONLY ->
                        processSerialOnly(normalizedPhone, body, resolvedSubscriptionId, source)
                    SmsProcessingMode.COMBINED_PATTERN ->
                        processCombined(normalizedPhone, body, config, resolvedSubscriptionId, source)
                    SmsProcessingMode.STEP_BY_STEP ->
                        processStepByStep(normalizedPhone, body, config, resolvedSubscriptionId, source)
                }
                val out = if (result.replySubscriptionId != null) {
                    result
                } else {
                    result.copy(replySubscriptionId = resolvedSubscriptionId)
                }
                dbg(
                    "OUT reply=\"${clip(out.reply)}\" " +
                        "regOk=${out.registrationSucceeded} firstReg=${out.firstRegistration} " +
                        "photo=${out.imageVerificationRequired} serial=${out.registeredSerial}",
                )
                out
            } catch (error: Exception) {
                dbg("EXCEPTION ${error.javaClass.simpleName}: ${error.message}")
                throw error
            } finally {
                activeTrace.remove()
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
        dbg("mode1 serial-only path serial=\"${clip(serial, 80)}\"")
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
        dbg("mode2 combined: try continue incomplete…")
        tryContinueMode2Registration(phone, body, config)?.let {
            dbg("mode2 → handled by tryContinueMode2Registration")
            return it
        }

        dbg("mode2: try partial serial-only…")
        tryRegisterPartialSerialOnly(phone, body, config, subscriptionId, registrationSource)?.let {
            dbg("mode2 → handled by tryRegisterPartialSerialOnly")
            return it
        }

        dbg("mode2: parseCombinedFlexible…")
        val result = SmsPatternParser.parseCombinedFlexible(
            message = body,
            separator = config.separator,
            requiredFields = config.combinedPattern,
            isValidSerial = { serial -> isUnusedSerialInDb(serial) },
        )
        val parsed = result.fields
        if (parsed != null) {
            dbg(
                "mode2 parse OK serial=${parsed.serial} city=${parsed.city} km=${parsed.km} → registerIfValid",
            )
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
        dbg("mode2 parse failed error=${result.error}")

        // Already-bound incomplete serial for this phone: never say "invalid" —
        // re-ask for the remaining fields instead.
        findIncompleteOwnedSerial(phone, body)?.let { incomplete ->
            dbg(
                "mode2 owned-incomplete fallback serial=${incomplete.serial} " +
                    "city=${incomplete.city} km=${incomplete.km} complete=${incomplete.registrationFieldsComplete}",
            )
            val category = categoryFor(incomplete)
            return response(
                partialMissingFieldsReply(
                    incomplete,
                    category,
                    knownCity = null,
                    lookupKnownCityIfMissing = false,
                ),
            )
        }

        if (result.error == SmsPatternParser.CombinedParseError.SERIAL_NOT_FOUND) {
            dbg("mode2 → reject SERIAL_NOT_FOUND")
            return rejectSerial(
                phone = phone,
                serial = guessSerialFromMessage(body),
                reason = SerialRegistrationErrorReason.NOT_FOUND,
            )
        }

        dbg("mode2 → combinedModeErrorReply ${result.error}")
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
            dbg("mode3 session EXPIRED → clear")
            sessionStore.clearSession(phone)
            if (!isUnusedSerial(body)) {
                dbg("mode3 expired + body not unused serial → SESSION_EXPIRED reply")
                return response(templates.resolve(SmsMessageId.SESSION_EXPIRED))
            }
            dbg("mode3 expired but body is unused serial → allow restart")
        }

        val session = sessionStore.findValidSession(phone, config.sessionTimeoutMinutes)

        if (session != null) {
            dbg(
                "mode3 active session serial=${session.serial} nextField=${session.nextField} " +
                    "collectedCity=${session.collectedCity} collectedKm=${session.collectedKm}",
            )
            if (isUnusedSerial(body)) {
                dbg("mode3 body is new unused serial → clear session + startStepSession")
                sessionStore.clearSession(phone)
                return startStepSession(phone, body, config, subscriptionId, registrationSource)
            }
            dbg("mode3 → continueStepSession")
            return continueStepSession(phone, body, config, session)
        }

        // No session: a fresh unused serial must start a new step flow — never let the
        // incomplete-row fallback treat it as city/km for a leftover partial registration.
        if (isUnusedSerial(body)) {
            dbg("mode3 no session + unused serial → startStepSession (skip incomplete fallback)")
            return startStepSession(phone, body, config, subscriptionId, registrationSource)
        }

        dbg("mode3 no session → tryContinueMode3Registration…")
        tryContinueMode3Registration(phone, body, config)?.let {
            dbg("mode3 → handled by tryContinueMode3Registration")
            return it
        }

        dbg("mode3 → startStepSession")
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
        dbg("mode3 startStepSession normalizedSerial=$serial")
        if (serial.isEmpty()) {
            dbg("mode3 start → empty serial → invalid")
            return response(replyInvalid())
        }

        val dao = database.serialDao()
        val row = dao.findBySerial(serial)
            ?: run {
                dbg("mode3 start → serial NOT_FOUND")
                return rejectSerial(phone, serial, SerialRegistrationErrorReason.NOT_FOUND)
            }

        val remaining = config.stepFieldsAfterSerial()
        dbg(
            "mode3 start row phone=${row.phone} complete=${row.registrationFieldsComplete} " +
                "city=${row.city} km=${row.km} remainingAfterSerial=$remaining",
        )

        when {
            !isSerialUsed(row.phone) -> {
                if (remaining.isEmpty()) {
                    dbg("mode3 start unused + no remaining fields → registerIfValid full")
                    return registerIfValid(
                        phone, serial, null, null, useFields = false, subscriptionId, registrationSource,
                    )
                }
                if (!registerPartialSerial(phone, serial, subscriptionId, registrationSource)) {
                    dbg("mode3 start registerPartialSerial FAILED → ALREADY_USED")
                    return rejectSerial(phone, serial, SerialRegistrationErrorReason.ALREADY_USED)
                }
                dbg("mode3 start registerPartialSerial OK")
            }
            row.phone!!.trim() == phone.trim() -> {
                if (!row.needsCompletion()) {
                    dbg("mode3 start already complete for this phone → reply valid (no new reg)")
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
                dbg("mode3 start incomplete owned serial → continue to prompt")
            }
            else -> {
                dbg("mode3 start serial owned by other phone → ALREADY_USED")
                return rejectSerial(phone, serial, SerialRegistrationErrorReason.ALREADY_USED)
            }
        }

        if (remaining.isEmpty()) {
            dbg("mode3 start remaining empty after bind → reply valid")
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
        dbg("mode3 start saveSession nextField=$nextField flow=${config.stepFlowType}")

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
        dbg(
            "mode3 continue body=\"${clip(body)}\" nextField=${session.nextField} " +
                "serial=${session.serial}",
        )

        if (session.nextField == SmsProcessingConfig.SESSION_COMBINED) {
            val parseResult = SmsPatternParser.parseCityKmAuto(body)
            val parsed = parseResult.fields
            if (parsed == null) {
                dbg("mode3 continue COMBINED parse FAIL error=${parseResult.error}")
                return response(combinedSecondStepErrorReply(parseResult.error))
            }
            dbg("mode3 continue COMBINED parse OK city=${parsed.city} km=${parsed.km} → finishStepRegistration")
            sessionStore.clearSession(phone)
            return finishStepRegistration(
                phone = phone,
                serial = session.serial,
                city = parsed.city,
                km = parsed.km,
            )
        }

        val expectedField = runCatching {
            SmsField.valueOf(session.nextField ?: return response(replyInvalid()))
        }.getOrElse {
            dbg("mode3 continue invalid nextField=${session.nextField}")
            return response(replyInvalid())
        }

        val parsed = SmsPatternParser.parseSingleField(body, expectedField)
        if (parsed == null) {
            dbg("mode3 continue parseSingleField($expectedField) FAIL")
            return when (expectedField) {
                SmsField.KM -> response(templates.resolve(SmsMessageId.KM_INVALID))
                else -> response(
                    templates.resolve(
                        SmsMessageId.STEP_PROMPT_FIELD,
                        mapOf("@1" to SmsProcessingConfig.fieldPromptLabel(expectedField)),
                    ),
                )
            }
        }
        dbg("mode3 continue parseSingleField($expectedField) OK city=${parsed.city} km=${parsed.km}")

        val collectedCity = parsed.city ?: session.collectedCity
        val collectedKm = parsed.km ?: session.collectedKm
        val remaining = config.stepFieldsAfterSerial()
        val currentIndex = remaining.indexOf(expectedField)
        if (currentIndex < 0) {
            dbg("mode3 continue expectedField not in remaining=$remaining → invalid")
            sessionStore.clearSession(phone)
            return response(replyInvalid())
        }

        val nextRemaining = remaining.drop(currentIndex + 1)
        if (nextRemaining.isEmpty()) {
            dbg(
                "mode3 continue last field done city=$collectedCity km=$collectedKm → finishStepRegistration",
            )
            sessionStore.clearSession(phone)
            return finishStepRegistration(
                phone = phone,
                serial = session.serial,
                city = collectedCity,
                km = collectedKm,
            )
        }

        dbg("mode3 continue advance nextField=${nextRemaining.first().name} city=$collectedCity km=$collectedKm")
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

    /**
     * Mode 2 (COMBINED_PATTERN) only: continue a partial registration started by
     * [tryRegisterPartialSerialOnly]. Never looks up a known city here — Mode 2 must
     * always collect city/km explicitly from the customer, never auto-complete from a
     * cached value (that previously let a bare serial SMS finish registration).
     *
     * Kept fully independent from Mode 3's fallback ([tryContinueMode3Registration]) on
     * purpose: they used to share one function gated by a mode flag, and a fix aimed at
     * one mode kept leaking into the other.
     */
    private suspend fun tryContinueMode2Registration(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
    ): GatewayApi.GatewayResponse? {
        val dao = database.serialDao()
        val incomplete = dao.findIncompleteByPhone(phone)
        if (incomplete == null) {
            dbg("mode2 continue: no incomplete row for phone")
            return null
        }
        val category = categoryFor(incomplete)
        val priorKm = RegistrationFieldRules.storedKm(incomplete.km)
        dbg(
            "mode2 continue: incomplete serial=${incomplete.serial} city=${incomplete.city} " +
                "km=${incomplete.km} priorKm=$priorKm completeFlag=${incomplete.registrationFieldsComplete} category=$category",
        )

        if (!RegistrationFieldRules.needsCompletion(phone, incomplete.city, priorKm, category, null)) {
            dbg("mode2 continue: needsCompletion=false → skip")
            return null
        }

        // Bare different serial (e.g. leftover incomplete F10384, user now sends F10404):
        // never treat that serial token as city/km for the old row.
        if (isDifferentSerialMessage(body, incomplete.serial)) {
            dbg("mode2 continue: body is different serial → skip incomplete path")
            return null
        }

        if (body.trim().equals(incomplete.serial, ignoreCase = true)) {
            dbg("mode2 continue: body equals serial → re-prompt")
            return partialFieldsPrompt(incomplete, category)
        }

        val missingFields = RegistrationFieldRules.missingFields(
            incomplete.city,
            priorKm,
            category,
            null,
        ).toSmsFields()
        dbg("mode2 continue: missingFields=$missingFields")
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
                dbg("mode2 continue: combined parse serial mismatch ${parsed.serial} → re-prompt")
                return partialFieldsPrompt(incomplete, category)
            }
            dbg(
                "mode2 continue: combined parse OK city=${parsed.city} km=${parsed.km} → completeRegistrationFields",
            )
            return completeRegistrationFields(
                phone = phone,
                serial = incomplete.serial,
                city = parsed.city ?: incomplete.city,
                km = parsed.km ?: priorKm,
            )
        }
        dbg("mode2 continue: combined parse miss error=${parsedFromCombined.error}")

        // Prefer extracting city/km from the remainder after stripping the serial,
        // so "SERIAL Tehran" is not treated as a single city token.
        val remainderAfterSerial = body
            .replace(Regex(Regex.escape(incomplete.serial), RegexOption.IGNORE_CASE), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        val extracted = SmsPatternParser.extractRemainingFields(
            remainderAfterSerial.ifBlank { body },
            needsCity = missingFields.contains(SmsField.CITY),
            needsKm = missingFields.contains(SmsField.KM),
        )
        dbg(
            "mode2 continue: remainder=\"${clip(remainderAfterSerial)}\" " +
                "extracted city=${extracted.city} km=${extracted.km}",
        )

        val city = incomplete.city?.takeIf { it.isNotBlank() }
            ?: extracted.city
                ?.takeIf { !it.equals(incomplete.serial, ignoreCase = true) }
                ?.takeIf { !looksLikeKnownSerial(it) }
        val km = priorKm ?: extracted.km

        val stillMissingCity = missingFields.contains(SmsField.CITY) && city.isNullOrBlank()
        val stillMissingKm = missingFields.contains(SmsField.KM) && km == null

        if (stillMissingCity || stillMissingKm) {
            val progressed = city != incomplete.city || km != priorKm
            dbg(
                "mode2 continue: still missing city=$stillMissingCity km=$stillMissingKm " +
                    "progressed=$progressed mergedCity=$city mergedKm=$km",
            )
            val updated = if (progressed) {
                dao.updateRegistrationFields(
                    phone = phone,
                    serial = incomplete.serial,
                    city = city,
                    km = km,
                    fieldsComplete = false,
                )
                dao.findBySerial(incomplete.serial) ?: incomplete
            } else {
                incomplete
            }
            return partialFieldsPrompt(updated, category)
        }

        dbg("mode2 continue: all fields present city=$city km=$km → completeRegistrationFields")
        return completeRegistrationFields(
            phone = phone,
            serial = incomplete.serial,
            city = city,
            km = km,
        )
    }

    private suspend fun partialFieldsPrompt(
        incomplete: SerialEntity,
        category: String,
    ): GatewayApi.GatewayResponse {
        return response(
            partialMissingFieldsReply(incomplete, category, knownCity = null, lookupKnownCityIfMissing = false),
        )
    }

    /**
     * Mode 3 (STEP_BY_STEP) fallback only: used when the persisted step session is gone
     * (e.g. cleared after a timeout, or the mode was switched mid-flow) but a partial
     * registration for this phone still exists. Kept independent from Mode 2's
     * continuation logic above. Any completion here routes through
     * [finishStepRegistration], same as the normal session-driven flow.
     */
    private suspend fun tryContinueMode3Registration(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
    ): GatewayApi.GatewayResponse? {
        val dao = database.serialDao()
        val incomplete = dao.findIncompleteByPhone(phone)
        if (incomplete == null) {
            dbg("mode3 fallback: no incomplete row")
            return null
        }
        val category = categoryFor(incomplete)
        val priorKm = RegistrationFieldRules.storedKm(incomplete.km)
        dbg(
            "mode3 fallback: incomplete serial=${incomplete.serial} city=${incomplete.city} " +
                "km=${incomplete.km} priorKm=$priorKm category=$category",
        )

        if (!RegistrationFieldRules.needsCompletion(phone, incomplete.city, priorKm, category, null)) {
            dbg("mode3 fallback: needsCompletion=false → skip")
            return null
        }

        // Bare different serial must start a new Mode 3 flow, not finish the leftover row.
        if (isDifferentSerialMessage(body, incomplete.serial)) {
            dbg("mode3 fallback: body is different serial → skip (let startStepSession handle)")
            return null
        }

        if (body.trim().equals(incomplete.serial, ignoreCase = true)) {
            dbg("mode3 fallback: body equals serial → re-prompt")
            return partialFieldsPrompt(incomplete, category)
        }

        val missingFields = RegistrationFieldRules.missingFields(
            incomplete.city,
            priorKm,
            category,
            null,
        ).toSmsFields()
        dbg("mode3 fallback: missingFields=$missingFields")
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
                dbg("mode3 fallback: combined serial mismatch → re-prompt")
                return partialFieldsPrompt(incomplete, category)
            }
            dbg(
                "mode3 fallback: combined OK city=${parsed.city} km=${parsed.km} → finishStepRegistration",
            )
            return finishStepRegistration(
                phone = phone,
                serial = incomplete.serial,
                city = parsed.city ?: incomplete.city,
                km = parsed.km ?: priorKm,
            )
        }
        dbg("mode3 fallback: combined miss error=${parsedFromCombined.error}")

        val remainderAfterSerial = body
            .replace(Regex(Regex.escape(incomplete.serial), RegexOption.IGNORE_CASE), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        val extracted = SmsPatternParser.extractRemainingFields(
            remainderAfterSerial.ifBlank { body },
            needsCity = missingFields.contains(SmsField.CITY),
            needsKm = missingFields.contains(SmsField.KM),
        )
        dbg(
            "mode3 fallback: remainder=\"${clip(remainderAfterSerial)}\" " +
                "extracted city=${extracted.city} km=${extracted.km}",
        )

        val city = incomplete.city?.takeIf { it.isNotBlank() }
            ?: extracted.city
                ?.takeIf { !it.equals(incomplete.serial, ignoreCase = true) }
                ?.takeIf { !looksLikeKnownSerial(it) }
        val km = priorKm ?: extracted.km

        if (missingFields.contains(SmsField.CITY) && city.isNullOrBlank()) {
            dbg("mode3 fallback: still missing city → re-prompt")
            return partialFieldsPrompt(incomplete, category)
        }
        if (missingFields.contains(SmsField.KM) && km == null) {
            dbg("mode3 fallback: still missing km → re-prompt")
            return partialFieldsPrompt(incomplete, category)
        }

        dbg("mode3 fallback: fields ready city=$city km=$km → finishStepRegistration")
        return finishStepRegistration(
            phone = phone,
            serial = incomplete.serial,
            city = city,
            km = km,
        )
    }

    /**
     * Mode 3 (STEP_BY_STEP) session-driven completion: the step session has already
     * collected exactly the fields configured in `stepFieldOrder`, so this finalizes
     * unconditionally instead of re-deriving "is anything still missing" — the session is
     * already cleared by the time this runs, so a "still missing" verdict would strand the
     * registration with no way to continue. (See [completeRegistrationFields] for the
     * category-aware version used by Mode 2's freeform continuation, where that recheck is
     * actually needed.)
     */
    private suspend fun finishStepRegistration(
        phone: String,
        serial: String,
        city: String?,
        km: Int?,
    ): GatewayApi.GatewayResponse {
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        dbg("finishStepRegistration serial=$normalizedSerial city=$city km=$km")
        val dao = database.serialDao()
        val row = dao.findBySerial(normalizedSerial) ?: run {
            dbg("finishStepRegistration: serial missing in DB")
            return response(replyInvalid())
        }
        if (row.phone?.trim() != phone.trim()) {
            dbg("finishStepRegistration: phone mismatch row=${row.phone}")
            return response(replyInvalid())
        }

        val known = knownCityFor(phone)
        val effectiveCity = RegistrationFieldRules.effectiveCity(city, known)
        dbg("finishStepRegistration: knownCity=$known effectiveCity=$effectiveCity")

        val affected = dao.updateRegistrationFields(
            phone = phone,
            serial = normalizedSerial,
            city = effectiveCity,
            km = km,
            fieldsComplete = true,
        )
        if (affected != 1) {
            dbg("finishStepRegistration: update affected=$affected")
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
        dbg("finishStepRegistration: OK firstReg=$firstReg photo=$needsPhoto")
        return response(
            replyValid(normalizedSerial),
            firstRegistration = firstReg,
            registrationSucceeded = true,
            imageVerificationRequired = needsPhoto,
            registeredSerial = normalizedSerial,
        )
    }

    /**
     * If [body] mentions a serial already bound incomplete to [phone], return that row.
     * Used so Mode 2 never answers "serial invalid" for the customer's own in-progress registration.
     */
    private suspend fun findIncompleteOwnedSerial(phone: String, body: String): SerialEntity? {
        val incomplete = database.serialDao().findIncompleteByPhone(phone) ?: return null
        val guessed = guessSerialFromMessage(body)
        if (guessed.isNotEmpty() && guessed.equals(incomplete.serial, ignoreCase = true)) {
            return incomplete
        }
        if (body.contains(incomplete.serial, ignoreCase = true)) {
            return incomplete
        }
        return null
    }

    /**
     * Mode 2 (COMBINED): body is exactly an unused/own serial with no city/km.
     * Bind the serial as incomplete and ask for remaining fields — never finish
     * registration here (unlike Mode 1 SERIAL_ONLY). Known-city auto-complete is
     * skipped so a lone serial SMS cannot complete warranty registration.
     */
    private suspend fun tryRegisterPartialSerialOnly(
        phone: String,
        body: String,
        config: SmsProcessingConfig,
        subscriptionId: Int,
        registrationSource: String,
    ): GatewayApi.GatewayResponse? {
        if (config.mode != SmsProcessingMode.COMBINED_PATTERN) return null

        val serial = SerialNormalizer.normalizeSerial(body)
        if (serial.isEmpty()) return null
        // Combined payloads must go through the full parser, not this path.
        if (body.trim().contains(Regex("\\s"))) {
            dbg("mode2 partial: body has whitespace → skip (full combined path)")
            return null
        }

        val dao = database.serialDao()
        val row = dao.findBySerial(serial)
        if (row == null) {
            dbg("mode2 partial: serial $serial not in DB → skip")
            return null
        }
        dbg("mode2 partial: serial=$serial phone=${row.phone} complete=${row.registrationFieldsComplete}")

        when {
            !isSerialUsed(row.phone) -> {
                if (!registerPartialSerial(phone, serial, subscriptionId, registrationSource)) {
                    dbg("mode2 partial: registerPartialSerial failed")
                    return null
                }
                dbg("mode2 partial: bound unused serial as incomplete")
            }
            row.phone!!.trim() == phone.trim() -> {
                if (row.registrationFieldsComplete &&
                    !RegistrationFieldRules.needsCompletion(
                        phone,
                        row.city,
                        row.km,
                        categoryFor(row),
                        knownCity = null,
                    )
                ) {
                    dbg("mode2 partial: already finished → skip")
                    return null
                }
                dao.markRegistrationIncomplete(
                    serial,
                    phone,
                    storedSubscriptionId(subscriptionId),
                )
                dbg("mode2 partial: re-marked incomplete")
            }
            else -> {
                dbg("mode2 partial: owned by other phone → skip")
                return null
            }
        }

        val updated = dao.findBySerial(serial) ?: return null
        val category = categoryFor(updated)
        dbg("mode2 partial: prompt missing fields category=$category city=${updated.city} km=${updated.km}")
        return response(
            partialMissingFieldsReply(updated, category, knownCity = null, lookupKnownCityIfMissing = false),
        )
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
        if (isSerialUsed(row.phone)) {
            return row.phone!!.trim() == phone.trim()
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
        dbg("completeRegistrationFields serial=$normalizedSerial city=$city km=$km")
        val dao = database.serialDao()
        val row = dao.findBySerial(normalizedSerial) ?: run {
            dbg("completeRegistrationFields: serial missing")
            return response(replyInvalid())
        }
        if (row.phone?.trim() != phone.trim()) {
            dbg("completeRegistrationFields: phone mismatch row=${row.phone}")
            return response(replyInvalid())
        }

        val category = categoryFor(row)
        val known = knownCityFor(phone)
        val effectiveCity = RegistrationFieldRules.effectiveCity(city, known)
        val stillMissing = RegistrationFieldRules.missingFields(effectiveCity, km, category, null)
        dbg(
            "completeRegistrationFields: category=$category known=$known effectiveCity=$effectiveCity " +
                "stillMissing city=${stillMissing.city} km=${stillMissing.km}",
        )
        if (!stillMissing.isEmpty()) {
            dao.updateRegistrationFields(
                phone = phone,
                serial = normalizedSerial,
                city = effectiveCity,
                km = km,
                fieldsComplete = false,
            )
            val updated = dao.findBySerial(normalizedSerial) ?: return response(replyInvalid())
            dbg("completeRegistrationFields: persist partial + re-prompt")
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
            dbg("completeRegistrationFields: update affected=$affected")
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
        dbg("completeRegistrationFields: COMPLETE firstReg=$firstReg photo=$needsPhoto")
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
        dbg("registerIfValid serial=$normalizedSerial city=$city km=$km useFields=$useFields")
        val dao = database.serialDao()
        val row = dao.findBySerial(normalizedSerial)
        if (row == null) {
            dbg("registerIfValid: NOT_FOUND")
            return rejectSerial(phone, normalizedSerial, SerialRegistrationErrorReason.NOT_FOUND)
        }
        if (isSerialUsed(row.phone)) {
            dbg("registerIfValid: ALREADY_USED by ${row.phone}")
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
        dbg(
            "registerIfValid: category=$category known=$known effectiveCity=$effectiveCity requiresKm=$requiresKmFlag",
        )
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
            dbg("registerIfValid: write affected=$affected → ALREADY_USED")
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
            if (!updated.registrationFieldsComplete ||
                RegistrationFieldRules.needsCompletion(
                    phone,
                    updated.city,
                    updated.km,
                    updatedCategory,
                    updatedKnown,
                )
            ) {
                dbg(
                    "registerIfValid: registered but still incomplete " +
                        "city=${updated.city} km=${updated.km} flag=${updated.registrationFieldsComplete}",
                )
                return response(partialMissingFieldsReply(updated, updatedCategory, updatedKnown))
            }
            val firstReg = onFullRegistrationSuccess(phone, normalizedSerial)
            val needsPhoto = isImageVerificationRequired(normalizedSerial)
            dbg("registerIfValid: FULL with fields firstReg=$firstReg")
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
        dbg("registerIfValid: FULL serial-only firstReg=$firstReg")
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

    /**
     * True when [body] is a single token that matches a different serial already present
     * in the DB (used or unused). Used so leftover incomplete registrations never treat
     * a newly typed serial like "F10404" as a city name.
     */
    private suspend fun isDifferentSerialMessage(body: String, incompleteSerial: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.contains(Regex("\\s"))) return false
        val candidate = SerialNormalizer.normalizeSerial(trimmed)
        if (candidate.isEmpty()) return false
        if (candidate.equals(incompleteSerial, ignoreCase = true)) return false
        return database.serialDao().findBySerial(candidate) != null
    }

    private suspend fun looksLikeKnownSerial(value: String): Boolean {
        val candidate = SerialNormalizer.normalizeSerial(value)
        if (candidate.isEmpty()) return false
        return database.serialDao().findBySerial(candidate) != null
    }

    private suspend fun isUnusedSerialInDb(serial: String): Boolean {
        val normalized = SerialNormalizer.normalizeSerial(serial)
        if (normalized.isEmpty()) return false
        val row = database.serialDao().findBySerial(normalized) ?: return false
        return !isSerialUsed(row.phone)
    }

    private fun isSerialUsed(phone: String?): Boolean {
        return PhoneUtils.isRegisteredPhone(phone)
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
        lookupKnownCityIfMissing: Boolean = true,
    ): String {
        val resolvedCategory = category ?: categoryFor(row)
        val known = knownCity
            ?: (if (lookupKnownCityIfMissing) row.phone?.let { knownCityFor(it) } else null)
        val missing = RegistrationFieldRules.missingFields(
            row.city,
            if (row.registrationFieldsComplete) row.km else RegistrationFieldRules.storedKm(row.km),
            resolvedCategory,
            known,
        )
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
