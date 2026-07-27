package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

object WhatsAppRecipientStatus {
    const val PENDING = "pending"
    const val SENDING = "sending"
    const val SENT = "sent"
    const val FAILED = "failed"
}

@Dao
interface WhatsAppCampaignDao {
    @Insert
    suspend fun insertCampaign(campaign: WhatsAppCampaignEntity): Long

    @Insert
    suspend fun insertRecipients(recipients: List<WhatsAppRecipientEntity>): List<Long>

    @Query("SELECT * FROM whatsapp_campaigns WHERE id = :id LIMIT 1")
    suspend fun findCampaignById(id: Long): WhatsAppCampaignEntity?

    @Query("SELECT * FROM whatsapp_campaigns ORDER BY createdAt DESC")
    suspend fun listCampaigns(): List<WhatsAppCampaignEntity>

    @Query(
        """
        SELECT * FROM whatsapp_campaigns
        WHERE status = :status
        ORDER BY queueOrder ASC
        LIMIT 1
        """,
    )
    suspend fun findFirstByStatus(status: String): WhatsAppCampaignEntity?

    @Query(
        """
        SELECT COUNT(*) FROM whatsapp_campaigns
        WHERE status IN ('running', 'paused')
        """,
    )
    suspend fun countActiveCampaigns(): Int

    @Query(
        """
        SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM whatsapp_campaigns
        """,
    )
    suspend fun nextQueueOrder(): Long

    @Query(
        """
        UPDATE whatsapp_campaigns
        SET status = :status,
            startedAt = CASE WHEN :status = 'running' AND startedAt IS NULL THEN :now ELSE startedAt END,
            completedAt = CASE WHEN :status IN ('completed', 'cancelled') THEN :now ELSE completedAt END
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(id: Long, status: String, now: Long): Int

    @Query(
        """
        UPDATE whatsapp_campaigns
        SET sentCount = sentCount + 1
        WHERE id = :id
        """,
    )
    suspend fun incrementSentCount(id: Long): Int

    @Query(
        """
        UPDATE whatsapp_campaigns
        SET failedCount = failedCount + 1
        WHERE id = :id
        """,
    )
    suspend fun incrementFailedCount(id: Long): Int

    @Query("DELETE FROM whatsapp_campaigns WHERE id = :id")
    suspend fun deleteCampaign(id: Long): Int

    @Query("DELETE FROM whatsapp_recipients WHERE campaignId = :campaignId")
    suspend fun deleteRecipientsByCampaignId(campaignId: Long): Int

    @Query(
        """
        SELECT * FROM whatsapp_recipients
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
    ): List<WhatsAppRecipientEntity>

    @Query(
        """
        SELECT * FROM whatsapp_recipients
        WHERE campaignId = :campaignId
        ORDER BY id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listRecipients(campaignId: Long, limit: Int, offset: Int): List<WhatsAppRecipientEntity>

    @Query(
        """
        SELECT COUNT(*) FROM whatsapp_recipients
        WHERE campaignId = :campaignId AND status = :status
        """,
    )
    suspend fun countRecipientsByStatus(campaignId: Long, status: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM whatsapp_recipients WHERE campaignId = :campaignId
        """,
    )
    suspend fun countRecipients(campaignId: Long): Int

    @Query("SELECT * FROM whatsapp_recipients WHERE id = :id LIMIT 1")
    suspend fun findRecipientById(id: Long): WhatsAppRecipientEntity?

    @Query(
        """
        SELECT * FROM whatsapp_recipients
        WHERE campaignId = :campaignId AND status = 'pending'
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun listPendingRecipients(campaignId: Long, limit: Int): List<WhatsAppRecipientEntity>

    @Query(
        """
        UPDATE whatsapp_recipients
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

    /**
     * Re-arm any recipients left in the transient 'sending' state (e.g. after the
     * engine crashed mid-batch) so they get picked up again.
     */
    @Query(
        """
        UPDATE whatsapp_recipients
        SET status = 'pending'
        WHERE campaignId = :campaignId AND status = 'sending'
        """,
    )
    suspend fun requeueSendingRecipients(campaignId: Long): Int

    /**
     * Atomically claims up to [limit] pending recipients for the running campaign,
     * marking them 'sending' and returning them so two concurrent engine polls
     * never grab the same numbers.
     */
    @Transaction
    suspend fun claimPendingRecipients(campaignId: Long, limit: Int): List<WhatsAppRecipientEntity> {
        val pending = listPendingRecipients(campaignId, limit)
        for (recipient in pending) {
            updateRecipientStatus(recipient.id, WhatsAppRecipientStatus.SENDING, null, null)
        }
        return pending
    }

    @Transaction
    suspend fun deleteCampaignWithRecipients(campaignId: Long) {
        deleteRecipientsByCampaignId(campaignId)
        deleteCampaign(campaignId)
    }
}
