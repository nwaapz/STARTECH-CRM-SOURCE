package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SmsSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsSessionStore(
    private val database: GatewayDatabase,
) {
    suspend fun findValidSession(phone: String, timeoutMinutes: Int): SmsSessionEntity? =
        withContext(Dispatchers.IO) {
            val dao = database.smsSessionDao()
            val cutoff = System.currentTimeMillis() - timeoutMinutes * 60_000L
            dao.deleteExpired(cutoff)
            val session = dao.findByPhone(phone) ?: return@withContext null
            if (session.updatedAt < cutoff) {
                dao.deleteByPhone(phone)
                null
            } else {
                session
            }
        }

    suspend fun isSessionExpired(phone: String, timeoutMinutes: Int): Boolean = withContext(Dispatchers.IO) {
        val session = database.smsSessionDao().findByPhone(phone) ?: return@withContext false
        val cutoff = System.currentTimeMillis() - timeoutMinutes * 60_000L
        session.updatedAt < cutoff
    }

    suspend fun saveSession(session: SmsSessionEntity) = withContext(Dispatchers.IO) {
        database.smsSessionDao().upsert(session)
    }

    suspend fun clearSession(phone: String) = withContext(Dispatchers.IO) {
        database.smsSessionDao().deleteByPhone(phone)
    }

    companion object {
        @Volatile
        private var instance: SmsSessionStore? = null

        fun getInstance(database: GatewayDatabase): SmsSessionStore {
            return instance ?: synchronized(this) {
                instance ?: SmsSessionStore(database).also { instance = it }
            }
        }
    }
}
