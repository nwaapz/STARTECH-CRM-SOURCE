package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialRegistrationErrorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        database.serialRegistrationErrorDao().insert(
            SerialRegistrationErrorEntity(
                phone = normalizedPhone,
                serial = normalizedSerial,
                reason = reason,
                at = System.currentTimeMillis(),
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
