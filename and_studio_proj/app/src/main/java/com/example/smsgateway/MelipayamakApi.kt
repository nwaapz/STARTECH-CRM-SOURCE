package com.example.smsgateway

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MelipayamakApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

object MelipayamakApi {
    private const val SEND_URL = "https://rest.payamak-panel.com/api/SendSMS/SendSMS"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun send(
        username: String,
        password: String,
        from: String,
        to: String,
        text: String,
    ) {
        val normalizedTo = PhoneUtils.normalizeForSmsSend(to)
        require(normalizedTo.isNotEmpty()) { "Phone number is empty" }
        require(username.isNotBlank()) { "Melipayamak username is not configured" }
        require(password.isNotBlank()) { "Melipayamak password is not configured" }
        require(from.isNotBlank()) { "Melipayamak sender line is not configured" }
        require(text.isNotBlank()) { "SMS text is empty" }

        val body = FormBody.Builder()
            .add("username", username.trim())
            .add("password", password)
            .add("to", normalizedTo)
            .add("from", from.trim())
            .add("text", text)
            .add("isFlash", "false")
            .build()

        val request = Request.Builder()
            .url(SEND_URL)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw MelipayamakApiException(
                        "Melipayamak HTTP ${response.code}: ${responseText.take(200)}",
                    )
                }
                parseSuccess(responseText)
            }
        } catch (error: MelipayamakApiException) {
            throw error
        } catch (error: Exception) {
            throw MelipayamakApiException("Melipayamak send failed: ${error.message}", error)
        }
    }

    private fun parseSuccess(responseText: String) {
        if (responseText.isBlank()) return
        val json = try {
            JSONObject(responseText)
        } catch (_: Exception) {
            if (responseText.contains("ok", ignoreCase = true)) return
            throw MelipayamakApiException("Invalid Melipayamak response: ${responseText.take(200)}")
        }

        val retStatus = json.optInt("RetStatus", -1)
        if (retStatus == 1) return

        val strStatus = json.optString("StrRetStatus").ifBlank { json.optString("Value") }
        val message = strStatus.ifBlank { "Melipayamak error (RetStatus=$retStatus)" }
        throw MelipayamakApiException(message)
    }
}
