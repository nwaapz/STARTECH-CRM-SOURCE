package com.example.smsgateway.lan

import android.content.Context
import com.example.smsgateway.BotChatIdResolver
import com.example.smsgateway.BotMenuSettings
import com.example.smsgateway.ClubAutoMessageScheduler
import com.example.smsgateway.CompletionReminderService
import com.example.smsgateway.CommunicationLog
import com.example.smsgateway.IranLocations
import com.example.smsgateway.IranProvince
import com.example.smsgateway.JalaliHelper
import com.example.smsgateway.MassSmsFilterRequest
import com.example.smsgateway.MassSmsQueueService
import com.example.smsgateway.MassSmsRecipientResolver
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.ReferralSettings
import com.example.smsgateway.RegistrationSource
import com.example.smsgateway.RegistrationFieldRules
import com.example.smsgateway.RubikaApi
import com.example.smsgateway.RubikaChatItem
import com.example.smsgateway.RubikaChatStore
import com.example.smsgateway.RubikaPoller
import com.example.smsgateway.SerialNormalizer
import com.example.smsgateway.SerialGenerator
import com.example.smsgateway.SerialGroupCategory
import com.example.smsgateway.SerialGroupGenerationMode
import com.example.smsgateway.SerialGroupWarranty
import com.example.smsgateway.SerialPhotoVerificationService
import com.example.smsgateway.SerialStateCsv
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SimCardHelper
import com.example.smsgateway.SimQuotaRouter
import com.example.smsgateway.SmsMessageTemplates
import com.example.smsgateway.SmsOutbound
import com.example.smsgateway.SmsOutboundQuotaExceededException
import com.example.smsgateway.SmsProcessingConfig
import com.example.smsgateway.SmsProcessingMode
import com.example.smsgateway.SyncEventWriter
import com.example.smsgateway.BaleApi
import com.example.smsgateway.BaleChatItem
import com.example.smsgateway.BaleChatStore
import com.example.smsgateway.BalePoller
import com.example.smsgateway.TelegramApi
import com.example.smsgateway.TelegramChatItem
import com.example.smsgateway.TelegramChatStore
import com.example.smsgateway.TelegramPoller
import com.example.smsgateway.WhatsAppCampaignService
import com.example.smsgateway.WhatsAppConfig
import com.example.smsgateway.data.BotMediaAssetEntity
import com.example.smsgateway.data.BotMediaKind
import com.example.smsgateway.data.ClubAutoMessageAutoSource
import com.example.smsgateway.data.ClubAutoMessageRuleEntity
import com.example.smsgateway.data.CommunicationMessageEntity
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.MassSmsAudienceType
import com.example.smsgateway.data.MassSmsCampaignEntity
import com.example.smsgateway.data.MassSmsCampaignStatus
import com.example.smsgateway.data.MassSmsChannel
import com.example.smsgateway.data.MassSmsRecipientEntity
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialErrorClientSummaryRow
import com.example.smsgateway.data.SerialGroupEntity
import com.example.smsgateway.data.SerialPhotoSubmissionEntity
import com.example.smsgateway.data.SerialRegistrationErrorEntity
import com.example.smsgateway.data.WhatsAppCampaignEntity
import com.example.smsgateway.data.WhatsAppRecipientEntity
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                val sims = SimQuotaRouter.statusList(appContext).map { status ->
                    SimQuotaStatusResponse(
                        label = status.label,
                        subscriptionId = status.subscriptionId,
                        role = status.role,
                        sent = status.sent,
                        limit = status.limit,
                        remaining = status.remaining,
                    )
                }
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
                        sims = sims,
                    ),
                )
            }

            post("/serials/batch") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                        category = body.category,
                        warrantyMonths = body.warrantyMonths,
                        warrantyKm = body.warrantyKm,
                        imageVerificationRequired = body.imageVerificationRequired,
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

            post("/serials/complex-batch") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                val body = call.receive<ComplexBatchRequestBody>()
                try {
                    val request = SerialGenerator.ComplexGenerateRequest(
                        count = body.count,
                        letterCount = body.letterCount,
                        digitCount = body.digitCount,
                        pattern = body.pattern,
                        prefix = body.prefix.orEmpty(),
                        city = body.city,
                        km = body.km,
                        description = body.description,
                        score = body.score,
                        category = body.category,
                        warrantyMonths = body.warrantyMonths,
                        warrantyKm = body.warrantyKm,
                        imageVerificationRequired = body.imageVerificationRequired,
                    )
                    val result = withContext(Dispatchers.IO) {
                        serialGenerator.generateComplex(request)
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
                }
            }

            post("/serials/import-batch") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                val body = call.receive<ImportBatchRequestBody>()
                try {
                    val request = SerialGenerator.ImportGenerateRequest(
                        serials = body.serials,
                        city = body.city,
                        km = body.km,
                        description = body.description,
                        score = body.score,
                        category = body.category,
                        warrantyMonths = body.warrantyMonths,
                        warrantyKm = body.warrantyKm,
                        imageVerificationRequired = body.imageVerificationRequired,
                    )
                    val result = withContext(Dispatchers.IO) {
                        serialGenerator.generateImport(request)
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
                }
            }

            post("/serials/import-csv") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                var score = 1
                var category = SerialGroupCategory.DEFAULT
                var description: String? = null
                var warrantyMonths: Int? = null
                var warrantyKm: Int? = null
                var imageVerificationRequired = false
                var tempFile: java.io.File? = null
                try {
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        try {
                            when (part) {
                                is PartData.FormItem -> {
                                    val value = part.value.trim()
                                    when (part.name) {
                                        "score" -> score = value.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                        "category" -> category = value.ifBlank { SerialGroupCategory.DEFAULT }
                                        "description" -> description = value.takeIf { it.isNotEmpty() }
                                        "warrantyMonths" -> warrantyMonths = value.toIntOrNull()?.takeIf { it > 0 }
                                        "warrantyKm" -> warrantyKm = value.toIntOrNull()?.takeIf { it > 0 }
                                        "imageVerificationRequired" -> {
                                            imageVerificationRequired = value.equals("true", ignoreCase = true) ||
                                                value == "1" ||
                                                value.equals("on", ignoreCase = true)
                                        }
                                    }
                                }
                                is PartData.FileItem -> {
                                    if (part.name == "file" || tempFile == null) {
                                        val dest = java.io.File.createTempFile(
                                            "serial_import_",
                                            ".csv",
                                            appContext.cacheDir,
                                        )
                                        part.streamProvider().use { input ->
                                            dest.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        tempFile?.delete()
                                        tempFile = dest
                                    }
                                }
                                else -> Unit
                            }
                        } finally {
                            part.dispose()
                        }
                    }

                    val file = tempFile
                    if (file == null || !file.exists() || file.length() == 0L) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "CSV file is required"))
                        return@post
                    }

                    val result = withContext(Dispatchers.IO) {
                        val parseResult = file.bufferedReader(Charsets.UTF_8).use { reader ->
                            SerialStateCsv.parse(reader)
                        }
                        serialGenerator.generateImportWithState(
                            SerialGenerator.ImportStateMetaRequest(
                                description = description,
                                score = score,
                                category = category,
                                warrantyMonths = warrantyMonths,
                                warrantyKm = warrantyKm,
                                imageVerificationRequired = imageVerificationRequired,
                            ),
                            parseResult,
                        )
                    }
                    call.respond(
                        BatchResponseBody(
                            inserted = result.inserted,
                            skipped = result.skipped,
                            total = result.total,
                            groupId = result.groupId,
                            usedInserted = result.usedInserted,
                            unusedInserted = result.unusedInserted,
                            invalidCount = result.invalidCount,
                        ),
                    )
                } catch (e: SerialStateCsv.CsvFormatException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "Invalid CSV"))
                } catch (e: SerialGenerator.ValidationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = e.message ?: "Validation error"))
                } finally {
                    tempFile?.delete()
                }
            }

            get("/serial-groups") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                val items = withContext(Dispatchers.IO) {
                    val groupDao = database.serialGroupDao()
                    val countsById = database.serialDao().countTotalsByGroup()
                        .associateBy { it.groupId }
                    groupDao.listAll().map { group ->
                        group.toResponse(serialGenerator, countsById[group.id])
                    }
                }
                call.respond(SerialGroupListResponse(items = items))
            }

            put("/serial-groups/{id}") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
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
                                category = body.category,
                                warrantyMonths = body.warrantyMonths,
                                warrantyKm = body.warrantyKm,
                                imageVerificationRequired = body.imageVerificationRequired,
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@delete
                    }
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid group id"))
                    return@delete
                }
                val deleted = withContext(Dispatchers.IO) {
                    val clubDao = database.clubAutoMessageDao()
                    clubDao.listByLinkedGroup(id).forEach { rule ->
                        clubDao.deleteSendsForRule(rule.id)
                        clubDao.deleteRule(rule.id)
                    }
                    serialGenerator.deleteGroup(id)
                }
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Serial group not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/serial-groups/{id}/serials") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid group id"))
                    return@get
                }
                try {
                    val serials = withContext(Dispatchers.IO) {
                        serialGenerator.listSerialsForGroup(id)
                    }
                    call.respond(
                        SerialGroupSerialsResponse(
                            groupId = id,
                            serials = serials,
                        ),
                    )
                } catch (e: SerialGenerator.ValidationException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(error = e.message ?: "Serial group not found"))
                }
            }

            get("/serials") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                val sourceParams = call.request.queryParameters.getAll("source")
                    .orEmpty()
                    .flatMap { it.split(',') }
                    .mapNotNull { RegistrationSource.normalizeOrNull(it) }
                    .toSet()
                val filterSms = if (RegistrationSource.SMS in sourceParams) 1 else 0
                val filterRubika = if (RegistrationSource.RUBIKA in sourceParams) 1 else 0
                val filterTelegram = if (RegistrationSource.TELEGRAM in sourceParams) 1 else 0
                val filterBale = if (RegistrationSource.BALE in sourceParams) 1 else 0
                val categoryParams = call.request.queryParameters.getAll("category")
                    .orEmpty()
                    .flatMap { it.split(',') }
                    .map { it.trim().lowercase() }
                    .filter { it in SerialGroupCategory.ALL }
                    .toSet()
                val filterEndUser = if (SerialGroupCategory.END_USER_CLIENT in categoryParams) 1 else 0
                val filterSeller = if (SerialGroupCategory.SELLER_TO_END_USER in categoryParams) 1 else 0
                val groupIds = call.request.queryParameters.getAll("groupIds")
                    .orEmpty()
                    .flatMap { it.split(',') }
                    .mapNotNull { it.trim().toLongOrNull() }
                    .distinct()
                val useGroupFilter = if (groupIds.isNotEmpty()) 1 else 0
                // Room rejects empty IN (); placeholder unused when useGroupFilter=0
                val groupIdsForQuery = if (groupIds.isNotEmpty()) groupIds else listOf(-1L)
                val provinceId = call.request.queryParameters["provinceId"]?.trim().orEmpty()
                val cityName = call.request.queryParameters["cityName"]?.trim().orEmpty()
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
                val offset = (page - 1) * limit

                try {
                    val config = settingsStore.getSmsProcessingConfig()
                    val (mappedItems, total) = withContext(Dispatchers.IO) {
                        val dao = database.serialDao()
                        val (rows, count) = when (registeredParam) {
                            "true" -> {
                                if (provinceId.isNotEmpty()) {
                                    val provinces = IranLocations.load(appContext)
                                    val all = dao.listRegisteredFilteredUnpaged(
                                        fromDate, toDate, search, phoneNorm,
                                        filterSms, filterRubika, filterTelegram, filterBale,
                                        filterEndUser, filterSeller,
                                        useGroupFilter, groupIdsForQuery,
                                    )
                                    val matched = all.filter { row ->
                                        IranLocations.matchesLocation(
                                            storedCity = row.city,
                                            provinceId = provinceId,
                                            cityName = cityName.ifEmpty { null },
                                            provinces = provinces,
                                        )
                                    }
                                    matched.drop(offset).take(limit) to matched.size
                                } else {
                                    val list = dao.listRegisteredFiltered(
                                        fromDate, toDate, search, phoneNorm,
                                        filterSms, filterRubika, filterTelegram, filterBale,
                                        filterEndUser, filterSeller,
                                        useGroupFilter, groupIdsForQuery,
                                        limit, offset,
                                    )
                                    list to dao.countRegisteredFiltered(
                                        fromDate, toDate, search, phoneNorm,
                                        filterSms, filterRubika, filterTelegram, filterBale,
                                        filterEndUser, filterSeller,
                                        useGroupFilter, groupIdsForQuery,
                                    )
                                }
                            }
                            "false" -> {
                                val list = dao.listUnusedFiltered(search, limit, offset)
                                list to dao.countUnusedFiltered(search)
                            }
                            else -> {
                                val list = dao.listAllFiltered(fromDate, toDate, search, phoneNorm, limit, offset)
                                list to dao.countAllFiltered(fromDate, toDate, search, phoneNorm)
                            }
                        }
                        val pairing = buildPhonePairingMap(database, rows)
                        val phones = rows.mapNotNull { it.phone?.trim()?.takeIf { p -> p.isNotEmpty() } }
                            .distinct()
                        val knownCityByPhone = if (phones.isEmpty()) {
                            emptyMap()
                        } else {
                            dao.listCitiesByPhones(phones)
                                .mapNotNull { row ->
                                    val p = row.phone.trim()
                                    val c = row.city?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                                    p to c
                                }
                                .toMap() // first wins; query is ORDER BY id DESC so latest first per phone if we group
                                .let { pairs ->
                                    // Keep first city per phone (latest due to ORDER BY id DESC)
                                    linkedMapOf<String, String>().apply {
                                        for ((p, c) in pairs) {
                                            if (!containsKey(p)) put(p, c)
                                        }
                                    }
                                }
                        }
                        rows.map { it.toResponse(database, config, pairing, knownCityByPhone) } to count
                    }

                    LanTraceLog.d(
                        "Serials",
                        "GET /serials registered=$registeredParam from=$fromDate to=$toDate " +
                            "defaultEndToday=$defaultEndToday search=$search phoneNorm=$phoneNorm " +
                            "sources=$sourceParams categories=$categoryParams groupIds=$groupIds " +
                            "provinceId=$provinceId cityName=$cityName " +
                            "-> total=$total items=${mappedItems.size}",
                    )

                    call.respond(
                        SerialListResponse(
                            items = mappedItems,
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                    if (!PhoneUtils.isRegisteredPhone(row.phone)) {
                        return@withContext ReminderResult.NoPhone
                    }
                    val phone = row.phone!!
                    val category = row.groupId?.let { gid ->
                        SerialGroupCategory.normalizeOrDefault(
                            database.serialGroupDao().findById(gid)?.category,
                        )
                    } ?: SerialGroupCategory.DEFAULT
                    val knownCity = dao.findLatestCityByPhone(phone)?.takeIf { it.isNotBlank() }
                    if (!RegistrationFieldRules.needsCompletion(
                            phone,
                            row.city,
                            row.km,
                            category,
                            knownCity,
                        )
                    ) {
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
                        call.respond(
                            updated.row.toResponse(
                                database,
                                settingsStore.getSmsProcessingConfig(),
                            ),
                        )
                }
            }

            put("/serials/{id}") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid serial id"))
                    return@put
                }
                val body = call.receive<UpdateSerialRegistrationRequest>()
                val serialTrim = SerialNormalizer.normalizeSerial(body.serial)
                if (serialTrim.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Ø³Ø±ÛŒØ§Ù„ Ø§Ù„Ø²Ø§Ù…ÛŒ Ø§Ø³Øª"))
                    return@put
                }
                val dateTrim = body.date.trim()
                if (dateTrim.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "ØªØ§Ø±ÛŒØ® Ø§Ù„Ø²Ø§Ù…ÛŒ Ø§Ø³Øª"))
                    return@put
                }
                val cityTrim = body.city?.trim()?.takeIf { it.isNotEmpty() }
                val kmValue = body.km?.takeIf { it >= 0 }

                val result = withContext(Dispatchers.IO) {
                    val dao = database.serialDao()
                    val row = dao.findById(id) ?: return@withContext UpdateSerialResult.NotFound
                    if (!PhoneUtils.isRegisteredPhone(row.phone)) {
                        return@withContext UpdateSerialResult.NotRegistered
                    }
                    val phone = row.phone!!
                    val normalizedDate = JalaliHelper.normalizeStoredDateTime(dateTrim)
                    val duplicate = dao.findBySerial(serialTrim)
                    if (duplicate != null && duplicate.id != id) {
                        return@withContext UpdateSerialResult.DuplicateSerial
                    }
                    val category = row.groupId?.let { gid ->
                        SerialGroupCategory.normalizeOrDefault(
                            database.serialGroupDao().findById(gid)?.category,
                        )
                    } ?: SerialGroupCategory.DEFAULT
                    val requiresKm = if (RegistrationFieldRules.requiresKm(category)) 1 else 0
                    val oldSerial = row.serial
                    val updatedRows = dao.updateRegistrationById(
                        id = id,
                        serial = serialTrim,
                        date = normalizedDate,
                        city = cityTrim,
                        km = kmValue,
                        requiresKm = requiresKm,
                    )
                    if (updatedRows == 0) {
                        return@withContext UpdateSerialResult.NotFound
                    }
                    if (!oldSerial.equals(serialTrim, ignoreCase = true)) {
                        database.serialPhotoSubmissionDao().updateSerialForPhone(
                            oldSerial = oldSerial,
                            newSerial = serialTrim,
                            phone = phone,
                        )
                    }
                    val refreshed = dao.findById(id) ?: return@withContext UpdateSerialResult.NotFound
                    SyncEventWriter.getInstance(database).recordRegistrationUpdated(
                        serial = refreshed.serial,
                        phone = refreshed.phone.orEmpty(),
                        date = refreshed.date.orEmpty(),
                        city = refreshed.city,
                        km = refreshed.km,
                    )
                    UpdateSerialResult.Success(refreshed)
                }

                when (result) {
                    UpdateSerialResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Serial not found"))
                    UpdateSerialResult.NotRegistered ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Ø§ÛŒÙ† Ø³Ø±ÛŒØ§Ù„ Ø«Ø¨Øª Ù†Ø´Ø¯Ù‡ Ø§Ø³Øª"))
                    UpdateSerialResult.DuplicateSerial ->
                        call.respond(HttpStatusCode.Conflict, ErrorResponse(error = "Ø³Ø±ÛŒØ§Ù„ ØªÚ©Ø±Ø§Ø±ÛŒ Ø§Ø³Øª"))
                    is UpdateSerialResult.Success ->
                        call.respond(
                            result.row.toResponse(
                                database,
                                settingsStore.getSmsProcessingConfig(),
                            ),
                        )
                }
            }

            get("/serial-errors/clients") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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

            post("/communications/send") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                val body = call.receive<CommunicationSendRequest>()
                val rawPhone = body.phone.trim()
                val text = body.body.trim()
                if (rawPhone.isEmpty() || text.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Ø´Ù…Ø§Ø±Ù‡ Ùˆ Ù…ØªÙ† Ù¾ÛŒØ§Ù… Ø§Ù„Ø²Ø§Ù…ÛŒ Ø§Ø³Øª"))
                    return@post
                }
                val channels = body.channels
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .filter { it in MassSmsChannel.ALL }
                if (channels.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Ø­Ø¯Ø§Ù‚Ù„ ÛŒÚ© ØªÙˆÙ†Ù„ Ù…Ø¹ØªØ¨Ø± Ø§Ù†ØªØ®Ø§Ø¨ Ú©Ù†ÛŒØ¯"))
                    return@post
                }
                val phone = PhoneUtils.formatForDisplay(rawPhone) ?: rawPhone
                val commLog = CommunicationLog.getInstance(appContext)
                val results = withContext(Dispatchers.IO) {
                    channels.map { channel ->
                        try {
                            sendCommunicationViaChannel(appContext, database, settingsStore, commLog, channel, phone, text)
                            CommunicationSendChannelResult(channel = channel, ok = true)
                        } catch (error: Exception) {
                            LanTraceLog.e("Comms", "send via $channel failed for $phone", error)
                            CommunicationSendChannelResult(
                                channel = channel,
                                ok = false,
                                error = error.message ?: "Ø®Ø·Ø§ Ø¯Ø± Ø§Ø±Ø³Ø§Ù„",
                            )
                        }
                    }
                }
                call.respond(CommunicationSendResponse(phone = phone, results = results))
            }

            get("/settings/sms-processing") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                call.respond(settingsStore.getSmsProcessingConfig().toResponse())
            }

            put("/settings/sms-processing") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
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

            get("/settings/sim-quota") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                call.respond(
                    SimQuotaSettingsResponse(
                        dailyLimit = settingsStore.getSmsDailyLimit(),
                        backupSimsEnabled = settingsStore.isBackupSimsEnabled(),
                    ),
                )
            }

            put("/settings/sim-quota") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                val body = call.receive<SimQuotaSettingsResponse>()
                if (body.dailyLimit < 1 || body.dailyLimit > 100_000) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "dailyLimit must be between 1 and 100000"),
                    )
                    return@put
                }
                settingsStore.setSmsDailyLimit(body.dailyLimit)
                settingsStore.setBackupSimsEnabled(body.backupSimsEnabled)
                call.respond(
                    SimQuotaSettingsResponse(
                        dailyLimit = settingsStore.getSmsDailyLimit(),
                        backupSimsEnabled = settingsStore.isBackupSimsEnabled(),
                    ),
                )
            }

            get("/settings/sms-messages") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                val templates = SmsMessageTemplates.getInstance(settingsStore)
                call.respond(templates.toApiResponse())
            }

            put("/settings/sms-messages") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    call.respond(buildRubikaStatus(settingsStore, appContext))
                }

                post("/connect") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                        SyncEventWriter.getInstance(database).recordDashboardEvent(
                            type = SyncEventWriter.TYPE_BOT_CONNECTED,
                            message = "اتصال ربات روبیکا",
                            extra = mapOf(
                                "channel" to "rubika",
                                "title" to (info.title.takeIf { it.isNotBlank() } ?: info.username),
                            ),
                        )
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    RubikaPoller.getInstance(appContext).stop()
                    settingsStore.clearRubikaBot()
                    SyncEventWriter.getInstance(database).recordDashboardEvent(
                        type = SyncEventWriter.TYPE_BOT_DISCONNECTED,
                        message = "قطع اتصال ربات روبیکا",
                        extra = mapOf("channel" to "rubika"),
                    )
                    call.respond(buildRubikaStatus(settingsStore, appContext))
                }

                get("/messages") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                call.respond(settingsStore.toRubikaSettingsResponse())
            }

            put("/settings/rubika") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                val body = call.receive<RubikaSettingsResponse>()
                settingsStore.setRubikaSerialEnabled(body.serialEnabled)
                settingsStore.setRubikaInviteEnabled(body.inviteEnabled)
                settingsStore.setRubikaInviteMessage(body.inviteMessage)
                settingsStore.setRubikaMenuSettings(body.toBotMenuSettings())
                call.respond(settingsStore.toRubikaSettingsResponse())
            }

            route("/telegram") {
                get("/status") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    call.respond(buildTelegramStatus(settingsStore, appContext))
                }

                post("/connect") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                        SyncEventWriter.getInstance(database).recordDashboardEvent(
                            type = SyncEventWriter.TYPE_BOT_CONNECTED,
                            message = "اتصال ربات تلگرام",
                            extra = mapOf(
                                "channel" to "telegram",
                                "title" to (info.title.takeIf { it.isNotBlank() } ?: info.username),
                            ),
                        )
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    TelegramPoller.getInstance(appContext).stop()
                    settingsStore.clearTelegramBot()
                    SyncEventWriter.getInstance(database).recordDashboardEvent(
                        type = SyncEventWriter.TYPE_BOT_DISCONNECTED,
                        message = "قطع اتصال ربات تلگرام",
                        extra = mapOf("channel" to "telegram"),
                    )
                    call.respond(buildTelegramStatus(settingsStore, appContext))
                }

                get("/messages") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                call.respond(settingsStore.toTelegramSettingsResponse())
            }

            put("/settings/telegram") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                val body = call.receive<TelegramSettingsResponse>()
                settingsStore.setTelegramSerialEnabled(body.serialEnabled)
                settingsStore.setTelegramInviteEnabled(body.inviteEnabled)
                settingsStore.setTelegramInviteMessage(body.inviteMessage)
                settingsStore.setTelegramMenuSettings(body.toBotMenuSettings())
                call.respond(settingsStore.toTelegramSettingsResponse())
            }

            route("/bale") {
                get("/status") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    call.respond(buildBaleStatus(settingsStore, appContext))
                }

                post("/connect") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<BaleConnectRequestBody>()
                    val token = body.token.trim()
                    if (token.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "token is required"))
                        return@post
                    }
                    try {
                        val info = withContext(Dispatchers.IO) {
                            BaleApi.getInstance().getMe(token)
                        }
                        settingsStore.setBaleBotActive(info, token)
                        BalePoller.getInstance(appContext).start()
                        SyncEventWriter.getInstance(database).recordDashboardEvent(
                            type = SyncEventWriter.TYPE_BOT_CONNECTED,
                            message = "اتصال ربات بله",
                            extra = mapOf(
                                "channel" to "bale",
                                "title" to (info.title.takeIf { it.isNotBlank() } ?: info.username),
                            ),
                        )
                        call.respond(buildBaleStatus(settingsStore, appContext))
                    } catch (error: Exception) {
                        LanTraceLog.e("Bale", "POST /bale/connect failed", error)
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Connect failed"),
                        )
                    }
                }

                post("/disconnect") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    BalePoller.getInstance(appContext).stop()
                    settingsStore.clearBaleBot()
                    SyncEventWriter.getInstance(database).recordDashboardEvent(
                        type = SyncEventWriter.TYPE_BOT_DISCONNECTED,
                        message = "قطع اتصال ربات بله",
                        extra = mapOf("channel" to "bale"),
                    )
                    call.respond(buildBaleStatus(settingsStore, appContext))
                }

                get("/messages") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
                    val chatStore = BaleChatStore.getInstance(appContext)
                    val items = if (since > 0L) {
                        chatStore.getMessagesSince(since, limit)
                    } else {
                        chatStore.getMessages(limit)
                    }
                    val total = withContext(Dispatchers.IO) {
                        database.baleMessageDao().countAll()
                    }
                    call.respond(
                        BaleMessageListResponse(
                            items = items.map { it.toBaleResponse() },
                            total = total,
                        ),
                    )
                }
            }

            get("/settings/bale") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                call.respond(settingsStore.toBaleSettingsResponse())
            }

            put("/settings/bale") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                val body = call.receive<BaleSettingsResponse>()
                settingsStore.setBaleSerialEnabled(body.serialEnabled)
                settingsStore.setBaleInviteEnabled(body.inviteEnabled)
                settingsStore.setBaleInviteMessage(body.inviteMessage)
                settingsStore.setBaleMenuSettings(body.toBotMenuSettings())
                call.respond(settingsStore.toBaleSettingsResponse())
            }

            route("/mass-sms") {
                get("/provinces") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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

                post("/media") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    var fileBytes: ByteArray? = null
                    var originalName: String? = null
                    var contentType: String? = null
                    val multipart = call.receiveMultipart()
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            originalName = part.originalFileName?.takeIf { it.isNotBlank() }
                            contentType = part.contentType?.toString()
                            fileBytes = part.streamProvider().use { it.readBytes() }
                        }
                        part.dispose()
                    }

                    val bytes = fileBytes
                    if (bytes == null || bytes.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "ÙØ§ÛŒÙ„ÛŒ Ø§Ø±Ø³Ø§Ù„ Ù†Ø´Ø¯"))
                        return@post
                    }

                    val response = withContext(Dispatchers.IO) {
                        val mime = contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        val kind = if (mime.startsWith("image/")) BotMediaKind.PHOTO else BotMediaKind.DOCUMENT
                        val safeName = (originalName ?: "file")
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                            .takeIf { it.isNotBlank() } ?: "file"
                        val dir = java.io.File(appContext.filesDir, "bot_media").apply { mkdirs() }
                        val stored = java.io.File(dir, "media_${System.currentTimeMillis()}_$safeName")
                        stored.writeBytes(bytes)
                        val assetId = database.botMediaAssetDao().insert(
                            BotMediaAssetEntity(
                                fileName = safeName,
                                mimeType = mime,
                                kind = kind,
                                localPath = stored.absolutePath,
                                sizeBytes = bytes.size.toLong(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        MassSmsMediaResponse(
                            assetId = assetId,
                            fileName = safeName,
                            mimeType = mime,
                            kind = kind,
                            sizeBytes = bytes.size.toLong(),
                        )
                    }
                    call.respond(response)
                }

                get("/campaigns") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val response = withContext(Dispatchers.IO) {
                        val dao = database.massSmsCampaignDao()
                        val campaigns = dao.listCampaigns()
                        val provinces = IranLocations.load(appContext)
                        val queued = campaigns.filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        val groupDao = database.serialGroupDao()
                        val mediaDao = database.botMediaAssetDao()
                        MassSmsCampaignListResponse(
                            items = campaigns.map { campaign ->
                                campaign.toResponse(provinces, queued, groupDao, serialGenerator, mediaDao)
                            },
                        )
                    }
                    call.respond(response)
                }

                get("/campaigns/{id}") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                        val mediaDao = database.botMediaAssetDao()
                        MassSmsCampaignDetailResponse(
                            campaign = campaign.toResponse(provinces, queued, groupDao, serialGenerator, mediaDao),
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<MassSmsCreateRequest>()
                    val validationError = validateMassSmsFilter(body.audienceType, body.message)
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = validationError))
                        return@post
                    }
                    val channelValidation = validateMassSmsChannel(body.channel, settingsStore)
                    if (channelValidation != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = channelValidation))
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
                                channel = body.channel,
                                mediaAssetId = body.mediaAssetId,
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
                                database.botMediaAssetDao(),
                            )
                        }
                        call.respond(response)
                    } catch (error: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = error.message ?: "Invalid request"))
                    }
                }

                post("/campaigns/{id}/pause") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                        updated.toResponse(provinces, queued, database.serialGroupDao(), serialGenerator, database.botMediaAssetDao())
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                        return@post
                    }
                    call.respond(response)
                }

                post("/campaigns/{id}/resume") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
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
                        updated.toResponse(provinces, queued, database.serialGroupDao(), serialGenerator, database.botMediaAssetDao())
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                        return@post
                    }
                    call.respond(response)
                }

                delete("/campaigns/{id}") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@delete
                    }
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

            route("/whatsapp") {
                get("/config") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    call.respond(settingsStore.getWhatsAppConfig().toConfigResponse())
                }

                put("/config") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                    val body = call.receive<WhatsAppConfigUpdateRequest>()
                    val config = WhatsAppConfig(
                        searchBoxSelector = body.searchBoxSelector,
                        searchResultSelector = body.searchResultSelector,
                        messageInputSelector = body.messageInputSelector,
                        sendButtonSelector = body.sendButtonSelector,
                        openMode = body.openMode,
                        searchByPhoneFirst = body.searchByPhoneFirst,
                        sendDelaySeconds = body.sendDelaySeconds,
                        perContactTimeoutMs = body.perContactTimeoutMs,
                    ).normalized()
                    settingsStore.setWhatsAppConfig(config)
                    call.respond(config.toConfigResponse())
                }

                post("/engine/heartbeat") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<WhatsAppEngineHeartbeatRequest>()
                    settingsStore.recordWhatsAppEngineHeartbeat(body.loggedIn)
                    call.respond(settingsStore.toWhatsAppEngineStatusResponse())
                }

                get("/engine/status") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    call.respond(settingsStore.toWhatsAppEngineStatusResponse())
                }

                post("/preview") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<WhatsAppPreviewRequest>()
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
                            .mapNotNull { PhoneUtils.normalizeToInternational(it) }
                            .distinct()
                    }
                    call.respond(
                        WhatsAppPreviewResponse(
                            count = phones.size,
                            samplePhones = phones.take(5),
                        ),
                    )
                }

                get("/campaigns") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val response = withContext(Dispatchers.IO) {
                        val dao = database.whatsAppCampaignDao()
                        val campaigns = dao.listCampaigns()
                        val provinces = IranLocations.load(appContext)
                        val queued = campaigns.filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        val groupDao = database.serialGroupDao()
                        WhatsAppCampaignListResponse(
                            items = campaigns.map { campaign ->
                                campaign.toWhatsAppResponse(provinces, queued, groupDao, serialGenerator)
                            },
                        )
                    }
                    call.respond(response)
                }

                get("/campaigns/{id}") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
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
                        val dao = database.whatsAppCampaignDao()
                        val campaign = dao.findCampaignById(id) ?: return@withContext null
                        val provinces = IranLocations.load(appContext)
                        val queued = dao.listCampaigns()
                            .filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        val recipients = dao.listRecipients(id, limit, offset)
                        val total = dao.countRecipients(id)
                        val groupDao = database.serialGroupDao()
                        WhatsAppCampaignDetailResponse(
                            campaign = campaign.toWhatsAppResponse(provinces, queued, groupDao, serialGenerator),
                            recipients = recipients.map { it.toWhatsAppRecipientResponse() },
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<WhatsAppCreateRequest>()
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
                            val campaign = WhatsAppCampaignService.getInstance(appContext).createCampaign(
                                message = body.message.trim(),
                                filter = body.toFilter(),
                            )
                            val provinces = IranLocations.load(appContext)
                            val queued = database.whatsAppCampaignDao().listCampaigns()
                                .filter { it.status == MassSmsCampaignStatus.QUEUED }
                                .sortedBy { it.queueOrder }
                            campaign.toWhatsAppResponse(
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
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@post
                    }
                    val response = withContext(Dispatchers.IO) {
                        val updated = WhatsAppCampaignService.getInstance(appContext).pauseCampaign(id)
                            ?: return@withContext null
                        val provinces = IranLocations.load(appContext)
                        val queued = database.whatsAppCampaignDao().listCampaigns()
                            .filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        updated.toWhatsAppResponse(provinces, queued, database.serialGroupDao(), serialGenerator)
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                        return@post
                    }
                    call.respond(response)
                }

                post("/campaigns/{id}/resume") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@post
                    }
                    val response = withContext(Dispatchers.IO) {
                        val updated = WhatsAppCampaignService.getInstance(appContext).resumeCampaign(id)
                            ?: return@withContext null
                        val provinces = IranLocations.load(appContext)
                        val queued = database.whatsAppCampaignDao().listCampaigns()
                            .filter { it.status == MassSmsCampaignStatus.QUEUED }
                            .sortedBy { it.queueOrder }
                        updated.toWhatsAppResponse(provinces, queued, database.serialGroupDao(), serialGenerator)
                    }
                    if (response == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                        return@post
                    }
                    call.respond(response)
                }

                delete("/campaigns/{id}") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@delete
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid campaign id"))
                        return@delete
                    }
                    val deleted = withContext(Dispatchers.IO) {
                        WhatsAppCampaignService.getInstance(appContext).deleteCampaign(id)
                    }
                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Campaign not found"))
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                get("/next-batch") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 5
                    val response = withContext(Dispatchers.IO) {
                        val batch = WhatsAppCampaignService.getInstance(appContext).nextBatch(limit)
                        if (batch == null || batch.recipients.isEmpty()) {
                            WhatsAppNextBatchResponse()
                        } else {
                            WhatsAppNextBatchResponse(
                                campaignId = batch.campaign.id,
                                message = batch.campaign.message,
                                recipients = batch.recipients.map {
                                    WhatsAppBatchRecipient(
                                        recipientId = it.id,
                                        phoneIntl = it.phoneIntl,
                                        contactName = it.contactName,
                                    )
                                },
                            )
                        }
                    }
                    call.respond(response)
                }

                post("/report") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<WhatsAppReportRequest>()
                    withContext(Dispatchers.IO) {
                        WhatsAppCampaignService.getInstance(appContext).reportResults(
                            body.results.map {
                                WhatsAppCampaignService.RecipientResult(
                                    recipientId = it.recipientId,
                                    success = it.success,
                                    error = it.error,
                                )
                            },
                        )
                    }
                    call.respond(WhatsAppReportResponse(updated = body.results.size))
                }
            }

            route("/club-auto-messages") {
                get {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val items = withContext(Dispatchers.IO) {
                        database.clubAutoMessageDao().listRules().map {
                            it.toClubAutoMessageResponse(database, serialGenerator)
                        }
                    }
                    call.respond(ClubAutoMessageRuleListResponse(items = items))
                }

                post {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val body = call.receive<ClubAutoMessageCreateRequest>()
                    val validationError = validateClubAutoMessage(
                        message = body.message,
                        offsetMonths = body.offsetMonths,
                        offsetDays = body.offsetDays,
                        sendHour = body.sendHour,
                        sendMinute = body.sendMinute,
                        delaySeconds = body.delaySeconds,
                        dailyCap = body.dailyCap,
                        sendSms = body.sendSms,
                        sendRubika = body.sendRubika,
                        sendTelegram = body.sendTelegram,
                        sendBale = body.sendBale,
                        serialCategory = body.serialCategory,
                        serialGroupIds = body.serialGroupIds,
                    )
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = validationError))
                        return@post
                    }
                    val groupIds = body.serialGroupIds.orEmpty().distinct()
                    val derivedCategory = withContext(Dispatchers.IO) {
                        deriveClubSerialCategory(groupIds, body.serialCategory, database)
                    }
                    val now = System.currentTimeMillis()
                    val autoSource = normalizeClubAutoSource(body.autoSource)
                    val linkedGroupId = body.linkedSerialGroupId
                        ?: groupIds.singleOrNull()?.takeIf { autoSource != null }
                    val entity = ClubAutoMessageRuleEntity(
                        title = body.title.trim().ifEmpty { defaultClubAutoMessageTitle(body) },
                        message = body.message.trim(),
                        offsetMonths = body.offsetMonths.coerceAtLeast(0),
                        offsetDays = body.offsetDays.coerceIn(-366, 366),
                        sendHour = body.sendHour,
                        sendMinute = body.sendMinute,
                        delaySeconds = body.delaySeconds.coerceAtLeast(1),
                        dailyCap = ClubAutoMessageScheduler.normalizeDailyCap(body.dailyCap),
                        sendSms = body.sendSms,
                        sendRubika = body.sendRubika,
                        sendTelegram = body.sendTelegram,
                        sendBale = body.sendBale,
                        serialCategory = derivedCategory,
                        serialGroupIdsJson = ClubAutoMessageScheduler.encodeSerialGroupIds(groupIds),
                        autoSource = autoSource,
                        linkedSerialGroupId = linkedGroupId,
                        enabled = body.enabled,
                        createdAt = now,
                        updatedAt = now,
                    )
                    val created = withContext(Dispatchers.IO) {
                        val id = database.clubAutoMessageDao().insertRule(entity)
                        ClubAutoMessageScheduler.getInstance(appContext).start()
                        database.clubAutoMessageDao().findRuleById(id)
                    }
                    if (created == null) {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = "Failed to create rule"))
                    } else {
                        call.respond(created.toClubAutoMessageResponse(database, serialGenerator))
                    }
                }

                put("/{id}") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid rule id"))
                        return@put
                    }
                    val body = call.receive<ClubAutoMessageUpdateRequest>()
                    val validationError = validateClubAutoMessage(
                        message = body.message,
                        offsetMonths = body.offsetMonths,
                        offsetDays = body.offsetDays,
                        sendHour = body.sendHour,
                        sendMinute = body.sendMinute,
                        delaySeconds = body.delaySeconds,
                        dailyCap = body.dailyCap,
                        sendSms = body.sendSms,
                        sendRubika = body.sendRubika,
                        sendTelegram = body.sendTelegram,
                        sendBale = body.sendBale,
                        serialCategory = body.serialCategory,
                        serialGroupIds = body.serialGroupIds,
                    )
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = validationError))
                        return@put
                    }
                    val groupIds = body.serialGroupIds.orEmpty().distinct()
                    val updated = withContext(Dispatchers.IO) {
                        val dao = database.clubAutoMessageDao()
                        val existing = dao.findRuleById(id) ?: return@withContext null
                        val derivedCategory = deriveClubSerialCategory(groupIds, body.serialCategory, database)
                        val now = System.currentTimeMillis()
                        val autoSource = normalizeClubAutoSource(body.autoSource) ?: existing.autoSource
                        val linkedGroupId = body.linkedSerialGroupId
                            ?: existing.linkedSerialGroupId
                            ?: groupIds.singleOrNull()?.takeIf { autoSource != null }
                        val next = existing.copy(
                            title = body.title.trim().ifEmpty { defaultClubAutoMessageTitle(body) },
                            message = body.message.trim(),
                            offsetMonths = body.offsetMonths.coerceAtLeast(0),
                            offsetDays = body.offsetDays.coerceIn(-366, 366),
                            sendHour = body.sendHour,
                            sendMinute = body.sendMinute,
                            delaySeconds = body.delaySeconds.coerceAtLeast(1),
                            dailyCap = ClubAutoMessageScheduler.normalizeDailyCap(body.dailyCap),
                            sendSms = body.sendSms,
                            sendRubika = body.sendRubika,
                            sendTelegram = body.sendTelegram,
                            sendBale = body.sendBale,
                            serialCategory = derivedCategory,
                            serialGroupIdsJson = ClubAutoMessageScheduler.encodeSerialGroupIds(groupIds),
                            autoSource = autoSource,
                            linkedSerialGroupId = linkedGroupId,
                            enabled = body.enabled,
                            updatedAt = now,
                        )
                        dao.updateRule(next)
                        ClubAutoMessageScheduler.getInstance(appContext).start()
                        dao.findRuleById(id)
                    }
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Rule not found"))
                    } else {
                        call.respond(updated.toClubAutoMessageResponse(database, serialGenerator))
                    }
                }

                delete("/{id}") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@delete
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid rule id"))
                        return@delete
                    }
                    val deleted = withContext(Dispatchers.IO) {
                        val dao = database.clubAutoMessageDao()
                        val existing = dao.findRuleById(id) ?: return@withContext false
                        dao.deleteSendsForRule(existing.id)
                        dao.deleteRule(existing.id)
                        true
                    }
                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Rule not found"))
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                post("/{id}/run-now") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid rule id"))
                        return@post
                    }
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            ClubAutoMessageScheduler.getInstance(appContext).runRuleNow(id)
                        }
                        call.respond(updated.toClubAutoMessageResponse(database, serialGenerator))
                    } catch (error: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Invalid request"),
                        )
                    }
                }

                post("/tick") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    withContext(Dispatchers.IO) {
                        ClubAutoMessageScheduler.getInstance(appContext).requestTickDueNow()
                    }
                    call.respond(ClubAutoMessageTickResponse(ok = true))
                }

                get("/{id}/preview") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid rule id"))
                        return@get
                    }
                    val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                    try {
                        val preview = withContext(Dispatchers.IO) {
                            ClubAutoMessageScheduler.getInstance(appContext).previewRecipients(id, page, limit)
                        }
                        call.respond(
                            ClubAutoMessagePreviewResponse(
                                items = preview.items.map { item ->
                                    ClubAutoMessagePreviewItem(
                                        phone = item.phone,
                                        serial = item.serial,
                                        channels = item.channels.map { ch ->
                                            ClubAutoMessagePreviewChannelItem(
                                                channel = ch.channel,
                                                status = ch.status,
                                            )
                                        },
                                        willSendAny = item.willSendAny,
                                    )
                                },
                                total = preview.total,
                                pendingCount = preview.pendingCount,
                                blockedCount = preview.blockedCount,
                                alreadyFullySentCount = preview.alreadyFullySentCount,
                                cutoffDateJalali = preview.cutoffDateJalali,
                                dailyCap = preview.dailyCap,
                                sentToday = preview.sentToday,
                                remainingToday = preview.remainingToday,
                                page = preview.page,
                                limit = preview.limit,
                            ),
                        )
                    } catch (error: IllegalArgumentException) {
                        val notFound = error.message == "Ù‚Ø§Ù†ÙˆÙ† ÛŒØ§ÙØª Ù†Ø´Ø¯"
                        call.respond(
                            if (notFound) HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Invalid request"),
                        )
                    }
                }

                get("/{id}/sends") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid rule id"))
                        return@get
                    }
                    val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                    val offset = (page - 1) * limit
                    val result = withContext(Dispatchers.IO) {
                        val dao = database.clubAutoMessageDao()
                        val existing = dao.findRuleById(id) ?: return@withContext null
                        val total = dao.countSendsForRule(existing.id)
                        val items = dao.listSendsForRule(existing.id, limit, offset).map { send ->
                            ClubAutoMessageSendItem(
                                id = send.id,
                                phone = send.phone,
                                serial = send.serial,
                                channel = send.channel,
                                sentAt = send.sentAt,
                                sentDateJalali = send.sentDateJalali,
                            )
                        }
                        ClubAutoMessageSendListResponse(
                            items = items,
                            total = total,
                            page = page,
                            limit = limit,
                        )
                    }
                    if (result == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Rule not found"))
                    } else {
                        call.respond(result)
                    }
                }
            }

            route("/serial-photo-submissions") {
                get {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val status = call.request.queryParameters["status"].orEmpty().trim()
                    val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 30
                    val offset = (page - 1) * limit
                    val result = withContext(Dispatchers.IO) {
                        val dao = database.serialPhotoSubmissionDao()
                        val total = dao.countFiltered(status)
                        val items = dao.listFiltered(status, limit, offset).map { row ->
                            row.toPhotoResponse(database, serialGenerator)
                        }
                        SerialPhotoSubmissionListResponse(
                            items = items,
                            total = total,
                            page = page,
                            limit = limit,
                        )
                    }
                    call.respond(result)
                }

                get("/{id}/image") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid id"))
                        return@get
                    }
                    val filePair = withContext(Dispatchers.IO) {
                        val row = database.serialPhotoSubmissionDao().findById(id) ?: return@withContext null
                        val file = java.io.File(row.localPath)
                        if (!file.exists()) return@withContext null
                        file.readBytes() to (row.mimeType ?: "image/jpeg")
                    }
                    if (filePair == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "Image not found"))
                    } else {
                        val (bytes, mime) = filePair
                        val contentType = when {
                            mime.contains("png") -> ContentType.Image.PNG
                            mime.contains("webp") -> ContentType("image", "webp")
                            mime.contains("gif") -> ContentType.Image.GIF
                            else -> ContentType.Image.JPEG
                        }
                        call.respondBytes(bytes, contentType)
                    }
                }

                post("/{id}/approve") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid id"))
                        return@post
                    }
                    val body = runCatching { call.receive<SerialPhotoReviewRequest>() }
                        .getOrElse { SerialPhotoReviewRequest() }
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            SerialPhotoVerificationService.getInstance(appContext)
                                .approve(id, body.note)
                                .toPhotoResponse(database, serialGenerator)
                        }
                        SyncEventWriter.getInstance(database).recordDashboardEvent(
                            type = SyncEventWriter.TYPE_PHOTO_APPROVED,
                            message = "تأیید عکس سریال ${updated.serial}",
                            extra = mapOf(
                                "serial" to updated.serial,
                                "phone" to updated.phone,
                                "submissionId" to updated.id,
                            ),
                        )
                        call.respond(updated)
                    } catch (error: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Invalid request"),
                        )
                    }
                }

                post("/{id}/reject") {
                    when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@post
                    }
                }
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid id"))
                        return@post
                    }
                    val body = runCatching { call.receive<SerialPhotoReviewRequest>() }
                        .getOrElse { SerialPhotoReviewRequest() }
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            SerialPhotoVerificationService.getInstance(appContext)
                                .reject(
                                    id = id,
                                    reasonCode = body.reasonCode,
                                    note = body.note,
                                )
                                .toPhotoResponse(database, serialGenerator)
                        }
                        val reason = body.reasonCode?.takeIf { it.isNotBlank() } ?: "rejected"
                        SyncEventWriter.getInstance(database).recordDashboardEvent(
                            type = SyncEventWriter.TYPE_PHOTO_REJECTED,
                            message = "رد عکس سریال ${updated.serial} ($reason)",
                            extra = mapOf(
                                "serial" to updated.serial,
                                "phone" to updated.phone,
                                "submissionId" to updated.id,
                                "reason" to reason,
                            ),
                        )
                        call.respond(updated)
                    } catch (error: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(error = error.message ?: "Invalid request"),
                        )
                    }
                }
            }

            get("/settings/referral") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                call.respond(settingsStore.getReferralSettings().toResponse())
            }

            put("/settings/referral") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@put
                    }
                }
                val body = call.receive<ReferralSettingsResponse>()
                settingsStore.setReferralSettings(
                    ReferralSettings(
                        enabled = body.enabled,
                        basePoints = body.basePoints,
                        decayPercent = body.decayPercent,
                        maxDepth = body.maxDepth,
                    ),
                )
                call.respond(settingsStore.getReferralSettings().toResponse())
            }

            get("/referral/customers") {
                when (val access = LanAuth.checkAccess(call, settingsStore, appContext)) {
                    LanAccessResult.OK -> Unit
                    else -> {
                        LanAuth.reject(call, access)
                        return@get
                    }
                }
                val search = call.request.queryParameters["search"].orEmpty().trim()
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                val offset = (page - 1) * limit
                val result = withContext(Dispatchers.IO) {
                    val dao = database.customerReferralDao()
                    val total = dao.countReferralCustomers(search)
                    val items = dao.listReferralCustomers(search, limit, offset).map { row ->
                        ReferralCustomerResponse(
                            phone = row.phone,
                            referralCode = row.referralCode,
                            referredBy = row.referredBy,
                            directDownline = row.directDownline,
                            score = row.score,
                            createdAt = row.createdAt,
                        )
                    }
                    ReferralCustomerListResponse(
                        items = items,
                        total = total,
                        page = page,
                        limit = limit,
                    )
                }
                call.respond(result)
            }

            configureHybridCrmRoutes(settingsStore, database, appContext)
        }

        get("/{path...}") {
            val segments = call.parameters.getAll("path") ?: emptyList()
            val relativePath = segments.joinToString("/")
            serveAsset(relativePath, assets, call)
        }
    }
}

