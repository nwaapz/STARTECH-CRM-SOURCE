package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.CommunicationMessageEntity
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SyncEventEntity
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CommunicationLog private constructor(
    private val context: Context,
    private val database: GatewayDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun logFromProcessor(
        sender: String,
        inbound: String,
        reply: String?,
        status: String,
        error: String?,
        simLabel: String?,
        timestamp: Long = System.currentTimeMillis(),
        channel: String = CHANNEL_SMS,
    ) {
        val phone = PhoneUtils.formatForDisplay(sender) ?: sender.trim()
        scope.launch {
            try {
                insertFromProcessor(phone, inbound, reply, status, error, simLabel, timestamp, channel)
                notifyListeners()
            } catch (e: Exception) {
                LanTraceLog.e("CommsLog", "Failed to log communication from processor for $phone", e)
            }
        }
    }

    fun logOutgoing(
        phone: String,
        body: String,
        status: String = STATUS_SENT,
        simLabel: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        channel: String = CHANNEL_SMS,
    ) {
        val normalizedPhone = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        scope.launch {
            try {
                database.communicationMessageDao().insert(
                    CommunicationMessageEntity(
                        phone = normalizedPhone,
                        direction = DIRECTION_OUTGOING,
                        body = body,
                        timestamp = timestamp,
                        status = status,
                        simLabel = simLabel,
                        channel = channel,
                    ),
                )
                notifyListeners()
            } catch (e: Exception) {
                LanTraceLog.e("CommsLog", "Failed to log outgoing communication for $normalizedPhone", e)
            }
        }
    }

    fun getLatest(limit: Int = DEFAULT_UI_LIMIT): List<CommunicationMessageEntity> {
        return runBlocking {
            withContext(Dispatchers.IO) {
                database.communicationMessageDao().getLatest(limit)
            }
        }
    }

    fun clear() {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.communicationMessageDao().deleteAll()
            }
            notifyListeners()
        }
    }

    suspend fun migrateFromActivityLogIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_MIGRATED, false)) return@withContext

                val activityLog = ActivityLog(context)
                val entries = activityLog.getEntries()
                if (entries.isNotEmpty()) {
                    val dao = database.communicationMessageDao()
                    val messages = buildList {
                        for (entry in entries.asReversed()) {
                            addAll(entry.toCommunicationMessages())
                        }
                    }
                    if (messages.isNotEmpty()) {
                        dao.insertAll(messages)
                        LanTraceLog.i("CommsLog", "Migrated ${messages.size} messages from ActivityLog")
                    }
                }
                activityLog.clear()
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            } catch (e: Exception) {
                LanTraceLog.e("CommsLog", "ActivityLog migration failed", e)
            }
        }
    }

    suspend fun backfillIfEmpty() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_SYNC_BACKFILLED, false)) return@withContext

                val dao = database.communicationMessageDao()
                if (dao.countAll() > 0) {
                    prefs.edit().putBoolean(KEY_SYNC_BACKFILLED, true).apply()
                    return@withContext
                }

                val events = database.syncEventDao().getSince(0, 500)
                val messages = buildList {
                    for (event in events) {
                        when (event.type) {
                            SyncEventWriter.TYPE_REGISTERED,
                            SyncEventWriter.TYPE_REGISTRATION_UPDATED,
                            -> addAll(event.toBackfillMessages())
                        }
                    }
                }
                if (messages.isNotEmpty()) {
                    dao.insertAll(messages)
                    LanTraceLog.i("CommsLog", "Backfilled ${messages.size} messages from sync_events")
                } else {
                    LanTraceLog.i("CommsLog", "No sync_events available for communication backfill")
                }
                prefs.edit().putBoolean(KEY_SYNC_BACKFILLED, true).apply()
            } catch (e: Exception) {
                LanTraceLog.e("CommsLog", "Sync backfill failed", e)
            }
        }
    }

    private fun SyncEventEntity.toBackfillMessages(): List<CommunicationMessageEntity> {
        val json = org.json.JSONObject(payload)
        val rawPhone = json.optString("phone").trim()
        if (rawPhone.isEmpty()) return emptyList()
        val phone = PhoneUtils.formatForDisplay(rawPhone) ?: rawPhone
        val serial = json.optString("serial").trim()
        if (serial.isEmpty()) return emptyList()
        val at = json.optLong("at", at)
        return listOf(
            CommunicationMessageEntity(
                phone = phone,
                direction = DIRECTION_INCOMING,
                body = serial,
                timestamp = at,
            ),
            CommunicationMessageEntity(
                phone = phone,
                direction = DIRECTION_OUTGOING,
                body = "ثبت شد: $serial",
                timestamp = at + 1,
                status = STATUS_SENT,
            ),
        )
    }

    private suspend fun insertFromProcessor(
        phone: String,
        inbound: String,
        reply: String?,
        status: String,
        error: String?,
        simLabel: String?,
        timestamp: Long,
        channel: String,
    ) {
        val dao = database.communicationMessageDao()
        dao.insert(
            CommunicationMessageEntity(
                phone = phone,
                direction = DIRECTION_INCOMING,
                body = inbound,
                timestamp = timestamp,
                status = null,
                simLabel = simLabel,
                channel = channel,
            ),
        )
        when {
            !reply.isNullOrBlank() -> {
                dao.insert(
                    CommunicationMessageEntity(
                        phone = phone,
                        direction = DIRECTION_OUTGOING,
                        body = reply,
                        timestamp = timestamp + 1,
                        status = STATUS_SENT,
                        simLabel = simLabel,
                        channel = channel,
                    ),
                )
            }
            status != STATUS_SENT -> {
                dao.insert(
                    CommunicationMessageEntity(
                        phone = phone,
                        direction = DIRECTION_OUTGOING,
                        body = error?.takeIf { it.isNotBlank() } ?: FAILED_REPLY_BODY,
                        timestamp = timestamp + 1,
                        status = status,
                        simLabel = simLabel,
                        channel = channel,
                    ),
                )
            }
        }
    }

    private fun notifyListeners() {
        scope.launch(Dispatchers.Main) {
            listeners.forEach { it() }
        }
    }

    private fun LogEntry.toCommunicationMessages(): List<CommunicationMessageEntity> {
        val phone = PhoneUtils.formatForDisplay(sender) ?: sender.trim()
        val isReminderOnly = inbound == CRM_REMINDER_MARKER
        return buildList {
            if (!isReminderOnly) {
                add(
                    CommunicationMessageEntity(
                        phone = phone,
                        direction = DIRECTION_INCOMING,
                        body = inbound,
                        timestamp = timestamp,
                        status = null,
                        simLabel = simLabel,
                    ),
                )
            }
            if (!reply.isNullOrBlank()) {
                add(
                    CommunicationMessageEntity(
                        phone = phone,
                        direction = DIRECTION_OUTGOING,
                        body = reply,
                        timestamp = timestamp + 1,
                        status = if (status == STATUS_SENT) STATUS_SENT else status,
                        simLabel = simLabel,
                    ),
                )
            } else if (!isReminderOnly && status != STATUS_SENT) {
                add(
                    CommunicationMessageEntity(
                        phone = phone,
                        direction = DIRECTION_OUTGOING,
                        body = error?.takeIf { it.isNotBlank() } ?: FAILED_REPLY_BODY,
                        timestamp = timestamp + 1,
                        status = status,
                        simLabel = simLabel,
                    ),
                )
            }
        }
    }

    companion object {
        const val DIRECTION_INCOMING = "incoming"
        const val DIRECTION_OUTGOING = "outgoing"
        const val STATUS_SENT = "sent"
        const val STATUS_FAILED = "failed"
        const val STATUS_GATEWAY_ERROR = "gateway_error"
        const val CHANNEL_SMS = "sms"
        const val CHANNEL_RUBIKA = "rubika"
        const val CHANNEL_TELEGRAM = "telegram"
        const val CHANNEL_BALE = "bale"
        private const val CRM_REMINDER_MARKER = "[CRM completion reminder]"
        private const val FAILED_REPLY_BODY = "ارسال پاسخ ناموفق"
        private const val DEFAULT_UI_LIMIT = 50
        private const val MIGRATION_PREFS = "communication_log_migration"
        private const val KEY_MIGRATED = "activity_log_migrated"
        private const val KEY_SYNC_BACKFILLED = "sync_events_backfilled"

        @Volatile
        private var instance: CommunicationLog? = null

        fun getInstance(context: Context): CommunicationLog {
            return instance ?: synchronized(this) {
                instance ?: CommunicationLog(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
