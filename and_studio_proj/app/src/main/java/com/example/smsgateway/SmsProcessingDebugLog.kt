package com.example.smsgateway

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Ring buffer of SMS / bot registration processing steps.
 * Shown in LAN CRM so Mode 1/2/3 bugs can be copied and shared for debugging.
 */
object SmsProcessingDebugLog {
    private const val MAX_ENTRIES = 1_200

    data class Entry(
        val id: Long,
        val timestamp: String,
        val correlationId: String,
        val phone: String,
        val mode: String,
        val message: String,
    ) {
        fun formatted(): String {
            val phonePart = if (phone.isBlank()) "" else " phone=$phone"
            val modePart = if (mode.isBlank()) "" else " mode=$mode"
            return "[$timestamp] #$correlationId$modePart$phonePart | $message"
        }
    }

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val nextId = AtomicLong(1)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(
        correlationId: String,
        phone: String = "",
        mode: String = "",
        message: String,
    ) {
        val entry = Entry(
            id = nextId.getAndIncrement(),
            timestamp = timeFormat.format(Date()),
            correlationId = correlationId,
            phone = phone,
            mode = mode,
            message = message,
        )
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(limit: Int = MAX_ENTRIES, phoneFilter: String? = null): List<Entry> {
        val all = entries.toList()
        val filtered = if (phoneFilter.isNullOrBlank()) {
            all
        } else {
            val needle = phoneFilter.trim()
            all.filter { it.phone.contains(needle) || it.message.contains(needle, ignoreCase = true) }
        }
        return filtered.takeLast(limit.coerceAtLeast(1)).asReversed()
    }

    @Synchronized
    fun snapshotText(limit: Int = MAX_ENTRIES, phoneFilter: String? = null): String {
        return snapshot(limit, phoneFilter).asReversed().joinToString("\n") { it.formatted() }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        log(correlationId = "system", message = "SMS processing debug log cleared")
    }

    @Synchronized
    fun size(): Int = entries.size
}
