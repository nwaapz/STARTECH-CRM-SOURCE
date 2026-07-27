package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bale_chats",
    indices = [
        Index(value = ["phoneNumber"]),
    ],
)
data class BaleChatContactEntity(
    @PrimaryKey
    val chatId: String,
    val phoneNumber: String? = null,
    val senderName: String? = null,
    val updatedAt: Long,
    val lastPhoneRequestAt: Long? = null,
)
