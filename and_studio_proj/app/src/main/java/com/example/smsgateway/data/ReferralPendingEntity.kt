package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Holds a referral code captured from a bot deep-link (e.g. "/start ref_XXXX")
 * before the customer's phone is known. Resolved and cleared once the phone is
 * captured, then used to set the referral edge.
 */
@Entity(
    tableName = "referral_pending",
    indices = [
        Index(value = ["channel", "chatId"], unique = true),
    ],
)
data class ReferralPendingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val channel: String,
    val chatId: String,
    val code: String,
    val createdAt: Long,
)
