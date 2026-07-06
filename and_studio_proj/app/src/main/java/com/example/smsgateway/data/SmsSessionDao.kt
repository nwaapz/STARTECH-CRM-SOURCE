package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SmsSessionDao {
    @Query("SELECT * FROM sms_sessions WHERE phone = :phone LIMIT 1")
    suspend fun findByPhone(phone: String): SmsSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SmsSessionEntity)

    @Query("DELETE FROM sms_sessions WHERE phone = :phone")
    suspend fun deleteByPhone(phone: String)

    @Query("DELETE FROM sms_sessions WHERE updatedAt < :cutoff")
    suspend fun deleteExpired(cutoff: Long)
}
