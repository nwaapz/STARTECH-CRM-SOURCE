package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

data class RegisteredPhoneCityRow(
    val phone: String,
    val city: String?,
)

@Dao
interface MassSmsCampaignDao {
    @Insert
    suspend fun insertCampaign(campaign: MassSmsCampaignEntity): Long

    @Insert
    suspend fun insertRecipients(recipients: List<MassSmsRecipientEntity>): List<Long>

    @Query("SELECT * FROM mass_sms_campaigns WHERE id = :id LIMIT 1")
    suspend fun findCampaignById(id: Long): MassSmsCampaignEntity?

    @Query("SELECT * FROM mass_sms_campaigns ORDER BY createdAt DESC")
    suspend fun listCampaigns(): List<MassSmsCampaignEntity>

    @Query(
        """
        SELECT * FROM mass_sms_campaigns
        WHERE status = :status
        ORDER BY queueOrder ASC
        LIMIT 1
        """,
    )
    suspend fun findFirstByStatus(status: String): MassSmsCampaignEntity?

    @Query(
        """
        SELECT COUNT(*) FROM mass_sms_campaigns
        WHERE status IN ('running', 'paused')
        """,
    )
    suspend fun countActiveCampaigns(): Int

    @Query(
        """
        SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM mass_sms_campaigns
        """,
    )
    suspend fun nextQueueOrder(): Long

    @Query(
        """
        UPDATE mass_sms_campaigns
        SET status = :status,
            startedAt = CASE WHEN :status = 'running' AND startedAt IS NULL THEN :now ELSE startedAt END,
            completedAt = CASE WHEN :status IN ('completed', 'cancelled') THEN :now ELSE completedAt END
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(id: Long, status: String, now: Long): Int

    @Query(
        """
        UPDATE mass_sms_campaigns
        SET sentCount = sentCount + 1
        WHERE id = :id
        """,
    )
    suspend fun incrementSentCount(id: Long): Int

    @Query(
        """
        UPDATE mass_sms_campaigns
        SET failedCount = failedCount + 1
        WHERE id = :id
        """,
    )
    suspend fun incrementFailedCount(id: Long): Int

    @Query("DELETE FROM mass_sms_campaigns WHERE id = :id")
    suspend fun deleteCampaign(id: Long): Int

    @Query("DELETE FROM mass_sms_recipients WHERE campaignId = :campaignId")
    suspend fun deleteRecipientsByCampaignId(campaignId: Long): Int

    @Query(
        """
        SELECT * FROM mass_sms_recipients
        WHERE campaignId = :campaignId AND status = :status
        ORDER BY id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listRecipientsByStatus(
        campaignId: Long,
        status: String,
        limit: Int,
        offset: Int,
    ): List<MassSmsRecipientEntity>

    @Query(
        """
        SELECT * FROM mass_sms_recipients
        WHERE campaignId = :campaignId
        ORDER BY id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listRecipients(campaignId: Long, limit: Int, offset: Int): List<MassSmsRecipientEntity>

    @Query(
        """
        SELECT COUNT(*) FROM mass_sms_recipients
        WHERE campaignId = :campaignId AND status = :status
        """,
    )
    suspend fun countRecipientsByStatus(campaignId: Long, status: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM mass_sms_recipients WHERE campaignId = :campaignId
        """,
    )
    suspend fun countRecipients(campaignId: Long): Int

    @Query(
        """
        SELECT * FROM mass_sms_recipients
        WHERE campaignId = :campaignId AND status = 'pending'
        ORDER BY id ASC
        LIMIT 1
        """,
    )
    suspend fun findNextPendingRecipient(campaignId: Long): MassSmsRecipientEntity?

    @Query(
        """
        UPDATE mass_sms_recipients
        SET status = :status, sentAt = :sentAt, errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun updateRecipientStatus(
        id: Long,
        status: String,
        sentAt: Long?,
        errorMessage: String?,
    ): Int

    @Transaction
    suspend fun deleteCampaignWithRecipients(campaignId: Long) {
        deleteRecipientsByCampaignId(campaignId)
        deleteCampaign(campaignId)
    }
}
