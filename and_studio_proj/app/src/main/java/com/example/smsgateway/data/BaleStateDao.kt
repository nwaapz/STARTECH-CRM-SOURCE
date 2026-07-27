package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BaleMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: BaleMessageEntity): Long

    @Query(
        """
        SELECT * FROM bale_messages
        ORDER BY timestamp DESC, messageId DESC
        LIMIT :limit
        """,
    )
    fun getLatest(limit: Int): List<BaleMessageEntity>

    @Query(
        """
        SELECT * FROM bale_messages
        WHERE timestamp > :since
        ORDER BY timestamp ASC, messageId ASC
        LIMIT :limit
        """,
    )
    fun getSince(since: Long, limit: Int): List<BaleMessageEntity>

    @Query("SELECT COUNT(*) FROM bale_messages")
    fun countAll(): Int
}

@Dao
interface BaleStateDao {
    @Query("SELECT * FROM bale_state WHERE id = :id LIMIT 1")
    fun get(id: Int = BaleStateEntity.SINGLETON_ID): BaleStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: BaleStateEntity)
}

@Dao
interface BaleChatContactDao {
    @Query("SELECT * FROM bale_chats WHERE chatId = :chatId LIMIT 1")
    fun get(chatId: String): BaleChatContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: BaleChatContactEntity)

    @Query(
        """
        UPDATE bale_messages
        SET phoneNumber = :phoneNumber
        WHERE chatId = :chatId AND (phoneNumber IS NULL OR phoneNumber = '')
        """,
    )
    fun backfillMessagePhones(chatId: String, phoneNumber: String)

    @Query(
        """
        SELECT COUNT(*) FROM bale_chats
        WHERE phoneNumber = :phone
          AND phoneNumber IS NOT NULL AND phoneNumber != ''
        """,
    )
    fun countByPhone(phone: String): Int

    @Query(
        """
        SELECT * FROM bale_chats
        WHERE phoneNumber = :phone
          AND phoneNumber IS NOT NULL AND phoneNumber != ''
        LIMIT 1
        """,
    )
    fun findByPhone(phone: String): BaleChatContactEntity?
}
