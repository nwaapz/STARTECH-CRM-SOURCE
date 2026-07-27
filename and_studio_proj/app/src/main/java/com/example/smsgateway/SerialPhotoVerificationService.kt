package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.BotChatSessionEntity
import com.example.smsgateway.data.BotChatSessionState
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialPhotoChannel
import com.example.smsgateway.data.SerialPhotoRejectReason
import com.example.smsgateway.data.SerialPhotoSubmissionEntity
import com.example.smsgateway.data.SerialPhotoSubmissionStatus
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bot + admin flow for product photo verification before awarding group score.
 */
class SerialPhotoVerificationService private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
) {
    data class StartResult(
        val reply: String,
        val serialButtons: List<String> = emptyList(),
    )

    data class PhotoSaveResult(
        val reply: String,
        val submissionId: Long? = null,
    )

    suspend fun listNeedingVerification(phone: String): List<SerialEntity> = withContext(Dispatchers.IO) {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        database.serialDao().listNeedingImageVerificationByPhone(normalized)
    }

    suspend fun startFlow(
        channel: String,
        chatId: String,
        phone: String,
    ): StartResult = withContext(Dispatchers.IO) {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        val candidates = database.serialDao().listNeedingImageVerificationByPhone(normalized)
        if (candidates.isEmpty()) {
            clearSession(channel, chatId)
            return@withContext StartResult(
                reply = appContext.getString(R.string.bot_photo_verify_none),
            )
        }
        if (candidates.size == 1) {
            val serial = candidates.first().serial
            setAwaitingPhoto(channel, chatId, normalized, serial)
            return@withContext StartResult(
                reply = appContext.getString(R.string.bot_photo_verify_send_photo, serial),
            )
        }
        setAwaitingPick(channel, chatId, normalized)
        val list = candidates.mapIndexed { index, row -> "${index + 1}) ${row.serial}" }
            .joinToString("\n")
        StartResult(
            reply = appContext.getString(R.string.bot_photo_verify_pick_serial) + "\n\n" + list,
            serialButtons = candidates.map { it.serial },
        )
    }

    /** After registration: jump straight to awaiting photo for that serial when eligible. */
    suspend fun startFlowForSerial(
        channel: String,
        chatId: String,
        phone: String,
        serial: String,
    ): StartResult = withContext(Dispatchers.IO) {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        val normalizedSerial = SerialNormalizer.normalizeSerial(serial)
        val candidates = database.serialDao().listNeedingImageVerificationByPhone(normalized)
        val match = candidates.firstOrNull {
            it.serial.equals(normalizedSerial, ignoreCase = true)
        }
        if (match == null) {
            return@withContext startFlow(channel, chatId, phone)
        }
        setAwaitingPhoto(channel, chatId, normalized, match.serial)
        StartResult(
            reply = appContext.getString(R.string.bot_photo_verify_send_photo, match.serial),
        )
    }

    suspend fun handleTextWhileInSession(
        channel: String,
        chatId: String,
        phone: String,
        text: String,
    ): String? = withContext(Dispatchers.IO) {
        val session = database.botChatSessionDao().find(channel, chatId) ?: return@withContext null
        val trimmed = text.trim()
        if (trimmed.equals(CANCEL_LABEL, ignoreCase = false) ||
            trimmed == appContext.getString(R.string.bot_photo_verify_cancel)
        ) {
            clearSession(channel, chatId)
            return@withContext appContext.getString(R.string.bot_photo_verify_cancelled)
        }
        when (session.state) {
            BotChatSessionState.AWAITING_SERIAL_PICK -> {
                val normalizedPhone = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
                val candidates = database.serialDao().listNeedingImageVerificationByPhone(normalizedPhone)
                val byNumber = trimmed.toIntOrNull()?.let { n ->
                    candidates.getOrNull(n - 1)?.serial
                }
                val serial = byNumber
                    ?: candidates.firstOrNull {
                        it.serial.equals(SerialNormalizer.normalizeSerial(trimmed), ignoreCase = true)
                    }?.serial
                if (serial == null) {
                    return@withContext appContext.getString(R.string.bot_photo_verify_pick_invalid)
                }
                setAwaitingPhoto(channel, chatId, normalizedPhone, serial)
                appContext.getString(R.string.bot_photo_verify_send_photo, serial)
            }
            BotChatSessionState.AWAITING_PHOTO -> {
                appContext.getString(
                    R.string.bot_photo_verify_awaiting_photo,
                    session.pendingSerial.orEmpty(),
                )
            }
            else -> null
        }
    }

    suspend fun hasActiveSession(channel: String, chatId: String): Boolean = withContext(Dispatchers.IO) {
        database.botChatSessionDao().find(channel, chatId) != null
    }

    suspend fun savePhotoBytes(
        channel: String,
        chatId: String,
        phone: String,
        bytes: ByteArray,
        mimeType: String?,
        remoteFileId: String?,
    ): PhotoSaveResult = withContext(Dispatchers.IO) {
        val session = database.botChatSessionDao().find(channel, chatId)
            ?: return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_no_session),
            )
        if (session.state != BotChatSessionState.AWAITING_PHOTO) {
            return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_pick_first),
            )
        }
        val serial = session.pendingSerial?.takeIf { it.isNotBlank() }
            ?: return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_pick_first),
            )
        val normalizedPhone = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        val row = database.serialDao().findBySerial(serial)
        val rowPhone = row?.phone?.let { PhoneUtils.formatForDisplay(it) }
        if (row == null || rowPhone.isNullOrBlank() || rowPhone != normalizedPhone) {
            clearSession(channel, chatId)
            return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_serial_mismatch),
            )
        }
        val groupId = row.groupId
        val group = groupId?.let { database.serialGroupDao().findById(it) }
        if (group == null || !group.imageVerificationRequired) {
            clearSession(channel, chatId)
            return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_not_required),
            )
        }
        if (database.serialPhotoSubmissionDao().countActiveForSerialPhone(serial, normalizedPhone) > 0) {
            clearSession(channel, chatId)
            return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_already_submitted),
            )
        }
        if (bytes.isEmpty()) {
            return@withContext PhotoSaveResult(
                reply = appContext.getString(R.string.bot_photo_verify_download_failed),
            )
        }

        val dir = File(appContext.filesDir, PHOTO_DIR).apply { mkdirs() }
        val ext = extensionForMime(mimeType)
        val file = File(dir, "photo_${System.currentTimeMillis()}_$serial$ext")
        file.writeBytes(bytes)

        val now = System.currentTimeMillis()
        val id = database.serialPhotoSubmissionDao().insert(
            SerialPhotoSubmissionEntity(
                serial = serial,
                serialRowId = row.id,
                groupId = groupId,
                phone = normalizedPhone,
                channel = channel,
                chatId = chatId,
                status = SerialPhotoSubmissionStatus.PENDING,
                localPath = file.absolutePath,
                mimeType = mimeType,
                remoteFileId = remoteFileId,
                scoreAwarded = 0,
                createdAt = now,
            ),
        )
        clearSession(channel, chatId)
        PhotoSaveResult(
            reply = appContext.getString(R.string.bot_photo_verify_received, serial),
            submissionId = id,
        )
    }

    suspend fun approve(id: Long, note: String?): SerialPhotoSubmissionEntity = withContext(Dispatchers.IO) {
        val dao = database.serialPhotoSubmissionDao()
        val row = dao.findById(id) ?: throw IllegalArgumentException("ارسال یافت نشد")
        if (row.status != SerialPhotoSubmissionStatus.PENDING) {
            throw IllegalArgumentException("این ارسال قبلاً بررسی شده است")
        }
        val group = row.groupId?.let { database.serialGroupDao().findById(it) }
        val score = group?.score?.coerceAtLeast(0) ?: 0
        val now = System.currentTimeMillis()
        dao.updateReview(
            id = id,
            status = SerialPhotoSubmissionStatus.APPROVED,
            reviewedAt = now,
            reviewNote = note?.trim()?.takeIf { it.isNotEmpty() },
            scoreAwarded = score,
        )
        if (score > 0) {
            try {
                ReferralService.getInstance(appContext).awardOwnPoints(
                    phone = row.phone,
                    points = score,
                    sourceType = com.example.smsgateway.data.CustomerScoreSourceType.PHOTO,
                    sourceId = id,
                )
            } catch (error: Exception) {
                LanTraceLog.e(TAG, "Failed to ledger photo score for submission $id", error)
            }
        }
        val updated = dao.findById(id) ?: row.copy(
            status = SerialPhotoSubmissionStatus.APPROVED,
            reviewedAt = now,
            scoreAwarded = score,
        )
        notifyUser(
            updated,
            appContext.getString(
                R.string.bot_photo_review_approved,
                updated.serial,
                score.toString(),
            ),
        )
        updated
    }

    suspend fun reject(
        id: Long,
        reasonCode: String?,
        note: String? = null,
    ): SerialPhotoSubmissionEntity = withContext(Dispatchers.IO) {
        val dao = database.serialPhotoSubmissionDao()
        val row = dao.findById(id) ?: throw IllegalArgumentException("ارسال یافت نشد")
        if (row.status != SerialPhotoSubmissionStatus.PENDING) {
            throw IllegalArgumentException("این ارسال قبلاً بررسی شده است")
        }
        val reason = SerialPhotoRejectReason.normalize(reasonCode)
        val storedNote = listOfNotNull(
            reason,
            note?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" | ")
        val now = System.currentTimeMillis()
        dao.updateReview(
            id = id,
            status = SerialPhotoSubmissionStatus.REJECTED,
            reviewedAt = now,
            reviewNote = storedNote,
            scoreAwarded = 0,
        )
        val updated = dao.findById(id) ?: row.copy(
            status = SerialPhotoSubmissionStatus.REJECTED,
            reviewedAt = now,
            reviewNote = storedNote,
            scoreAwarded = 0,
        )
        notifyUser(updated, rejectMessageFor(updated.serial, reason, note))
        updated
    }

    private fun rejectMessageFor(serial: String, reason: String, note: String?): String {
        return when (reason) {
            SerialPhotoRejectReason.UNCLEAR ->
                appContext.getString(R.string.bot_photo_review_rejected_unclear, serial)
            SerialPhotoRejectReason.NOT_RELATED ->
                appContext.getString(R.string.bot_photo_review_rejected_not_related, serial)
            SerialPhotoRejectReason.WRONG_SERIAL ->
                appContext.getString(R.string.bot_photo_review_rejected_wrong_serial, serial)
            else -> {
                val custom = note?.trim().orEmpty()
                if (custom.isNotEmpty()) {
                    appContext.getString(
                        R.string.bot_photo_review_rejected_other_custom,
                        serial,
                        custom,
                    )
                } else {
                    appContext.getString(R.string.bot_photo_review_rejected_other, serial)
                }
            }
        }
    }

    private fun notifyUser(row: SerialPhotoSubmissionEntity, message: String) {
        if (row.chatId.isBlank() || message.isBlank()) return
        val settings = SettingsStore.getInstance(appContext)
        try {
            when (row.channel) {
                SerialPhotoChannel.RUBIKA -> {
                    val token = settings.getRubikaToken().trim()
                    if (token.isBlank()) {
                        LanTraceLog.w(TAG, "Skip Rubika review notify: token blank")
                        return
                    }
                    RubikaApi.getInstance().sendMessage(token, row.chatId, message)
                }
                SerialPhotoChannel.TELEGRAM -> {
                    val token = settings.getTelegramToken().trim()
                    if (token.isBlank()) {
                        LanTraceLog.w(TAG, "Skip Telegram review notify: token blank")
                        return
                    }
                    TelegramApi.getInstance().sendMessage(token, row.chatId, message)
                }
                SerialPhotoChannel.BALE -> {
                    val token = settings.getBaleToken().trim()
                    if (token.isBlank()) {
                        LanTraceLog.w(TAG, "Skip Bale review notify: token blank")
                        return
                    }
                    BaleApi.getInstance().sendMessage(token, row.chatId, message)
                }
                else -> LanTraceLog.w(TAG, "Skip review notify: unknown channel ${row.channel}")
            }
            LanTraceLog.i(TAG, "Review notify sent channel=${row.channel} chat=${row.chatId}")
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "Failed to notify user after photo review id=${row.id}", error)
        }
    }

    private suspend fun setAwaitingPick(channel: String, chatId: String, phone: String) {
        upsertSession(channel, chatId, phone, BotChatSessionState.AWAITING_SERIAL_PICK, null)
    }

    private suspend fun setAwaitingPhoto(
        channel: String,
        chatId: String,
        phone: String,
        serial: String,
    ) {
        upsertSession(channel, chatId, phone, BotChatSessionState.AWAITING_PHOTO, serial)
    }

    private suspend fun upsertSession(
        channel: String,
        chatId: String,
        phone: String,
        state: String,
        pendingSerial: String?,
    ) {
        val dao = database.botChatSessionDao()
        val existing = dao.find(channel, chatId)
        val now = System.currentTimeMillis()
        if (existing == null) {
            dao.insert(
                BotChatSessionEntity(
                    channel = channel,
                    chatId = chatId,
                    phone = phone,
                    state = state,
                    pendingSerial = pendingSerial,
                    updatedAt = now,
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    phone = phone,
                    state = state,
                    pendingSerial = pendingSerial,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun clearSession(channel: String, chatId: String) {
        database.botChatSessionDao().clear(channel, chatId)
    }

    companion object {
        private const val TAG = "SerialPhotoVerify"
        private const val PHOTO_DIR = "serial_photo_submissions"
        const val CANCEL_LABEL = "انصراف"

        private fun extensionForMime(mimeType: String?): String {
            return when (mimeType?.lowercase()) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> ".jpg"
            }
        }

        @Volatile
        private var instance: SerialPhotoVerificationService? = null

        fun getInstance(context: Context): SerialPhotoVerificationService {
            return instance ?: synchronized(this) {
                instance ?: SerialPhotoVerificationService(
                    context.applicationContext,
                    GatewayDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }

        fun channelFor(botChannel: BotMenuChannel): String = when (botChannel) {
            BotMenuChannel.RUBIKA -> SerialPhotoChannel.RUBIKA
            BotMenuChannel.TELEGRAM -> SerialPhotoChannel.TELEGRAM
            BotMenuChannel.BALE -> SerialPhotoChannel.BALE
        }
    }
}
