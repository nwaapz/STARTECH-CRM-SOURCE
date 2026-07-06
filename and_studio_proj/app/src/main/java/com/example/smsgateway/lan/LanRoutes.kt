package com.example.smsgateway.lan

import android.content.Context
import com.example.smsgateway.CompletionReminderService
import com.example.smsgateway.CommunicationLog
import com.example.smsgateway.JalaliHelper
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.SmsProcessingConfig
import com.example.smsgateway.SmsProcessingMode
import com.example.smsgateway.SerialGenerator
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SmsMessageTemplates
import com.example.smsgateway.SyncEventWriter
import com.example.smsgateway.data.CommunicationMessageEntity
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialGroupEntity
import com.example.smsgateway.data.SerialRegistrationErrorEntity
import com.example.smsgateway.IranLocations
import com.example.smsgateway.IranProvince
import com.example.smsgateway.MassSmsFilterRequest
import com.example.smsgateway.MassSmsQueueService
import com.example.smsgateway.MassSmsRecipientResolver
import com.example.smsgateway.RubikaApi
import com.example.smsgateway.RubikaChatStore
import com.example.smsgateway.RubikaChatItem
import com.example.smsgateway.RubikaPoller
import com.example.smsgateway.TelegramApi
import com.example.smsgateway.TelegramChatStore
import com.example.smsgateway.TelegramChatItem
import com.example.smsgateway.TelegramPoller
import com.example.smsgateway.data.MassSmsAudienceType
import com.example.smsgateway.data.MassSmsCampaignEntity
import com.example.smsgateway.data.MassSmsCampaignStatus
import com.example.smsgateway.data.MassSmsRecipientEntity
import com.example.smsgateway.data.SerialErrorClientSummaryRow
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.text.Charsets

private const val TAG = "Assets"

