package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.ClubAutoMessageChannel
import com.example.smsgateway.data.ClubAutoMessageRuleEntity
import com.example.smsgateway.data.ClubAutoMessageRunStatus
import com.example.smsgateway.data.ClubAutoMessageSendEntity
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.PhoneSerialDateRow
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * Runs club auto-message rules daily at each rule's configured local time.
 * Configuration is done only via the web CRM; this class executes on the gateway.
 *
 * Recipients: serials registered on or before (today − offset) in the selected groups.
 * Each serial receives a given rule on a given channel at most once (permanent ledger).
 * Each run alarms at most [ClubAutoMessageRuleEntity.dailyCap] distinct serials; the rest wait for tomorrow.
 */
class ClubAutoMessageScheduler private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private var loopJob: Job? = null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            LanTraceLog.i(TAG, "Club auto-message scheduler started")
            while (isActive) {
                try {
                    tick()
                } catch (error: Exception) {
                    LanTraceLog.e(TAG, "Scheduler tick failed", error)
                }
                val nearDue = try {
                    hasRuleNearOrPastDue()
                } catch (_: Exception) {
                    false
                }
                delay(if (nearDue) FAST_CHECK_INTERVAL_MS else CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * CRM nudge when countdown hits due: ensure the loop is running and process due rules ASAP.
     * Returns immediately; work runs on the scheduler scope (poll CRM for runStatus/progress).
     */
    fun requestTickDueNow() {
        start()
        scope.launch {
            try {
                tick()
            } catch (error: Exception) {
                LanTraceLog.e(TAG, "tickDueNow failed", error)
            }
        }
    }

    /** Manual run from CRM (ignores clock; still respects permanent channel dedup + daily cap). */
    suspend fun runRuleNow(ruleId: Long): ClubAutoMessageRuleEntity {
        val rule = database.clubAutoMessageDao().findRuleById(ruleId)
            ?: throw IllegalArgumentException("قانون یافت نشد")
        return runMutex.withLock {
            executeRule(rule, force = true)
            database.clubAutoMessageDao().findRuleById(ruleId)
                ?: throw IllegalStateException("Rule missing after run")
        }
    }

    /**
     * Preview who would receive this rule on the next run (before any send).
     * Paginated over eligible serials; each row includes per-channel send readiness.
     */
    suspend fun previewRecipients(
        ruleId: Long,
        page: Int,
        limit: Int,
    ): ClubAutoMessagePreviewResult {
        val rule = database.clubAutoMessageDao().findRuleById(ruleId)
            ?: throw IllegalArgumentException("قانون یافت نشد")
        if (rule.offsetMonths <= 0 && rule.offsetDays == 0) {
            throw IllegalArgumentException("بازه زمانی باید حداقل یک ماه یا یک روز باشد")
        }
        if (!rule.sendSms && !rule.sendRubika && !rule.sendTelegram && !rule.sendBale) {
            throw IllegalArgumentException("حداقل یک کانال ارسال باید فعال باشد")
        }

        val onOrBeforeDate = JalaliHelper.jalaliDateOffsetMonthsDays(rule.offsetMonths, rule.offsetDays)
        val serials = resolveEligibleSerials(rule, onOrBeforeDate)
        val settings = SettingsStore.getInstance(appContext)
        val rubikaToken = settings.getRubikaToken().trim()
        val telegramToken = settings.getTelegramToken().trim()
        val baleToken = settings.getBaleToken().trim()

        var pendingCount = 0
        var blockedCount = 0
        var alreadyFullySentCount = 0
        val allItems = ArrayList<ClubAutoMessagePreviewItem>(serials.size)

        for (reg in serials) {
            val phone = PhoneUtils.normalizeForSmsSend(reg.phone).ifBlank { reg.phone }
            if (phone.isBlank() || reg.serial.isBlank()) continue

            val channels = ArrayList<ClubAutoMessagePreviewChannel>()
            if (rule.sendSms) {
                channels.add(previewSmsChannel(rule.id, reg.serial))
            }
            if (rule.sendRubika) {
                channels.add(
                    previewBotChannel(
                        ruleId = rule.id,
                        serial = reg.serial,
                        phone = phone,
                        channel = ClubAutoMessageChannel.RUBIKA,
                        token = rubikaToken,
                        resolveChatId = { resolveRubikaChatId(it) },
                    ),
                )
            }
            if (rule.sendTelegram) {
                channels.add(
                    previewBotChannel(
                        ruleId = rule.id,
                        serial = reg.serial,
                        phone = phone,
                        channel = ClubAutoMessageChannel.TELEGRAM,
                        token = telegramToken,
                        resolveChatId = { resolveTelegramChatId(it) },
                    ),
                )
            }
            if (rule.sendBale) {
                channels.add(
                    previewBotChannel(
                        ruleId = rule.id,
                        serial = reg.serial,
                        phone = phone,
                        channel = ClubAutoMessageChannel.BALE,
                        token = baleToken,
                        resolveChatId = { resolveBaleChatId(it) },
                    ),
                )
            }

            val willSendAny = channels.any { it.status == PreviewChannelStatus.PENDING }
            val allAlreadySent = channels.isNotEmpty() &&
                channels.all { it.status == PreviewChannelStatus.ALREADY_SENT }
            when {
                willSendAny -> pendingCount++
                allAlreadySent -> alreadyFullySentCount++
                else -> blockedCount++
            }
            allItems.add(
                ClubAutoMessagePreviewItem(
                    phone = phone,
                    serial = reg.serial,
                    channels = channels,
                    willSendAny = willSendAny,
                ),
            )
        }

        val safePage = page.coerceAtLeast(1)
        val safeLimit = limit.coerceIn(1, 200)
        val offset = (safePage - 1) * safeLimit
        val pageItems = if (offset >= allItems.size) {
            emptyList()
        } else {
            allItems.subList(offset, minOf(offset + safeLimit, allItems.size))
        }

        val dailyCap = normalizeDailyCap(rule.dailyCap)
        val sentToday = database.clubAutoMessageDao()
            .countDistinctSerialsSentOnDate(rule.id, JalaliHelper.todayJalaliDate())
        return ClubAutoMessagePreviewResult(
            items = pageItems,
            total = allItems.size,
            pendingCount = pendingCount,
            blockedCount = blockedCount,
            alreadyFullySentCount = alreadyFullySentCount,
            cutoffDateJalali = onOrBeforeDate,
            dailyCap = dailyCap,
            sentToday = sentToday,
            remainingToday = (dailyCap - sentToday).coerceAtLeast(0),
            page = safePage,
            limit = safeLimit,
        )
    }

    private suspend fun resolveEligibleSerials(
        rule: ClubAutoMessageRuleEntity,
        onOrBeforeDate: String,
    ): List<EligibleSerial> {
        val groupIds = decodeSerialGroupIds(rule.serialGroupIdsJson)
        val rows: List<PhoneSerialDateRow> = if (groupIds.isNotEmpty()) {
            database.serialDao().listRegisteredPhoneSerialDatesForGroups(groupIds)
        } else {
            val category = SerialGroupCategory.normalizeOrDefault(rule.serialCategory)
            database.serialDao().listRegisteredPhoneSerialDatesForCategory(category)
        }
        val bySerial = LinkedHashMap<String, EligibleSerial>()
        for (row in rows) {
            val serial = row.serial.trim().uppercase()
            val phone = row.phone.trim()
            if (serial.isEmpty() || phone.isEmpty()) continue
            if (!JalaliHelper.isJalaliDateOnOrBefore(row.date, onOrBeforeDate)) continue
            val dateKey = JalaliHelper.datePrefixForCompare(row.date).orEmpty()
            val existing = bySerial[serial]
            if (existing == null || dateKey < existing.dateKey) {
                bySerial[serial] = EligibleSerial(
                    phone = phone,
                    serial = serial,
                    dateKey = dateKey,
                    km = row.km,
                )
            }
        }
        return bySerial.values.sortedWith(
            compareBy<EligibleSerial> { it.dateKey.ifEmpty { "9999/99/99" } }
                .thenBy { it.serial },
        )
    }

    private suspend fun previewSmsChannel(ruleId: Long, serial: String): ClubAutoMessagePreviewChannel {
        val status = if (alreadySentSerial(ruleId, serial, ClubAutoMessageChannel.SMS)) {
            PreviewChannelStatus.ALREADY_SENT
        } else {
            PreviewChannelStatus.PENDING
        }
        return ClubAutoMessagePreviewChannel(
            channel = ClubAutoMessageChannel.SMS,
            status = status,
        )
    }

    private suspend fun previewBotChannel(
        ruleId: Long,
        serial: String,
        phone: String,
        channel: String,
        token: String,
        resolveChatId: (String) -> String?,
    ): ClubAutoMessagePreviewChannel {
        val status = when {
            alreadySentSerial(ruleId, serial, channel) -> PreviewChannelStatus.ALREADY_SENT
            token.isBlank() -> PreviewChannelStatus.NO_TOKEN
            resolveChatId(phone) == null -> PreviewChannelStatus.NO_CHAT
            else -> PreviewChannelStatus.PENDING
        }
        return ClubAutoMessagePreviewChannel(
            channel = channel,
            status = status,
        )
    }

    private suspend fun tick() {
        runMutex.withLock {
            tickLocked()
        }
    }

    /** Caller must hold [runMutex]. */
    private suspend fun tickLocked() {
        if (!com.example.smsgateway.license.LicenseGate.allowsOperationalUse(appContext)) {
            return
        }
        val calendar = Calendar.getInstance()
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val todayJalali = JalaliHelper.todayJalaliDate()

        val dueRules = database.clubAutoMessageDao().listEnabledRules().filter { rule ->
            val ruleMinutes = rule.sendHour * 60 + rule.sendMinute
            if (nowMinutes < ruleMinutes) return@filter false
            if (rule.runStatus == ClubAutoMessageRunStatus.RUNNING) return@filter false
            if (rule.lastRunDateJalali == todayJalali) return@filter false
            remainingDailyCap(rule, todayJalali) > 0
        }
        if (dueRules.isEmpty()) return

        for (rule in dueRules) {
            val fresh = database.clubAutoMessageDao().findRuleById(rule.id) ?: continue
            if (!fresh.enabled) continue
            if (fresh.runStatus == ClubAutoMessageRunStatus.RUNNING) continue
            val freshMinutes = fresh.sendHour * 60 + fresh.sendMinute
            if (nowMinutes < freshMinutes) continue
            if (remainingDailyCap(fresh, todayJalali) <= 0) continue
            try {
                executeRule(fresh, force = false)
            } catch (error: Exception) {
                LanTraceLog.e(TAG, "Failed running rule ${fresh.id}", error)
            }
        }
    }

    private suspend fun hasRuleNearOrPastDue(): Boolean {
        val calendar = Calendar.getInstance()
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val todayJalali = JalaliHelper.todayJalaliDate()
        return database.clubAutoMessageDao().listEnabledRules().any { rule ->
            if (rule.runStatus == ClubAutoMessageRunStatus.RUNNING) return@any true
            if (rule.lastRunDateJalali == todayJalali) return@any false
            if (remainingDailyCap(rule, todayJalali) <= 0) return@any false
            val ruleMinutes = rule.sendHour * 60 + rule.sendMinute
            val minutesUntil = ruleMinutes - nowMinutes
            nowMinutes >= ruleMinutes || minutesUntil in 0..1
        }
    }

    private suspend fun remainingDailyCap(rule: ClubAutoMessageRuleEntity, todayJalali: String): Int {
        val cap = normalizeDailyCap(rule.dailyCap)
        val sentToday = database.clubAutoMessageDao()
            .countDistinctSerialsSentOnDate(rule.id, todayJalali)
        return (cap - sentToday).coerceAtLeast(0)
    }

    private suspend fun serialNeedsAnyChannel(
        rule: ClubAutoMessageRuleEntity,
        serial: String,
    ): Boolean {
        if (rule.sendSms && !alreadySentSerial(rule.id, serial, ClubAutoMessageChannel.SMS)) {
            return true
        }
        if (rule.sendRubika && !alreadySentSerial(rule.id, serial, ClubAutoMessageChannel.RUBIKA)) {
            return true
        }
        if (rule.sendTelegram && !alreadySentSerial(rule.id, serial, ClubAutoMessageChannel.TELEGRAM)) {
            return true
        }
        if (rule.sendBale && !alreadySentSerial(rule.id, serial, ClubAutoMessageChannel.BALE)) {
            return true
        }
        return false
    }

    private suspend fun executeRule(rule: ClubAutoMessageRuleEntity, force: Boolean): ClubAutoMessageRuleEntity {
        if (rule.offsetMonths <= 0 && rule.offsetDays == 0) {
            throw IllegalArgumentException("بازه زمانی باید حداقل یک ماه یا یک روز باشد")
        }
        if (!rule.sendSms && !rule.sendRubika && !rule.sendTelegram && !rule.sendBale) {
            throw IllegalArgumentException("حداقل یک کانال ارسال باید فعال باشد")
        }

        val todayJalali = JalaliHelper.todayJalaliDate()
        val remainingCap = remainingDailyCap(rule, todayJalali)
        if (remainingCap <= 0) {
            LanTraceLog.i(TAG, "Rule ${rule.id} skipped: daily cap reached")
            return rule
        }

        val onOrBeforeDate = JalaliHelper.jalaliDateOffsetMonthsDays(rule.offsetMonths, rule.offsetDays)
        val groupIds = decodeSerialGroupIds(rule.serialGroupIdsJson)
        val allEligible = resolveEligibleSerials(rule, onOrBeforeDate)
        val pending = allEligible.filter { serialNeedsAnyChannel(rule, it.serial) }
        val batch = pending.take(remainingCap)
        val delayMs = rule.delaySeconds.coerceAtLeast(1) * 1000L
        val runStartedAt = System.currentTimeMillis()
        LanTraceLog.i(
            TAG,
            "Running rule ${rule.id} onOrBefore=$onOrBeforeDate groupIds=$groupIds " +
                "eligible=${allEligible.size} pending=${pending.size} batch=${batch.size} " +
                "dailyCap=${normalizeDailyCap(rule.dailyCap)} remainingCap=$remainingCap " +
                "force=$force delaySeconds=${rule.delaySeconds}",
        )

        database.clubAutoMessageDao().updateRunProgress(
            id = rule.id,
            runStatus = ClubAutoMessageRunStatus.RUNNING,
            runStartedAt = runStartedAt,
            runTotalCount = batch.size,
            runProcessedCount = 0,
            runSentCount = 0,
            runFailCount = 0,
            updatedAt = runStartedAt,
        )

        var success = 0
        var fail = 0
        var skippedNoChat = 0
        var skippedAlready = 0
        var skippedNoToken = 0
        var processed = 0
        val settings = SettingsStore.getInstance(appContext)
        val rubikaToken = settings.getRubikaToken().trim()
        val telegramToken = settings.getTelegramToken().trim()
        val baleToken = settings.getBaleToken().trim()
        if (rule.sendRubika && rubikaToken.isBlank()) {
            LanTraceLog.w(TAG, "Rule ${rule.id}: Rubika enabled but token is blank — all Rubika sends will be skipped")
        }
        if (rule.sendTelegram && telegramToken.isBlank()) {
            LanTraceLog.w(TAG, "Rule ${rule.id}: Telegram enabled but token is blank — all Telegram sends will be skipped")
        }
        if (rule.sendBale && baleToken.isBlank()) {
            LanTraceLog.w(TAG, "Rule ${rule.id}: Bale enabled but token is blank — all Bale sends will be skipped")
        }
        val rubikaApi = RubikaApi()
        val telegramApi = TelegramApi()
        val baleApi = BaleApi()
        val commLog = CommunicationLog.getInstance(appContext)
        val needsPlaceholders = messageContainsAnyPlaceholder(rule.message)

        for (reg in batch) {
            val phone = PhoneUtils.normalizeForSmsSend(reg.phone).ifBlank { reg.phone }
            if (phone.isBlank() || reg.serial.isBlank()) continue

            val message = if (needsPlaceholders) {
                applyPlaceholders(
                    rule.message,
                    listOf(
                        RegistrationPlaceholder(
                            serial = reg.serial,
                            date = reg.dateKey,
                            km = reg.km,
                        ),
                    ),
                )
            } else {
                rule.message
            }

            if (rule.sendSms) {
                when (sendSmsChannel(rule, phone, reg.serial, message, todayJalali, commLog)) {
                    SendOutcome.SENT -> success++
                    SendOutcome.FAILED -> fail++
                    SendOutcome.SKIPPED_ALREADY -> skippedAlready++
                    SendOutcome.SKIPPED_NO_TOKEN,
                    SendOutcome.SKIPPED_NO_CHAT,
                    -> Unit
                }
                delay(delayMs)
            }
            if (rule.sendRubika) {
                when (
                    sendBotChannel(
                        rule = rule,
                        phone = phone,
                        serial = reg.serial,
                        message = message,
                        channel = ClubAutoMessageChannel.RUBIKA,
                        todayJalali = todayJalali,
                        token = rubikaToken,
                        send = { token, chatId, text -> rubikaApi.sendMessage(token, chatId, text) },
                        resolveChatId = { resolveRubikaChatId(it) },
                    )
                ) {
                    SendOutcome.SENT -> success++
                    SendOutcome.FAILED -> fail++
                    SendOutcome.SKIPPED_ALREADY -> skippedAlready++
                    SendOutcome.SKIPPED_NO_TOKEN -> {
                        skippedNoToken++
                        fail++
                    }
                    SendOutcome.SKIPPED_NO_CHAT -> {
                        skippedNoChat++
                        fail++
                    }
                }
                delay(delayMs)
            }
            if (rule.sendTelegram) {
                when (
                    sendBotChannel(
                        rule = rule,
                        phone = phone,
                        serial = reg.serial,
                        message = message,
                        channel = ClubAutoMessageChannel.TELEGRAM,
                        todayJalali = todayJalali,
                        token = telegramToken,
                        send = { token, chatId, text -> telegramApi.sendMessage(token, chatId, text) },
                        resolveChatId = { resolveTelegramChatId(it) },
                    )
                ) {
                    SendOutcome.SENT -> success++
                    SendOutcome.FAILED -> fail++
                    SendOutcome.SKIPPED_ALREADY -> skippedAlready++
                    SendOutcome.SKIPPED_NO_TOKEN -> {
                        skippedNoToken++
                        fail++
                    }
                    SendOutcome.SKIPPED_NO_CHAT -> {
                        skippedNoChat++
                        fail++
                    }
                }
                delay(delayMs)
            }
            if (rule.sendBale) {
                when (
                    sendBotChannel(
                        rule = rule,
                        phone = phone,
                        serial = reg.serial,
                        message = message,
                        channel = ClubAutoMessageChannel.BALE,
                        todayJalali = todayJalali,
                        token = baleToken,
                        send = { token, chatId, text -> baleApi.sendMessage(token, chatId, text) },
                        resolveChatId = { resolveBaleChatId(it) },
                    )
                ) {
                    SendOutcome.SENT -> success++
                    SendOutcome.FAILED -> fail++
                    SendOutcome.SKIPPED_ALREADY -> skippedAlready++
                    SendOutcome.SKIPPED_NO_TOKEN -> {
                        skippedNoToken++
                        fail++
                    }
                    SendOutcome.SKIPPED_NO_CHAT -> {
                        skippedNoChat++
                        fail++
                    }
                }
                delay(delayMs)
            }

            processed++
            database.clubAutoMessageDao().updateRunProgress(
                id = rule.id,
                runStatus = ClubAutoMessageRunStatus.RUNNING,
                runStartedAt = runStartedAt,
                runTotalCount = batch.size,
                runProcessedCount = processed,
                runSentCount = success,
                runFailCount = fail,
                updatedAt = System.currentTimeMillis(),
            )
        }

        val leftForTomorrow = (pending.size - batch.size).coerceAtLeast(0)
        LanTraceLog.i(
            TAG,
            "Rule ${rule.id} finished success=$success fail=$fail " +
                "skippedAlready=$skippedAlready skippedNoChat=$skippedNoChat skippedNoToken=$skippedNoToken " +
                "batch=${batch.size} leftForTomorrow=$leftForTomorrow",
        )

        val now = System.currentTimeMillis()
        database.clubAutoMessageDao().updateLastRun(
            id = rule.id,
            lastRunAt = now,
            lastRunDateJalali = todayJalali,
            lastSuccessCount = success,
            lastFailCount = fail,
            runStatus = ClubAutoMessageRunStatus.IDLE,
            runStartedAt = runStartedAt,
            runTotalCount = batch.size,
            runProcessedCount = processed,
            runSentCount = success,
            runFailCount = fail,
            updatedAt = now,
        )
        return database.clubAutoMessageDao().findRuleById(rule.id) ?: rule
    }

    private suspend fun sendSmsChannel(
        rule: ClubAutoMessageRuleEntity,
        phone: String,
        serial: String,
        message: String,
        todayJalali: String,
        commLog: CommunicationLog,
    ): SendOutcome {
        if (alreadySentSerial(rule.id, serial, ClubAutoMessageChannel.SMS)) {
            return SendOutcome.SKIPPED_ALREADY
        }
        return try {
            val subscriptionId = SmsOutbound.resolveSubscriptionIdOrThrow(appContext, null)
            val simLabel = SimCardHelper.simLabelForSubscription(appContext, subscriptionId)
            SmsOutbound.send(appContext, subscriptionId, phone, message)
            commLog.logOutgoing(
                phone = phone,
                body = message,
                status = CommunicationLog.STATUS_SENT,
                simLabel = simLabel,
            )
            recordSend(rule.id, phone, serial, ClubAutoMessageChannel.SMS, todayJalali)
            SendOutcome.SENT
        } catch (error: SmsOutboundQuotaExceededException) {
            LanTraceLog.w(TAG, "SMS quota exceeded for $phone serial=$serial rule=${rule.id}")
            SendOutcome.FAILED
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "SMS send failed for $phone serial=$serial rule=${rule.id}", error)
            SendOutcome.FAILED
        }
    }

    private suspend fun sendBotChannel(
        rule: ClubAutoMessageRuleEntity,
        phone: String,
        serial: String,
        message: String,
        channel: String,
        todayJalali: String,
        token: String,
        send: (String, String, String) -> String,
        resolveChatId: (String) -> String?,
    ): SendOutcome {
        if (alreadySentSerial(rule.id, serial, channel)) {
            return SendOutcome.SKIPPED_ALREADY
        }
        if (token.isBlank()) {
            return SendOutcome.SKIPPED_NO_TOKEN
        }
        val chatId = resolveChatId(phone)
        if (chatId == null) {
            LanTraceLog.w(TAG, "$channel skip $phone serial=$serial rule=${rule.id}: no paired chat id")
            return SendOutcome.SKIPPED_NO_CHAT
        }
        return try {
            send(token, chatId, message)
            recordSend(rule.id, phone, serial, channel, todayJalali)
            SendOutcome.SENT
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "$channel send failed for $phone serial=$serial rule=${rule.id}", error)
            SendOutcome.FAILED
        }
    }

    private fun resolveRubikaChatId(phone: String): String? =
        BotChatIdResolver.resolveRubika(database, phone)

    private fun resolveTelegramChatId(phone: String): String? =
        BotChatIdResolver.resolveTelegram(database, phone)

    private fun resolveBaleChatId(phone: String): String? =
        BotChatIdResolver.resolveBale(database, phone)

    private suspend fun alreadySentSerial(
        ruleId: Long,
        serial: String,
        channel: String,
    ): Boolean {
        val key = serial.trim().uppercase()
        if (key.isEmpty()) return false
        return database.clubAutoMessageDao().countSendBySerial(ruleId, key, channel) > 0
    }

    private suspend fun recordSend(
        ruleId: Long,
        phone: String,
        serial: String,
        channel: String,
        todayJalali: String,
    ) {
        val key = serial.trim().uppercase().ifEmpty { "__phone__$phone" }
        database.clubAutoMessageDao().insertSend(
            ClubAutoMessageSendEntity(
                ruleId = ruleId,
                phone = phone,
                serial = key,
                channel = channel,
                sentAt = System.currentTimeMillis(),
                sentDateJalali = todayJalali,
            ),
        )
    }

    private data class EligibleSerial(
        val phone: String,
        val serial: String,
        val dateKey: String,
        val km: Int?,
    )

    private enum class SendOutcome {
        SENT,
        FAILED,
        SKIPPED_ALREADY,
        SKIPPED_NO_TOKEN,
        SKIPPED_NO_CHAT,
    }

    object PreviewChannelStatus {
        const val PENDING = "pending"
        const val ALREADY_SENT = "already_sent"
        const val NO_CHAT = "no_chat"
        const val NO_TOKEN = "no_token"
    }

    data class ClubAutoMessagePreviewChannel(
        val channel: String,
        val status: String,
    )

    data class ClubAutoMessagePreviewItem(
        val phone: String,
        val serial: String,
        val channels: List<ClubAutoMessagePreviewChannel>,
        val willSendAny: Boolean,
    )

    data class ClubAutoMessagePreviewResult(
        val items: List<ClubAutoMessagePreviewItem>,
        val total: Int,
        val pendingCount: Int,
        val blockedCount: Int,
        val alreadyFullySentCount: Int,
        val cutoffDateJalali: String,
        val dailyCap: Int = 200,
        val sentToday: Int = 0,
        val remainingToday: Int = 200,
        val page: Int,
        val limit: Int,
    )

    companion object {
        private const val TAG = "ClubAutoMsg"
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val FAST_CHECK_INTERVAL_MS = 5_000L

        /** Placeholders replaced with the recipient's registration fields at send time. */
        val SERIAL_PLACEHOLDERS = listOf("{سریال}", "{serial}")
        val KM_PLACEHOLDERS = listOf("{کیلومتر}", "{km}")
        val DATE_PLACEHOLDERS = listOf("{تاریخ}", "{date}")
        private const val FIELD_JOIN_SEPARATOR = "، "

        fun normalizeDailyCap(raw: Int): Int = raw.coerceIn(1, 10_000)

        fun messageContainsAnyPlaceholder(message: String): Boolean =
            SERIAL_PLACEHOLDERS.any { message.contains(it) } ||
                KM_PLACEHOLDERS.any { message.contains(it) } ||
                DATE_PLACEHOLDERS.any { message.contains(it) }

        fun messageContainsSerialPlaceholder(message: String): Boolean =
            SERIAL_PLACEHOLDERS.any { message.contains(it) }

        fun applySerialPlaceholder(message: String, serials: List<String>): String {
            val joined = serials
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(FIELD_JOIN_SEPARATOR)
            var text = message
            SERIAL_PLACEHOLDERS.forEach { placeholder ->
                text = text.replace(placeholder, joined)
            }
            return text
        }

        fun applyPlaceholders(
            message: String,
            registrations: List<RegistrationPlaceholder>,
        ): String {
            val serials = registrations.map { it.serial }.filter { it.isNotEmpty() }.distinct()
            val kms = registrations.mapNotNull { it.km }.distinct()
            val dates = registrations.map { it.date }.filter { it.isNotEmpty() }.distinct()
            var text = message
            val serialJoined = serials.joinToString(FIELD_JOIN_SEPARATOR)
            val kmJoined = kms.joinToString(FIELD_JOIN_SEPARATOR)
            val dateJoined = dates.joinToString(FIELD_JOIN_SEPARATOR)
            SERIAL_PLACEHOLDERS.forEach { text = text.replace(it, serialJoined) }
            KM_PLACEHOLDERS.forEach { text = text.replace(it, kmJoined) }
            DATE_PLACEHOLDERS.forEach { text = text.replace(it, dateJoined) }
            return text
        }

        data class RegistrationPlaceholder(
            val serial: String,
            val date: String,
            val km: Int?,
        )

        @Volatile
        private var instance: ClubAutoMessageScheduler? = null

        fun decodeSerialGroupIds(serialGroupIdsJson: String?): List<Long> {
            if (serialGroupIdsJson.isNullOrBlank()) return emptyList()
            return serialGroupIdsJson.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .distinct()
        }

        fun encodeSerialGroupIds(ids: List<Long>?): String? {
            if (ids.isNullOrEmpty()) return null
            return ids.distinct().joinToString(",")
        }

        fun getInstance(context: Context): ClubAutoMessageScheduler {
            return instance ?: synchronized(this) {
                instance ?: ClubAutoMessageScheduler(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}

