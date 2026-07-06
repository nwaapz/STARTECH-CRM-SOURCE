package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RubikaMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: RubikaMessageEntity): Long

    @Query(
        """
        SELECT * FROM rubika_messages
        ORDER BY timestamp DESC, messageId DESC
        LIMIT :limit
        """,
    )
    fun getLatest(limit: Int): List<RubikaMessageEntity>

    @Query(
        """
        SELECT * FROM rubika_messages
        WHERE timestamp > :since
        ORDER BY timestamp ASC, messageId ASC
        LIMIT :limit
        """,
    )
    fun getSince(since: Long, limit: Int): List<RubikaMessageEntity>

    @Query("SELECT COUNT(*) FROM rubika_messages")
    fun countAll(): Int

    @Query("DELETE FROM rubika_messages")
    fun clearAll()
}
