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
    /** end_user_client | seller_to_end_user */
    val category: String = "end_user_client",
    /** range | complex | import */
    val generationMode: String = "range",
    /** Warranty duration in months after registration (optional if warrantyKm set). */
    val warrantyMonths: Int? = null,
    /** Warranty mileage limit in km after registration (optional if warrantyMonths set). */
    val warrantyKm: Int? = null,
    /**
     * When true, club score for serials in this group is awarded only after
     * admin approves a customer-submitted product photo via Rubika/Telegram.
     */
    val imageVerificationRequired: Boolean = false,
    val createdAt: Long,
    /** Last-write-wins timestamp for cloud hybrid sync (epoch ms). */
    val syncUpdatedMs: Long = 0,
    /** Remote MySQL `serial_groups.id` when linked via cloud sync; null if never pulled. */
    val syncOriginGroupId: Long? = null,
)
