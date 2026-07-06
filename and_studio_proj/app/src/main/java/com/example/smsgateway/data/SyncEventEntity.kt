package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_events",
    indices = [Index(value = ["at"])],
)
data class SyncEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val payload: String,
    val at: Long,
)
