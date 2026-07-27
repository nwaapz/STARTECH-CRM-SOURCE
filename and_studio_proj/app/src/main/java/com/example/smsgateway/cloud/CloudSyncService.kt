package com.example.smsgateway.cloud

import android.content.Context
import android.util.Log
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.LotteryWinnerEntity
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialGroupEntity
import com.example.smsgateway.data.SyncOutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudSyncResult(val ok: Boolean, val message: String? = null)

/**
 * Pushes outbox events to remote PHP `/api/sync/push` and pulls changes from `/api/sync/pull`.
 * Applies last-write-wins on [SerialEntity.syncUpdatedMs] / group syncUpdatedMs.
 * Pull apply never enqueues outbox events (avoids sync loops).
 */
class CloudSyncService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore.getInstance(appContext)
    private val db = GatewayDatabase.getInstance(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun enqueueSerialUpsert(serial: SerialEntity) = withContext(Dispatchers.IO) {
        val group = serial.groupId?.let { db.serialGroupDao().findById(it) }
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("serial", serial.serial)
            .put("phone", serial.phone)
            .put("km", serial.km)
            .put("city", serial.city)
            .put("dateJalali", serial.date)
            .put("regSource", serial.registrationSource)
            .put("localGroupId", serial.groupId)
            .put("lanGroupId", serial.groupId)
            .put("category", group?.category ?: "end_user_client")
            .put("syncUpdatedMs", if (serial.syncUpdatedMs > 0) serial.syncUpdatedMs else now)
        if (group?.syncOriginGroupId != null) {
            payload.put("syncOriginGroupId", group.syncOriginGroupId)
        }
        enqueue("serial_upsert", payload)
    }

    suspend fun enqueueLotteryWinner(winner: LotteryWinnerEntity) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("phone", winner.phone)
            .put("city", winner.city)
            .put("scoreAtWin", winner.scoreAtWin)
            .put("dateJalali", winner.dateJalali)
            .put("wonAt", winner.wonAt)
            .put("syncUpdatedMs", winner.syncUpdatedMs)
        enqueue("lottery_winner", payload)
    }

    private suspend fun enqueue(type: String, payload: JSONObject) {
        db.syncOutboxDao().insert(
            SyncOutboxEntity(
                eventId = UUID.randomUUID().toString(),
                type = type,
                payloadJson = payload.toString(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun syncOnce(): CloudSyncResult = withContext(Dispatchers.IO) {
        val base = settings.getCloudSyncUrl().trimEnd('/')
        val token = settings.getCloudSyncToken()
        if (base.isBlank() || token.isBlank()) {
            return@withContext CloudSyncResult(false, "Cloud sync URL/token not configured")
        }
        try {
            pushPending(base, token)
            pullRemote(base, token)
            CloudSyncResult(true, "Sync ok")
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            CloudSyncResult(false, e.message)
        }
    }

    private suspend fun pushPending(base: String, token: String) {
        val pending = db.syncOutboxDao().listPending(50)
        if (pending.isEmpty()) return
        val events = JSONArray()
        for (row in pending) {
            events.put(
                JSONObject()
                    .put("eventId", row.eventId)
                    .put("type", row.type)
                    .put("payload", JSONObject(row.payloadJson))
                    .put("createdAt", row.createdAt),
            )
        }
        val body = JSONObject().put("events", events).toString()
        val request = Request.Builder()
            .url("$base/api/sync/push")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            val now = System.currentTimeMillis()
            if (!response.isSuccessful) {
                val err = response.body?.string()
                for (row in pending) {
                    db.syncOutboxDao().markAttempt(
                        row.id,
                        SyncOutboxEntity.STATUS_PENDING,
                        err ?: "HTTP ${response.code}",
                        now,
                    )
                }
                throw IllegalStateException("Push failed: HTTP ${response.code}")
            }
            for (row in pending) {
                db.syncOutboxDao().markAttempt(row.id, SyncOutboxEntity.STATUS_SENT, null, now)
            }
            settings.setCloudSyncLastPushMs(now)
        }
        // Drain remaining outbox in follow-up pages
        if (db.syncOutboxDao().listPending(1).isNotEmpty()) {
            pushPending(base, token)
        }
    }

    private suspend fun pullRemote(base: String, token: String) {
        var pages = 0
        while (pages < MAX_PULL_PAGES) {
            pages++
            val since = settings.getCloudSyncLastPullMs()
            val request = Request.Builder()
                .url("$base/api/sync/pull?since=$since")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            val hasMore = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Pull failed: HTTP ${response.code}")
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    settings.setCloudSyncLastPullMs(System.currentTimeMillis())
                    return@use false
                }
                val json = JSONObject(text)
                val cursor = json.optLong("cursor", System.currentTimeMillis())
                val hasMoreFlag = json.optBoolean("hasMore", false)
                applyGroups(json.optJSONArray("groups") ?: JSONArray())
                applyDeletedGroups(json.optJSONArray("deletedGroups") ?: JSONArray())
                applySerials(json.optJSONArray("serials") ?: JSONArray())
                settings.setCloudSyncLastPullMs(cursor)
                hasMoreFlag && cursor > since
            }
            if (!hasMore) break
        }
    }

    private suspend fun applyGroups(groups: JSONArray) {
        val groupDao = db.serialGroupDao()
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val remoteId = g.optLong("id", 0L)
            if (remoteId <= 0L) continue
            val syncMs = g.optLong("syncUpdatedMs", 0L)
            val localGroupIdHint = if (g.has("localGroupId") && !g.isNull("localGroupId")) {
                g.optLong("localGroupId")
            } else {
                null
            }
            val existing = groupDao.findBySyncOriginGroupId(remoteId)
                ?: localGroupIdHint?.let { groupDao.findById(it) }

            val prefix = g.optString("prefix")
            val startNumber = g.optInt("startNumber")
            val endNumber = g.optInt("endNumber")
            val padWidth = g.optInt("padWidth")
            val description = g.optString("description").takeIf { it.isNotBlank() }
            val score = g.optInt("score", 1).coerceAtLeast(1)
            val category = g.optString("category", "end_user_client")
            val generationMode = g.optString("generationMode", "range")
            val warrantyMonths = if (g.has("warrantyMonths") && !g.isNull("warrantyMonths")) {
                g.optInt("warrantyMonths")
            } else {
                null
            }
            val warrantyKm = if (g.has("warrantyKm") && !g.isNull("warrantyKm")) {
                g.optInt("warrantyKm")
            } else {
                null
            }
            val imageVerificationRequired = g.optBoolean("imageVerificationRequired", false)
            val createdAt = g.optLong("createdAt", syncMs)

            if (existing == null) {
                groupDao.insert(
                    SerialGroupEntity(
                        prefix = prefix,
                        startNumber = startNumber,
                        endNumber = endNumber,
                        padWidth = padWidth,
                        description = description,
                        score = score,
                        category = category,
                        generationMode = generationMode,
                        warrantyMonths = warrantyMonths,
                        warrantyKm = warrantyKm,
                        imageVerificationRequired = imageVerificationRequired,
                        createdAt = createdAt,
                        syncUpdatedMs = syncMs,
                        syncOriginGroupId = remoteId,
                    ),
                )
            } else if (syncMs >= existing.syncUpdatedMs) {
                groupDao.update(
                    existing.copy(
                        prefix = prefix,
                        startNumber = startNumber,
                        endNumber = endNumber,
                        padWidth = padWidth,
                        description = description,
                        score = score,
                        category = category,
                        generationMode = generationMode,
                        warrantyMonths = warrantyMonths,
                        warrantyKm = warrantyKm,
                        imageVerificationRequired = imageVerificationRequired,
                        syncUpdatedMs = syncMs,
                        syncOriginGroupId = remoteId,
                    ),
                )
            } else if (existing.syncOriginGroupId == null) {
                groupDao.setSyncOriginGroupId(existing.id, remoteId, existing.syncUpdatedMs)
            }
        }
    }

    private suspend fun applyDeletedGroups(deleted: JSONArray) {
        val groupDao = db.serialGroupDao()
        val serialDao = db.serialDao()
        for (i in 0 until deleted.length()) {
            val d = deleted.getJSONObject(i)
            val remoteId = d.optLong("remoteGroupId", 0L)
            val localHint = if (d.has("localGroupId") && !d.isNull("localGroupId")) {
                d.optLong("localGroupId")
            } else {
                null
            }
            val group = when {
                remoteId > 0 -> groupDao.findBySyncOriginGroupId(remoteId)
                else -> null
            } ?: localHint?.let { groupDao.findById(it) }
            if (group != null) {
                serialDao.deleteByGroupId(group.id)
                groupDao.deleteById(group.id)
            }
        }
    }

    private suspend fun applySerials(serials: JSONArray) {
        val serialDao = db.serialDao()
        val groupDao = db.serialGroupDao()
        for (i in 0 until serials.length()) {
            val s = serials.getJSONObject(i)
            val serialValue = s.optString("serial").trim()
            if (serialValue.isEmpty()) continue
            val syncMs = s.optLong("syncUpdatedMs", System.currentTimeMillis())
            val phone = s.optString("phone").takeIf { it.isNotBlank() }
            val city = s.optString("city").takeIf { it.isNotBlank() }
            val km = if (s.has("km") && !s.isNull("km")) s.optInt("km") else null
            val dateJalali = s.optString("dateJalali").takeIf { it.isNotBlank() }
                ?: s.optString("date").takeIf { it.isNotBlank() }
            val regSource = s.optString("regSource").takeIf { it.isNotBlank() }
            val remoteGroupId = if (s.has("lanGroupId") && !s.isNull("lanGroupId")) {
                s.optLong("lanGroupId")
            } else {
                null
            }
            val localGroupId = remoteGroupId?.let { rid ->
                groupDao.findBySyncOriginGroupId(rid)?.id
            }
            val fieldsComplete = !phone.isNullOrBlank() && !city.isNullOrBlank() && km != null
            val existing = serialDao.findBySerial(serialValue)

            if (existing == null) {
                serialDao.insertAll(
                    listOf(
                        SerialEntity(
                            serial = serialValue,
                            phone = phone,
                            date = dateJalali,
                            city = city,
                            km = km,
                            registrationFieldsComplete = if (phone.isNullOrBlank()) {
                                true
                            } else {
                                fieldsComplete
                            },
                            registrationSource = regSource,
                            groupId = localGroupId,
                            syncUpdatedMs = syncMs,
                        ),
                    ),
                )
            } else if (syncMs >= existing.syncUpdatedMs) {
                serialDao.applySyncUpsert(
                    serial = serialValue,
                    phone = phone,
                    date = dateJalali,
                    city = city,
                    km = km,
                    fieldsComplete = if (phone.isNullOrBlank()) true else fieldsComplete,
                    registrationSource = regSource,
                    groupId = localGroupId ?: existing.groupId,
                    syncUpdatedMs = syncMs,
                )
            }
        }
    }

    companion object {
        private const val TAG = "CloudSyncService"
        private const val MAX_PULL_PAGES = 50
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: CloudSyncService? = null

        fun getInstance(context: Context): CloudSyncService {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncService(context.applicationContext).also { instance = it }
            }
        }
    }
}
