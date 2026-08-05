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
        Index(value = ["status", "coalesceKey"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Idempotent UUID for remote dedupe. */
    val eventId: String,
    /** e.g. serial_upsert, serial_batch_upsert, group_upsert */
    val type: String,
    val payloadJson: String,
    val createdAt: Long,
    val status: String = STATUS_PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
    /**
     * Coalesce key so only one pending entry exists per serial/group
     * (e.g. `serial:ABC123`, `group:12:group_upsert`).
     */
    val coalesceKey: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SYNCED = "synced"
        const val STATUS_SENT = "sent" // legacy alias of synced
        const val STATUS_FAILED = "failed"
    }
}
