package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_send_quota_events",
    indices = [
        Index(value = ["subscriptionKey", "sentAt"]),
        Index(value = ["sentAt"]),
    ],
)
data class SmsSendQuotaEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val subscriptionKey: String,
    val sentAt: Long,
)
