package com.example.smsgateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RubikaApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    class RubikaException(message: String) : Exception(message)

    data class RubikaMessageUpdate(
        val chatId: String,
        val messageId: String,
        val text: String,
        val senderId: String? = null,
        val senderType: String? = null,
        val phoneNumber: String? = null,
        val contactFirstName: String? = null,
        val contactLastName: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class RubikaUpdatesResult(
        val updates: List<RubikaMessageUpdate>,
        val nextOffsetId: String?,
    )

    data class RubikaChatInfo(
        val chatId: String,
        val chatType: String? = null,
        val userId: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val title: String? = null,
        val username: String? = null,
    )

    fun getMe(token: String): RubikaBotInfo {
        val body = post(token, "getMe", JSONObject())
        val bot = body.getJSONObject("data").getJSONObject("bot")
        return RubikaBotInfo(
            botId = bot.optString("bot_id"),
            title = bot.optString("bot_title"),
            username = bot.optString("username"),
        )
    }

    fun getChat(token: String, chatId: String): RubikaChatInfo {
        val body = post(token, "getChat", JSONObject().put("chat_id", chatId))
        val chat = body.getJSONObject("data").getJSONObject("chat")
        return RubikaChatInfo(
            chatId = chat.optString("chat_id", chatId),
            chatType = chat.optString("chat_type").takeIf { it.isNotBlank() },
            userId = chat.optString("user_id").takeIf { it.isNotBlank() },
            firstName = chat.optString("first_name").takeIf { it.isNotBlank() },
            lastName = chat.optString("last_name").takeIf { it.isNotBlank() },
            title = chat.optString("title").takeIf { it.isNotBlank() },
            username = chat.optString("username").takeIf { it.isNotBlank() },
        )
    }

    fun sendMessage(token: String, chatId: String, text: String): String {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
        val body = post(token, "sendMessage", payload)
        return body.getJSONObject("data").optString("message_id")
    }

    fun sendPhoneNumberRequest(
        token: String,
        chatId: String,
        text: String,
        buttonText: String,
    ): String {
        val button = JSONObject()
            .put("id", "ask_phone")
            .put("type", "AskMyPhoneNumber")
            .put("button_text", buttonText)
        val row = JSONObject().put("buttons", org.json.JSONArray().put(button))
        val keypad = JSONObject()
            .put("rows", org.json.JSONArray().put(row))
            .put("resize_keyboard", true)
            .put("one_time_keyboard", true)
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("chat_keypad_type", "New")
            .put("chat_keypad", keypad)
        val body = post(token, "sendMessage", payload)
        return body.getJSONObject("data").optString("message_id")
    }

    fun getUpdates(token: String, offsetId: String?, limit: Int = 10): RubikaUpdatesResult {
        val payload = JSONObject().put("limit", limit)
        if (!offsetId.isNullOrBlank()) {
            payload.put("offset_id", offsetId)
        }
        val body = post(token, "getUpdates", payload)
        val data = body.getJSONObject("data")
        val updatesArray = data.optJSONArray("updates") ?: org.json.JSONArray()
        val updates = buildList {
            for (index in 0 until updatesArray.length()) {
                val update = updatesArray.getJSONObject(index)
                if (update.optString("type") != "NewMessage") continue
                val chatId = update.optString("chat_id")
                val newMessage = update.optJSONObject("new_message") ?: continue
                val messageId = newMessage.optString("message_id")
                if (chatId.isBlank() || messageId.isBlank()) continue

                val contact = newMessage.optJSONObject("contact_message")
                    ?: newMessage.optJSONObject("contact")
                val phoneNumber = extractPhoneFromMessage(newMessage)
                val contactFirstName = contact?.optString("first_name")?.takeIf { it.isNotBlank() }
                val contactLastName = contact?.optString("last_name")?.takeIf { it.isNotBlank() }
                val messageTime = newMessage.optLong("time", 0L).let { raw ->
                    if (raw > 0L) raw * 1000L else System.currentTimeMillis()
                }
                val text = newMessage.optString("text")
                val displayText = when {
                    phoneNumber != null && text.isBlank() -> phoneNumber
                    text.isNotBlank() -> text
                    else -> phoneNumber.orEmpty()
                }

                add(
                    RubikaMessageUpdate(
                        chatId = chatId,
                        messageId = messageId,
                        text = displayText,
                        senderId = newMessage.optString("sender_id").takeIf { it.isNotBlank() },
                        senderType = newMessage.optString("sender_type").takeIf { it.isNotBlank() },
                        phoneNumber = phoneNumber,
                        contactFirstName = contactFirstName,
                        contactLastName = contactLastName,
                        timestamp = messageTime,
                    ),
                )
            }
        }
        val nextOffsetId = data.optString("next_offset_id").takeIf { it.isNotBlank() }
        return RubikaUpdatesResult(updates = updates, nextOffsetId = nextOffsetId)
    }

    private fun post(token: String, method: String, payload: JSONObject): JSONObject {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) {
            throw RubikaException("Bot token is not configured")
        }

        val url = "$BASE_URL/$trimmedToken/$method"
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val body = try {
                JSONObject(bodyText)
            } catch (_: Exception) {
                throw RubikaException("Invalid JSON response (${response.code})")
            }

            if (!response.isSuccessful) {
                val error = body.optString("error").ifBlank { "HTTP ${response.code}" }
                throw RubikaException(error)
            }

            val status = body.optString("status")
            if (status != "OK") {
                val error = body.optString("error").ifBlank { "API status: $status" }
                throw RubikaException(error)
            }

            return body
        }
    }

    companion object {
        private const val BASE_URL = "https://botapi.rubika.ir/v3"

        fun extractPhoneFromMessage(newMessage: JSONObject): String? {
            val contact = newMessage.optJSONObject("contact_message")
                ?: newMessage.optJSONObject("contact")
            parseContactPhone(contact)?.let { return it }
            return extractPhoneFromText(newMessage.optString("text"))
        }

        fun extractPhoneFromText(text: String): String? {
            val trimmed = PhoneUtils.normalizeToAsciiDigits(text.trim())
            if (trimmed.isBlank()) return null
            if (PhoneUtils.isPhoneLikeInput(trimmed)) {
                return PhoneUtils.formatForDisplay(trimmed)
            }
            val match = IRAN_PHONE_REGEX.find(trimmed) ?: return null
            return PhoneUtils.formatForDisplay(match.value)
        }

        fun isPhoneShareText(text: String): Boolean {
            return extractPhoneFromText(text) != null
        }

        private fun parseContactPhone(contact: JSONObject?): String? {
            if (contact == null) return null
            listOf("phone_number", "phoneNumber", "phone").forEach { key ->
                contact.optString(key)
                    .takeIf { it.isNotBlank() }
                    ?.let { return PhoneUtils.formatForDisplay(PhoneUtils.normalizeToAsciiDigits(it)) }
            }
            return null
        }

        private val IRAN_PHONE_REGEX = Regex("""(?:\+98|0098|98|0)?9\d{9}""")

        @Volatile
        private var instance: RubikaApi? = null

        fun getInstance(): RubikaApi {
            return instance ?: synchronized(this) {
                instance ?: RubikaApi().also { instance = it }
            }
        }
    }
}
