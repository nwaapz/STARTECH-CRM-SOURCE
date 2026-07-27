package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReferralPendingDao {
    @Query("SELECT code FROM referral_pending WHERE channel = :channel AND chatId = :chatId LIMIT 1")
    suspend fun findCode(channel: String, chatId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReferralPendingEntity): Long

    @Query("DELETE FROM referral_pending WHERE channel = :channel AND chatId = :chatId")
    suspend fun deleteFor(channel: String, chatId: String)
}
