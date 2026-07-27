package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lottery_suspended_phones")
data class LotterySuspendedPhoneEntity(
    @PrimaryKey
    val phone: String,
    val reason: String? = null,
    val createdAt: Long,
)
