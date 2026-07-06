package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telegram_state")
data class TelegramStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val nextOffsetId: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
