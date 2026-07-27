package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SmsSendQuotaEventDao {
    @Insert
    suspend fun insert(event: SmsSendQuotaEventEntity): Long

    @Query(
        """
        SELECT COUNT(*) FROM sms_send_quota_events
        WHERE subscriptionKey = :subscriptionKey AND sentAt >= :sinceMs
        """,
    )
    suspend fun countSince(subscriptionKey: String, sinceMs: Long): Int

    @Query("DELETE FROM sms_send_quota_events WHERE sentAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}