private suspend fun ClubAutoMessageRuleEntity.toClubAutoMessageResponse(
    database: GatewayDatabase,
    serialGenerator: SerialGenerator,
): ClubAutoMessageRuleResponse {
    val groupIds = ClubAutoMessageScheduler.decodeSerialGroupIds(serialGroupIdsJson)
    val labels = if (groupIds.isEmpty()) {
        null
    } else {
        groupIds.mapNotNull { groupId ->
            val group = database.serialGroupDao().findById(groupId) ?: return@mapNotNull null
            val warranty = SerialGroupWarranty.formatFa(group.warrantyMonths, group.warrantyKm) ?: "â€”"
            "${serialGenerator.buildGroupLabel(group)} â€” $warranty"
        }.takeIf { it.isNotEmpty() }
    }
    return ClubAutoMessageRuleResponse(
        id = id,
        title = title,
        message = message,
        offsetMonths = offsetMonths,
        offsetDays = offsetDays,
        sendHour = sendHour,
        sendMinute = sendMinute,
        delaySeconds = delaySeconds,
        dailyCap = ClubAutoMessageScheduler.normalizeDailyCap(dailyCap),
        sendSms = sendSms,
        sendRubika = sendRubika,
        sendTelegram = sendTelegram,
        sendBale = sendBale,
        serialCategory = SerialGroupCategory.normalizeOrDefault(serialCategory),
        serialGroupIds = groupIds.takeIf { it.isNotEmpty() },
        serialGroupLabels = labels,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastRunAt = lastRunAt,
        lastRunDateJalali = lastRunDateJalali,
        lastSuccessCount = lastSuccessCount,
        lastFailCount = lastFailCount,
        totalSentCount = database.clubAutoMessageDao().countSendsForRule(id),
        runStatus = runStatus,
        runStartedAt = runStartedAt,
        runTotalCount = runTotalCount,
        runProcessedCount = runProcessedCount,
        runSentCount = runSentCount,
        runFailCount = runFailCount,
        autoSource = autoSource,
        linkedSerialGroupId = linkedSerialGroupId,
    )
}

