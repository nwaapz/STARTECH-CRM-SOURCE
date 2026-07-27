package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_sessions")
data class SmsSessionEntity(
    @PrimaryKey
    val phone: String,
    val serial: String,
    val nextField: String?,
    val collectedCity: String? = null,
    val collectedKm: Int? = null,
    val updatedAt: Long,
    /** SIM used for this conversation so continuation replies stay on one number. */
    val subscriptionId: Int? = null,
)
