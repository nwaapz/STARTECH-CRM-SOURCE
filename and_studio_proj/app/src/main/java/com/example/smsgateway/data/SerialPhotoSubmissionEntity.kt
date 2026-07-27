package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object SerialPhotoSubmissionStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
}

object SerialPhotoChannel {
    const val RUBIKA = "rubika"
    const val TELEGRAM = "telegram"
    const val BALE = "bale"
}

/** Admin reject reasons stored in reviewNote (code) and shown to the user. */
object SerialPhotoRejectReason {
    const val UNCLEAR = "unclear"
    const val NOT_RELATED = "not_related"
    const val WRONG_SERIAL = "wrong_serial"
    const val OTHER = "other"

    fun normalize(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            UNCLEAR, "not_clear", "blurry" -> UNCLEAR
            NOT_RELATED, "unrelated" -> NOT_RELATED
            WRONG_SERIAL, "wrong_number", "wrong_serial_number" -> WRONG_SERIAL
            else -> OTHER
        }
    }
}

@Entity(
    tableName = "serial_photo_submissions",
    indices = [
        Index(value = ["status", "createdAt"]),
        Index(value = ["phone"]),
        Index(value = ["serial"]),
        Index(value = ["groupId"]),
    ],
)
data class SerialPhotoSubmissionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serial: String,
    val serialRowId: Long?,
    val groupId: Long?,
    val phone: String,
    val channel: String,
    val chatId: String,
    val status: String = SerialPhotoSubmissionStatus.PENDING,
    /** Absolute path on device storage. */
    val localPath: String,
    val mimeType: String? = null,
    val remoteFileId: String? = null,
    val scoreAwarded: Int = 0,
    val createdAt: Long,
    val reviewedAt: Long? = null,
    val reviewNote: String? = null,
)

object BotChatSessionState {
    const val AWAITING_SERIAL_PICK = "awaiting_serial_pick"
    const val AWAITING_PHOTO = "awaiting_photo"
}

@Entity(
    tableName = "bot_chat_sessions",
    indices = [
        Index(value = ["channel", "chatId"], unique = true),
    ],
)
data class BotChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val channel: String,
    val chatId: String,
    val phone: String,
    val state: String,
    val pendingSerial: String? = null,
    val updatedAt: Long,
)
