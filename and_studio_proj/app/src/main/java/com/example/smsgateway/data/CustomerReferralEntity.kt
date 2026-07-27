package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per customer (keyed by normalized phone). Stores the customer's own
 * unique [referralCode] and the DIRECT inviter ([referredBy]). The whole
 * multi-level referral tree is reconstructed by walking [referredBy] pointers.
 */
@Entity(
    tableName = "customer_referrals",
    indices = [
        Index(value = ["referralCode"], unique = true),
        Index(value = ["referredBy"]),
    ],
)
data class CustomerReferralEntity(
    @PrimaryKey
    val phone: String,
    val referralCode: String,
    /** DIRECT inviter's phone. Null when the customer joined without a referral. */
    val referredBy: String? = null,
    /** Raw code that was used to attach [referredBy] (audit only). */
    val referredByCode: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