fun Application.configureLanRoutes(
    appContext: Context,
    settingsStore: SettingsStore,
    database: GatewayDatabase,
) {
    val serialGenerator = SerialGenerator(database)
    val assets = appContext.assets
    LanTraceLog.i("Routes", "Registering LAN CRM routes")

    routing {
        get("/") {
            serveAsset("", assets, call)
        }

        get("/api/health") {
            call.respond(
                HealthResponse(serverTime = System.currentTimeMillis()),
            )
        }

        get("/api/ping") {
            call.respondText(
                text = "CRM OK",
                contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
            )
        }

        route("/api") {
            get("/sync") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val dao = database.serialDao()
                val syncDao = database.syncEventDao()
                val total = withContext(Dispatchers.IO) { dao.countAll() }
                val unused = withContext(Dispatchers.IO) { dao.countUnused() }
                val registered = withContext(Dispatchers.IO) { dao.countRegistered() }
                val eventList = withContext(Dispatchers.IO) { syncDao.getSince(since) }
                val changes = eventList.mapNotNull { event ->
                    parseSyncChange(event.type, event.payload, event.at)
                }
                val serverTime = System.currentTimeMillis()
                call.respond(
                    SyncResponse(
                        serverTime = serverTime,
                        cursor = serverTime,
                        stats = StatsResponse(
                            total = total,
                            unused = unused,
                            registered = registered,
                        ),
                        changes = changes,
                    ),
                )
            }

            post("/serials/batch") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@post
                }
                val body = call.receive<BatchRequestBody>()
                try {
                    val request = SerialGenerator.GenerateRequest(
                        prefix = body.prefix,
                        start = body.start,
                        end = body.end,
                        padWidth = body.padWidth,
                        city = body.city,
                        km = body.km,
                        description = body.description,
                        score = body.score,
                    )
                    val result = withContext(Dispatchers.IO) {
                        serialGenerator.generate(request)
                    }
                    call.respond(
                        BatchResponseBody(
                            inserted = result.inserted,
                            skipped = result.skipped,
                            total = result.total,
                            groupId = result.groupId,
                        ),
                    )
                } catch (e: SerialGenerator.ValidationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "Validation error"))
                } catch (e: SerialGenerator.DuplicateSerialException) {
                    call.respond(HttpStatusCode.Conflict, e.toDuplicateResponse())
                }
            }

            get("/serial-groups") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val items = withContext(Dispatchers.IO) {
                    val groupDao = database.serialGroupDao()
                    groupDao.listAll().map { group ->
                        group.toResponse(groupDao, serialGenerator)
                    }
                }
                call.respond(SerialGroupListResponse(items = items))
            }

            put("/serial-groups/{id}") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@put
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid group id"))
                    return@put
                }
                val body = call.receive<UpdateSerialGroupRequestBody>()
                try {
                    val updated = withContext(Dispatchers.IO) {
                        serialGenerator.updateGroup(
                            id,
                            SerialGenerator.UpdateGroupRequest(
                                description = body.description,
                                score = body.score,
                            ),
                        )
                    }
                    call.respond(
                        updated.toResponse(database.serialGroupDao(), serialGenerator),
                    )
                } catch (e: SerialGenerator.ValidationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "Validation error"))
                }
            }

            post("/serial-groups/{id}/extend") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@post
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid group id"))
                    return@post
                }
                val body = call.receive<ExtendSerialGroupRangeRequestBody>()
                try {
                    val result = withContext(Dispatchers.IO) {
                        serialGenerator.extendGroupRange(
                            id,
                            SerialGenerator.ExtendRangeRequest(
                                addBelow = body.addBelow,
                                addAbove = body.addAbove,
                                newStart = body.newStart,
                                newEnd = body.newEnd,
                            ),
                        )
                    }
                    val group = withContext(Dispatchers.IO) {
                        database.serialGroupDao().findById(id)
                    }
                    if (group == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Serial group not found"))
                        return@post
                    }
                    call.respond(
                        ExtendSerialGroupRangeResponseBody(
                            inserted = result.inserted,
                            skipped = result.skipped,
                            startNumber = result.startNumber,
                            endNumber = result.endNumber,
                            group = group.toResponse(database.serialGroupDao(), serialGenerator),
                        ),
                    )
                } catch (e: SerialGenerator.ValidationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "Validation error"))
                } catch (e: SerialGenerator.DuplicateSerialException) {
                    call.respond(HttpStatusCode.Conflict, e.toDuplicateResponse())
                }
            }

            delete("/serial-groups/{id}") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@delete
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid group id"))
                    return@delete
                }
                val deleted = withContext(Dispatchers.IO) {
                    serialGenerator.deleteGroup(id)
                }
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Serial group not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/serials") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val registeredParam = call.request.queryParameters["registered"]
                val defaultEndToday = call.request.queryParameters["defaultEndToday"] == "true"
                val fromDate = JalaliHelper.normalizeFromDateParam(
                    call.request.queryParameters["from"]?.trim().orEmpty(),
                )
                var toDate = JalaliHelper.normalizeToDateParam(
                    call.request.queryParameters["to"]?.trim().orEmpty(),
                )
                if (toDate.isEmpty() && defaultEndToday) {
                    toDate = JalaliHelper.normalizeToDateParam(JalaliHelper.todayJalaliDate())
                }
                val search = call.request.queryParameters["search"]?.trim().orEmpty()
                val phoneNorm = if (PhoneUtils.isPhoneLikeInput(search)) {
                    PhoneUtils.normalizeSearchPrefix(search)
                } else {
                    ""
                }
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
                val offset = (page - 1) * limit

                try {
                    val (items, total) = withContext(Dispatchers.IO) {
                        val dao = database.serialDao()
                        when (registeredParam) {
                            "true" -> {
                                val rows = dao.listRegisteredFiltered(fromDate, toDate, search, phoneNorm, limit, offset)
                                rows to dao.countRegisteredFiltered(fromDate, toDate, search, phoneNorm)
                            }
                            "false" -> {
                                val rows = dao.listUnusedFiltered(search, limit, offset)
                                rows to dao.countUnusedFiltered(search)
                            }
                            else -> {
                                val rows = dao.listAllFiltered(fromDate, toDate, search, phoneNorm, limit, offset)
                                rows to dao.countAllFiltered(fromDate, toDate, search, phoneNorm)
                            }
                        }
                    }

                    LanTraceLog.d(
                        "Serials",
                        "GET /serials registered=$registeredParam from=$fromDate to=$toDate " +
                            "defaultEndToday=$defaultEndToday search=$search phoneNorm=$phoneNorm " +
                            "-> total=$total items=${items.size}",
                    )

                    call.respond(
                        SerialListResponse(
                            items = items.map { it.toResponse(settingsStore.getSmsProcessingConfig()) },
                            total = total,
                            page = page,
                            limit = limit,
                        ),
                    )
                } catch (error: Exception) {
                    LanTraceLog.e("Serials", "GET /serials failed", error)
                    throw error
                }
            }

            post("/serials/{id}/send-completion-reminder") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@post
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid serial id"))
                    return@post
                }

                val updated = withContext(Dispatchers.IO) {
                    val dao = database.serialDao()
                    val row = dao.findById(id)
                        ?: return@withContext ReminderResult.NotFound
                    if (row.phone.isNullOrBlank()) {
                        return@withContext ReminderResult.NoPhone
                    }
                    if (!row.needsCompletionForDisplay(settingsStore.getSmsProcessingConfig())) {
                        return@withContext ReminderResult.AlreadyComplete
                    }
                    if (row.completionReminderSent()) {
                        return@withContext ReminderResult.AlreadySent
                    }
                    val config = settingsStore.getSmsProcessingConfig()
                    val templates = SmsMessageTemplates.getInstance(settingsStore)
                    try {
                        CompletionReminderService.sendReminder(appContext, row, config, templates)
                    } catch (error: Exception) {
                        LanTraceLog.e(TAG, "Completion reminder SMS failed for serial ${row.serial}", error)
                        return@withContext ReminderResult.SendFailed(
                            error.message ?: "SMS send failed",
                        )
                    }
                    val sentAt = System.currentTimeMillis()
                    dao.markCompletionReminderSent(row.serial, sentAt)
                    val refreshed = dao.findById(id)
                        ?: return@withContext ReminderResult.NotFound
                    ReminderResult.Success(refreshed)
                }

                when (updated) {
                    ReminderResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Serial not found"))
                    ReminderResult.NoPhone ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Serial has no phone number"))
                    ReminderResult.AlreadyComplete ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Registration is already complete"))
                    ReminderResult.AlreadySent ->
                        call.respond(HttpStatusCode.Conflict, ErrorResponse(error = "Completion reminder already sent"))
                    is ReminderResult.SendFailed ->
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse(error = updated.message))
                    is ReminderResult.Success ->
                        call.respond(updated.row.toResponse(settingsStore.getSmsProcessingConfig()))
                }
            }

            get("/serial-errors/clients") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 9
                val offset = (page - 1) * limit

                val (items, total) = withContext(Dispatchers.IO) {
                    val dao = database.serialRegistrationErrorDao()
                    dao.listClientSummaries(offset, limit) to dao.countDistinctClients()
                }

                call.respond(
                    SerialErrorClientListResponse(
                        items = items.map { it.toClientSummaryResponse() },
                        total = total,
                        page = page,
                        limit = limit,
                    ),
                )
            }

            get("/serial-errors") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val rawPhone = call.request.queryParameters["phone"]?.trim().orEmpty()
                if (rawPhone.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "phone is required"))
                    return@get
                }
                val phone = PhoneUtils.formatForDisplay(rawPhone) ?: rawPhone

                val items = withContext(Dispatchers.IO) {
                    database.serialRegistrationErrorDao().listByPhone(phone)
                }

                call.respond(
                    SerialErrorListResponse(
                        phone = phone,
                        items = items.map { it.toSerialErrorResponse() },
                    ),
                )
            }

            get("/communications/thread") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val rawPhone = call.request.queryParameters["phone"]?.trim().orEmpty()
                if (rawPhone.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "phone is required"))
                    return@get
                }
                val phone = PhoneUtils.formatForDisplay(rawPhone) ?: rawPhone
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

                val (items, hasMore) = withContext(Dispatchers.IO) {
                    val dao = database.communicationMessageDao()
                    val batch = if (before != null) {
                        dao.getThreadBefore(phone, before, limit)
                    } else {
                        dao.getThreadLatest(phone, limit)
                    }
                    val ordered = batch.asReversed()
                    val oldestTimestamp = ordered.firstOrNull()?.timestamp
                    val more = if (oldestTimestamp != null) {
                        dao.countThreadBefore(phone, oldestTimestamp) > 0
                    } else {
                        false
                    }
                    ordered to more
                }

                call.respond(
                    CommunicationThreadResponse(
                        phone = phone,
                        items = items.map { it.toCommunicationResponse() },
                        hasMore = hasMore,
                    ),
                )
            }

            get("/communications") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                val offset = (page - 1) * limit
                val directionParam = call.request.queryParameters["direction"]?.trim().orEmpty()
                val directionFilter = when (directionParam) {
                    "" -> ""
                    "incoming", "outgoing" -> directionParam
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid direction filter"))
                        return@get
                    }
                }
                val phonePrefix = PhoneUtils.normalizeSearchPrefix(
                    call.request.queryParameters["phone"]?.trim().orEmpty(),
                )

                try {
                    withContext(Dispatchers.IO) {
                        if (database.communicationMessageDao().countAll() == 0) {
                            CommunicationLog.getInstance(appContext).backfillIfEmpty()
                        }
                    }

                    val (items, total) = withContext(Dispatchers.IO) {
                        val dao = database.communicationMessageDao()
                        if (directionFilter.isEmpty() && phonePrefix.isEmpty()) {
                            dao.getPage(offset, limit) to dao.countAll()
                        } else {
                            dao.getPageFiltered(directionFilter, phonePrefix, offset, limit) to
                                dao.countFiltered(directionFilter, phonePrefix)
                        }
                    }

                    LanTraceLog.d(
                        "Comms",
                        "GET /communications page=$page limit=$limit direction=$directionFilter " +
                            "phone=$phonePrefix -> total=$total items=${items.size}",
                    )

                    call.respond(
                        CommunicationListResponse(
                            items = items.map { it.toCommunicationResponse() },
                            total = total,
                            page = page,
                            limit = limit,
                        ),
                    )
                } catch (error: Exception) {
                    LanTraceLog.e("Comms", "GET /communications failed", error)
                    throw error
                }
            }

            get("/settings/sms-processing") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                call.respond(settingsStore.getSmsProcessingConfig().toResponse())
            }

            put("/settings/sms-processing") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@put
                }
                val body = call.receive<SmsProcessingSettingsResponse>()
                val config = body.toConfig()
                val error = config.validate()
                if (error != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = error))
                    return@put
                }
                settingsStore.setSmsProcessingConfig(config)
                call.respond(settingsStore.getSmsProcessingConfig().toResponse())
            }

            get("/settings/sms-messages") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                val templates = SmsMessageTemplates.getInstance(settingsStore)
                call.respond(templates.toApiResponse())
            }

            put("/settings/sms-messages") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@put
                }
                val body = call.receive<SmsMessagesSettingsResponse>()
                val templates = SmsMessageTemplates.getInstance(settingsStore)
                try {
                    templates.applyFromApi(body.messages)
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = error.message ?: "Invalid message templates"))
                    return@put
                }
                call.respond(templates.toApiResponse())
            }

            route("/rubika") {
                get("/status") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    call.respond(buildRubikaStatus(settingsStore, appContext))
                }

                post("/connect") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    val body = call.receive<RubikaConnectRequestBody>()
                    val token = body.token.trim()
                    if (token.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "token is required"))
                        return@post
                    }
                    try {
                        val info = withContext(Dispatchers.IO) {
                            RubikaApi.getInstance().getMe(token)
                        }
                        settingsStore.setRubikaBotActive(info, token)
                        RubikaPoller.getInstance(appContext).start()
                        call.respond(buildRubikaStatus(settingsStore, appContext))
                    } catch (error: Exception) {
                        LanTraceLog.e("Rubika", "POST /rubika/connect failed", error)
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Connect failed"),
                        )
                    }
                }

                post("/disconnect") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    RubikaPoller.getInstance(appContext).stop()
                    settingsStore.clearRubikaBot()
                    call.respond(buildRubikaStatus(settingsStore, appContext))
                }

                get("/messages") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
                    val chatStore = RubikaChatStore.getInstance(appContext)
                    val items = if (since > 0L) {
                        chatStore.getMessagesSince(since, limit)
                    } else {
                        chatStore.getMessages(limit)
                    }
                    val total = withContext(Dispatchers.IO) {
                        database.rubikaMessageDao().countAll()
                    }
                    call.respond(
                        RubikaMessageListResponse(
                            items = items.map { it.toRubikaResponse() },
                            total = total,
                        ),
                    )
                }
            }

            get("/settings/rubika") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                call.respond(
                    RubikaSettingsResponse(
                        serialEnabled = settingsStore.isRubikaSerialEnabled(),
                    ),
                )
            }

            put("/settings/rubika") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@put
                }
                val body = call.receive<RubikaSettingsResponse>()
                settingsStore.setRubikaSerialEnabled(body.serialEnabled)
                call.respond(
                    RubikaSettingsResponse(
                        serialEnabled = settingsStore.isRubikaSerialEnabled(),
                    ),
                )
            }

            route("/telegram") {
                get("/status") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    call.respond(buildTelegramStatus(settingsStore, appContext))
                }

                post("/connect") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    val body = call.receive<TelegramConnectRequestBody>()
                    val token = body.token.trim()
                    if (token.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "token is required"))
                        return@post
                    }
                    try {
                        val info = withContext(Dispatchers.IO) {
                            TelegramApi.getInstance().getMe(token)
                        }
                        settingsStore.setTelegramBotActive(info, token)
                        TelegramPoller.getInstance(appContext).start()
                        call.respond(buildTelegramStatus(settingsStore, appContext))
                    } catch (error: Exception) {
                        LanTraceLog.e("Telegram", "POST /telegram/connect failed", error)
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Connect failed"),
                        )
                    }
                }

                post("/disconnect") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    TelegramPoller.getInstance(appContext).stop()
                    settingsStore.clearTelegramBot()
                    call.respond(buildTelegramStatus(settingsStore, appContext))
                }

                get("/messages") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
                    val chatStore = TelegramChatStore.getInstance(appContext)
                    val items = if (since > 0L) {
                        chatStore.getMessagesSince(since, limit)
                    } else {
                        chatStore.getMessages(limit)
                    }
                    val total = withContext(Dispatchers.IO) {
                        database.telegramMessageDao().countAll()
                    }
                    call.respond(
                        TelegramMessageListResponse(
                            items = items.map { it.toTelegramResponse() },
                            total = total,
                        ),
                    )
                }
            }

            get("/settings/telegram") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@get
                }
                call.respond(
                    TelegramSettingsResponse(
                        serialEnabled = settingsStore.isTelegramSerialEnabled(),
                    ),
                )
            }

            put("/settings/telegram") {
                if (!LanAuth.isAuthorized(call, settingsStore)) {
                    LanAuth.unauthorized(call)
                    return@put
                }
                val body = call.receive<TelegramSettingsResponse>()
                settingsStore.setTelegramSerialEnabled(body.serialEnabled)
                call.respond(
                    TelegramSettingsResponse(
                        serialEnabled = settingsStore.isTelegramSerialEnabled(),
                    ),
                )
            }

            route("/mass-sms") {
                get("/provinces") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    val provinces = withContext(Dispatchers.IO) {
                        IranLocations.load(appContext)
                    }
                    call.respond(
                        IranProvinceListResponse(
                            items = provinces.map {
                                IranProvinceResponse(
                                    id = it.id,
                                    name = it.name,
                                    cities = it.cities,
                                )
                            },
                        ),
                    )
                }

                post("/preview") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    val body = call.receive<MassSmsPreviewRequest>()
                    val validationError = validateMassSmsFilter(body.audienceType, message = null)
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = validationError))
                        return@post
                    }
                    val groupValidation = withContext(Dispatchers.IO) {
                        validateMassSmsSerialGroups(body.serialGroupIds, database.serialGroupDao())
                    }
                    if (groupValidation != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = groupValidation))
                        return@post
                    }
                    val dateValidation = validateMassSmsRegistrationDates(
                        body.registrationFromDate,
                        body.registrationToDate,
                    )
                    if (dateValidation != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = dateValidation))
                        return@post
                    }
                    val filter = body.toFilter()
                    val phones = withContext(Dispatchers.IO) {
                        MassSmsRecipientResolver.getInstance(appContext).resolvePhones(filter)
                    }
                    call.respond(
                        MassSmsPreviewResponse(
                            count = phones.size,
                            samplePhones = phones.take(5),
                        ),
                    )
                }

                get("/campaigns") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    val response = withContext(Dispatchers.IO) {
                        val dao = database.massSmsCampaignDao()
                        val campaigns = dao.listCampaigns()
                        val provinces = IranLocations.load(appContext)
                        val queued = campaigns.filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        val groupDao = database.serialGroupDao()
                        MassSmsCampaignListResponse(
                            items = campaigns.map { campaign ->
                                campaign.toResponse(provinces, queued, groupDao, serialGenerator)
                            },
                        )
                    }
                    call.respond(response)
                }

                get("/campaigns/{id}") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@get
                    }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@get
                    }
                    val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                    val offset = (page - 1) * limit

                    val detail = withContext(Dispatchers.IO) {
                        val dao = database.massSmsCampaignDao()
                        val campaign = dao.findCampaignById(id) ?: return@withContext null
                        val provinces = IranLocations.load(appContext)
                        val queued = dao.listCampaigns()
                            .filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        val recipients = dao.listRecipients(id, limit, offset)
                        val total = dao.countRecipients(id)
                        val groupDao = database.serialGroupDao()
                        MassSmsCampaignDetailResponse(
                            campaign = campaign.toResponse(provinces, queued, groupDao, serialGenerator),
                            recipients = recipients.map { it.toRecipientResponse() },
                            total = total,
                            page = page,
                            limit = limit,
                        )
                    }

                    if (detail == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                    } else {
                        call.respond(detail)
                    }
                }

                post("/campaigns") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    val body = call.receive<MassSmsCreateRequest>()
                    val validationError = validateMassSmsFilter(body.audienceType, body.message)
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = validationError))
                        return@post
                    }
                    val groupValidation = withContext(Dispatchers.IO) {
                        validateMassSmsSerialGroups(body.serialGroupIds, database.serialGroupDao())
                    }
                    if (groupValidation != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = groupValidation))
                        return@post
                    }
                    val dateValidation = validateMassSmsRegistrationDates(
                        body.registrationFromDate,
                        body.registrationToDate,
                    )
                    if (dateValidation != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = dateValidation))
                        return@post
                    }
                    try {
                        val response = withContext(Dispatchers.IO) {
                            val campaign = MassSmsQueueService.getInstance(appContext).createCampaign(
                                message = body.message.trim(),
                                delaySeconds = body.delaySeconds.coerceAtLeast(1),
                                filter = body.toFilter(),
                            )
                            val provinces = IranLocations.load(appContext)
                            val queued = database.massSmsCampaignDao().listCampaigns()
                                .filter { it.status == MassSmsCampaignStatus.QUEUED }
                                .sortedBy { it.queueOrder }
                            campaign.toResponse(
                                provinces,
                                queued,
                                database.serialGroupDao(),
                                serialGenerator,
                            )
                        }
                        call.respond(response)
                    } catch (error: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = error.message ?: "Invalid request"))
                    }
                }

                post("/campaigns/{id}/pause") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@post
                    }
                    val response = withContext(Dispatchers.IO) {
                        val updated = MassSmsQueueService.getInstance(appContext).pauseCampaign(id)
                            ?: return@withContext null
                        val provinces = IranLocations.load(appContext)
                        val queued = database.massSmsCampaignDao().listCampaigns()
                            .filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        updated.toResponse(provinces, queued, database.serialGroupDao(), serialGenerator)
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                        return@post
                    }
                    call.respond(response)
                }

                post("/campaigns/{id}/resume") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@post
                    }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@post
                    }
                    val response = withContext(Dispatchers.IO) {
                        val updated = MassSmsQueueService.getInstance(appContext).resumeCampaign(id)
                            ?: return@withContext null
                        val provinces = IranLocations.load(appContext)
                        val queued = database.massSmsCampaignDao().listCampaigns()
                            .filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        updated.toResponse(provinces, queued, database.serialGroupDao(), serialGenerator)
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                        return@post
                    }
                    call.respond(response)
                }

                delete("/campaigns/{id}") {
                    if (!LanAuth.isAuthorized(call, settingsStore)) {
                        LanAuth.unauthorized(call)
                        return@delete
                    }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@delete
                    }
                    val deleted = withContext(Dispatchers.IO) {
                        MassSmsQueueService.getInstance(appContext).deleteCampaign(id)
                    }
                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }

        get("/{path...}") {
            val segments = call.parameters.getAll("path") ?: emptyList()
            val relativePath = segments.joinToString("/")
            serveAsset(relativePath, assets, call)
        }
    }
}

