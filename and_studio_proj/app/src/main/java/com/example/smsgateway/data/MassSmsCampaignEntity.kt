package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object MassSmsAudienceType {
    const val REGISTERED = "registered"
    const val ERROR = "error"
    const val ALL = "all"
}

object MassSmsChannel {
    const val SMS = "sms"
    const val RUBIKA = "rubika"
    const val TELEGRAM = "telegram"
    const val BALE = "bale"

    val ALL = setOf(SMS, RUBIKA, TELEGRAM, BALE)

    fun isBot(channel: String): Boolean = channel == RUBIKA || channel == TELEGRAM || channel == BALE
}

object MassSmsCampaignStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
}

@Entity(
    tableName = "mass_sms_campaigns",
    indices = [
        Index(value = ["status", "queueOrder"]),
        Index(value = ["createdAt"]),
    ],
)
data class MassSmsCampaignEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val message: String,
    val delaySeconds: Int = 10,
    val audienceType: String,
    val channel: String = MassSmsChannel.SMS,
    val mediaAssetId: Long? = null,
    val provinceId: String? = null,
    val cityName: String? = null,
    val serialGroupIdsJson: String? = null,
    val registrationFromDate: String? = null,
    val registrationToDate: String? = null,
    val status: String = MassSmsCampaignStatus.QUEUED,
    val totalCount: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val queueOrder: Long,
)

object MassSmsRecipientStatus {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"
}

@Entity(
    tableName = "mass_sms_recipients",
    indices = [
        Index(value = ["campaignId", "status"]),
        Index(value = ["campaignId"]),
    ],
)
data class MassSmsRecipientEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val campaignId: Long,
    val phone: String,
    val status: String = MassSmsRecipientStatus.PENDING,
    val sentAt: Long? = null,
    val errorMessage: String? = null,
)
