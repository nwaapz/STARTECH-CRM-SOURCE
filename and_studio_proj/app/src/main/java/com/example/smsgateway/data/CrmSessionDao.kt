package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CrmSessionDao {
    @Insert
    suspend fun insert(entity: CrmSessionEntity): Long

    @Query("SELECT * FROM crm_sessions WHERE token = :token LIMIT 1")
    suspend fun findByToken(token: String): CrmSessionEntity?

    @Query("DELETE FROM crm_sessions WHERE token = :token")
    suspend fun deleteByToken(token: String)

    @Query("DELETE FROM crm_sessions WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM crm_sessions WHERE userId = :userId")
    suspend fun deleteByUserId(userId: Long)
}