private suspend fun deriveClubSerialCategory(
    groupIds: List<Long>,
    fallbackCategory: String,
    database: GatewayDatabase,
): String {
    if (groupIds.isEmpty()) {
        return SerialGroupCategory.requireValid(fallbackCategory)
    }
    val categories = groupIds.mapNotNull { id ->
        database.serialGroupDao().findById(id)?.let {
            SerialGroupCategory.normalizeOrDefault(it.category)
        }
    }.distinct()
    return categories.singleOrNull()
        ?: SerialGroupCategory.requireValid(fallbackCategory)
}

private fun validateClubAutoMessage(
    message: String,
    offsetMonths: Int,
    offsetDays: Int,
    sendHour: Int,
    sendMinute: Int,
    delaySeconds: Int,
    dailyCap: Int,
    sendSms: Boolean,
    sendRubika: Boolean,
    sendTelegram: Boolean,
    sendBale: Boolean,
    serialCategory: String,
    serialGroupIds: List<Long>?,
): String? {
    if (message.trim().isEmpty()) return "متن پیام الزامی است"
    if (offsetMonths < 0) return "بازه زمانی نامعتبر است"
    if (offsetDays < -366 || offsetDays > 366) return "تعداد روز باید بین ۳۶۶- تا ۳۶۶ باشد"
    if (offsetMonths == 0 && offsetDays == 0) return "حداقل یک ماه یا یک روز فاصله زمانی لازم است"
    if (offsetDays < 0 && offsetMonths <= 0) {
        return "برای یادآوری قبل از پایان گارانتی، مدت گارانتی (ماه) لازم است"
    }
    if (sendHour !in 0..23) return "ساعت ارسال باید بین ۰ تا ۲۳ باشد"
    if (sendMinute !in 0..59) return "دقیقه ارسال باید بین ۰ تا ۵۹ باشد"
    if (delaySeconds < 1) return "تأخیر بین پیام‌ها باید حداقل ۱ ثانیه باشد"
    if (dailyCap < 1 || dailyCap > 10_000) return "سقف روزانه باید بین ۱ تا ۱۰۰۰۰ باشد"
    if (!sendSms && !sendRubika && !sendTelegram && !sendBale) return "حداقل یک کانال ارسال باید فعال باشد"
    if (serialGroupIds.isNullOrEmpty()) {
        return "حداقل یک گروه سریال انتخاب کنید"
    }
    try {
        SerialGroupCategory.requireValid(serialCategory)
    } catch (e: SerialGenerator.ValidationException) {
        return e.message ?: "دسته سریال نامعتبر است"
    }
    return null
}

