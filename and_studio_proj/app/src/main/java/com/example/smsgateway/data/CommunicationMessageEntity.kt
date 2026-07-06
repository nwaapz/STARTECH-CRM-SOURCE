package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "communication_messages",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["phone", "timestamp"]),
    ],
)
data class CommunicationMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phone: String,
    val direction: String,
    val body: String,
    val timestamp: Long,
    val status: String? = null,
    val simLabel: String? = null,
)
