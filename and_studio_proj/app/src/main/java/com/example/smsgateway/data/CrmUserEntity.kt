package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * CRM login users (LAN + synced to remote `users`).
 * Roles: 1 = reporter, 4 = manager, 5 = super-admin (legacy PHP compatible).
 */
@Entity(
    tableName = "crm_users",
    indices = [Index(value = ["username"], unique = true)],
)
data class CrmUserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    /** BCrypt or Android-compatible hash; never store plaintext for new users. */
    val passwordHash: String,
    val role: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val syncUpdatedMs: Long = 0,
)
