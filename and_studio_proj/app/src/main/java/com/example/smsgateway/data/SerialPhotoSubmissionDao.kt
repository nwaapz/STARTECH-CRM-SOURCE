package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SerialPhotoSubmissionDao {
    @Insert
    suspend fun insert(row: SerialPhotoSubmissionEntity): Long

    @Update
    suspend fun update(row: SerialPhotoSubmissionEntity)

    @Query("SELECT * FROM serial_photo_submissions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SerialPhotoSubmissionEntity?

    @Query(
        """
        SELECT COUNT(*) FROM serial_photo_submissions
        WHERE serial = :serial
          AND phone = :phone
          AND status IN ('pending', 'approved')
        """,
    )
    suspend fun countActiveForSerialPhone(serial: String, phone: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM serial_photo_submissions
        WHERE (:status = '' OR status = :status)
        """,
    )
    suspend fun countFiltered(status: String = ""): Int

    @Query(
        """
        SELECT * FROM serial_photo_submissions
        WHERE (:status = '' OR status = :status)
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listFiltered(
        status: String = "",
        limit: Int,
        offset: Int,
    ): List<SerialPhotoSubmissionEntity>

    @Query(
        """
        UPDATE serial_photo_submissions
        SET status = :status,
            reviewedAt = :reviewedAt,
            reviewNote = :reviewNote,
            scoreAwarded = :scoreAwarded
        WHERE id = :id
        """,
    )
    suspend fun updateReview(
        id: Long,
        status: String,
        reviewedAt: Long,
        reviewNote: String?,
        scoreAwarded: Int,
    ): Int

    @Query(
        """
        UPDATE serial_photo_submissions
        SET serial = :newSerial
        WHERE UPPER(serial) = UPPER(:oldSerial)
          AND phone = :phone
        """,
    )
    suspend fun updateSerialForPhone(
        oldSerial: String,
        newSerial: String,
        phone: String,
    ): Int

    @Query(
        """
        SELECT * FROM serial_photo_submissions
        WHERE phone = :phone
          AND status = 'approved'
          AND scoreAwarded > 0
        ORDER BY id ASC
        """,
    )
    suspend fun listApprovedWithScore(phone: String): List<SerialPhotoSubmissionEntity>
}

@Dao
interface BotChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BotChatSessionEntity): Long

    @Update
    suspend fun update(session: BotChatSessionEntity)

    @Query(
        """
        SELECT * FROM bot_chat_sessions
        WHERE channel = :channel AND chatId = :chatId
        LIMIT 1
        """,
    )
    suspend fun find(channel: String, chatId: String): BotChatSessionEntity?

    @Query("DELETE FROM bot_chat_sessions WHERE channel = :channel AND chatId = :chatId")
    suspend fun clear(channel: String, chatId: String)
}
