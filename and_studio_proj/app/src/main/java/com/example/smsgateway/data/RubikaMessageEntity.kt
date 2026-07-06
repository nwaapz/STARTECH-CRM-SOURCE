package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rubika_messages",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["chatId", "timestamp"]),
    ],
)
data class RubikaMessageEntity(
    @PrimaryKey
    val messageId: String,
    val chatId: String,
    val senderId: String? = null,
    val text: String,
    val timestamp: Long,
    val direction: String,
    val phoneNumber: String? = null,
    val senderName: String? = null,
)
