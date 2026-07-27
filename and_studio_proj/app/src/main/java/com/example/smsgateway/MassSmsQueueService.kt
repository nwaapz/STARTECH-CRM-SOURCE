package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.BotMediaAssetEntity
import com.example.smsgateway.data.BotMediaKind
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.MassSmsCampaignEntity
import com.example.smsgateway.data.MassSmsCampaignStatus
import com.example.smsgateway.data.MassSmsChannel
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
import java.io.File

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
        channel: String = MassSmsChannel.SMS,
        mediaAssetId: Long? = null,
    ): MassSmsCampaignEntity {
        val resolvedChannel = channel.takeIf { it in MassSmsChannel.ALL } ?: MassSmsChannel.SMS
        if (mediaAssetId != null) {
            require(database.botMediaAssetDao().findById(mediaAssetId) != null) {
                "فایل پیوست یافت نشد"
            }
        }

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
                channel = resolvedChannel,
                mediaAssetId = if (resolvedChannel == MassSmsChannel.SMS) null else mediaAssetId,
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
        if (!com.example.smsgateway.license.LicenseGate.allowsOperationalUse(appContext)) {
            return
        }
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

        // Loaded once and refreshed in-memory as file_ids get cached after the first upload.
        var mediaAsset: BotMediaAssetEntity? = campaign.mediaAssetId?.let {
            database.botMediaAssetDao().findById(it)
        }

        while (true) {
            val current = dao.findCampaignById(campaign.id) ?: return true
            if (current.status == MassSmsCampaignStatus.PAUSED) return false
            if (current.status != MassSmsCampaignStatus.RUNNING) return true

            val recipient = dao.findNextPendingRecipient(campaign.id) ?: return true

            val outcome = try {
                if (current.channel == MassSmsChannel.SMS) {
                    sendSms(recipient.phone, current.message)
                } else {
                    val (result, refreshedAsset) = sendBot(current, recipient.phone, mediaAsset)
                    mediaAsset = refreshedAsset
                    result
                }
            } catch (error: Exception) {
                SendResult.Failed(error.message ?: "خطا در ارسال")
            }

            when (outcome) {
                is SendResult.Sent -> {
                    dao.updateRecipientStatus(
                        recipient.id,
                        MassSmsRecipientStatus.SENT,
                        System.currentTimeMillis(),
                        null,
                    )
                    dao.incrementSentCount(campaign.id)
                }
                is SendResult.Skipped -> {
                    dao.updateRecipientStatus(
                        recipient.id,
                        MassSmsRecipientStatus.SKIPPED,
                        System.currentTimeMillis(),
                        outcome.reason,
                    )
                    dao.incrementSkippedCount(campaign.id)
                }
                is SendResult.Failed -> {
                    dao.updateRecipientStatus(
                        recipient.id,
                        MassSmsRecipientStatus.FAILED,
                        System.currentTimeMillis(),
                        outcome.reason,
                    )
                    dao.incrementFailedCount(campaign.id)
                }
            }

            delay(current.delaySeconds * 1000L)

            val afterDelay = dao.findCampaignById(campaign.id)
            if (afterDelay == null || afterDelay.status == MassSmsCampaignStatus.PAUSED) {
                return false
            }
        }
    }

    private sealed class SendResult {
        object Sent : SendResult()
        data class Skipped(val reason: String) : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    private fun sendSms(phone: String, message: String): SendResult {
        val commLog = CommunicationLog.getInstance(appContext)
        return try {
            val subscriptionId = SmsOutbound.resolveSubscriptionIdOrThrow(appContext, null)
            val simLabel = SimCardHelper.simLabelForSubscription(appContext, subscriptionId)
            SmsOutbound.send(appContext, subscriptionId, phone, message)
            commLog.logOutgoing(
                phone = phone,
                body = message,
                status = CommunicationLog.STATUS_SENT,
                simLabel = simLabel,
            )
            SendResult.Sent
        } catch (error: SmsOutboundQuotaExceededException) {
            SendResult.Skipped("سهمیه روزانه پیامک تمام شده")
        }
    }

    /**
     * Sends a bot message for a single recipient. Returns the outcome plus the possibly-updated
     * media asset (with a freshly cached file_id) so subsequent recipients avoid re-uploading.
     */
    private suspend fun sendBot(
        campaign: MassSmsCampaignEntity,
        phone: String,
        asset: BotMediaAssetEntity?,
    ): Pair<SendResult, BotMediaAssetEntity?> {
        val settings = SettingsStore.getInstance(appContext)
        val channel = campaign.channel

        val token = when (channel) {
            MassSmsChannel.RUBIKA -> settings.getRubikaToken()
            MassSmsChannel.TELEGRAM -> settings.getTelegramToken()
            MassSmsChannel.BALE -> settings.getBaleToken()
            else -> ""
        }
        if (token.isBlank()) {
            return SendResult.Skipped("ربات فعال نیست") to asset
        }

        val chatId = when (channel) {
            MassSmsChannel.RUBIKA -> BotChatIdResolver.resolveRubika(database, phone)
            MassSmsChannel.TELEGRAM -> BotChatIdResolver.resolveTelegram(database, phone)
            MassSmsChannel.BALE -> BotChatIdResolver.resolveBale(database, phone)
            else -> null
        } ?: return SendResult.Skipped("کاربر این ربات را فعال نکرده است") to asset

        if (asset == null) {
            when (channel) {
                MassSmsChannel.RUBIKA -> RubikaApi.getInstance().sendMessage(token, chatId, campaign.message)
                MassSmsChannel.TELEGRAM -> TelegramApi.getInstance().sendMessage(token, chatId, campaign.message)
                MassSmsChannel.BALE -> BaleApi.getInstance().sendMessage(token, chatId, campaign.message)
            }
            return SendResult.Sent to asset
        }

        val updatedAsset = sendBotMedia(channel, token, chatId, campaign.message, asset)
        return SendResult.Sent to updatedAsset
    }

    private suspend fun sendBotMedia(
        channel: String,
        token: String,
        chatId: String,
        caption: String,
        asset: BotMediaAssetEntity,
    ): BotMediaAssetEntity {
        val isImage = asset.kind == BotMediaKind.PHOTO
        val mediaDao = database.botMediaAssetDao()

        return when (channel) {
            MassSmsChannel.RUBIKA -> {
                val cached = asset.rubikaFileId
                if (!cached.isNullOrBlank()) {
                    RubikaApi.getInstance().sendFileByFileId(token, chatId, caption, cached)
                    asset
                } else {
                    val bytes = readAssetBytes(asset)
                    val result = RubikaApi.getInstance().uploadAndSendFile(
                        token, chatId, caption, bytes, asset.fileName, asset.mimeType, isImage,
                    )
                    val fileId = result.fileId
                    if (!fileId.isNullOrBlank()) {
                        mediaDao.updateRubikaFileId(asset.id, fileId)
                        asset.copy(rubikaFileId = fileId)
                    } else {
                        asset
                    }
                }
            }
            MassSmsChannel.TELEGRAM -> {
                val cached = asset.telegramFileId
                val api = TelegramApi.getInstance()
                if (!cached.isNullOrBlank()) {
                    if (isImage) api.sendPhotoByFileId(token, chatId, caption, cached)
                    else api.sendDocumentByFileId(token, chatId, caption, cached)
                    asset
                } else {
                    val bytes = readAssetBytes(asset)
                    val result = if (isImage) {
                        api.uploadPhoto(token, chatId, caption, bytes, asset.fileName, asset.mimeType)
                    } else {
                        api.uploadDocument(token, chatId, caption, bytes, asset.fileName, asset.mimeType)
                    }
                    val fileId = result.fileId
                    if (!fileId.isNullOrBlank()) {
                        mediaDao.updateTelegramFileId(asset.id, fileId)
                        asset.copy(telegramFileId = fileId)
                    } else {
                        asset
                    }
                }
            }
            MassSmsChannel.BALE -> {
                val cached = asset.baleFileId
                val api = BaleApi.getInstance()
                if (!cached.isNullOrBlank()) {
                    if (isImage) api.sendPhotoByFileId(token, chatId, caption, cached)
                    else api.sendDocumentByFileId(token, chatId, caption, cached)
                    asset
                } else {
                    val bytes = readAssetBytes(asset)
                    val result = if (isImage) {
                        api.uploadPhoto(token, chatId, caption, bytes, asset.fileName, asset.mimeType)
                    } else {
                        api.uploadDocument(token, chatId, caption, bytes, asset.fileName, asset.mimeType)
                    }
                    val fileId = result.fileId
                    if (!fileId.isNullOrBlank()) {
                        mediaDao.updateBaleFileId(asset.id, fileId)
                        asset.copy(baleFileId = fileId)
                    } else {
                        asset
                    }
                }
            }
            else -> asset
        }
    }

    private fun readAssetBytes(asset: BotMediaAssetEntity): ByteArray {
        val file = File(asset.localPath)
        if (!file.exists()) {
            throw IllegalStateException("فایل پیوست روی دستگاه یافت نشد")
        }
        return file.readBytes()
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
