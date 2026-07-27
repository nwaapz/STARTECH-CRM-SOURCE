package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialRegistrationErrorEntity
import com.example.smsgateway.data.SyncOutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

object SerialRegistrationErrorReason {
    const val NOT_FOUND = "not_found"
    const val ALREADY_USED = "already_used"
}

class SerialRegistrationErrorWriter(
    private val database: GatewayDatabase,
) {
    suspend fun record(
        phone: String,
        serial: String,
        reason: String,
    ) = withContext(Dispatchers.IO) {
        val normalizedPhone = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        if (normalizedSerial.isEmpty()) return@withContext
        val at = System.currentTimeMillis()
        database.serialRegistrationErrorDao().insert(
            SerialRegistrationErrorEntity(
                phone = normalizedPhone,
                serial = normalizedSerial,
                reason = reason,
                at = at,
            ),
        )
        // Push to remote CRM so dashboard registration-error tables stay in sync.
        val payload = JSONObject()
            .put("phone", normalizedPhone)
            .put("serial", normalizedSerial)
            .put("reason", reason)
            .put("at", at)
        database.syncOutboxDao().insert(
            SyncOutboxEntity(
                eventId = UUID.randomUUID().toString(),
                type = "registration_error",
                payloadJson = payload.toString(),
                createdAt = at,
            ),
        )
    }

    companion object {
        @Volatile
        private var instance: SerialRegistrationErrorWriter? = null

        fun getInstance(database: GatewayDatabase): SerialRegistrationErrorWriter {
            return instance ?: synchronized(this) {
                instance ?: SerialRegistrationErrorWriter(database).also { instance = it }
            }
        }
    }
}
