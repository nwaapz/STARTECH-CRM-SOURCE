package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.cloud.CloudSyncScheduler
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialGroupEntity
import com.example.smsgateway.data.SyncEventEntity
import com.example.smsgateway.data.SyncOutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class SyncEventWriter(
    private val database: GatewayDatabase,
) {
    private var appContext: Context? = null

    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun recordRegistered(
        serial: String,
        phone: String,
        date: String,
        city: String? = null,
        km: Int? = null,
    ) = withContext(Dispatchers.IO) {
        insertEvent(TYPE_REGISTERED, buildRegistrationPayload(serial, phone, date, city, km))
        enqueueSerialCloud(serial, phone, date, city, km)
    }

    suspend fun recordRegistrationUpdated(
        serial: String,
        phone: String,
        date: String,
        city: String?,
        km: Int?,
    ) = withContext(Dispatchers.IO) {
        insertEvent(TYPE_REGISTRATION_UPDATED, buildRegistrationPayload(serial, phone, date, city, km))
        enqueueSerialCloud(serial, phone, date, city, km)
    }

    private suspend fun enqueueSerialCloud(
        serial: String,
        phone: String,
        date: String,
        city: String?,
        km: Int?,
    ) {
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        val now = System.currentTimeMillis()
        database.serialDao().touchSyncUpdatedMs(normalizedSerial, now)
        val row = database.serialDao().findBySerial(normalizedSerial)
        val group = row?.groupId?.let { database.serialGroupDao().findById(it) }
        val payload = JSONObject().apply {
            put("serial", normalizedSerial)
            put("phone", phone)
            put("dateJalali", date)
            if (city != null) put("city", city)
            if (km != null) put("km", km)
            if (row?.groupId != null) put("localGroupId", row.groupId)
            if (group?.syncOriginGroupId != null) put("syncOriginGroupId", group.syncOriginGroupId)
            if (row?.registrationSource != null) put("regSource", row.registrationSource)
            if (!group?.category.isNullOrBlank()) put("category", group!!.category)
            put("syncUpdatedMs", now)
        }
        insertOutbox(
            TYPE_SERIAL_UPSERT,
            payload,
            now,
            coalesceKey = coalesceSerialKey(normalizedSerial),
        )
        requestCloudSyncSoon()
    }

    /**
     * After local inventory create/extend: push group metadata + unused serials in batches.
     * Not used for CSV import.
     */
    suspend fun enqueueInventoryForGroup(groupId: Long, serialsFilter: List<String>? = null) =
        withContext(Dispatchers.IO) {
            val group = database.serialGroupDao().findById(groupId) ?: return@withContext
            enqueueGroupUpsert(group)
            if (serialsFilter != null) {
                val serials = serialsFilter.mapNotNull {
                    database.serialDao().findBySerial(SerialNormalizer.normalizeSerial(it))
                }
                enqueueSerialBatches(serials, group)
                requestCloudSyncSoon()
                return@withContext
            }
            var offset = 0
            while (true) {
                val page = database.serialDao().listByGroupIdPaged(
                    groupId,
                    SERIAL_BATCH_SIZE,
                    offset,
                )
                if (page.isEmpty()) break
                enqueueSerialBatches(page, group)
                offset += page.size
                if (page.size < SERIAL_BATCH_SIZE) break
            }
            requestCloudSyncSoon()
        }

    suspend fun enqueueGroupUpsert(group: SerialGroupEntity) = withContext(Dispatchers.IO) {
        val now = if (group.syncUpdatedMs > 0) group.syncUpdatedMs else System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("localGroupId", group.id)
            if (group.syncOriginGroupId != null) put("syncOriginGroupId", group.syncOriginGroupId)
            put("prefix", group.prefix)
            put("startNumber", group.startNumber)
            put("endNumber", group.endNumber)
            put("padWidth", group.padWidth)
            put("description", group.description)
            put("score", group.score)
            put("category", group.category)
            put("generationMode", group.generationMode)
            put("warrantyMonths", group.warrantyMonths)
            put("warrantyKm", group.warrantyKm)
            put("imageVerificationRequired", group.imageVerificationRequired)
            put("createdAt", group.createdAt)
            put("syncUpdatedMs", now)
        }
        insertOutbox(
            TYPE_GROUP_UPSERT,
            payload,
            now,
            coalesceKey = "group:${group.id}:$TYPE_GROUP_UPSERT",
        )
        requestCloudSyncSoon()
    }

    suspend fun enqueueGroupDelete(group: SerialGroupEntity) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("localGroupId", group.id)
            if (group.syncOriginGroupId != null) put("syncOriginGroupId", group.syncOriginGroupId)
            put("syncUpdatedMs", now)
        }
        insertOutbox(
            TYPE_GROUP_DELETE,
            payload,
            now,
            coalesceKey = "group:${group.id}:$TYPE_GROUP_DELETE",
        )
    }

    private suspend fun enqueueSerialBatches(
        serials: List<SerialEntity>,
        group: SerialGroupEntity,
    ) {
        if (serials.isEmpty()) return
        val now = System.currentTimeMillis()
        // One pending entry per serial (coalesced) instead of opaque batches — simplifies conflicts.
        for (s in serials) {
            val syncMs = if (s.syncUpdatedMs > 0) s.syncUpdatedMs else now
            val payload = JSONObject().apply {
                put("serial", s.serial)
                put("phone", s.phone)
                put("city", s.city)
                put("km", s.km)
                put("dateJalali", s.date)
                put("regSource", s.registrationSource)
                put("localGroupId", group.id)
                if (group.syncOriginGroupId != null) {
                    put("syncOriginGroupId", group.syncOriginGroupId)
                }
                put("category", group.category)
                put("syncUpdatedMs", syncMs)
            }
            insertOutbox(
                TYPE_SERIAL_UPSERT,
                payload,
                now,
                coalesceKey = coalesceSerialKey(s.serial),
            )
        }
    }

    private suspend fun insertOutbox(
        type: String,
        payload: JSONObject,
        createdAt: Long,
        coalesceKey: String?,
    ) {
        val dao = database.syncOutboxDao()
        if (!coalesceKey.isNullOrBlank()) {
            dao.deletePendingByCoalesceKey(coalesceKey)
        }
        dao.insert(
            SyncOutboxEntity(
                eventId = UUID.randomUUID().toString(),
                type = type,
                payloadJson = payload.toString(),
                createdAt = createdAt,
                coalesceKey = coalesceKey,
            ),
        )
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
            put("message", "افزودن $inserted سریال (تکراری: $skipped)")
        }
        insertEvent(TYPE_SERIALS_ADDED, payload.toString())
        // Dashboard activity is LAN-only; not pushed to cloud buffer.
    }

    /**
     * Generic dashboard / recent-events entry (photo review, bot connect, etc.).
     * Written locally for LAN CRM only (not cloud-buffered).
     */
    suspend fun recordDashboardEvent(
        type: String,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val at = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("message", message)
            put("at", at)
            for ((k, v) in extra) {
                when (v) {
                    null -> Unit
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        insertEvent(type, payload.toString())
    }

    private suspend fun pruneOldEvents() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        database.syncEventDao().deleteOlderThan(cutoff)
    }

    private fun requestCloudSyncSoon() {
        appContext?.let { CloudSyncScheduler.getInstance(it).pushSoon() }
    }

    companion object {
        const val TYPE_REGISTERED = "registered"
        const val TYPE_REGISTRATION_UPDATED = "registration_updated"
        const val TYPE_SERIALS_ADDED = "serials_added"
        const val TYPE_PHOTO_APPROVED = "photo_approved"
        const val TYPE_PHOTO_REJECTED = "photo_rejected"
        const val TYPE_BOT_CONNECTED = "bot_connected"
        const val TYPE_BOT_DISCONNECTED = "bot_disconnected"

        const val TYPE_SERIAL_UPSERT = "serial_upsert"
        const val TYPE_SERIAL_BATCH_UPSERT = "serial_batch_upsert"
        const val TYPE_GROUP_UPSERT = "group_upsert"
        const val TYPE_GROUP_DELETE = "group_delete"

        private const val SERIAL_BATCH_SIZE = 200
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        fun coalesceSerialKey(serial: String): String =
            "serial:" + serial.trim().uppercase()

        @Volatile
        private var instance: SyncEventWriter? = null

        fun getInstance(context: Context): SyncEventWriter {
            val writer = getInstance(GatewayDatabase.getInstance(context))
            writer.attachContext(context)
            return writer
        }

        fun getInstance(database: GatewayDatabase): SyncEventWriter {
            return instance ?: synchronized(this) {
                instance ?: SyncEventWriter(database).also { instance = it }
            }
        }
    }
}
