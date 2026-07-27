package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["status", "createdAt"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Idempotent UUID for remote dedupe. */
    val eventId: String,
    /** e.g. serial_upsert, lottery_winner, user_upsert */
    val type: String,
    val payloadJson: String,
    val createdAt: Long,
    val status: String = STATUS_PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_FAILED = "failed"
    }
}
