package com.payamesh.licenseadmin.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "license_seats",
    indices = [Index(value = ["msisdn"], unique = true)],
)
data class LicenseSeatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val msisdn: String,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revoked: Boolean = false,
    val previousMsisdn: String? = null,
)

@Entity(tableName = "license_check_logs")
data class LicenseCheckLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val senderMsisdn: String,
    val nonce: String,
    val result: String,
    val at: Long,
    val deviceId: String? = null,
    val simFingerprint: String? = null,
)
