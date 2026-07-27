package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "whatsapp_campaigns",
    indices = [
        Index(value = ["status", "queueOrder"]),
        Index(value = ["createdAt"]),
    ],
)
data class WhatsAppCampaignEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val message: String,
    val audienceType: String,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIdsJson: String? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
    val status: String = MassSmsCampaignStatus.QUEUED,
    val totalCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val queueOrder: Long,
)

@Entity(
    tableName = "whatsapp_recipients",
    indices = [
        Index(value = ["campaignId", "status"]),
        Index(value = ["campaignId"]),
    ],
)
data class WhatsAppRecipientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val campaignId: Long,
    val phone: String,
    val phoneIntl: String,
    val contactName: String? = null,
    val status: String = MassSmsRecipientStatus.PENDING,
    val sentAt: Long? = null,
    val errorMessage: String? = null,
)
