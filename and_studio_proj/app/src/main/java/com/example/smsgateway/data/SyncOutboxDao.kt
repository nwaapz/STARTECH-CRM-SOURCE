package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SyncOutboxEntity): Long

    @Query(
        """
        DELETE FROM sync_outbox
        WHERE status = 'pending' AND coalesceKey = :coalesceKey
        """,
    )
    suspend fun deletePendingByCoalesceKey(coalesceKey: String)

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE status = 'pending'
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun listPending(limit: Int = 5000): List<SyncOutboxEntity>

    @Query(
        """
        UPDATE sync_outbox
        SET status = :status, attempts = attempts + 1,
            lastError = :lastError, lastAttemptAt = :at
        WHERE id = :id
        """,
    )
    suspend fun markAttempt(id: Long, status: String, lastError: String?, at: Long)

    @Query(
        """
        UPDATE sync_outbox
        SET status = 'synced', lastAttemptAt = :at, lastError = NULL
        WHERE id IN (:ids)
        """,
    )
    suspend fun markSynced(ids: List<Long>, at: Long)

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'pending'")
    suspend fun countPending(): Int

    @Query("DELETE FROM sync_outbox WHERE status IN ('synced', 'sent') AND createdAt < :before")
    suspend fun pruneSyncedOlderThan(before: Long)
}
