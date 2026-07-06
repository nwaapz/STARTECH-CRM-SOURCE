package com.example.smsgateway.lan

import com.example.smsgateway.SettingsStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

object LanAuth {
    fun isAuthorized(call: ApplicationCall, settingsStore: SettingsStore): Boolean {
        val authHeader = call.request.headers["Authorization"] ?: return false
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return false
        val token = authHeader.removePrefix("Bearer ").trim()
        val expected = settingsStore.getLanPin()
        return token.isNotEmpty() && token == expected
    }

    suspend fun unauthorized(call: ApplicationCall) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(error = "Invalid PIN"))
    }
}
