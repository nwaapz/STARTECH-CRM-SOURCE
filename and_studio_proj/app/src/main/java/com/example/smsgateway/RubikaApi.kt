package com.example.smsgateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RubikaApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    class RubikaException(message: String) : Exception(message)

    /** messageId of the sent message plus the platform file_id that can be reused for future sends. */
    data class SendMediaResult(val messageId: String, val fileId: String?)

    data class RubikaMessageUpdate(
        val chatId: String,
        val messageId: String,
        val text: String,
        val senderId: String? = null,
        val senderType: String? = null,
        val phoneNumber: String? = null,
        val contactFirstName: String? = null,
        val contactLastName: String? = null,
        val fileId: String? = null,
        val fileMimeType: String? = null,
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

    fun sendMessageWithReplyKeyboard(
        token: String,
        chatId: String,
        text: String,
        rows: List<List<String>>,
    ): String {
        if (rows.isEmpty()) {
            return sendMessage(token, chatId, text)
        }
        val keypadRows = org.json.JSONArray()
        rows.forEachIndexed { rowIndex, rowLabels ->
            val buttons = org.json.JSONArray()
            rowLabels.forEachIndexed { buttonIndex, label ->
                buttons.put(
                    JSONObject()
                        .put("id", "menu_${rowIndex}_$buttonIndex")
                        .put("type", "Simple")
                        .put("button_text", label),
                )
            }
            keypadRows.put(JSONObject().put("buttons", buttons))
        }
        val keypad = JSONObject()
            .put("rows", keypadRows)
            .put("resize_keyboard", true)
            .put("one_time_keyboard", false)
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("chat_keypad_type", "New")
            .put("chat_keypad", keypad)
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

    /** Sends a file already known to Rubika, reusing its file_id (no upload). */
    fun sendFileByFileId(token: String, chatId: String, caption: String, fileId: String): SendMediaResult {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("file_id", fileId)
        if (caption.isNotBlank()) payload.put("text", caption)
        val body = post(token, "sendFile", payload)
        val data = body.getJSONObject("data")
        return SendMediaResult(data.optString("message_id"), fileId)
    }

    /**
     * Uploads file bytes via Rubika's requestSendFile flow, sends it, and returns the new file_id
     * for reuse on subsequent sends.
     */
    fun uploadAndSendFile(
        token: String,
        chatId: String,
        caption: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        isImage: Boolean,
    ): SendMediaResult {
        val type = if (isImage) "Image" else "File"
        val requestBody = post(token, "requestSendFile", JSONObject().put("type", type))
        val uploadUrl = requestBody.getJSONObject("data").optString("upload_url")
        if (uploadUrl.isBlank()) {
            throw RubikaException("Rubika upload_url missing")
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaType()),
            )
            .build()
        val uploadRequest = Request.Builder().url(uploadUrl).post(multipart).build()
        val fileId = client.newCall(uploadRequest).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val json = try {
                JSONObject(bodyText)
            } catch (_: Exception) {
                throw RubikaException("Invalid upload response (${response.code})")
            }
            if (!response.isSuccessful) {
                throw RubikaException("Failed to upload Rubika file (${response.code})")
            }
            json.optJSONObject("data")?.optString("file_id")?.takeIf { it.isNotBlank() }
                ?: json.optString("file_id").takeIf { it.isNotBlank() }
                ?: throw RubikaException("Rubika file_id missing after upload")
        }

        return sendFileByFileId(token, chatId, caption, fileId)
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
                val fileInfo = extractIncomingFile(newMessage)
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
                        fileId = fileInfo?.first,
                        fileMimeType = fileInfo?.second,
                        timestamp = messageTime,
                    ),
                )
            }
        }
        val nextOffsetId = data.optString("next_offset_id").takeIf { it.isNotBlank() }
        return RubikaUpdatesResult(updates = updates, nextOffsetId = nextOffsetId)
    }

    fun downloadFileBytes(token: String, fileId: String): Pair<ByteArray, String?> {
        val body = post(token, "getFile", JSONObject().put("file_id", fileId))
        val data = body.optJSONObject("data") ?: body
        val downloadUrl = data.optString("download_url")
            .ifBlank { data.optJSONObject("file")?.optString("download_url").orEmpty() }
            .ifBlank { data.optString("url") }
        if (downloadUrl.isBlank()) {
            throw RubikaException("Rubika download_url missing")
        }
        val request = Request.Builder().url(downloadUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RubikaException("Failed to download Rubika file (${response.code})")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val mime = response.header("Content-Type")
            return bytes to mime
        }
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

        /** Returns (fileId, mimeTypeHint) for photo/file messages. */
        fun extractIncomingFile(newMessage: JSONObject): Pair<String, String?>? {
            val candidates = listOfNotNull(
                newMessage.optJSONObject("file"),
                newMessage.optJSONObject("file_inline"),
                newMessage.optJSONObject("photo"),
                newMessage.optJSONObject("image"),
            )
            for (obj in candidates) {
                val id = listOf("file_id", "fileId", "id")
                    .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
                    .firstOrNull()
                if (id != null) {
                    val mime = obj.optString("mime").ifBlank {
                        obj.optString("mime_type")
                    }.ifBlank {
                        obj.optString("file_name").let { name ->
                            when {
                                name.endsWith(".png", true) -> "image/png"
                                name.endsWith(".webp", true) -> "image/webp"
                                name.endsWith(".gif", true) -> "image/gif"
                                else -> "image/jpeg"
                            }
                        }
                    }.takeIf { it.isNotBlank() }
                    return id to mime
                }
            }
            val topLevelId = newMessage.optString("file_id").takeIf { it.isNotBlank() }
            return topLevelId?.let { it to "image/jpeg" }
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
