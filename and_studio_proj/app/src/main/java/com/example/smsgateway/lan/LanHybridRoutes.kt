package com.example.smsgateway.lan

import android.content.Context
import com.example.smsgateway.CrmAuthService
import com.example.smsgateway.CrmRoles
import com.example.smsgateway.JalaliHelper
import com.example.smsgateway.LotteryService
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.cloud.CloudSyncService
import com.example.smsgateway.data.GatewayDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hybrid CRM routes: auth, admin users, lottery, daily chart, cloud sync status.
 * Registered inside `/api` alongside existing LAN routes.
 */
fun Route.configureHybridCrmRoutes(
    settingsStore: SettingsStore,
    database: GatewayDatabase,
    appContext: Context,
) {
    val authService = CrmAuthService.getInstance(appContext)
    val lotteryService = LotteryService.getInstance(appContext)

    suspend fun requireUser(
        call: ApplicationCall,
        minRole: Int = CrmRoles.REPORTER,
    ): com.example.smsgateway.CrmAuthUser? {
        val header = call.request.headers["Authorization"]
        val token = if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            header.removePrefix("Bearer ").trim()
        } else {
            null
        }
        if (token.isNullOrEmpty()) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Missing token"))
            return null
        }
        if (appContext.let { !com.example.smsgateway.license.LicenseGate.allowsOperationalUse(it) }) {
            LanAuth.licenseForbidden(call)
            return null
        }
        val user = authService.resolveBearer(token, settingsStore.getLanPin())
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Invalid credentials"))
            return null
        }
        if (user.role < minRole) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(error = "Insufficient role"))
            return null
        }
        return user
    }

    post("/auth/login") {
        // Login itself: allow without prior session; still require license when context present
        if (!com.example.smsgateway.license.LicenseGate.allowsOperationalUse(appContext)) {
            LanAuth.licenseForbidden(call)
            return@post
        }
        val body = call.receive<AuthLoginRequest>()
        val result = authService.login(body.username, body.password)
        if (result == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Invalid credentials"))
            return@post
        }
        val (token, user) = result
        call.respond(AuthLoginResponse(token = token, username = user.username, role = user.role))
    }

    get("/auth/me") {
        val user = requireUser(call) ?: return@get
        call.respond(AuthMeResponse(id = user.id, username = user.username, role = user.role))
    }

    route("/admin") {
        get("/users") {
            val user = requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@get
            val users = authService.listUsers().map {
                CrmUserResponse(
                    id = it.id,
                    username = it.username,
                    role = it.role,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            call.respond(users)
        }

        post("/users") {
            requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@post
            val body = call.receive<CrmUserCreateRequest>()
            if (body.username.isBlank() || body.password.length < 4) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Invalid username/password"))
                return@post
            }
            try {
                val created = authService.createUser(body.username, body.password, body.role)
                call.respond(
                    CrmUserResponse(
                        id = created.id,
                        username = created.username,
                        role = created.role,
                        createdAt = created.createdAt,
                        updatedAt = created.updatedAt,
                    ),
                )
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error = "Username exists"))
            }
        }

        delete("/users/{id}") {
            val me = requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Bad id"))
                return@delete
            }
            if (id == me.id) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Cannot delete self"))
                return@delete
            }
            authService.deleteUser(id)
            call.respond(OkResponse(ok = true))
        }

        put("/users/{id}/password") {
            requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@put
            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Bad id"))
                return@put
            }
            val body = call.receive<CrmPasswordChangeRequest>()
            if (body.password.length < 4) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Password too short"))
                return@put
            }
            if (!authService.changePassword(id, body.password)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "User not found"))
                return@put
            }
            call.respond(OkResponse(ok = true))
        }

        get("/sync-status") {
            requireUser(call, CrmRoles.MANAGER) ?: return@get
            val pending = withContext(Dispatchers.IO) { database.syncOutboxDao().countPending() }
            val url = settingsStore.getCloudSyncUrl()
            call.respond(
                CloudSyncStatusResponse(
                    configured = url.isNotBlank() && settingsStore.getCloudSyncToken().isNotBlank(),
                    url = url.ifBlank { null },
                    pendingOutbox = pending,
                    lastPushMs = settingsStore.getCloudSyncLastPushMs(),
                    lastPullMs = settingsStore.getCloudSyncLastPullMs(),
                ),
            )
        }

        put("/sync-config") {
            requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@put
            val body = call.receive<CloudSyncConfigRequest>()
            body.url?.let { settingsStore.setCloudSyncUrl(it) }
            body.token?.let { settingsStore.setCloudSyncToken(it) }
            call.respond(OkResponse(ok = true))
        }

        post("/sync-now") {
            requireUser(call, CrmRoles.MANAGER) ?: return@post
            val result = CloudSyncService.getInstance(appContext).syncOnce()
            call.respond(OkResponse(ok = result.ok, message = result.message))
        }
    }

    route("/lottery") {
        get("/participants") {
            requireUser(call, CrmRoles.MANAGER) ?: return@get
            val city = call.request.queryParameters["city"]
            val phone = call.request.queryParameters["phone"]
            val items = lotteryService.listParticipants(city, phone).map {
                LotteryParticipantResponse(
                    phone = PhoneUtils.formatForDisplay(it.phone) ?: it.phone,
                    score = it.score,
                    city = it.city,
                )
            }
            call.respond(LotteryParticipantListResponse(items = items, total = items.size))
        }

        post("/draw") {
            requireUser(call, CrmRoles.MANAGER) ?: return@post
            val city = call.request.queryParameters["city"]
            val participants = lotteryService.listParticipants(city, null)
            val winner = lotteryService.drawWeighted(participants)
            if (winner == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Empty pool"))
                return@post
            }
            call.respond(
                LotteryDrawResponse(
                    phone = PhoneUtils.formatForDisplay(winner.phone) ?: winner.phone,
                    score = winner.score,
                    city = winner.city,
                ),
            )
        }

        post("/confirm") {
            requireUser(call, CrmRoles.MANAGER) ?: return@post
            val body = call.receive<LotteryConfirmRequest>()
            val winner = lotteryService.confirmWinner(body.phone)
            CloudSyncService.getInstance(appContext).enqueueLotteryWinner(winner)
            call.respond(
                LotteryWinnerResponse(
                    id = winner.id,
                    phone = PhoneUtils.formatForDisplay(winner.phone) ?: winner.phone,
                    city = winner.city,
                    scoreAtWin = winner.scoreAtWin,
                    dateJalali = winner.dateJalali,
                    wonAt = winner.wonAt,
                ),
            )
        }

        get("/winners") {
            requireUser(call, CrmRoles.MANAGER) ?: return@get
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val (items, total) = lotteryService.listWinners(page, limit)
            call.respond(
                LotteryWinnerListResponse(
                    items = items.map {
                        LotteryWinnerResponse(
                            id = it.id,
                            phone = PhoneUtils.formatForDisplay(it.phone) ?: it.phone,
                            city = it.city,
                            scoreAtWin = it.scoreAtWin,
                            dateJalali = it.dateJalali,
                            wonAt = it.wonAt,
                        )
                    },
                    total = total,
                    page = page,
                    limit = limit,
                ),
            )
        }

        delete("/winners/{id}") {
            requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Bad id"))
                return@delete
            }
            lotteryService.deleteWinner(id)
            call.respond(OkResponse(ok = true))
        }

        get("/suspended") {
            requireUser(call, CrmRoles.MANAGER) ?: return@get
            call.respond(
                lotteryService.listSuspended().map {
                    mapOf(
                        "phone" to (PhoneUtils.formatForDisplay(it.phone) ?: it.phone),
                        "reason" to (it.reason ?: ""),
                        "createdAt" to it.createdAt.toString(),
                    )
                },
            )
        }

        post("/suspend") {
            requireUser(call, CrmRoles.MANAGER) ?: return@post
            val body = call.receive<LotterySuspendRequest>()
            lotteryService.suspendPhone(body.phone, body.reason)
            call.respond(OkResponse(ok = true))
        }

        delete("/suspend/{phone}") {
            requireUser(call, CrmRoles.MANAGER) ?: return@delete
            val phone = call.parameters["phone"] ?: return@delete
            lotteryService.unsuspendPhone(phone)
            call.respond(OkResponse(ok = true))
        }

        get("/preset") {
            requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@get
            val phone = settingsStore.getLotteryPresetPhone().ifBlank { null }
            call.respond(LotteryPresetResponse(phone = phone))
        }

        put("/preset") {
            requireUser(call, CrmRoles.SUPER_ADMIN) ?: return@put
            val body = call.receive<LotteryPresetRequest>()
            if (body.phone.isNullOrBlank()) {
                settingsStore.clearLotteryPresetPhone()
            } else {
                settingsStore.setLotteryPresetPhone(body.phone)
            }
            call.respond(LotteryPresetResponse(phone = settingsStore.getLotteryPresetPhone().ifBlank { null }))
        }
    }

    get("/stats/registrations-by-day") {
        requireUser(call) ?: return@get
        val (defaultFrom, defaultTo) = JalaliHelper.currentJalaliMonthRange()
        val from = call.request.queryParameters["from"]?.let {
            JalaliHelper.datePrefixForCompare(it)
        } ?: defaultFrom
        val to = call.request.queryParameters["to"]?.let {
            JalaliHelper.datePrefixForCompare(it)
        } ?: defaultTo
        val counts = withContext(Dispatchers.IO) {
            database.serialDao().countRegisteredByJalaliDay(from, to)
        }.associate { it.day to it.count }
        // Zero-fill every day in range (inclusive) when same Jalali month; otherwise fill from-to by day strings
        val days = buildList {
            val fromParts = from.split("/").mapNotNull { it.toIntOrNull() }
            val toParts = to.split("/").mapNotNull { it.toIntOrNull() }
            if (fromParts.size == 3 && toParts.size == 3 &&
                fromParts[0] == toParts[0] && fromParts[1] == toParts[1]
            ) {
                val y = fromParts[0]
                val m = fromParts[1]
                val last = JalaliHelper.jalaliDaysInMonth(y, m)
                for (d in 1..last) {
                    val key = JalaliHelper.formatJalaliDate(y, m, d)
                    if (key < from || key > to) continue
                    add(DayCountResponse(day = key, count = counts[key] ?: 0))
                }
            } else {
                // Fallback: only days that have data + endpoints
                val keys = (counts.keys + from + to).toSortedSet()
                for (key in keys) {
                    if (key < from || key > to) continue
                    add(DayCountResponse(day = key, count = counts[key] ?: 0))
                }
            }
        }
        call.respond(RegistrationsByDayResponse(from = from, to = to, days = days))
    }
}
