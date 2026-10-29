package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SyncEventDao {
    @Insert
    suspend fun insert(event: SyncEventEntity): Long

    @Query("SELECT * FROM sync_events WHERE at > :since ORDER BY at ASC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int = 100): List<SyncEventEntity>

    @Query("DELETE FROM sync_events WHERE at < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /**
     * Recent registration / edit events whose payload mentions [serialNeedle]
     * (caller should pass the exact serial; LIKE is case-sensitive in SQLite).
     */
    @Query(
        """
        SELECT * FROM sync_events
        WHERE (type = 'registered' OR type = 'registration_updated')
          AND payload LIKE '%' || :serialNeedle || '%'
        ORDER BY at DESC
        LIMIT :limit
        """,
    )
    suspend fun findRecentRegistrationEventsForSerial(
        serialNeedle: String,
        limit: Int = 20,
    ): List<SyncEventEntity>
}
