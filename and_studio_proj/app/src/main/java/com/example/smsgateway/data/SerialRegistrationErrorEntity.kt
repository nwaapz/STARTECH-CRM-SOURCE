package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "serial_registration_errors",
    indices = [
        Index(value = ["phone", "at"]),
        Index(value = ["at"]),
    ],
)
data class SerialRegistrationErrorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phone: String,
    val serial: String,
    val reason: String,
    val at: Long,
)
