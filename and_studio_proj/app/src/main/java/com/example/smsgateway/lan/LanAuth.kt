package com.example.smsgateway.lan

import com.example.smsgateway.CrmAuthService
import com.example.smsgateway.SettingsStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.runBlocking

enum class LanAccessResult {
    OK,
    BAD_PIN,
    LICENSE_BLOCKED,
}

object LanAuth {
    fun checkAccess(
        call: ApplicationCall,
        settingsStore: SettingsStore,
        appContext: android.content.Context? = null,
    ): LanAccessResult {
        val authHeader = call.request.headers["Authorization"] ?: return LanAccessResult.BAD_PIN
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return LanAccessResult.BAD_PIN
        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isEmpty()) return LanAccessResult.BAD_PIN

        val expectedPin = settingsStore.getLanPin()
        val pinOk = token == expectedPin
        val sessionOk = if (!pinOk && appContext != null) {
            runBlocking {
                CrmAuthService.getInstance(appContext).resolveBearer(token, expectedPin) != null
            }
        } else {
            false
        }
        if (!pinOk && !sessionOk) return LanAccessResult.BAD_PIN

        if (appContext != null &&
            !com.example.smsgateway.license.LicenseGate.allowsOperationalUse(appContext)
        ) {
            return LanAccessResult.LICENSE_BLOCKED
        }
        return LanAccessResult.OK
    }

    fun isAuthorized(
        call: ApplicationCall,
        settingsStore: SettingsStore,
        appContext: android.content.Context? = null,
    ): Boolean = checkAccess(call, settingsStore, appContext) == LanAccessResult.OK

    suspend fun unauthorized(call: ApplicationCall) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Invalid PIN"))
    }

    suspend fun licenseForbidden(call: ApplicationCall) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(error = "License check required"),
        )
    }

    suspend fun reject(call: ApplicationCall, result: LanAccessResult) {
        when (result) {
            LanAccessResult.OK -> Unit
            LanAccessResult.BAD_PIN -> unauthorized(call)
            LanAccessResult.LICENSE_BLOCKED -> licenseForbidden(call)
        }
    }
}
