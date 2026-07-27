package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BotMediaAssetDao {
    @Insert
    suspend fun insert(asset: BotMediaAssetEntity): Long

    @Query("SELECT * FROM bot_media_assets WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BotMediaAssetEntity?

    @Query("SELECT * FROM bot_media_assets ORDER BY createdAt DESC")
    suspend fun listAll(): List<BotMediaAssetEntity>

    @Query("UPDATE bot_media_assets SET rubikaFileId = :fileId WHERE id = :id")
    suspend fun updateRubikaFileId(id: Long, fileId: String): Int

    @Query("UPDATE bot_media_assets SET telegramFileId = :fileId WHERE id = :id")
    suspend fun updateTelegramFileId(id: Long, fileId: String): Int

    @Query("UPDATE bot_media_assets SET baleFileId = :fileId WHERE id = :id")
    suspend fun updateBaleFileId(id: Long, fileId: String): Int

    @Query("DELETE FROM bot_media_assets WHERE id = :id")
    suspend fun delete(id: Long): Int
}