private fun normalizeClubAutoSource(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when (value) {
        ClubAutoMessageAutoSource.WARRANTY_END,
        ClubAutoMessageAutoSource.WARRANTY_PRE,
        -> value
        else -> null
    }
}

private fun defaultClubAutoMessageTitle(body: ClubAutoMessageCreateRequest): String {
    val parts = mutableListOf<String>()
    if (body.offsetMonths > 0) parts.add("${body.offsetMonths} ماه")
    when {
        body.offsetDays > 0 -> parts.add("${body.offsetDays} روز")
        body.offsetDays < 0 -> parts.add("${-body.offsetDays} روز مانده")
    }
    val span = if (parts.isEmpty()) "خودکار" else parts.joinToString(" و ")
    return when (normalizeClubAutoSource(body.autoSource)) {
        ClubAutoMessageAutoSource.WARRANTY_END -> "پایان گارانتی — $span"
        ClubAutoMessageAutoSource.WARRANTY_PRE -> "یادآوری گارانتی — $span"
        else -> "پیام خودکار — $span قبل"
    }
}

private fun defaultClubAutoMessageTitle(body: ClubAutoMessageUpdateRequest): String {
    val parts = mutableListOf<String>()
    if (body.offsetMonths > 0) parts.add("${body.offsetMonths} ماه")
    when {
        body.offsetDays > 0 -> parts.add("${body.offsetDays} روز")
        body.offsetDays < 0 -> parts.add("${-body.offsetDays} روز مانده")
    }
    val span = if (parts.isEmpty()) "خودکار" else parts.joinToString(" و ")
    return when (normalizeClubAutoSource(body.autoSource)) {
        ClubAutoMessageAutoSource.WARRANTY_END -> "پایان گارانتی — $span"
        ClubAutoMessageAutoSource.WARRANTY_PRE -> "یادآوری گارانتی — $span"
        else -> "پیام خودکار — $span قبل"
    }
}

