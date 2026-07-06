package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TelegramMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: TelegramMessageEntity): Long

    @Query(
        """
        SELECT * FROM telegram_messages
        ORDER BY timestamp DESC, messageId DESC
        LIMIT :limit
        """,
    )
    fun getLatest(limit: Int): List<TelegramMessageEntity>

    @Query(
        """
        SELECT * FROM telegram_messages
        WHERE timestamp > :since
        ORDER BY timestamp ASC, messageId ASC
        LIMIT :limit
        """,
    )
    fun getSince(since: Long, limit: Int): List<TelegramMessageEntity>

    @Query("SELECT COUNT(*) FROM telegram_messages")
    fun countAll(): Int
}

@Dao
interface TelegramStateDao {
    @Query("SELECT * FROM telegram_state WHERE id = :id LIMIT 1")
    fun get(id: Int = TelegramStateEntity.SINGLETON_ID): TelegramStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TelegramStateEntity)
}

@Dao
interface TelegramChatContactDao {
    @Query("SELECT * FROM telegram_chats WHERE chatId = :chatId LIMIT 1")
    fun get(chatId: String): TelegramChatContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TelegramChatContactEntity)

    @Query(
        """
        UPDATE telegram_messages
        SET phoneNumber = :phoneNumber
        WHERE chatId = :chatId AND (phoneNumber IS NULL OR phoneNumber = '')
        """,
    )
    fun backfillMessagePhones(chatId: String, phoneNumber: String)
}
