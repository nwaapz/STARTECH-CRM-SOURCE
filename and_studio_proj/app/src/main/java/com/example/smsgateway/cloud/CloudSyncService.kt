package com.example.smsgateway.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smsgateway.MainActivity
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.R
import com.example.smsgateway.SerialNormalizer
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialGroupEntity
import com.example.smsgateway.data.SyncOutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CloudSyncResult(
    val ok: Boolean,
    val message: String? = null,
    val needsConflictResolution: Boolean = false,
)

data class CloudSyncProgress(
    val running: Boolean = false,
    /** idle | fetch | apply | push | conflicts | done | error */
    val phase: String = "idle",
    val percent: Int = 0,
    val detail: String? = null,
    val pushDone: Int = 0,
    val pushTotal: Int = 0,
    val pullPage: Int = 0,
    val pullPagesEstimate: Int = 0,
    val applyRowsDone: Int = 0,
    val applyRowsTotal: Int = 0,
    val repairGroupsDone: Int = 0,
    val repairGroupsTotal: Int = 0,
    val pendingLocal: Int = 0,
    val pendingRemote: Int = 0,
    val conflictCount: Int = 0,
)

/**
 * Dual-buffer cloud sync: exchange pending change buffers only.
 * Conflicts (same serial in both buffers with different data) pause for user choice.
 */