private fun CommunicationMessageEntity.toCommunicationResponse() = CommunicationMessageResponse(
    id = id,
    phone = PhoneUtils.formatForDisplay(phone) ?: phone,
    direction = direction,
    body = body,
    timestamp = timestamp,
    status = status,
    simLabel = simLabel,
)

private fun SerialRegistrationErrorEntity.toSerialErrorResponse() = SerialRegistrationErrorResponse(
    phone = PhoneUtils.formatForDisplay(phone) ?: phone,
    serial = serial,
    reason = reason,
    at = at,
)

private fun SerialErrorClientSummaryRow.toClientSummaryResponse() = SerialErrorClientSummaryResponse(
    phone = PhoneUtils.formatForDisplay(phone) ?: phone,
    latestAt = latestAt,
    latestSerial = latestSerial,
    latestReason = latestReason,
    attemptCount = attemptCount,
)

private fun parseSyncChange(type: String, payload: String, at: Long): SyncChangeResponse? {
    return try {
        val json = JSONObject(payload)
        when (type) {
            SyncEventWriter.TYPE_REGISTERED,
            SyncEventWriter.TYPE_REGISTRATION_UPDATED -> SyncChangeResponse(
                type = type,
                serial = json.optString("serial").takeIf { it.isNotEmpty() },
                phone = PhoneUtils.formatForDisplay(json.optString("phone").takeIf { it.isNotEmpty() }),
                date = json.optString("date").takeIf { it.isNotEmpty() },
                city = json.optString("city").takeIf { it.isNotEmpty() },
                km = if (json.has("km") && !json.isNull("km")) json.getInt("km") else null,
                at = json.optLong("at", at),
            )
            SyncEventWriter.TYPE_SERIALS_ADDED -> SyncChangeResponse(
                type = type,
                inserted = json.optInt("inserted"),
                skipped = json.optInt("skipped"),
                total = json.optInt("total"),
                at = json.optLong("at", at),
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun SerialGenerator.DuplicateSerialException.toDuplicateResponse() = DuplicateSerialErrorResponse(
    error = message ?: "تولید انجام نشد — سریال‌های تکراری یافت شد.",
    duplicateCount = duplicateCount,
    duplicateRanges = duplicateRanges.map { range ->
        DuplicateRangeResponse(
            rangeLabel = range.rangeLabel,
            count = range.count,
        )
    },
)

private suspend fun SerialGroupEntity.toResponse(
    groupDao: com.example.smsgateway.data.SerialGroupDao,
    serialGenerator: SerialGenerator,
): SerialGroupResponse {
    val total = groupDao.countByGroupId(id)
    val used = groupDao.countUsedByGroupId(id)
    val unused = total - used
    val salePercentage = if (total > 0) {
        kotlin.math.round(used * 1000.0 / total) / 10.0
    } else {
        0.0
    }
    return SerialGroupResponse(
        id = id,
        prefix = prefix,
        startNumber = startNumber,
        endNumber = endNumber,
        padWidth = padWidth,
        rangeLabel = serialGenerator.buildRangeLabel(prefix, startNumber, endNumber, padWidth),
        description = description,
        score = score,
        total = total,
        used = used,
        unused = unused,
        salePercentage = salePercentage,
        createdAt = createdAt,
    )
}

private fun SerialEntity.toResponse(config: SmsProcessingConfig) = SerialRowResponse(
    id = id,
    serial = serial,
    phone = PhoneUtils.formatForDisplay(phone),
    date = date,
    city = city,
    km = km,
    needsCompletion = needsCompletionForDisplay(config),
    completionReminderSent = completionReminderSent(),
    completionReminderSentAt = completionReminderSentAt,
)

private fun SerialEntity.needsCompletionForDisplay(config: SmsProcessingConfig): Boolean {
    if (phone.isNullOrBlank()) return false
    if (!registrationFieldsComplete) return true
    if (config.mode == SmsProcessingMode.SERIAL_ONLY) return false
    return hasMissingRegistrationFields()
}

private sealed class ReminderResult {
    data object NotFound : ReminderResult()
    data object NoPhone : ReminderResult()
    data object AlreadyComplete : ReminderResult()
    data object AlreadySent : ReminderResult()
    data class SendFailed(val message: String) : ReminderResult()
    data class Success(val row: SerialEntity) : ReminderResult()
}

private suspend fun serveAsset(
    relativePath: String,
    assets: android.content.res.AssetManager,
    call: io.ktor.server.application.ApplicationCall,
) {
    val normalized = relativePath.trim('/')
    val assetPath = when {
        normalized.isEmpty() -> "crm/index.html"
        else -> "crm/$normalized"
    }
    val isAssetFile = normalized.startsWith("assets/") || normalized.endsWith(".js") ||
        normalized.endsWith(".css") || normalized.endsWith(".ico")
    LanTraceLog.d(TAG, "serveAsset request=/$normalized -> assetPath=$assetPath")
    val loaded = loadAsset(assets, assetPath)
        ?: if (isAssetFile) {
            LanTraceLog.e(TAG, "Missing asset: $assetPath (requested: /$normalized)")
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Asset not found: $normalized"))
            return
        } else {
            loadAsset(assets, "crm/index.html")
        }
        ?: run {
            LanTraceLog.e(TAG, "CRM assets missing — crm/index.html not found in APK")
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(error = "CRM assets missing. Rebuild crm-web and APK."),
            )
            return
        }
    val (bytes, contentType) = loaded
    LanTraceLog.d(TAG, "Serving $assetPath (${bytes.size} bytes, $contentType)")
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondBytes(bytes, contentType)
}

private fun loadAsset(
    assets: android.content.res.AssetManager,
    path: String,
): Pair<ByteArray, ContentType>? {
    return try {
        val bytes = assets.open(path).use { it.readBytes() }
        bytes to contentTypeForPath(path)
    } catch (e: Exception) {
        LanTraceLog.w(TAG, "Could not load asset $path: ${e.message}")
        null
    }
}

private fun contentTypeForPath(path: String): ContentType {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".html") -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
        lower.endsWith(".js") -> ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
        lower.endsWith(".css") -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
        lower.endsWith(".json") -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
        lower.endsWith(".svg") -> ContentType.Image.SVG
        lower.endsWith(".png") -> ContentType.Image.PNG
        lower.endsWith(".ico") -> ContentType.Image.Any
        lower.endsWith(".woff2") -> ContentType("font", "woff2")
        else -> ContentType.Application.OctetStream
    }
}

private fun validateMassSmsFilter(audienceType: String, message: String?): String? {
    when (audienceType) {
        MassSmsAudienceType.REGISTERED,
        MassSmsAudienceType.ERROR,
        MassSmsAudienceType.ALL,
        -> Unit
        else -> return "نوع مخاطب نامعتبر است"
    }
    if (message != null && message.trim().isEmpty()) {
        return "متن پیام الزامی است"
    }
    return null
}

private suspend fun validateMassSmsSerialGroups(
    serialGroupIds: List<Long>?,
    groupDao: com.example.smsgateway.data.SerialGroupDao,
): String? {
    if (serialGroupIds == null) return null
    if (serialGroupIds.isEmpty()) return "حداقل یک گروه سریال انتخاب کنید"
    for (id in serialGroupIds) {
        if (groupDao.findById(id) == null) {
            return "گروه سریال نامعتبر است"
        }
    }
    return null
}

private val massSmsJalaliDatePattern = Regex("^\\d{4}/\\d{2}/\\d{2}$")

private fun validateMassSmsRegistrationDates(from: String?, to: String?): String? {
    val fromTrim = from?.trim().orEmpty()
    val toTrim = to?.trim().orEmpty()
    if (fromTrim.isEmpty() && toTrim.isEmpty()) return null
    if (fromTrim.isNotEmpty() && !massSmsJalaliDatePattern.matches(fromTrim)) {
        return "تاریخ شروع ثبت‌نام نامعتبر است"
    }
    if (toTrim.isNotEmpty() && !massSmsJalaliDatePattern.matches(toTrim)) {
        return "تاریخ پایان ثبت‌نام نامعتبر است"
    }
    if (fromTrim.isNotEmpty() && toTrim.isNotEmpty()) {
        val fromNorm = JalaliHelper.normalizeFromDateParam(fromTrim).substring(0, 10)
        val toNorm = JalaliHelper.normalizeToDateParam(toTrim).substring(0, 10)
        if (fromNorm > toNorm) {
            return "تاریخ شروع نمی‌تواند بعد از تاریخ پایان باشد"
        }
    }
    return null
}

private fun decodeSerialGroupIds(serialGroupIdsJson: String?): List<Long>? {
    if (serialGroupIdsJson.isNullOrBlank()) return null
    return serialGroupIdsJson.split(",")
        .mapNotNull { it.trim().toLongOrNull() }
        .takeIf { it.isNotEmpty() }
}

private fun MassSmsPreviewRequest.toFilter() = MassSmsFilterRequest(
    audienceType = audienceType,
    provinceId = provinceId?.takeIf { it.isNotBlank() },
    cityName = cityName?.takeIf { it.isNotBlank() },
    serialGroupIds = serialGroupIds?.takeIf { it.isNotEmpty() },
    registrationFromDate = registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
    registrationToDate = registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
)

private fun MassSmsCreateRequest.toFilter() = MassSmsFilterRequest(
    audienceType = audienceType,
    provinceId = provinceId?.takeIf { it.isNotBlank() },
    cityName = cityName?.takeIf { it.isNotBlank() },
    serialGroupIds = serialGroupIds?.takeIf { it.isNotEmpty() },
    registrationFromDate = registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
    registrationToDate = registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
)

private suspend fun MassSmsCampaignEntity.toResponse(
    provinces: List<IranProvince>,
    queuedCampaigns: List<MassSmsCampaignEntity>,
    groupDao: com.example.smsgateway.data.SerialGroupDao,
    serialGenerator: SerialGenerator,
): MassSmsCampaignResponse {
    val queuePosition = if (status == MassSmsCampaignStatus.QUEUED) {
        val index = queuedCampaigns.indexOfFirst { it.id == id }
        if (index >= 0) index + 1 else null
    } else {
        null
    }
    val ids = decodeSerialGroupIds(serialGroupIdsJson)
    val labels = if (ids.isNullOrEmpty()) {
        null
    } else {
        ids.mapNotNull { groupId ->
            groupDao.findById(groupId)?.let { group ->
                serialGenerator.buildRangeLabel(
                    group.prefix,
                    group.startNumber,
                    group.endNumber,
                    group.padWidth,
                )
            }
        }.takeIf { it.isNotEmpty() }
    }
    return MassSmsCampaignResponse(
        id = id,
        message = message,
        delaySeconds = delaySeconds,
        audienceType = audienceType,
        provinceId = provinceId,
        provinceName = IranLocations.provinceName(provinceId, provinces),
        cityName = cityName,
        serialGroupIds = ids,
        serialGroupLabels = labels,
        registrationFromDate = registrationFromDate,
        registrationToDate = registrationToDate,
        status = status,
        totalCount = totalCount,
        sentCount = sentCount,
        failedCount = failedCount,
        pendingCount = (totalCount - sentCount - failedCount).coerceAtLeast(0),
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
        queueOrder = queueOrder,
        queuePosition = queuePosition,
    )
}

private fun MassSmsRecipientEntity.toRecipientResponse() = MassSmsRecipientResponse(
    id = id,
    phone = PhoneUtils.formatForDisplay(phone) ?: phone,
    status = status,
    sentAt = sentAt,
    errorMessage = errorMessage,
)

private fun buildRubikaStatus(settingsStore: SettingsStore, appContext: Context): RubikaStatusResponse {
    val botInfo = settingsStore.getRubikaBotInfo()
    return RubikaStatusResponse(
        active = settingsStore.isRubikaBotActive(),
        botTitle = botInfo?.title?.takeIf { it.isNotBlank() },
        botUsername = botInfo?.username?.takeIf { it.isNotBlank() },
        serialEnabled = settingsStore.isRubikaSerialEnabled(),
        polling = RubikaPoller.getInstance(appContext).isPolling(),
    )
}

private fun RubikaChatItem.toRubikaResponse() = RubikaMessageResponse(
    messageId = messageId,
    chatId = chatId,
    senderId = senderId,
    text = text,
    timestamp = timestamp,
    direction = direction,
    phoneNumber = phoneNumber,
    senderName = senderName,
)

private fun buildTelegramStatus(settingsStore: SettingsStore, appContext: Context): TelegramStatusResponse {
    val botInfo = settingsStore.getTelegramBotInfo()
    return TelegramStatusResponse(
        active = settingsStore.isTelegramBotActive(),
        botTitle = botInfo?.title?.takeIf { it.isNotBlank() },
        botUsername = botInfo?.username?.takeIf { it.isNotBlank() },
        serialEnabled = settingsStore.isTelegramSerialEnabled(),
        polling = TelegramPoller.getInstance(appContext).isPolling(),
    )
}

private fun TelegramChatItem.toTelegramResponse() = TelegramMessageResponse(
    messageId = messageId,
    chatId = chatId,
    senderId = senderId,
    text = text,
    timestamp = timestamp,
    direction = direction,
    phoneNumber = phoneNumber,
    senderName = senderName,
)
