package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SmsSendQuotaEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SimSmsQuotaStore(
    private val context: Context,
    private val database: GatewayDatabase = GatewayDatabase.getInstance(context),
) {
    private val settingsStore = SettingsStore.getInstance(context)
    private val dao = database.smsSendQuotaEventDao()

    fun getLimit(): Int = settingsStore.getSmsDailyLimit()

    fun windowStartMs(nowMs: Long = System.currentTimeMillis()): Long =
        nowMs - WINDOW_MS

    suspend fun sentInLast24h(subscriptionId: Int, nowMs: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            pruneOlderThan(windowStartMs(nowMs))
            val key = SimCardHelper.fingerprintForSubscription(context, subscriptionId)
            dao.countSince(key, windowStartMs(nowMs))
        }

    suspend fun remaining(subscriptionId: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val limit = getLimit().coerceAtLeast(0)
        return (limit - sentInLast24h(subscriptionId, nowMs)).coerceAtLeast(0)
    }

    fun sentInLast24hBlocking(subscriptionId: Int): Int =
        runBlocking { sentInLast24h(subscriptionId) }

    fun remainingBlocking(subscriptionId: Int): Int =
        runBlocking { remaining(subscriptionId) }

    suspend fun recordSend(subscriptionId: Int, sentAt: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val key = SimCardHelper.fingerprintForSubscription(context, subscriptionId)
            dao.insert(
                SmsSendQuotaEventEntity(
                    subscriptionKey = key,
                    sentAt = sentAt,
                ),
            )
            pruneOlderThan(windowStartMs(sentAt))
        }

    fun recordSendBlocking(subscriptionId: Int) {
        runBlocking { recordSend(subscriptionId) }
    }

    private suspend fun pruneOlderThan(cutoffMs: Long) {
        dao.deleteOlderThan(cutoffMs)
    }

    companion object {
        const val WINDOW_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_DAILY_LIMIT = 300

        @Volatile
        private var instance: SimSmsQuotaStore? = null

        fun getInstance(context: Context): SimSmsQuotaStore {
            return instance ?: synchronized(this) {
                instance ?: SimSmsQuotaStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
