package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CommunicationMessageDao {
    @Insert
    suspend fun insert(message: CommunicationMessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<CommunicationMessageEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM communication_messages")
    suspend fun countAll(): Int

    @Query(
        """
        SELECT * FROM communication_messages
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPage(offset: Int, limit: Int): List<CommunicationMessageEntity>

    @Query(
        """
        SELECT * FROM communication_messages
        WHERE (:direction = '' OR direction = :direction)
          AND (:phonePrefix = '' OR phone LIKE :phonePrefix || '%')
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPageFiltered(
        direction: String,
        phonePrefix: String,
        offset: Int,
        limit: Int,
    ): List<CommunicationMessageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM communication_messages
        WHERE (:direction = '' OR direction = :direction)
          AND (:phonePrefix = '' OR phone LIKE :phonePrefix || '%')
        """,
    )
    suspend fun countFiltered(direction: String, phonePrefix: String): Int

    @Query(
        """
        SELECT * FROM communication_messages
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getLatest(limit: Int): List<CommunicationMessageEntity>

    @Query(
        """
        SELECT * FROM communication_messages
        WHERE phone = :phone AND timestamp < :before
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getThreadBefore(phone: String, before: Long, limit: Int): List<CommunicationMessageEntity>

    @Query(
        """
        SELECT * FROM communication_messages
        WHERE phone = :phone
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getThreadLatest(phone: String, limit: Int): List<CommunicationMessageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM communication_messages
        WHERE phone = :phone AND timestamp < :before
        """,
    )
    suspend fun countThreadBefore(phone: String, before: Long): Int

    @Query("DELETE FROM communication_messages")
    suspend fun deleteAll()
}
