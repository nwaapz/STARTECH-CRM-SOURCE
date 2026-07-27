package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object BotMediaKind {
    const val PHOTO = "photo"
    const val DOCUMENT = "document"
}

@Entity(tableName = "bot_media_assets")
data class BotMediaAssetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val mimeType: String,
    val kind: String,
    val localPath: String,
    val sizeBytes: Long,
    val rubikaFileId: String? = null,
    val telegramFileId: String? = null,
    val baleFileId: String? = null,
    val createdAt: Long,
)
