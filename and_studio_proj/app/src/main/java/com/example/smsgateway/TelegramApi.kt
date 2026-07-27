package com.example.smsgateway

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    class TelegramException(message: String) : Exception(message)

    /** messageId of the sent message plus the platform file_id that can be reused for future sends. */
    data class SendMediaResult(val messageId: String, val fileId: String?)

    data class TelegramMessageUpdate(
        val chatId: String,
        val messageId: String,
        val text: String,
        val senderId: String? = null,
        val senderType: String? = null,
        val phoneNumber: String? = null,
        val contactFirstName: String? = null,
        val contactLastName: String? = null,
        val senderFirstName: String? = null,
        val senderLastName: String? = null,
        val photoFileId: String? = null,
        val photoMimeType: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class TelegramUpdatesResult(
        val updates: List<TelegramMessageUpdate>,
        val nextOffsetId: String?,
    )

    fun getMe(token: String): TelegramBotInfo {
        val body = post(token, "getMe", JSONObject())
        val bot = body.getJSONObject("result")
        return TelegramBotInfo(
            botId = bot.optLong("id").toString(),
            title = bot.optString("first_name"),
            username = bot.optString("username"),
        )
    }

    fun sendMessage(token: String, chatId: String, text: String): String {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
        val body = post(token, "sendMessage", payload)
        return body.getJSONObject("result").optLong("message_id").toString()
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
        val keyboardRows = org.json.JSONArray()
        rows.forEach { rowLabels ->
            val row = org.json.JSONArray()
            rowLabels.forEach { label ->
                row.put(JSONObject().put("text", label))
            }
            keyboardRows.put(row)
        }
        val keyboard = JSONObject()
            .put("keyboard", keyboardRows)
            .put("resize_keyboard", true)
            .put("one_time_keyboard", false)
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("reply_markup", keyboard)
        val body = post(token, "sendMessage", payload)
        return body.getJSONObject("result").optLong("message_id").toString()
    }

    fun sendPhoneNumberRequest(
        token: String,
        chatId: String,
        text: String,
        buttonText: String,
    ): String {
        val button = JSONObject()
            .put("text", buttonText)
            .put("request_contact", true)
        val keyboard = JSONObject()
            .put("keyboard", org.json.JSONArray().put(org.json.JSONArray().put(button)))
            .put("resize_keyboard", true)
            .put("one_time_keyboard", true)
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("reply_markup", keyboard)
        val body = post(token, "sendMessage", payload)
        return body.getJSONObject("result").optLong("message_id").toString()
    }

    /** Sends a photo already known to Telegram, reusing its file_id (no upload). */
    fun sendPhotoByFileId(token: String, chatId: String, caption: String, fileId: String): SendMediaResult {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("photo", fileId)
        if (caption.isNotBlank()) payload.put("caption", caption)
        val body = post(token, "sendPhoto", payload)
        return parsePhotoResult(body)
    }

    /** Sends a document already known to Telegram, reusing its file_id (no upload). */
    fun sendDocumentByFileId(token: String, chatId: String, caption: String, fileId: String): SendMediaResult {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("document", fileId)
        if (caption.isNotBlank()) payload.put("caption", caption)
        val body = post(token, "sendDocument", payload)
        return parseDocumentResult(body)
    }

    /** Uploads photo bytes and returns the new file_id for reuse. */
    fun uploadPhoto(
        token: String,
        chatId: String,
        caption: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): SendMediaResult {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
        if (caption.isNotBlank()) builder.addFormDataPart("caption", caption)
        builder.addFormDataPart(
            "photo",
            fileName,
            bytes.toRequestBody(mimeType.toMediaType()),
        )
        val body = postMultipart(token, "sendPhoto", builder.build())
        return parsePhotoResult(body)
    }

    /** Uploads document bytes and returns the new file_id for reuse. */
    fun uploadDocument(
        token: String,
        chatId: String,
        caption: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): SendMediaResult {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
        if (caption.isNotBlank()) builder.addFormDataPart("caption", caption)
        builder.addFormDataPart(
            "document",
            fileName,
            bytes.toRequestBody(mimeType.toMediaType()),
        )
        val body = postMultipart(token, "sendDocument", builder.build())
        return parseDocumentResult(body)
    }

    private fun parsePhotoResult(body: JSONObject): SendMediaResult {
        val result = body.getJSONObject("result")
        val messageId = result.optLong("message_id").toString()
        val fileId = extractLargestPhotoFileId(result)
        return SendMediaResult(messageId, fileId)
    }

    private fun parseDocumentResult(body: JSONObject): SendMediaResult {
        val result = body.getJSONObject("result")
        val messageId = result.optLong("message_id").toString()
        val fileId = result.optJSONObject("document")?.optString("file_id")?.takeIf { it.isNotBlank() }
        return SendMediaResult(messageId, fileId)
    }

    fun getUpdates(token: String, offsetId: String?, limit: Int = 10): TelegramUpdatesResult {
        val payload = JSONObject().put("limit", limit)
        if (!offsetId.isNullOrBlank()) {
            payload.put("offset", offsetId.toLongOrNull() ?: offsetId)
        }
        val body = post(token, "getUpdates", payload)
        val updatesArray = body.getJSONArray("result")
        val updates = buildList {
            for (index in 0 until updatesArray.length()) {
                val update = updatesArray.getJSONObject(index)
                val message = update.optJSONObject("message") ?: continue
                val from = message.optJSONObject("from")
                if (from?.optBoolean("is_bot") == true) continue

                val chat = message.getJSONObject("chat")
                val chatId = chat.optLong("id").toString()
                val rawMessageId = message.optLong("message_id").toString()
                if (chatId.isBlank() || rawMessageId.isBlank()) continue

                val messageId = "$chatId:$rawMessageId"
                val phoneNumber = extractPhoneFromMessage(message)
                val contact = message.optJSONObject("contact")
                val contactFirstName = contact?.optString("first_name")?.takeIf { it.isNotBlank() }
                val contactLastName = contact?.optString("last_name")?.takeIf { it.isNotBlank() }
                val senderFirstName = from?.optString("first_name")?.takeIf { it.isNotBlank() }
                val senderLastName = from?.optString("last_name")?.takeIf { it.isNotBlank() }
                val messageTime = message.optLong("date", 0L).let { raw ->
                    if (raw > 0L) raw * 1000L else System.currentTimeMillis()
                }
                val text = message.optString("text").ifBlank {
                    message.optString("caption")
                }
                val photoFileId = extractLargestPhotoFileId(message)
                    ?: message.optJSONObject("document")
                        ?.takeIf { doc ->
                            doc.optString("mime_type").startsWith("image/")
                        }
                        ?.optString("file_id")
                        ?.takeIf { it.isNotBlank() }
                val photoMimeType = when {
                    photoFileId == null -> null
                    message.optJSONArray("photo") != null -> "image/jpeg"
                    else -> message.optJSONObject("document")?.optString("mime_type")
                        ?.takeIf { it.isNotBlank() } ?: "image/jpeg"
                }
                val displayText = when {
                    phoneNumber != null && text.isBlank() -> phoneNumber
                    text.isNotBlank() -> text
                    else -> phoneNumber.orEmpty()
                }

                add(
                    TelegramMessageUpdate(
                        chatId = chatId,
                        messageId = messageId,
                        text = displayText,
                        senderId = from?.optLong("id")?.toString()?.takeIf { it.isNotBlank() },
                        senderType = if (from?.optBoolean("is_bot") == true) "Bot" else "User",
                        phoneNumber = phoneNumber,
                        contactFirstName = contactFirstName,
                        contactLastName = contactLastName,
                        senderFirstName = senderFirstName,
                        senderLastName = senderLastName,
                        photoFileId = photoFileId,
                        photoMimeType = photoMimeType,
                        timestamp = messageTime,
                    ),
                )
            }
        }
        val nextOffsetId = if (updatesArray.length() > 0) {
            val lastUpdateId = updatesArray.getJSONObject(updatesArray.length() - 1).optLong("update_id")
            (lastUpdateId + 1).toString()
        } else {
            null
        }
        return TelegramUpdatesResult(updates = updates, nextOffsetId = nextOffsetId)
    }

    fun downloadFileBytes(token: String, fileId: String): Pair<ByteArray, String?> {
        val fileInfo = post(token, "getFile", JSONObject().put("file_id", fileId))
        val result = fileInfo.getJSONObject("result")
        val filePath = result.optString("file_path")
        if (filePath.isBlank()) {
            throw TelegramException("Telegram file path missing")
        }
        val url = "$BASE_URL/file/bot${token.trim()}/$filePath"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw TelegramException("Failed to download Telegram file (${response.code})")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val mime = response.header("Content-Type")
            return bytes to mime
        }
    }

    private fun post(token: String, method: String, payload: JSONObject): JSONObject {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) {
            throw TelegramException("Bot token is not configured")
        }

        val url = "$BASE_URL/bot$trimmedToken/$method"
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return execute(request)
    }

    private fun postMultipart(token: String, method: String, body: MultipartBody): JSONObject {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) {
            throw TelegramException("Bot token is not configured")
        }
        val url = "$BASE_URL/bot$trimmedToken/$method"
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val body = try {
                JSONObject(bodyText)
            } catch (_: Exception) {
                throw TelegramException("Invalid JSON response (${response.code})")
            }

            if (!response.isSuccessful) {
                val error = body.optString("description").ifBlank { "HTTP ${response.code}" }
                throw TelegramException(error)
            }

            if (!body.optBoolean("ok", false)) {
                val error = body.optString("description").ifBlank { "Telegram API error" }
                throw TelegramException(error)
            }

            return body
        }
    }

    companion object {
        private const val BASE_URL = "https://api.telegram.org"

        fun extractPhoneFromMessage(message: JSONObject): String? {
            val contact = message.optJSONObject("contact")
            parseContactPhone(contact)?.let { return it }
            return RubikaApi.extractPhoneFromText(message.optString("text"))
        }

        fun extractLargestPhotoFileId(message: JSONObject): String? {
            val photos = message.optJSONArray("photo") ?: return null
            var bestId: String? = null
            var bestSize = -1
            for (i in 0 until photos.length()) {
                val photo = photos.optJSONObject(i) ?: continue
                val size = photo.optInt("file_size", 0)
                val id = photo.optString("file_id").takeIf { it.isNotBlank() } ?: continue
                if (size >= bestSize) {
                    bestSize = size
                    bestId = id
                }
            }
            return bestId
        }

        fun isPhoneShareText(text: String): Boolean {
            return RubikaApi.extractPhoneFromText(text) != null
        }

        private fun parseContactPhone(contact: JSONObject?): String? {
            if (contact == null) return null
            contact.optString("phone_number")
                .takeIf { it.isNotBlank() }
                ?.let { return PhoneUtils.formatForDisplay(PhoneUtils.normalizeToAsciiDigits(it)) }
            return null
        }

        @Volatile
        private var instance: TelegramApi? = null

        fun getInstance(): TelegramApi {
            return instance ?: synchronized(this) {
                instance ?: TelegramApi().also { instance = it }
            }
        }
    }
}
