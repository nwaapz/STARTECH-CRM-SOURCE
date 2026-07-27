package com.example.smsgateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GatewayApi(private val settingsStore: SettingsStore) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class GatewayResponse(
        val status: String,
        val reply: String,
        val firstRegistration: Boolean = false,
        val registrationSucceeded: Boolean = false,
        /** True when the registered serial's group requires a product photo for score. */
        val imageVerificationRequired: Boolean = false,
        val registeredSerial: String? = null,
        /** SIM that should send the reply (sticky / budget-aware). */
        val replySubscriptionId: Int? = null,
    )

    class GatewayException(message: String) : Exception(message)

    fun forwardSms(from: String, message: String): GatewayResponse {
        val url = settingsStore.getGatewayUrl()
        if (url.isBlank()) {
            throw GatewayException("Gateway URL is not configured")
        }

        val payload = JSONObject()
            .put("from", from)
            .put("message", message)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(payload)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val body = try {
                JSONObject(bodyText)
            } catch (_: Exception) {
                throw GatewayException("Invalid JSON response (${response.code})")
            }

            if (!response.isSuccessful) {
                val error = body.optString("error").ifBlank { "HTTP ${response.code}" }
                throw GatewayException(error)
            }

            val reply = body.optString("reply").ifBlank {
                body.optString("reply_content")
            }
            if (reply.isBlank()) {
                throw GatewayException("Response missing reply field")
            }

            return GatewayResponse(
                status = body.optString("status", "success"),
                reply = reply,
            )
        }
    }
}
