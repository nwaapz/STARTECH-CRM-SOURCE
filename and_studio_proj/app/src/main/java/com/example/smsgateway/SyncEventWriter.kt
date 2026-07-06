package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SyncEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SyncEventWriter(
    private val database: GatewayDatabase,
) {
    suspend fun recordRegistered(
        serial: String,
        phone: String,
        date: String,
        city: String? = null,
        km: Int? = null,
    ) = withContext(Dispatchers.IO) {
        insertEvent(TYPE_REGISTERED, buildRegistrationPayload(serial, phone, date, city, km))
    }

    suspend fun recordRegistrationUpdated(
        serial: String,
        phone: String,
        date: String,
        city: String?,
        km: Int?,
    ) = withContext(Dispatchers.IO) {
        insertEvent(TYPE_REGISTRATION_UPDATED, buildRegistrationPayload(serial, phone, date, city, km))
    }

    private suspend fun insertEvent(type: String, payload: String) {
        val at = System.currentTimeMillis()
        database.syncEventDao().insert(
            SyncEventEntity(type = type, payload = payload, at = at),
        )
        pruneOldEvents()
    }

    private fun buildRegistrationPayload(
        serial: String,
        phone: String,
        date: String,
        city: String?,
        km: Int?,
    ): String {
        val at = System.currentTimeMillis()
        return JSONObject().apply {
            put("serial", serial)
            put("phone", phone)
            put("date", date)
            if (city != null) put("city", city)
            if (km != null) put("km", km)
            put("at", at)
        }.toString()
    }

    suspend fun recordSerialsAdded(inserted: Int, skipped: Int, total: Int) = withContext(Dispatchers.IO) {
        val at = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("inserted", inserted)
            put("skipped", skipped)
            put("total", total)
            put("at", at)
        }.toString()
        database.syncEventDao().insert(
            SyncEventEntity(type = TYPE_SERIALS_ADDED, payload = payload, at = at),
        )
        pruneOldEvents()
    }

    private suspend fun pruneOldEvents() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        database.syncEventDao().deleteOlderThan(cutoff)
    }

    companion object {
        const val TYPE_REGISTERED = "registered"
        const val TYPE_REGISTRATION_UPDATED = "registration_updated"
        const val TYPE_SERIALS_ADDED = "serials_added"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: SyncEventWriter? = null

        fun getInstance(database: GatewayDatabase): SyncEventWriter {
            return instance ?: synchronized(this) {
                instance ?: SyncEventWriter(database).also { instance = it }
            }
        }
    }
}
