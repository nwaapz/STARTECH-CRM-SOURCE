package com.example.smsgateway.lan

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LanTraceLog {
    private const val LOG_TAG = "GatewayLan"
    private const val MAX_ENTRIES = 400

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        fun formatted(): String = "[$timestamp] ${level.name}/$tag: $message"
    }

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val listeners = CopyOnWriteArrayList<(List<Entry>) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun d(tag: String, message: String) = append(Level.DEBUG, tag, message)

    @Synchronized
    fun i(tag: String, message: String) = append(Level.INFO, tag, message)

    @Synchronized
    fun w(tag: String, message: String) = append(Level.WARN, tag, message)

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable == null) {
            message
        } else {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        append(Level.ERROR, tag, fullMessage)
        if (throwable != null) {
            append(Level.ERROR, tag, Log.getStackTraceString(throwable).take(2000))
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList().asReversed()

    @Synchronized
    fun snapshotText(): String = snapshot().joinToString("\n") { it.formatted() }

    @Synchronized
    fun clear() {
        entries.clear()
        i("Trace", "Log cleared")
        notifyListeners()
    }

    fun addListener(listener: (List<Entry>) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (List<Entry>) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun append(level: Level, tag: String, message: String) {
        val entry = Entry(
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
        )
        if (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
        entries.addLast(entry)
        when (level) {
            Level.DEBUG -> Log.d(LOG_TAG, "${tag}: $message")
            Level.INFO -> Log.i(LOG_TAG, "${tag}: $message")
            Level.WARN -> Log.w(LOG_TAG, "${tag}: $message")
            Level.ERROR -> Log.e(LOG_TAG, "${tag}: $message")
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val copy = snapshot()
        for (listener in listeners) {
            listener(copy)
        }
    }
}