class CloudSyncService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore.getInstance(appContext)
    private val db = GatewayDatabase.getInstance(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _progressFlow = MutableStateFlow(CloudSyncProgress())
    val progressFlow: StateFlow<CloudSyncProgress> = _progressFlow.asStateFlow()

    private val _conflictSession = MutableStateFlow<SyncConflictSession?>(null)
    val conflictSessionFlow: StateFlow<SyncConflictSession?> = _conflictSession.asStateFlow()

    private val syncMutex = Mutex()

    fun getProgress(): CloudSyncProgress = _progressFlow.value

    fun isSyncRunning(): Boolean = _progressFlow.value.running

    fun getConflictSession(): SyncConflictSession? = _conflictSession.value

    fun clearConflictSession() {
        _conflictSession.value = null
        cancelConflictNotification()
    }

    private fun setProgress(
        running: Boolean,
        phase: String,
        percent: Int,
        detail: String? = null,
        pushDone: Int = _progressFlow.value.pushDone,
        pushTotal: Int = _progressFlow.value.pushTotal,
        applyRowsDone: Int = _progressFlow.value.applyRowsDone,
        applyRowsTotal: Int = _progressFlow.value.applyRowsTotal,
        pendingLocal: Int = _progressFlow.value.pendingLocal,
        pendingRemote: Int = _progressFlow.value.pendingRemote,
        conflictCount: Int = _progressFlow.value.conflictCount,
        pullPage: Int = _progressFlow.value.pullPage,
    ) {
        _progressFlow.value = CloudSyncProgress(
            running = running,
            phase = phase,
            percent = percent.coerceIn(0, 100),
            detail = detail,
            pushDone = pushDone,
            pushTotal = pushTotal,
            applyRowsDone = applyRowsDone,
            applyRowsTotal = applyRowsTotal,
            pendingLocal = pendingLocal,
            pendingRemote = pendingRemote,
            conflictCount = conflictCount,
            pullPage = pullPage,
        )
    }

    /** @deprecated Lottery winners are no longer cloud-buffered. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun enqueueLotteryWinner(winner: com.example.smsgateway.data.LotteryWinnerEntity) {
        // Intentionally no-op: lottery is not part of dual-buffer sync.
    }

    suspend fun pullOnce(): CloudSyncResult = pullInternal(resolutions = emptyMap())

    suspend fun pullWithResolutions(resolutions: Map<String, SyncConflictChoice>): CloudSyncResult =
        pullInternal(resolutions = resolutions)

    /**
     * Manual catch-up: ask the server to re-enqueue its most recently registered
     * serials as pending buffer entries (count-based, not date-based — covers a gap
     * of any age), then pull/apply/ack them. Use this after loading a stale
     * CSV/backup that left the phone behind server-side registrations, or as the
     * everyday "Get from server" action.
     */
    suspend fun requestBackfillAndPull(): CloudSyncResult = withContext(Dispatchers.IO) {
        val base = settings.getCloudSyncUrl().trimEnd('/')
        val token = settings.getCloudSyncToken()
        if (base.isBlank() || token.isBlank()) {
            val msg = "Cloud sync URL/token not configured"
            setProgress(running = false, phase = "error", percent = 0, detail = msg)
            return@withContext CloudSyncResult(false, msg)
        }
        try {
            setProgress(
                running = true,
                phase = "fetch",
                percent = 5,
                detail = "Requesting missed data from server…",
            )
            val enqueued = requestBackfill(base, token)
            setProgress(
                running = true,
                phase = "fetch",
                percent = 20,
                detail = "Server queued $enqueued record(s), fetching…",
            )
            pullOnce()
        } catch (e: CancellationException) {
            setProgress(running = false, phase = "error", percent = 0, detail = "Cancelled")
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "backfill request failed", e)
            val msg = e.message ?: "Backfill request failed"
            setProgress(running = false, phase = "error", percent = 0, detail = msg)
            CloudSyncResult(false, msg)
        }
    }

    /**
     * Upload-only: push everything pending in the local outbox. Fetches the remote
     * buffer solely to detect same-serial conflicts (so a push can't blindly clobber
     * a value another device already queued); conflicting serials are left pending
     * (no dialog) for the next "Get from server" to resolve. Never applies or acks
     * any remote data.
     */
    suspend fun pushOnce(): CloudSyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val base = settings.getCloudSyncUrl().trimEnd('/')
            val token = settings.getCloudSyncToken()
            if (base.isBlank() || token.isBlank()) {
                val msg = "Cloud sync URL/token not configured"
                setProgress(running = false, phase = "error", percent = 0, detail = msg)
                return@withLock CloudSyncResult(false, msg)
            }
            try {
                setProgress(running = true, phase = "fetch", percent = 10, detail = "Loading local changes…")
                val localPending = loadLocalPending()
                if (localPending.isEmpty()) {
                    setProgress(running = false, phase = "done", percent = 100, detail = "Nothing to send", pendingLocal = 0)
                    return@withLock CloudSyncResult(true, "Nothing to send")
                }
                setProgress(
                    running = true,
                    phase = "fetch",
                    percent = 20,
                    detail = "Checking for conflicts…",
                    pendingLocal = localPending.size,
                )
                val remotePending = try {
                    fetchRemoteBuffer(base, token)
                } catch (e: Exception) {
                    Log.w(TAG, "push: remote buffer fetch failed, pushing without conflict check", e)
                    emptyList()
                }
                val conflicts = BufferConflictDetector.findConflicts(localPending, remotePending)
                val conflictSerials = conflicts.map { it.serial.uppercase() }.toSet()

                val toPush = ArrayList<SyncBufferEntry>()
                for (entry in localPending) {
                    when (entry.type) {
                        "group_upsert", "group_delete" -> toPush.add(entry)
                        "serial_upsert", "serial_batch_upsert" -> {
                            val serials = BufferConflictDetector.expandBySerial(listOf(entry)).keys
                            val blocked = serials.any { it.uppercase() in conflictSerials }
                            if (!blocked) {
                                toPush.add(entry)
                            } else if (entry.type == "serial_batch_upsert" && serials.size > 1) {
                                for ((serial, expanded) in BufferConflictDetector.expandBySerial(listOf(entry))) {
                                    if (serial.uppercase() !in conflictSerials) toPush.add(expanded)
                                }
                            }
                            // else: single-serial conflict — left pending, no push.
                        }
                    }
                }

                setProgress(
                    running = true,
                    phase = "push",
                    percent = 40,
                    detail = "Uploading ${toPush.size} change(s)…",
                    pushTotal = toPush.size,
                )
                if (toPush.isNotEmpty()) {
                    pushLocalEntries(base, token, toPush)
                }
                settings.setCloudSyncLastPushMs(System.currentTimeMillis())

                val pendingLeft = db.syncOutboxDao().countPending()
                val skipped = conflictSerials.size
                val msg = if (skipped > 0) {
                    "Sent ${toPush.size}; $skipped serial(s) held back (conflict) — pending left: $pendingLeft"
                } else {
                    "Sent ${toPush.size} change(s) (pending left: $pendingLeft)"
                }
                setProgress(
                    running = false,
                    phase = "done",
                    percent = 100,
                    detail = msg,
                    pendingLocal = pendingLeft,
                    pushDone = toPush.size,
                    pushTotal = toPush.size,
                )
                CloudSyncResult(true, msg)
            } catch (e: CancellationException) {
                setProgress(running = false, phase = "error", percent = 0, detail = "Cancelled")
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "push failed", e)
                val msg = e.message ?: "Push failed"
                setProgress(running = false, phase = "error", percent = 0, detail = msg)
                CloudSyncResult(false, msg)
            }
        }
    }

    private suspend fun pullInternal(
        resolutions: Map<String, SyncConflictChoice>,
    ): CloudSyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val base = settings.getCloudSyncUrl().trimEnd('/')
            val token = settings.getCloudSyncToken()
            if (base.isBlank() || token.isBlank()) {
                val msg = "Cloud sync URL/token not configured"
                setProgress(running = false, phase = "error", percent = 0, detail = msg)
                return@withLock CloudSyncResult(false, msg)
            }
            try {
                var page = 0
                var totalApplied = 0
                // The server returns pending entries oldest-first, capped per request
                // (there's no periodic background sync anymore to drain a big backlog
                // over several cycles), so a single "Get from server" tap must keep
                // paging until the server reports nothing left — otherwise a backlog
                // bigger than one page would leave the newest changes stuck at the
                // back of the queue forever.
                while (page < MAX_PULL_PAGES) {
                    page++
                    setProgress(
                        running = true,
                        phase = "fetch",
                        percent = 5,
                        detail = if (page == 1) "Fetching buffers…" else "Fetching more (page $page)…",
                        pullPage = page,
                    )
                    val localPending = loadLocalPending()
                    val remotePending = fetchRemoteBuffer(base, token)
                    setProgress(
                        running = true,
                        phase = "fetch",
                        percent = 15,
                        detail = "Comparing buffers…",
                        pendingLocal = localPending.size,
                        pendingRemote = remotePending.size,
                        pullPage = page,
                    )

                    if (remotePending.isEmpty()) {
                        clearConflictSession()
                        val pendingLeft = db.syncOutboxDao().countPending()
                        val msg = if (totalApplied > 0) {
                            "Got data from server ($totalApplied applied; local pending left: $pendingLeft)"
                        } else {
                            "Already up to date (local pending left: $pendingLeft)"
                        }
                        setProgress(
                            running = false,
                            phase = "done",
                            percent = 100,
                            detail = msg,
                            pendingLocal = pendingLeft,
                            conflictCount = 0,
                        )
                        return@withLock CloudSyncResult(true, msg)
                    }

                    val conflicts = BufferConflictDetector.findConflicts(localPending, remotePending)
                    val unresolved = conflicts.filter {
                        resolutions[it.serial.uppercase()] == null &&
                            resolutions[it.serial] == null
                    }
                    if (unresolved.isNotEmpty()) {
                        val session = SyncConflictSession(conflicts = unresolved)
                        _conflictSession.value = session
                        setProgress(
                            running = false,
                            phase = "conflicts",
                            percent = 30,
                            detail = "Waiting for conflict resolution (${unresolved.size})",
                            pendingLocal = localPending.size,
                            pendingRemote = remotePending.size,
                            conflictCount = unresolved.size,
                        )
                        showConflictNotification(unresolved.size)
                        // Still apply non-conflicting entries first.
                        applyNonConflictingRemote(
                            base,
                            token,
                            remotePending,
                            conflicts,
                            resolutions,
                            pauseAfter = true,
                        )
                        setProgress(
                            running = false,
                            phase = "conflicts",
                            percent = 100,
                            detail = "Waiting for conflict resolution (${unresolved.size})",
                            pendingLocal = db.syncOutboxDao().countPending(),
                            pendingRemote = remotePending.size,
                            conflictCount = unresolved.size,
                        )
                        return@withLock CloudSyncResult(
                            ok = false,
                            message = "Conflicts require resolution (${unresolved.size})",
                            needsConflictResolution = true,
                        )
                    }

                    clearConflictSession()
                    applyNonConflictingRemote(
                        base,
                        token,
                        remotePending,
                        conflicts,
                        resolutions,
                        pauseAfter = false,
                    )
                    totalApplied += remotePending.size
                    // A short page (less than the server's per-request cap) means the
                    // backlog is drained; no need to make one more round-trip.
                    if (remotePending.size < PULL_PAGE_SIZE_HINT) {
                        val pendingLeft = db.syncOutboxDao().countPending()
                        val msg = "Got data from server ($totalApplied applied; local pending left: $pendingLeft)"
                        setProgress(
                            running = false,
                            phase = "done",
                            percent = 100,
                            detail = msg,
                            pendingLocal = pendingLeft,
                            conflictCount = 0,
                        )
                        return@withLock CloudSyncResult(true, msg)
                    }
                }
                val pendingLeft = db.syncOutboxDao().countPending()
                val msg = "Got data from server ($totalApplied applied so far; more remains — tap again)"
                setProgress(
                    running = false,
                    phase = "done",
                    percent = 100,
                    detail = msg,
                    pendingLocal = pendingLeft,
                    conflictCount = 0,
                )
                CloudSyncResult(true, msg)
            } catch (e: CancellationException) {
                setProgress(running = false, phase = "error", percent = 0, detail = "Cancelled")
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "pull failed", e)
                val msg = e.message ?: "Pull failed"
                setProgress(running = false, phase = "error", percent = 0, detail = msg)
                CloudSyncResult(false, msg)
            }
        }
    }

    /** Remote → local only: apply non-conflicting/resolved-REMOTE remote entries and ack them. */
    private suspend fun applyNonConflictingRemote(
        base: String,
        token: String,
        remotePending: List<SyncBufferEntry>,
        conflicts: List<SyncConflictItem>,
        resolutions: Map<String, SyncConflictChoice>,
        pauseAfter: Boolean,
    ): CloudSyncResult {
        val conflictSerials = conflicts.map { it.serial.uppercase() }.toSet()
        fun choiceFor(serial: String): SyncConflictChoice? {
            val key = serial.uppercase()
            return resolutions[key] ?: resolutions[serial]
        }

        // Remote → local: non-conflict remote entries + conflicts resolved as REMOTE
        val remoteToApply = ArrayList<SyncBufferEntry>()
        val remoteAckIds = ArrayList<String>()
        for (entry in remotePending) {
            when (entry.type) {
                "group_upsert", "group_delete" -> {
                    remoteToApply.add(entry)
                    remoteAckIds.add(entry.entryId)
                }
                "serial_upsert", "serial_batch_upsert" -> {
                    val serials = BufferConflictDetector.expandBySerial(listOf(entry)).keys
                    val blocked = serials.any { s ->
                        s.uppercase() in conflictSerials && choiceFor(s) == null
                    }
                    if (blocked) continue
                    val onlyRemoteWins = serials.all { s ->
                        s.uppercase() !in conflictSerials || choiceFor(s) == SyncConflictChoice.REMOTE
                    }
                    if (onlyRemoteWins || serials.none { it.uppercase() in conflictSerials }) {
                        // For batches with mixed resolutions, expand to per-serial.
                        if (entry.type == "serial_batch_upsert" &&
                            serials.any { it.uppercase() in conflictSerials }
                        ) {
                            for ((serial, expanded) in BufferConflictDetector.expandBySerial(listOf(entry))) {
                                val c = choiceFor(serial)
                                if (serial.uppercase() in conflictSerials && c != SyncConflictChoice.REMOTE) {
                                    continue
                                }
                                if (serial.uppercase() in conflictSerials && c == null) continue
                                remoteToApply.add(expanded)
                            }
                        } else {
                            remoteToApply.add(entry)
                        }
                        remoteAckIds.add(entry.entryId)
                    } else {
                        for ((serial, expanded) in BufferConflictDetector.expandBySerial(listOf(entry))) {
                            if (choiceFor(serial) == SyncConflictChoice.REMOTE ||
                                serial.uppercase() !in conflictSerials
                            ) {
                                remoteToApply.add(expanded)
                            }
                        }
                        // ACK whole remote entry only when all serials resolved away from pending conflict
                        val allResolved = serials.all { s ->
                            s.uppercase() !in conflictSerials || choiceFor(s) != null
                        }
                        if (allResolved) remoteAckIds.add(entry.entryId)
                    }
                }
                else -> {
                    remoteAckIds.add(entry.entryId)
                }
            }
        }

        setProgress(
            running = true,
            phase = "apply",
            percent = 40,
            detail = "Applying ${remoteToApply.size} remote changes…",
            applyRowsTotal = remoteToApply.size,
        )
        var applyFailures = 0
        for ((idx, entry) in remoteToApply.withIndex()) {
            try {
                applyRemoteEntryLocally(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                applyFailures++
                Log.w(
                    TAG,
                    "apply failed for ${entry.type}/${entry.entryId}: ${e.message}",
                    e,
                )
            }
            // Throttle UI progress updates — flooding StateFlow on every row can ANR/crash Compose.
            val done = idx + 1
            if (done == remoteToApply.size || done % 25 == 0) {
                setProgress(
                    running = true,
                    phase = "apply",
                    percent = 40 + (done * 40) / remoteToApply.size.coerceAtLeast(1),
                    applyRowsDone = done,
                    applyRowsTotal = remoteToApply.size,
                    detail = if (applyFailures > 0) {
                        "Applying $done/${remoteToApply.size} ($applyFailures failed)…"
                    } else {
                        "Applying $done/${remoteToApply.size}…"
                    },
                )
            }
        }
        if (remoteAckIds.isNotEmpty()) {
            ackRemote(base, token, remoteAckIds.distinct())
        }

        // Remote won a conflict: drop the matching local outbox row without pushing it.
        for (c in conflicts) {
            if (choiceFor(c.serial) == SyncConflictChoice.REMOTE) {
                c.local.localOutboxId?.let {
                    db.syncOutboxDao().markSynced(listOf(it), System.currentTimeMillis())
                }
            }
        }

        settings.setCloudSyncLastPullMs(System.currentTimeMillis())

        val pendingLeft = db.syncOutboxDao().countPending()
        val msg = if (pauseAfter) {
            "Partial pull; ${conflicts.count { choiceFor(it.serial) == null }} conflicts pending"
        } else {
            "Got data from server (local pending left: $pendingLeft)"
        }
        return CloudSyncResult(true, msg, needsConflictResolution = pauseAfter)
    }

    private suspend fun loadLocalPending(): List<SyncBufferEntry> {
        return db.syncOutboxDao().listPending(5000).map { row ->
            SyncBufferEntry(
                entryId = row.eventId,
                type = row.type,
                serial = extractSerial(row.type, row.payloadJson),
                payload = JSONObject(row.payloadJson),
                createdAt = row.createdAt,
                localOutboxId = row.id,
            )
        }
    }

    private fun extractSerial(type: String, payloadJson: String): String? {
        if (type != "serial_upsert") return null
        return try {
            JSONObject(payloadJson).optString("serial").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchRemoteBuffer(base: String, token: String): List<SyncBufferEntry> {
        val urls = listOf(
            "$base/api/sync/buffer",
            "$base/api/sync/buffer/",
            "$base/api/sync/buffer/index.php",
        )
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("X-Sync-Token", token)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = text.trim().take(240).ifBlank { "(empty body)" }
                        lastError = IllegalStateException("Buffer GET HTTP ${response.code}: $detail")
                        return@use
                    }
                    val json = JSONObject(text)
                    val arr = json.optJSONArray("entries") ?: JSONArray()
                    val out = ArrayList<SyncBufferEntry>(arr.length())
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val payload = e.optJSONObject("payload") ?: JSONObject()
                        out.add(
                            SyncBufferEntry(
                                entryId = e.optString("entryId"),
                                type = e.optString("type"),
                                serial = e.optString("serial").takeIf { it.isNotBlank() && it != "null" },
                                payload = payload,
                                createdAt = e.optLong("createdAt", 0L),
                            ),
                        )
                    }
                    return out
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Buffer GET failed")
    }

    /** POST /api/sync/buffer/backfill — returns the number of rows the server enqueued. */
    private fun requestBackfill(base: String, token: String): Int {
        val urls = listOf(
            "$base/api/sync/buffer/backfill",
            "$base/api/sync/buffer/backfill.php",
            "$base/api/sync/buffer/backfill/index.php",
        )
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("X-Sync-Token", token)
                    .addHeader("Content-Type", "application/json")
                    .post("{}".toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = text.trim().take(240).ifBlank { "(empty body)" }
                        lastError = IllegalStateException("Backfill HTTP ${response.code}: $detail")
                        return@use
                    }
                    val json = JSONObject(text)
                    return json.optInt("enqueued", 0)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Backfill request failed")
    }

    private suspend fun pushLocalEntries(base: String, token: String, entries: List<SyncBufferEntry>) {
        var done = 0
        for (batch in entries.chunked(PUSH_BATCH_SIZE)) {
            pushBatch(base, token, batch)
            done += batch.size
            setProgress(
                running = true,
                phase = "push",
                percent = 70 + (done * 20) / entries.size.coerceAtLeast(1),
                detail = "Uploaded $done/${entries.size} entries",
                pushDone = done,
                pushTotal = entries.size,
            )
        }
    }

    private suspend fun pushBatch(base: String, token: String, entries: List<SyncBufferEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("entryId", e.entryId)
                    .put("eventId", e.entryId)
                    .put("type", e.type)
                    .put("payload", e.payload)
                    .put("serial", e.serial)
                    .put("createdAt", e.createdAt),
            )
        }
        val body = JSONObject().put("entries", arr).toString()
        val urls = listOf(
            "$base/api/sync/buffer/apply",
            "$base/api/sync/buffer/apply.php",
            "$base/api/sync/buffer/apply/index.php",
        )
        var ok = false
        var lastCode = 0
        var lastErr = ""
        for (url in urls) {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Sync-Token", token)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    lastCode = response.code
                    if (response.isSuccessful) {
                        ok = true
                    } else {
                        lastErr = response.body?.string()?.take(200).orEmpty()
                    }
                }
            } catch (e: IOException) {
                // Socket reset / timeout: fall through to the next URL spelling.
                lastErr = e.message ?: e.javaClass.simpleName
            }
            if (ok) break
        }
        val now = System.currentTimeMillis()
        if (!ok) {
            for (e in entries) {
                e.localOutboxId?.let {
                    db.syncOutboxDao().markAttempt(
                        it,
                        SyncOutboxEntity.STATUS_PENDING,
                        lastErr.ifBlank { "HTTP $lastCode" },
                        now,
                    )
                }
            }
            throw IllegalStateException(
                "Buffer apply failed (${entries.size} entries): HTTP $lastCode" +
                    if (lastErr.isNotBlank()) " — $lastErr" else "",
            )
        }
        val ids = entries.mapNotNull { it.localOutboxId }
        if (ids.isNotEmpty()) {
            db.syncOutboxDao().markSynced(ids, now)
        }
    }

    private fun ackRemote(base: String, token: String, entryIds: List<String>) {
        if (entryIds.isEmpty()) return
        for (batch in entryIds.chunked(ACK_BATCH_SIZE)) {
            if (!ackBatch(base, token, batch)) {
                Log.w(TAG, "Remote buffer ACK failed for ${batch.size} entries")
            }
        }
    }

    private fun ackBatch(base: String, token: String, entryIds: List<String>): Boolean {
        val body = JSONObject().put("entryIds", JSONArray(entryIds)).toString()
        val urls = listOf(
            "$base/api/sync/buffer/ack",
            "$base/api/sync/buffer/ack.php",
            "$base/api/sync/buffer/ack/index.php",
        )
        for (url in urls) {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Sync-Token", token)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return true
                }
            } catch (e: IOException) {
                Log.w(TAG, "ACK attempt failed on $url: ${e.message}")
            }
        }
        return false
    }

    private suspend fun applyRemoteEntryLocally(entry: SyncBufferEntry) {
        when (entry.type) {
            "group_upsert" -> applyGroupUpsert(entry.payload)
            "group_delete" -> applyGroupDelete(entry.payload)
            "serial_upsert" -> applySerialPayload(entry.payload)
            "serial_batch_upsert" -> {
                val arr = entry.payload.optJSONArray("serials") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (!item.has("syncOriginGroupId") && entry.payload.has("syncOriginGroupId")) {
                        item.put("syncOriginGroupId", entry.payload.get("syncOriginGroupId"))
                    }
                    if (!item.has("localGroupId") && entry.payload.has("localGroupId")) {
                        item.put("localGroupId", entry.payload.get("localGroupId"))
                    }
                    if (!item.has("category") && entry.payload.has("category")) {
                        item.put("category", entry.payload.get("category"))
                    }
                    applySerialPayload(item)
                }
            }
        }
    }

    private suspend fun applyGroupUpsert(p: JSONObject) {
        val groupDao = db.serialGroupDao()
        val remoteId = when {
            p.has("syncOriginGroupId") && !p.isNull("syncOriginGroupId") -> p.optLong("syncOriginGroupId")
            p.has("id") -> p.optLong("id")
            else -> 0L
        }
        val localHint = if (p.has("localGroupId") && !p.isNull("localGroupId")) {
            p.optLong("localGroupId")
        } else {
            null
        }
        val existing = when {
            remoteId > 0 -> groupDao.findBySyncOriginGroupId(remoteId)
            else -> null
        } ?: localHint?.let { groupDao.findById(it) }

        val syncMs = p.optLong("syncUpdatedMs", System.currentTimeMillis())
        val entity = SerialGroupEntity(
            id = existing?.id ?: 0,
            prefix = p.optString("prefix"),
            startNumber = p.optInt("startNumber"),
            endNumber = p.optInt("endNumber"),
            padWidth = p.optInt("padWidth"),
            description = p.optString("description").takeIf { it.isNotBlank() },
            score = p.optInt("score", 1).coerceAtLeast(0),
            category = p.optString("category", "end_user_client"),
            generationMode = p.optString("generationMode", "range"),
            warrantyMonths = if (p.has("warrantyMonths") && !p.isNull("warrantyMonths")) {
                p.optInt("warrantyMonths")
            } else {
                null
            },
            warrantyKm = if (p.has("warrantyKm") && !p.isNull("warrantyKm")) {
                p.optInt("warrantyKm")
            } else {
                null
            },
            imageVerificationRequired = p.optBoolean("imageVerificationRequired", false),
            createdAt = p.optLong("createdAt", syncMs).takeIf { it > 0 } ?: System.currentTimeMillis(),
            syncUpdatedMs = syncMs,
            syncOriginGroupId = if (remoteId > 0) remoteId else existing?.syncOriginGroupId,
        )
        if (existing == null) {
            groupDao.insert(entity.copy(id = 0))
        } else {
            groupDao.update(entity.copy(id = existing.id))
        }
    }

    private suspend fun applyGroupDelete(p: JSONObject) {
        val groupDao = db.serialGroupDao()
        val serialDao = db.serialDao()
        val remoteId = if (p.has("syncOriginGroupId") && !p.isNull("syncOriginGroupId")) {
            p.optLong("syncOriginGroupId")
        } else {
            0L
        }
        val localHint = if (p.has("localGroupId") && !p.isNull("localGroupId")) {
            p.optLong("localGroupId")
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

    private suspend fun applySerialPayload(p: JSONObject) {
        val serialDao = db.serialDao()
        val groupDao = db.serialGroupDao()
        val serialValue = SerialNormalizer.normalizeSerial(p.optString("serial"))
        if (serialValue.isEmpty()) return
        val phone = PhoneUtils.nullIfUnregistered(
            p.optString("phone").takeIf { it.isNotBlank() && it != "null" },
        )
        val city = p.optString("city").takeIf { it.isNotBlank() && it != "null" }
        val km = if (p.has("km") && !p.isNull("km")) p.optInt("km") else null
        val dateJalali = p.optString("dateJalali").takeIf { it.isNotBlank() && it != "null" }
            ?: p.optString("date").takeIf { it.isNotBlank() && it != "null" }
        val regSource = p.optString("regSource").takeIf { it.isNotBlank() && it != "null" }
        val syncMs = p.optLong("syncUpdatedMs", System.currentTimeMillis())
        val remoteGroupId = when {
            p.has("syncOriginGroupId") && !p.isNull("syncOriginGroupId") -> p.optLong("syncOriginGroupId")
            p.has("lanGroupId") && !p.isNull("lanGroupId") -> p.optLong("lanGroupId")
            else -> null
        }
        val mappedGroupId = remoteGroupId?.takeIf { it > 0 }?.let { rid ->
            groupDao.findBySyncOriginGroupId(rid)?.id
        } ?: if (p.has("localGroupId") && !p.isNull("localGroupId")) {
            p.optLong("localGroupId").takeIf { it > 0 }
        } else {
            null
        }

        val existing = serialDao.findBySerial(serialValue)
        // Remote payloads are often sparse (e.g. registration without date_jalali, or
        // MySQL `time` only). Never clobber good local CSV/import fields with nulls.
        val mergedPhone = phone ?: existing?.phone
        val mergedDate = dateJalali ?: existing?.date
        val mergedCity = city ?: existing?.city
        val mergedKm = km ?: existing?.km
        val mergedRegSource = regSource ?: existing?.registrationSource
        val mergedRegistered = PhoneUtils.isRegisteredPhone(mergedPhone)
        val fieldsComplete = mergedRegistered && !mergedCity.isNullOrBlank() && mergedKm != null
        if (existing == null) {
            serialDao.insertAll(
                listOf(
                    SerialEntity(
                        serial = serialValue,
                        phone = mergedPhone,
                        date = mergedDate,
                        city = mergedCity,
                        km = mergedKm,
                        registrationFieldsComplete = if (!mergedRegistered) true else fieldsComplete,
                        registrationSource = mergedRegSource,
                        groupId = mappedGroupId,
                        syncUpdatedMs = syncMs,
                    ),
                ),
            )
        } else {
            serialDao.applyBufferUpsert(
                serial = serialValue,
                phone = mergedPhone,
                date = mergedDate,
                city = mergedCity,
                km = mergedKm,
                fieldsComplete = if (!mergedRegistered) true else fieldsComplete,
                registrationSource = mergedRegSource,
                groupId = mappedGroupId ?: existing.groupId,
                syncUpdatedMs = syncMs,
            )
        }
    }

    private fun showConflictNotification(count: Int) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CONFLICT_CHANNEL_ID,
                        appContext.getString(R.string.settings_cloud_sync_conflict_channel),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SYNC_CONFLICTS, true)
            }
            val pi = PendingIntent.getActivity(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // Must be a white-on-transparent status-bar icon — mipmap launcher icons
            // make the system kill the app with RemoteServiceException on many devices.
            val notification = NotificationCompat.Builder(appContext, CONFLICT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sync_notification)
                .setContentTitle(appContext.getString(R.string.settings_cloud_sync_conflict_title))
                .setContentText(
                    appContext.getString(R.string.settings_cloud_sync_conflict_body, count),
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(CONFLICT_NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "conflict notification failed", e)
        }
    }

    private fun cancelConflictNotification() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(CONFLICT_NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "CloudSyncService"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val EXTRA_OPEN_SYNC_CONFLICTS = "open_sync_conflicts"
        private const val CONFLICT_CHANNEL_ID = "cloud_sync_conflicts"
        private const val CONFLICT_NOTIFICATION_ID = 71042

        /**
         * Shared hosts drop oversized or long-running requests mid-stream (seen as
         * "connection reset"), so buffers are exchanged in small batches.
         */
        private const val PUSH_BATCH_SIZE = 100
        private const val ACK_BATCH_SIZE = 500

        /**
         * Must match the server's per-request cap in `GET /api/sync/buffer`
         * (`sync_buffer_list_pending($conn, 500)`). Used only to detect a short
         * (final) page so pullInternal can stop without one extra empty round-trip.
         */
        private const val PULL_PAGE_SIZE_HINT = 500

        /** Safety cap so a huge backlog can't loop forever in a single tap (500 * 40 = 20k rows). */
        private const val MAX_PULL_PAGES = 40

        @Volatile
        private var instance: CloudSyncService? = null

        fun getInstance(context: Context): CloudSyncService {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncService(context.applicationContext).also { instance = it }
            }
        }
    }
}
