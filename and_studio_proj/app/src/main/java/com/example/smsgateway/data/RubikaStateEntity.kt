package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rubika_state")
data class RubikaStateEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val nextOffsetId: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
