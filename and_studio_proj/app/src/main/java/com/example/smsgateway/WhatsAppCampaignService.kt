package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.MassSmsCampaignStatus
import com.example.smsgateway.data.WhatsAppCampaignEntity
import com.example.smsgateway.data.WhatsAppRecipientEntity
import com.example.smsgateway.data.WhatsAppRecipientStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manages WhatsApp broadcast campaigns. Unlike [MassSmsQueueService] there is no in-app sender
 * loop: the phone only resolves the audience, stores recipients, and hands batches to the
 * external PC Playwright engine which reports results back.
 */
class WhatsAppCampaignService private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Batch(
        val campaign: WhatsAppCampaignEntity,
        val recipients: List<WhatsAppRecipientEntity>,
    )

    suspend fun createCampaign(
        message: String,
        filter: MassSmsFilterRequest,
    ): WhatsAppCampaignEntity {
        val resolver = MassSmsRecipientResolver.getInstance(appContext)
        val phones = resolver.resolvePhones(filter)
        val serialDao = database.serialDao()

        val recipients = mutableListOf<WhatsAppRecipientEntity>()
        val seen = HashSet<String>()
        for (phone in phones) {
            val intl = PhoneUtils.normalizeToInternational(phone) ?: continue
            if (!seen.add(intl)) continue
            val national = intl.substring(2)
            val rowId = serialDao.findFirstRowIdByNationalSuffix(national)
            recipients.add(
                WhatsAppRecipientEntity(
                    campaignId = 0,
                    phone = phone,
                    phoneIntl = intl,
                    contactName = rowId?.let { "client$it" },
                ),
            )
        }
        require(recipients.isNotEmpty()) { "هیچ گیرنده‌ی معتبری برای واتساپ یافت نشد" }

        val dao = database.whatsAppCampaignDao()
        val now = System.currentTimeMillis()
        val queueOrder = dao.nextQueueOrder()
        val hasActive = dao.countActiveCampaigns() > 0
        val initialStatus = if (hasActive) {
            MassSmsCampaignStatus.QUEUED
        } else {
            MassSmsCampaignStatus.RUNNING
        }

        val campaignId = dao.insertCampaign(
            WhatsAppCampaignEntity(
                message = message.trim(),
                audienceType = filter.audienceType,
                provinceId = filter.provinceId,
                cityName = filter.cityName,
                serialGroupIdsJson = encodeSerialGroupIds(filter.serialGroupIds),
                registrationFromDate = filter.registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
                registrationToDate = filter.registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
                status = initialStatus,
                totalCount = recipients.size,
                sentCount = 0,
                failedCount = 0,
                createdAt = now,
                startedAt = if (initialStatus == MassSmsCampaignStatus.RUNNING) now else null,
                completedAt = null,
                queueOrder = queueOrder,
            ),
        )

        dao.insertRecipients(recipients.map { it.copy(campaignId = campaignId) })

        return dao.findCampaignById(campaignId)
            ?: throw IllegalStateException("Campaign not found after insert")
    }

    /** Claims the next batch of pending recipients for the currently running campaign. */
    suspend fun nextBatch(limit: Int): Batch? {
        val dao = database.whatsAppCampaignDao()
        val campaign = dao.findFirstByStatus(MassSmsCampaignStatus.RUNNING) ?: return null
        val claimed = dao.claimPendingRecipients(campaign.id, limit.coerceIn(1, 50))
        if (claimed.isEmpty()) {
            maybeComplete(campaign.id)
        }
        return Batch(campaign, claimed)
    }

    /** Applies engine-reported results and completes/promotes campaigns as needed. */
    suspend fun reportResults(results: List<RecipientResult>) {
        val dao = database.whatsAppCampaignDao()
        val touchedCampaigns = HashSet<Long>()
        val now = System.currentTimeMillis()
        for (result in results) {
            val recipient = dao.findRecipientById(result.recipientId) ?: continue
            if (recipient.status == WhatsAppRecipientStatus.SENT ||
                recipient.status == WhatsAppRecipientStatus.FAILED
            ) {
                continue
            }
            if (result.success) {
                dao.updateRecipientStatus(recipient.id, WhatsAppRecipientStatus.SENT, now, null)
                dao.incrementSentCount(recipient.campaignId)
            } else {
                dao.updateRecipientStatus(
                    recipient.id,
                    WhatsAppRecipientStatus.FAILED,
                    now,
                    result.error?.takeIf { it.isNotBlank() } ?: "خطا در ارسال واتساپ",
                )
                dao.incrementFailedCount(recipient.campaignId)
            }
            touchedCampaigns.add(recipient.campaignId)
        }
        touchedCampaigns.forEach { maybeComplete(it) }
    }

    suspend fun pauseCampaign(id: Long): WhatsAppCampaignEntity? {
        val dao = database.whatsAppCampaignDao()
        val campaign = dao.findCampaignById(id) ?: return null
        if (campaign.status != MassSmsCampaignStatus.RUNNING) return campaign
        dao.updateStatus(id, MassSmsCampaignStatus.PAUSED, System.currentTimeMillis())
        return dao.findCampaignById(id)
    }

    suspend fun resumeCampaign(id: Long): WhatsAppCampaignEntity? {
        val dao = database.whatsAppCampaignDao()
        val campaign = dao.findCampaignById(id) ?: return null
        if (campaign.status != MassSmsCampaignStatus.PAUSED) return campaign
        // Only one campaign may run at a time; if another is active, re-queue instead.
        val hasOtherActive = dao.countActiveCampaigns() > 0 &&
            dao.findFirstByStatus(MassSmsCampaignStatus.RUNNING)?.id != id
        val target = if (hasOtherActive) MassSmsCampaignStatus.QUEUED else MassSmsCampaignStatus.RUNNING
        dao.updateStatus(id, target, System.currentTimeMillis())
        return dao.findCampaignById(id)
    }

    suspend fun deleteCampaign(id: Long): Boolean {
        val dao = database.whatsAppCampaignDao()
        val campaign = dao.findCampaignById(id) ?: return false
        val wasActive = campaign.status == MassSmsCampaignStatus.RUNNING ||
            campaign.status == MassSmsCampaignStatus.PAUSED
        dao.deleteCampaignWithRecipients(id)
        if (wasActive) {
            promoteNextQueued()
        }
        return true
    }

    fun recoverOnStartup() {
        scope.launch {
            val dao = database.whatsAppCampaignDao()
            val running = dao.findFirstByStatus(MassSmsCampaignStatus.RUNNING)
            if (running != null) {
                // Re-arm any recipients left mid-flight by a crashed engine.
                dao.requeueSendingRecipients(running.id)
                return@launch
            }
            promoteNextQueued()
        }
    }

    private suspend fun maybeComplete(campaignId: Long) {
        val dao = database.whatsAppCampaignDao()
        val campaign = dao.findCampaignById(campaignId) ?: return
        if (campaign.status != MassSmsCampaignStatus.RUNNING) return
        val pending = dao.countRecipientsByStatus(campaignId, WhatsAppRecipientStatus.PENDING)
        val sending = dao.countRecipientsByStatus(campaignId, WhatsAppRecipientStatus.SENDING)
        if (pending == 0 && sending == 0) {
            dao.updateStatus(campaignId, MassSmsCampaignStatus.COMPLETED, System.currentTimeMillis())
            promoteNextQueued()
        }
    }

    private suspend fun promoteNextQueued() {
        val dao = database.whatsAppCampaignDao()
        if (dao.countActiveCampaigns() > 0) return
        val next = dao.findFirstByStatus(MassSmsCampaignStatus.QUEUED) ?: return
        dao.updateStatus(next.id, MassSmsCampaignStatus.RUNNING, System.currentTimeMillis())
    }

    data class RecipientResult(
        val recipientId: Long,
        val success: Boolean,
        val error: String? = null,
    )

    companion object {
        @Volatile
        private var instance: WhatsAppCampaignService? = null

        private fun encodeSerialGroupIds(ids: List<Long>?): String? {
            if (ids.isNullOrEmpty()) return null
            return ids.joinToString(",")
        }

        fun getInstance(context: Context): WhatsAppCampaignService {
            return instance ?: synchronized(this) {
                instance ?: WhatsAppCampaignService(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
