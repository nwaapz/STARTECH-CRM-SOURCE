package com.example.smsgateway

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ActivityLog(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun getEntries(): List<LogEntry> {
        val raw = prefs.getString(KEY_ENTRIES, "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    LogEntry(
                        timestamp = item.getLong("timestamp"),
                        sender = item.getString("sender"),
                        inbound = item.getString("inbound"),
                        reply = item.optString("reply").ifBlank { null },
                        status = item.getString("status"),
                        error = item.optString("error").ifBlank { null },
                        simLabel = item.optString("simLabel").ifBlank { null },
                    )
                )
            }
        }
    }

    fun add(entry: LogEntry) {
        val current = JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
        val updated = JSONArray()
        updated.put(entry.toJson())
        for (i in 0 until minOf(current.length(), MAX_ENTRIES - 1)) {
            updated.put(current.getJSONObject(i))
        }
        prefs.edit().putString(KEY_ENTRIES, updated.toString()).apply()
        listeners.forEach { it() }
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
        listeners.forEach { it() }
    }

    private fun LogEntry.toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("sender", sender)
        put("inbound", inbound)
        put("reply", reply ?: "")
        put("status", status)
        put("error", error ?: "")
        put("simLabel", simLabel ?: "")
    }

    companion object {
        private const val PREFS_NAME = "activity_log"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 50

        @Volatile
        private var instance: ActivityLog? = null

        fun getInstance(context: Context): ActivityLog {
            return instance ?: synchronized(this) {
                instance ?: ActivityLog(context.applicationContext).also { instance = it }
            }
        }
    }
}
