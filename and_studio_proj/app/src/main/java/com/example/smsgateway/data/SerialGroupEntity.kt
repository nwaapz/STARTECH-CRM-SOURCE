package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "serial_groups")
data class SerialGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val prefix: String,
    val startNumber: Int,
    val endNumber: Int,
    val padWidth: Int,
    val description: String? = null,
    val score: Int = 1,
    val createdAt: Long,
)