/**
 * Sends a single free-text message to [phone] through one tunnel and records it in the unified
 * communication log so it shows up (with the right channel badge) in the conversation thread.
 * Throws with a Persian message on any failure so the caller can surface it per-channel.
 */
private fun sendCommunicationViaChannel(
    appContext: Context,
    database: GatewayDatabase,
    settingsStore: SettingsStore,
    commLog: CommunicationLog,
    channel: String,
    phone: String,
    text: String,
) {
    when (channel) {
        MassSmsChannel.SMS -> {
            try {
                val subscriptionId = SmsOutbound.resolveSubscriptionIdOrThrow(appContext, null)
                val simLabel = SimCardHelper.simLabelForSubscription(appContext, subscriptionId)
                SmsOutbound.send(appContext, subscriptionId, phone, text)
                commLog.logOutgoing(
                    phone = phone,
                    body = text,
                    status = CommunicationLog.STATUS_SENT,
                    simLabel = simLabel,
                    channel = CommunicationLog.CHANNEL_SMS,
                )
            } catch (error: SmsOutboundQuotaExceededException) {
                throw IllegalStateException("سهمیه روزانه پیامک تمام شده", error)
            }
        }
        MassSmsChannel.RUBIKA, MassSmsChannel.TELEGRAM, MassSmsChannel.BALE -> {
            val token = when (channel) {
                MassSmsChannel.RUBIKA -> settingsStore.getRubikaToken()
                MassSmsChannel.TELEGRAM -> settingsStore.getTelegramToken()
                else -> settingsStore.getBaleToken()
            }
            if (token.isBlank()) throw IllegalStateException("Ø±Ø¨Ø§Øª ÙØ¹Ø§Ù„ Ù†ÛŒØ³Øª")
            val chatId = when (channel) {
                MassSmsChannel.RUBIKA -> BotChatIdResolver.resolveRubika(database, phone)
                MassSmsChannel.TELEGRAM -> BotChatIdResolver.resolveTelegram(database, phone)
                else -> BotChatIdResolver.resolveBale(database, phone)
            } ?: throw IllegalStateException("Ú©Ø§Ø±Ø¨Ø± Ø§ÛŒÙ† ØªÙˆÙ†Ù„ Ø±Ø§ ÙØ¹Ø§Ù„ Ù†Ú©Ø±Ø¯Ù‡ Ø§Ø³Øª")
            val simLabel = when (channel) {
                MassSmsChannel.RUBIKA -> "Ø±ÙˆØ¨ÛŒÚ©Ø§"
                MassSmsChannel.TELEGRAM -> "ØªÙ„Ú¯Ø±Ø§Ù…"
                else -> "Ø¨Ù„Ù‡"
            }
            when (channel) {
                MassSmsChannel.RUBIKA -> RubikaApi.getInstance().sendMessage(token, chatId, text)
                MassSmsChannel.TELEGRAM -> TelegramApi.getInstance().sendMessage(token, chatId, text)
                else -> BaleApi.getInstance().sendMessage(token, chatId, text)
            }
            commLog.logOutgoing(
                phone = phone,
                body = text,
                status = CommunicationLog.STATUS_SENT,
                simLabel = simLabel,
                channel = channel,
            )
        }
        else -> throw IllegalArgumentException("ØªÙˆÙ†Ù„ Ù†Ø§Ù…Ø¹ØªØ¨Ø±")
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
    channel = channel,
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
                message = json.optString("message").takeIf { it.isNotEmpty() },
                at = json.optLong("at", at),
            )
            SyncEventWriter.TYPE_SERIALS_ADDED -> SyncChangeResponse(
                type = type,
                inserted = json.optInt("inserted"),
                skipped = json.optInt("skipped"),
                total = json.optInt("total"),
                message = json.optString("message").takeIf { it.isNotEmpty() },
                at = json.optLong("at", at),
            )
            else -> SyncChangeResponse(
                type = type,
                serial = json.optString("serial").takeIf { it.isNotEmpty() },
                phone = PhoneUtils.formatForDisplay(json.optString("phone").takeIf { it.isNotEmpty() }),
                message = json.optString("message").takeIf { it.isNotEmpty() } ?: type,
                key = json.optString("key").takeIf { it.isNotEmpty() }
                    ?: json.optString("channel").takeIf { it.isNotEmpty() },
                title = json.optString("title").takeIf { it.isNotEmpty() },
                removed = if (json.has("removed") && !json.isNull("removed")) json.getInt("removed") else null,
                at = json.optLong("at", at),
            )
        }
    } catch (_: Exception) {
        null
    }
}

