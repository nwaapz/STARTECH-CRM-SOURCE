package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "crm_sessions",
    indices = [Index(value = ["token"], unique = true)],
)
data class CrmSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val token: String,
    val createdAt: Long,
    val expiresAt: Long,
)
