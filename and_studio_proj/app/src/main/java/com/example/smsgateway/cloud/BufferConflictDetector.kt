package com.example.smsgateway.cloud

import org.json.JSONArray
import org.json.JSONObject

enum class SyncConflictChoice {
    LOCAL,
    REMOTE,
}

data class SyncBufferEntry(
    val entryId: String,
    val type: String,
    val serial: String?,
    val payload: JSONObject,
    val createdAt: Long,
    val localOutboxId: Long? = null,
)

data class SyncConflictItem(
    val serial: String,
    val local: SyncBufferEntry,
    val remote: SyncBufferEntry,
)

data class SyncConflictSession(
    val conflicts: List<SyncConflictItem>,
    val createdAt: Long = System.currentTimeMillis(),
)

object BufferConflictDetector {
    /**
     * Rule 2A: same serial in both buffers with differing registration fields.
     * Batch entries are expanded to per-serial for comparison.
     */
    fun findConflicts(
        local: List<SyncBufferEntry>,
        remote: List<SyncBufferEntry>,
    ): List<SyncConflictItem> {
        val localBySerial = expandBySerial(local)
        val remoteBySerial = expandBySerial(remote)
        val out = ArrayList<SyncConflictItem>()
        for ((serial, localEntry) in localBySerial) {
            val remoteEntry = remoteBySerial[serial] ?: continue
            if (registrationFieldsDiffer(localEntry.payload, remoteEntry.payload)) {
                out.add(SyncConflictItem(serial = serial, local = localEntry, remote = remoteEntry))
            }
        }
        return out.sortedBy { it.serial }
    }

    fun expandBySerial(entries: List<SyncBufferEntry>): Map<String, SyncBufferEntry> {
        val map = LinkedHashMap<String, SyncBufferEntry>()
        for (entry in entries) {
            when (entry.type) {
                "serial_upsert" -> {
                    val serial = normalizeSerial(
                        entry.serial ?: entry.payload.optString("serial"),
                    )
                    if (serial.isNotEmpty()) {
                        map[serial] = entry.copy(serial = serial)
                    }
                }
                "serial_batch_upsert" -> {
                    val arr = entry.payload.optJSONArray("serials") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val serial = normalizeSerial(item.optString("serial"))
                        if (serial.isEmpty()) continue
                        val payload = JSONObject(item.toString())
                        if (!payload.has("syncOriginGroupId") && entry.payload.has("syncOriginGroupId")) {
                            payload.put("syncOriginGroupId", entry.payload.get("syncOriginGroupId"))
                        }
                        if (!payload.has("localGroupId") && entry.payload.has("localGroupId")) {
                            payload.put("localGroupId", entry.payload.get("localGroupId"))
                        }
                        if (!payload.has("category") && entry.payload.has("category")) {
                            payload.put("category", entry.payload.get("category"))
                        }
                        map[serial] = SyncBufferEntry(
                            entryId = entry.entryId + ":" + serial,
                            type = "serial_upsert",
                            serial = serial,
                            payload = payload,
                            createdAt = entry.createdAt,
                            localOutboxId = entry.localOutboxId,
                        )
                    }
                }
            }
        }
        return map
    }

    fun registrationFieldsDiffer(a: JSONObject, b: JSONObject): Boolean {
        return norm(a, "phone") != norm(b, "phone") ||
            norm(a, "city") != norm(b, "city") ||
            normKm(a) != normKm(b) ||
            norm(a, "dateJalali").ifEmpty { norm(a, "date") } !=
            norm(b, "dateJalali").ifEmpty { norm(b, "date") } ||
            norm(a, "regSource") != norm(b, "regSource") ||
            groupLinkKey(a) != groupLinkKey(b)
    }

    private fun groupLinkKey(p: JSONObject): String {
        val origin = if (p.has("syncOriginGroupId") && !p.isNull("syncOriginGroupId")) {
            p.optLong("syncOriginGroupId").toString()
        } else {
            ""
        }
        val local = if (p.has("localGroupId") && !p.isNull("localGroupId")) {
            p.optLong("localGroupId").toString()
        } else if (p.has("lanGroupId") && !p.isNull("lanGroupId")) {
            p.optLong("lanGroupId").toString()
        } else {
            ""
        }
        return "$origin|$local"
    }

    private fun norm(p: JSONObject, key: String): String {
        if (!p.has(key) || p.isNull(key)) return ""
        val v = p.optString(key).trim()
        if (v.isEmpty() || v == "null" || v == "0") return ""
        return v
    }

    private fun normKm(p: JSONObject): String {
        if (!p.has("km") || p.isNull("km")) return ""
        return p.optInt("km").toString()
    }

    private fun normalizeSerial(raw: String?): String =
        (raw ?: "").trim().uppercase()
}