private fun SerialGenerator.DuplicateSerialException.toDuplicateResponse() = DuplicateSerialErrorResponse(
    error = message ?: "ØªÙˆÙ„ÛŒØ¯ Ø§Ù†Ø¬Ø§Ù… Ù†Ø´Ø¯ â€” Ø³Ø±ÛŒØ§Ù„â€ŒÙ‡Ø§ÛŒ ØªÚ©Ø±Ø§Ø±ÛŒ ÛŒØ§ÙØª Ø´Ø¯.",
    duplicateCount = duplicateCount,
    duplicateRanges = duplicateRanges.map { range ->
        DuplicateRangeResponse(
            rangeLabel = range.rangeLabel,
            count = range.count,
        )
    },
)

private fun SerialGroupEntity.toResponse(
    serialGenerator: SerialGenerator,
    counts: com.example.smsgateway.data.GroupIdCountRow? = null,
    totalOverride: Int? = null,
    usedOverride: Int? = null,
): SerialGroupResponse {
    val total = totalOverride ?: counts?.total ?: 0
    val used = usedOverride ?: counts?.used ?: 0
    val unused = (total - used).coerceAtLeast(0)
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
        rangeLabel = serialGenerator.buildGroupLabel(this),
        description = description,
        score = score,
        category = SerialGroupCategory.normalizeOrDefault(category),
        generationMode = SerialGroupGenerationMode.normalizeOrDefault(generationMode),
        warrantyMonths = warrantyMonths,
        warrantyKm = warrantyKm,
        imageVerificationRequired = imageVerificationRequired,
        total = total,
        used = used,
        unused = unused,
        salePercentage = salePercentage,
        createdAt = createdAt,
    )
}

