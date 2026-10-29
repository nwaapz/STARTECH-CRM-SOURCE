package com.example.smsgateway.cloud

import android.content.Context
import android.util.Log
import com.example.smsgateway.JalaliHelper
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.SerialNormalizer
import com.example.smsgateway.SettingsStore
import com.example.smsgateway.SyncEventWriter
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One-shot repair for registered serials whose report date is null/blank.
 * Does **not** use dual-buffer sync (get/send/backfill); only:
 *  1) recovers a date from local Room history when possible
 *  2) looks up dates on the remote host via a dedicated dates endpoint
 */
class NullDateRepairService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore.getInstance(appContext)
    private val db = GatewayDatabase.getInstance(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Result(
        val ok: Boolean,
        val scanned: Int = 0,
        val fixedLocal: Int = 0,
        val fixedRemote: Int = 0,
        val unfixed: Int = 0,
        val message: String? = null,
    )

    suspend fun repair(): Result = withContext(Dispatchers.IO) {
        val rows = db.serialDao().listRegisteredWithNullDate()
        if (rows.isEmpty()) {
            return@withContext Result(
                ok = true,
                scanned = 0,
                message = "ردیفی با تاریخ خالی پیدا نشد",
            )
        }

        var fixedLocal = 0
        val stillMissing = ArrayList<SerialEntity>()

        for (row in rows) {
            val recovered = recoverDateFromLocalHistory(row.serial)
            if (recovered != null && applyDate(row, recovered, fromRemote = false)) {
                fixedLocal++
            } else {
                stillMissing.add(row)
            }
        }

        var fixedRemote = 0
        val remainingAfterRemote = ArrayList<SerialEntity>()
        if (stillMissing.isNotEmpty()) {
            val remoteDates = fetchDatesFromRemote(stillMissing.map { it.serial })
            for (row in stillMissing) {
                val key = SerialNormalizer.normalizeSerial(row.serial)
                val remoteDate = remoteDates[key]
                if (!remoteDate.isNullOrBlank() && applyDate(row, remoteDate, fromRemote = true)) {
                    fixedRemote++
                } else {
                    remainingAfterRemote.add(row)
                }
            }
        }

        val unfixed = remainingAfterRemote.size
        val message = buildString {
            append("بررسی ")
            append(rows.size)
            append(" ردیف — محلی: ")
            append(fixedLocal)
            append("، سرور: ")
            append(fixedRemote)
            if (unfixed > 0) {
                append("، بدون تاریخ: ")
                append(unfixed)
            }
        }
        Result(
            ok = true,
            scanned = rows.size,
            fixedLocal = fixedLocal,
            fixedRemote = fixedRemote,
            unfixed = unfixed,
            message = message,
        )
    }

    private suspend fun recoverDateFromLocalHistory(serial: String): String? {
        val needle = SerialNormalizer.normalizeSerial(serial)
        if (needle.isEmpty()) return null
        val events = db.syncEventDao().findRecentRegistrationEventsForSerial(needle)
        for (event in events) {
            val parsed = extractDateFromPayload(event.payload, needle) ?: continue
            return parsed
        }
        return null
    }

    private fun extractDateFromPayload(payloadJson: String, expectedSerial: String): String? {
        return try {
            val obj = JSONObject(payloadJson)
            val payloadSerial = SerialNormalizer.normalizeSerial(
                obj.optString("serial").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            )
            if (payloadSerial.isNotEmpty() && payloadSerial != expectedSerial) return null
            val raw = obj.optString("date").takeIf { it.isNotBlank() && it != "null" }
                ?: obj.optString("dateJalali").takeIf { it.isNotBlank() && it != "null" }
            normalizeRecoveredDate(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeRecoveredDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.equals("null", ignoreCase = true)) return null
        JalaliHelper.parseToStoredJalaliDateTime(trimmed)?.let { return it }
        val latin = PhoneUtils.normalizeToAsciiDigits(trimmed)
        if (latin.isBlank() || latin.equals("null", ignoreCase = true)) return null
        // Accept already-Jalali values that only needed digit normalization.
        if (JalaliHelper.datePrefixForCompare(latin) != null) {
            return JalaliHelper.normalizeStoredDateTime(latin)
        }
        return null
    }

    private suspend fun applyDate(row: SerialEntity, rawDate: String, fromRemote: Boolean): Boolean {
        val normalized = normalizeRecoveredDate(rawDate) ?: return false
        val now = System.currentTimeMillis()
        val updated = db.serialDao().updateDateIfNull(row.id, normalized, now)
        if (updated <= 0) return false
        val phone = row.phone?.takeIf { PhoneUtils.isRegisteredPhone(it) } ?: return true
        try {
            SyncEventWriter.getInstance(appContext).recordRegistrationUpdated(
                serial = row.serial,
                phone = phone,
                date = normalized,
                city = row.city,
                km = row.km,
            )
        } catch (e: Exception) {
            Log.w(TAG, "enqueue after ${if (fromRemote) "remote" else "local"} date fix failed", e)
        }
        return true
    }

    /**
     * Dedicated remote lookup — not buffer get/send/backfill.
     * @return map of UPPER(serial) → dateJalali
     */
    private fun fetchDatesFromRemote(serials: List<String>): Map<String, String> {
        val base = settings.getCloudSyncUrl().trimEnd('/')
        val token = settings.getCloudSyncToken()
        if (base.isBlank() || token.isBlank() || serials.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, String>()
        for (chunk in serials.chunked(REMOTE_BATCH_SIZE)) {
            val arr = JSONArray()
            for (s in chunk) {
                val n = SerialNormalizer.normalizeSerial(s)
                if (n.isNotEmpty()) arr.put(n)
            }
            if (arr.length() == 0) continue
            val body = JSONObject().put("serials", arr).toString()
            val urls = listOf(
                "$base/api/sync/serials/dates",
                "$base/api/sync/serials/dates.php",
                "$base/api/sync/serials/dates/index.php",
            )
            var applied = false
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
                        if (!response.isSuccessful) return@use
                        val text = response.body?.string().orEmpty()
                        if (text.isBlank()) return@use
                        val json = JSONObject(text)
                        val items = json.optJSONArray("items") ?: return@use
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val serial = SerialNormalizer.normalizeSerial(
                                item.optString("serial").orEmpty(),
                            )
                            val date = item.optString("dateJalali").takeIf { it.isNotBlank() && it != "null" }
                                ?: item.optString("date").takeIf { it.isNotBlank() && it != "null" }
                            if (serial.isEmpty() || date.isNullOrBlank()) continue
                            out[serial] = date
                        }
                        applied = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "remote date lookup failed for $url", e)
                }
                if (applied) break
            }
        }
        return out
    }

    companion object {
        private const val TAG = "NullDateRepair"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val REMOTE_BATCH_SIZE = 100

        @Volatile
        private var instance: NullDateRepairService? = null

        fun getInstance(context: Context): NullDateRepairService {
            return instance ?: synchronized(this) {
                instance ?: NullDateRepairService(context.applicationContext).also { instance = it }
            }
        }
    }
}
