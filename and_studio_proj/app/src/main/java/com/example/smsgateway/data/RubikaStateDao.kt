package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RubikaStateDao {
    @Query("SELECT * FROM rubika_state WHERE id = :id LIMIT 1")
    fun get(id: Int = RubikaStateEntity.SINGLETON_ID): RubikaStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: RubikaStateEntity)
}

@Dao
interface RubikaChatContactDao {
    @Query("SELECT * FROM rubika_chats WHERE chatId = :chatId LIMIT 1")
    fun get(chatId: String): RubikaChatContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: RubikaChatContactEntity)

    @Query(
        """
        UPDATE rubika_messages
        SET phoneNumber = :phoneNumber
        WHERE chatId = :chatId AND (phoneNumber IS NULL OR phoneNumber = '')
        """,
    )
    fun backfillMessagePhones(chatId: String, phoneNumber: String)
}