private suspend fun SerialGroupEntity.toResponse(
    groupDao: com.example.smsgateway.data.SerialGroupDao,
    serialGenerator: SerialGenerator,
): SerialGroupResponse {
    val total = groupDao.countByGroupId(id)
    val used = groupDao.countUsedByGroupId(id)
    return toResponse(serialGenerator, totalOverride = total, usedOverride = used)
}

private suspend fun SerialPhotoSubmissionEntity.toPhotoResponse(
    database: GatewayDatabase,
    serialGenerator: SerialGenerator,
): SerialPhotoSubmissionResponse {
    val group = groupId?.let { database.serialGroupDao().findById(it) }
    val groupLabel = group?.let { serialGenerator.buildGroupLabel(it) }
    val score = group?.score ?: 0
    return SerialPhotoSubmissionResponse(
        id = id,
        serial = serial,
        serialRowId = serialRowId,
        groupId = groupId,
        groupLabel = groupLabel,
        phone = phone,
        channel = channel,
        status = status,
        imageUrl = "/api/serial-photo-submissions/$id/image",
        mimeType = mimeType,
        score = score,
        scoreAwarded = scoreAwarded,
        createdAt = createdAt,
        reviewedAt = reviewedAt,
        reviewNote = reviewNote,
    )
}

private data class PhoneBotPairing(
    val pairedRubika: Boolean = false,
    val pairedTelegram: Boolean = false,
    val pairedBale: Boolean = false,
)

private fun buildPhonePairingMap(
    database: GatewayDatabase,
    rows: List<SerialEntity>,
): Map<String, PhoneBotPairing> {
    val phones = rows.mapNotNull { row ->
        PhoneUtils.formatForDisplay(row.phone)?.takeIf { it.isNotBlank() }
    }.distinct()
    if (phones.isEmpty()) return emptyMap()

    val rubikaDao = database.rubikaChatContactDao()
    val telegramDao = database.telegramChatContactDao()
    val baleDao = database.baleChatContactDao()
    return phones.associateWith { phone ->
        PhoneBotPairing(
            pairedRubika = rubikaDao.countByPhone(phone) > 0,
            pairedTelegram = telegramDao.countByPhone(phone) > 0,
            pairedBale = baleDao.countByPhone(phone) > 0,
        )
    }
}

private fun ReferralSettings.toResponse(): ReferralSettingsResponse {
    return ReferralSettingsResponse(
        enabled = enabled,
        basePoints = basePoints,
        decayPercent = decayPercent,
        maxDepth = maxDepth,
    )
}

private suspend fun SerialEntity.toResponse(
    database: GatewayDatabase,
    @Suppress("UNUSED_PARAMETER") config: SmsProcessingConfig,
    pairingByPhone: Map<String, PhoneBotPairing> = emptyMap(),
    knownCityByPhone: Map<String, String> = emptyMap(),
): SerialRowResponse {
    val displayPhone = PhoneUtils.formatForDisplay(phone)
    val pairing = displayPhone?.let { pairingByPhone[it] } ?: PhoneBotPairing()
    val category = groupId?.let { gid ->
        SerialGroupCategory.normalizeOrDefault(
            database.serialGroupDao().findById(gid)?.category,
        )
    } ?: SerialGroupCategory.DEFAULT
    val phoneKey = phone?.trim().orEmpty()
    val knownCity = when {
        phoneKey.isEmpty() -> null
        knownCityByPhone.containsKey(phoneKey) -> knownCityByPhone[phoneKey]
        else -> database.serialDao().findLatestCityByPhone(phoneKey)?.takeIf { it.isNotBlank() }
    }
    val missing = RegistrationFieldRules.missingFields(city, km, category, knownCity)
    val storedCity = city?.takeIf { it.isNotBlank() }
    val known = knownCity?.takeIf { it.isNotBlank() }
    val cityInherited = storedCity == null && known != null
    val displayCity = if (cityInherited) known else storedCity
    return SerialRowResponse(
        id = id,
        serial = serial,
        phone = displayPhone,
        date = date,
        city = displayCity,
        km = km,
        needsCompletion = RegistrationFieldRules.needsCompletion(
            phone,
            city,
            km,
            category,
            knownCity,
        ),
        missingCity = missing.city,
        missingKm = missing.km,
        cityInherited = cityInherited,
        category = category,
        completionReminderSent = completionReminderSent(),
        completionReminderSentAt = completionReminderSentAt,
        registrationSource = registrationSource,
        pairedRubika = pairing.pairedRubika,
        pairedTelegram = pairing.pairedTelegram,
        pairedBale = pairing.pairedBale,
    )
}

private sealed class ReminderResult {
    data object NotFound : ReminderResult()
    data object NoPhone : ReminderResult()
    data object AlreadyComplete : ReminderResult()
    data object AlreadySent : ReminderResult()
    data class SendFailed(val message: String) : ReminderResult()
    data class Success(val row: SerialEntity) : ReminderResult()
}

private sealed class UpdateSerialResult {
    data object NotFound : UpdateSerialResult()
    data object NotRegistered : UpdateSerialResult()
    data object DuplicateSerial : UpdateSerialResult()
    data class Success(val row: SerialEntity) : UpdateSerialResult()
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
            LanTraceLog.e(TAG, "CRM assets missing â€” crm/index.html not found in APK")
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
        else -> return "Ù†ÙˆØ¹ Ù…Ø®Ø§Ø·Ø¨ Ù†Ø§Ù…Ø¹ØªØ¨Ø± Ø§Ø³Øª"
    }
    if (message != null && message.trim().isEmpty()) {
        return "Ù…ØªÙ† Ù¾ÛŒØ§Ù… Ø§Ù„Ø²Ø§Ù…ÛŒ Ø§Ø³Øª"
    }
    return null
}

private fun validateMassSmsChannel(channel: String, settingsStore: SettingsStore): String? {
    if (channel !in MassSmsChannel.ALL) {
        return "Ú©Ø§Ù†Ø§Ù„ Ø§Ø±Ø³Ø§Ù„ Ù†Ø§Ù…Ø¹ØªØ¨Ø± Ø§Ø³Øª"
    }
    val active = when (channel) {
        MassSmsChannel.RUBIKA -> settingsStore.getRubikaToken().isNotBlank()
        MassSmsChannel.TELEGRAM -> settingsStore.getTelegramToken().isNotBlank()
        MassSmsChannel.BALE -> settingsStore.getBaleToken().isNotBlank()
        else -> true
    }
    if (!active) {
        return "Ø±Ø¨Ø§Øª Ø§Ù†ØªØ®Ø§Ø¨â€ŒØ´Ø¯Ù‡ ÙØ¹Ø§Ù„ Ù†ÛŒØ³Øª"
    }
    return null
}

