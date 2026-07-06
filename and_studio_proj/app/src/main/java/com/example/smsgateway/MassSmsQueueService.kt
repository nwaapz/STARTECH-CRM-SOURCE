package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.MassSmsCampaignEntity
import com.example.smsgateway.data.MassSmsCampaignStatus
import com.example.smsgateway.data.MassSmsRecipientEntity
import com.example.smsgateway.data.MassSmsRecipientStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MassSmsQueueService private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerMutex = Mutex()
    private var workerJob: Job? = null

    fun enqueue() {
        scope.launch {
            workerMutex.withLock {
                if (workerJob?.isActive == true) return@launch
                workerJob = scope.launch { processQueue() }
            }
        }
    }

    suspend fun createCampaign(
        message: String,
        delaySeconds: Int,
        filter: MassSmsFilterRequest,
    ): MassSmsCampaignEntity {
        val resolver = MassSmsRecipientResolver.getInstance(appContext)
        val phones = resolver.resolvePhones(filter)
        require(phones.isNotEmpty()) { "هیچ گیرنده‌ای با فیلتر انتخاب‌شده یافت نشد" }

        val dao = database.massSmsCampaignDao()
        val now = System.currentTimeMillis()
        val queueOrder = dao.nextQueueOrder()
        val hasActive = dao.countActiveCampaigns() > 0
        val initialStatus = if (hasActive) {
            MassSmsCampaignStatus.QUEUED
        } else {
            MassSmsCampaignStatus.RUNNING
        }

        val campaignId = dao.insertCampaign(
            MassSmsCampaignEntity(
                message = message.trim(),
                delaySeconds = delaySeconds.coerceAtLeast(1),
                audienceType = filter.audienceType,
                provinceId = filter.provinceId,
                cityName = filter.cityName,
                serialGroupIdsJson = encodeSerialGroupIds(filter.serialGroupIds),
                registrationFromDate = filter.registrationFromDate?.trim()?.takeIf { it.isNotEmpty() },
                registrationToDate = filter.registrationToDate?.trim()?.takeIf { it.isNotEmpty() },
                status = initialStatus,
                totalCount = phones.size,
                sentCount = 0,
                failedCount = 0,
                createdAt = now,
                startedAt = if (initialStatus == MassSmsCampaignStatus.RUNNING) now else null,
                completedAt = null,
                queueOrder = queueOrder,
            ),
        )

        dao.insertRecipients(
            phones.map { phone ->
                MassSmsRecipientEntity(
                    campaignId = campaignId,
                    phone = phone,
                    status = MassSmsRecipientStatus.PENDING,
                )
            },
        )

        val campaign = dao.findCampaignById(campaignId)
            ?: throw IllegalStateException("Campaign not found after insert")

        if (initialStatus == MassSmsCampaignStatus.RUNNING) {
            enqueue()
        }

        return campaign
    }

    suspend fun pauseCampaign(id: Long): MassSmsCampaignEntity? {
        val dao = database.massSmsCampaignDao()
        val campaign = dao.findCampaignById(id) ?: return null
        if (campaign.status != MassSmsCampaignStatus.RUNNING) return campaign
        dao.updateStatus(id, MassSmsCampaignStatus.PAUSED, System.currentTimeMillis())
        return dao.findCampaignById(id)
    }

    suspend fun resumeCampaign(id: Long): MassSmsCampaignEntity? {
        val dao = database.massSmsCampaignDao()
        val campaign = dao.findCampaignById(id) ?: return null
        if (campaign.status != MassSmsCampaignStatus.PAUSED) return campaign
        dao.updateStatus(id, MassSmsCampaignStatus.RUNNING, System.currentTimeMillis())
        enqueue()
        return dao.findCampaignById(id)
    }

    suspend fun deleteCampaign(id: Long): Boolean {
        val dao = database.massSmsCampaignDao()
        val campaign = dao.findCampaignById(id) ?: return false
        val wasActive = campaign.status == MassSmsCampaignStatus.RUNNING ||
            campaign.status == MassSmsCampaignStatus.PAUSED
        dao.deleteCampaignWithRecipients(id)
        if (wasActive) {
            promoteNextQueued()
            enqueue()
        }
        return true
    }

    fun recoverOnStartup() {
        scope.launch {
            val dao = database.massSmsCampaignDao()
            val running = dao.findFirstByStatus(MassSmsCampaignStatus.RUNNING)
            if (running != null) {
                enqueue()
                return@launch
            }
            promoteNextQueued()
            enqueue()
        }
    }

    private suspend fun promoteNextQueued() {
        val dao = database.massSmsCampaignDao()
        if (dao.countActiveCampaigns() > 0) return
        val next = dao.findFirstByStatus(MassSmsCampaignStatus.QUEUED) ?: return
        dao.updateStatus(next.id, MassSmsCampaignStatus.RUNNING, System.currentTimeMillis())
    }

    private suspend fun processQueue() {
        while (true) {
            val dao = database.massSmsCampaignDao()
            val campaign = dao.findFirstByStatus(MassSmsCampaignStatus.RUNNING) ?: break
            val finished = processCampaign(campaign)
            if (!finished) break

            dao.updateStatus(campaign.id, MassSmsCampaignStatus.COMPLETED, System.currentTimeMillis())
            promoteNextQueued()
        }
    }

    private suspend fun processCampaign(campaign: MassSmsCampaignEntity): Boolean {
        val dao = database.massSmsCampaignDao()
        val commLog = CommunicationLog.getInstance(appContext)

        while (true) {
            val current = dao.findCampaignById(campaign.id) ?: return true
            if (current.status == MassSmsCampaignStatus.PAUSED) return false
            if (current.status != MassSmsCampaignStatus.RUNNING) return true

            val recipient = dao.findNextPendingRecipient(campaign.id) ?: return true

            try {
                val subscriptionId = SmsOutbound.resolveSubscriptionId(appContext, null)
                val simLabel = SimCardHelper.simLabelForSubscription(appContext, subscriptionId)
                SmsOutbound.send(appContext, subscriptionId, recipient.phone, current.message)
                commLog.logOutgoing(
                    phone = recipient.phone,
                    body = current.message,
                    status = CommunicationLog.STATUS_SENT,
                    simLabel = simLabel,
                )
                dao.updateRecipientStatus(
                    recipient.id,
                    MassSmsRecipientStatus.SENT,
                    System.currentTimeMillis(),
                    null,
                )
                dao.incrementSentCount(campaign.id)
            } catch (error: Exception) {
                dao.updateRecipientStatus(
                    recipient.id,
                    MassSmsRecipientStatus.FAILED,
                    System.currentTimeMillis(),
                    error.message ?: "خطا در ارسال",
                )
                dao.incrementFailedCount(campaign.id)
            }

            delay(current.delaySeconds * 1000L)

            val afterDelay = dao.findCampaignById(campaign.id)
            if (afterDelay == null || afterDelay.status == MassSmsCampaignStatus.PAUSED) {
                return false
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MassSmsQueueService? = null

        private fun encodeSerialGroupIds(ids: List<Long>?): String? {
            if (ids.isNullOrEmpty()) return null
            return ids.joinToString(",")
        }

        fun getInstance(context: Context): MassSmsQueueService {
            return instance ?: synchronized(this) {
                instance ?: MassSmsQueueService(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }
    }
}
