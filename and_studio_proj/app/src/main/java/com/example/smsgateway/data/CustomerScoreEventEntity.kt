package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object CustomerScoreReason {
    const val REFERRAL = "referral"
    const val PURCHASE = "purchase"
    const val MANUAL = "manual"
}

object CustomerScoreSourceType {
    const val SERIAL = "serial"
    const val PHOTO = "photo"
    const val MANUAL = "manual"
}

/**
 * Append-only points ledger. A customer's balance is SUM(points) for their phone.
 * The unique index guarantees a single purchase pays each upline slot at most once,
 * making award retries idempotent.
 */
@Entity(
    tableName = "customer_score_events",
    indices = [
        Index(value = ["phone"]),
        Index(value = ["phone", "sourceType", "sourceId", "level"], unique = true),
    ],
)
data class CustomerScoreEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Recipient of the points. */
    val phone: String,
    val points: Int,
    /** referral | purchase | manual */
    val reason: String,
    /** 0 = own purchase, 1..N = distance up the referral chain. */
    val level: Int,
    /** The buyer whose purchase triggered this award (null for manual). */
    val sourcePhone: String? = null,
    /** serial | photo | manual */
    val sourceType: String,
    val sourceId: Long,
    val createdAt: Long,
)