private suspend fun validateMassSmsSerialGroups(
    serialGroupIds: List<Long>?,
    groupDao: com.example.smsgateway.data.SerialGroupDao,
): String? {
    if (serialGroupIds == null) return null
    if (serialGroupIds.isEmpty()) return "Ø­Ø¯Ø§Ù‚Ù„ ÛŒÚ© Ú¯Ø±ÙˆÙ‡ Ø³Ø±ÛŒØ§Ù„ Ø§Ù†ØªØ®Ø§Ø¨ Ú©Ù†ÛŒØ¯"
    for (id in serialGroupIds) {
        if (groupDao.findById(id) == null) {
            return "Ú¯Ø±ÙˆÙ‡ Ø³Ø±ÛŒØ§Ù„ Ù†Ø§Ù…Ø¹ØªØ¨Ø± Ø§Ø³Øª"
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
        return "ØªØ§Ø±ÛŒØ® Ø´Ø±ÙˆØ¹ Ø«Ø¨Øªâ€ŒÙ†Ø§Ù… Ù†Ø§Ù…Ø¹ØªØ¨Ø± Ø§Ø³Øª"
    }
    if (toTrim.isNotEmpty() && !massSmsJalaliDatePattern.matches(toTrim)) {
        return "ØªØ§Ø±ÛŒØ® Ù¾Ø§ÛŒØ§Ù† Ø«Ø¨Øªâ€ŒÙ†Ø§Ù… Ù†Ø§Ù…Ø¹ØªØ¨Ø± Ø§Ø³Øª"
    }
    if (fromTrim.isNotEmpty() && toTrim.isNotEmpty()) {
        val fromNorm = JalaliHelper.normalizeFromDateParam(fromTrim).substring(0, 10)
        val toNorm = JalaliHelper.normalizeToDateParam(toTrim).substring(0, 10)
        if (fromNorm > toNorm) {
            return "ØªØ§Ø±ÛŒØ® Ø´Ø±ÙˆØ¹ Ù†Ù…ÛŒâ€ŒØªÙˆØ§Ù†Ø¯ Ø¨Ø¹Ø¯ Ø§Ø² ØªØ§Ø±ÛŒØ® Ù¾Ø§ÛŒØ§Ù† Ø¨Ø§Ø´Ø¯"
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
    excludePairedRubika = excludePairedRubika,
    excludePairedTelegram = excludePairedTelegram,
    excludePairedBale = excludePairedBale,
)

private fun MassSmsCreateRequest.toFilter() = MassSmsFilterRequest(
    audienceType = audienceType,
    provinceId = provinceId?.takeIf { it.isNotBlank() },
    cityName = cityName?.takeIf { it.isNotBlank() },
    serialGroupIds = serialGroupIds?.takeIf { it.isNotEmpty() },
    registrationFromDate = registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
    registrationToDate = registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
    excludePairedRubika = excludePairedRubika,
    excludePairedTelegram = excludePairedTelegram,
    excludePairedBale = excludePairedBale,
)

private suspend fun MassSmsCampaignEntity.toResponse(
    provinces: List<IranProvince>,
    queuedCampaigns: List<MassSmsCampaignEntity>,
    groupDao: com.example.smsgateway.data.SerialGroupDao,
    serialGenerator: SerialGenerator,
    mediaDao: com.example.smsgateway.data.BotMediaAssetDao,
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
    val mediaFileName = mediaAssetId?.let { mediaDao.findById(it)?.fileName }
    return MassSmsCampaignResponse(
        id = id,
        message = message,
        delaySeconds = delaySeconds,
        audienceType = audienceType,
        channel = channel,
        mediaAssetId = mediaAssetId,
        mediaFileName = mediaFileName,
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
        skippedCount = skippedCount,
        pendingCount = (totalCount - sentCount - failedCount - skippedCount).coerceAtLeast(0),
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

private fun WhatsAppConfig.toConfigResponse() = WhatsAppConfigResponse(
    searchBoxSelector = searchBoxSelector,
    searchResultSelector = searchResultSelector,
    messageInputSelector = messageInputSelector,
    sendButtonSelector = sendButtonSelector,
    openMode = openMode,
    searchByPhoneFirst = searchByPhoneFirst,
    sendDelaySeconds = sendDelaySeconds,
    perContactTimeoutMs = perContactTimeoutMs,
)

private fun SettingsStore.toWhatsAppEngineStatusResponse(): WhatsAppEngineStatusResponse {
    val lastSeen = getWhatsAppEngineLastSeen().takeIf { it > 0L }
    val online = lastSeen != null &&
        System.currentTimeMillis() - lastSeen <= WHATSAPP_ENGINE_STALE_THRESHOLD_MS
    return WhatsAppEngineStatusResponse(
        online = online,
        loggedIn = online && isWhatsAppEngineLoggedIn(),
        lastSeen = lastSeen,
        staleThresholdMs = WHATSAPP_ENGINE_STALE_THRESHOLD_MS,
    )
}

private suspend fun WhatsAppCampaignEntity.toWhatsAppResponse(
    provinces: List<IranProvince>,
    queuedCampaigns: List<WhatsAppCampaignEntity>,
    groupDao: com.example.smsgateway.data.SerialGroupDao,
    serialGenerator: SerialGenerator,
): WhatsAppCampaignResponse {
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
    return WhatsAppCampaignResponse(
        id = id,
        message = message,
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

private fun WhatsAppRecipientEntity.toWhatsAppRecipientResponse() = WhatsAppRecipientResponse(
    id = id,
    phone = PhoneUtils.formatForDisplay(phone) ?: phone,
    phoneIntl = phoneIntl,
    contactName = contactName,
    status = status,
    sentAt = sentAt,
    errorMessage = errorMessage,
)

private fun WhatsAppPreviewRequest.toFilter() = MassSmsFilterRequest(
    audienceType = audienceType,
    provinceId = provinceId?.takeIf { it.isNotBlank() },
    cityName = cityName?.takeIf { it.isNotBlank() },
    serialGroupIds = serialGroupIds?.takeIf { it.isNotEmpty() },
    registrationFromDate = registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
    registrationToDate = registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
    excludePairedRubika = excludePairedRubika,
    excludePairedTelegram = excludePairedTelegram,
    excludePairedBale = excludePairedBale,
)

private fun WhatsAppCreateRequest.toFilter() = MassSmsFilterRequest(
    audienceType = audienceType,
    provinceId = provinceId?.takeIf { it.isNotBlank() },
    cityName = cityName?.takeIf { it.isNotBlank() },
    serialGroupIds = serialGroupIds?.takeIf { it.isNotEmpty() },
    registrationFromDate = registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
    registrationToDate = registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
    excludePairedRubika = excludePairedRubika,
    excludePairedTelegram = excludePairedTelegram,
    excludePairedBale = excludePairedBale,
)

private const val WHATSAPP_ENGINE_STALE_THRESHOLD_MS = 45_000L

private fun buildRubikaStatus(settingsStore: SettingsStore, appContext: Context): RubikaStatusResponse {
    val botInfo = settingsStore.getRubikaBotInfo()
    return RubikaStatusResponse(
        active = settingsStore.isRubikaBotActive(),
        botTitle = botInfo?.title?.takeIf { it.isNotBlank() },
        botUsername = botInfo?.username?.takeIf { it.isNotBlank() },
        serialEnabled = settingsStore.isRubikaSerialEnabled(),
        inviteEnabled = settingsStore.isRubikaInviteEnabled(),
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
        inviteEnabled = settingsStore.isTelegramInviteEnabled(),
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

private fun RubikaSettingsResponse.toBotMenuSettings() = BotMenuSettings(
    registerWarranty = menuRegisterWarranty,
    warrantyStatus = menuWarrantyStatus,
    sendProductPhoto = menuSendProductPhoto,
    inviteFriends = menuInviteFriends,
    myScore = menuMyScore,
    support = menuSupport,
    help = menuHelp,
)

private fun TelegramSettingsResponse.toBotMenuSettings() = BotMenuSettings(
    registerWarranty = menuRegisterWarranty,
    warrantyStatus = menuWarrantyStatus,
    sendProductPhoto = menuSendProductPhoto,
    inviteFriends = menuInviteFriends,
    myScore = menuMyScore,
    support = menuSupport,
    help = menuHelp,
)

private fun SettingsStore.toRubikaSettingsResponse(): RubikaSettingsResponse {
    val menu = getRubikaMenuSettings()
    return RubikaSettingsResponse(
        serialEnabled = isRubikaSerialEnabled(),
        inviteEnabled = isRubikaInviteEnabled(),
        inviteMessage = getRubikaInviteMessage(),
        menuRegisterWarranty = menu.registerWarranty,
        menuWarrantyStatus = menu.warrantyStatus,
        menuSendProductPhoto = menu.sendProductPhoto,
        menuInviteFriends = menu.inviteFriends,
        menuMyScore = menu.myScore,
        menuSupport = menu.support,
        menuHelp = menu.help,
    )
}

private fun SettingsStore.toTelegramSettingsResponse(): TelegramSettingsResponse {
    val menu = getTelegramMenuSettings()
    return TelegramSettingsResponse(
        serialEnabled = isTelegramSerialEnabled(),
        inviteEnabled = isTelegramInviteEnabled(),
        inviteMessage = getTelegramInviteMessage(),
        menuRegisterWarranty = menu.registerWarranty,
        menuWarrantyStatus = menu.warrantyStatus,
        menuSendProductPhoto = menu.sendProductPhoto,
        menuInviteFriends = menu.inviteFriends,
        menuMyScore = menu.myScore,
        menuSupport = menu.support,
        menuHelp = menu.help,
    )
}

private fun buildBaleStatus(settingsStore: SettingsStore, appContext: Context): BaleStatusResponse {
    val botInfo = settingsStore.getBaleBotInfo()
    return BaleStatusResponse(
        active = settingsStore.isBaleBotActive(),
        botTitle = botInfo?.title?.takeIf { it.isNotBlank() },
        botUsername = botInfo?.username?.takeIf { it.isNotBlank() },
        serialEnabled = settingsStore.isBaleSerialEnabled(),
        inviteEnabled = settingsStore.isBaleInviteEnabled(),
        polling = BalePoller.getInstance(appContext).isPolling(),
    )
}

private fun BaleChatItem.toBaleResponse() = BaleMessageResponse(
    messageId = messageId,
    chatId = chatId,
    senderId = senderId,
    text = text,
    timestamp = timestamp,
    direction = direction,
    phoneNumber = phoneNumber,
    senderName = senderName,
)

private fun BaleSettingsResponse.toBotMenuSettings() = BotMenuSettings(
    registerWarranty = menuRegisterWarranty,
    warrantyStatus = menuWarrantyStatus,
    sendProductPhoto = menuSendProductPhoto,
    inviteFriends = menuInviteFriends,
    myScore = menuMyScore,
    support = menuSupport,
    help = menuHelp,
)

private fun SettingsStore.toBaleSettingsResponse(): BaleSettingsResponse {
    val menu = getBaleMenuSettings()
    return BaleSettingsResponse(
        serialEnabled = isBaleSerialEnabled(),
        inviteEnabled = isBaleInviteEnabled(),
        inviteMessage = getBaleInviteMessage(),
        menuRegisterWarranty = menu.registerWarranty,
        menuWarrantyStatus = menu.warrantyStatus,
        menuSendProductPhoto = menu.sendProductPhoto,
        menuInviteFriends = menu.inviteFriends,
        menuMyScore = menu.myScore,
        menuSupport = menu.support,
        menuHelp = menu.help,
    )
}

