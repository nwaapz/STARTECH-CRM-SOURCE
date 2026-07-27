package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.runBlocking

class BaleProcessor private constructor(
    private val appContext: Context,
) {
    private val settingsStore = SettingsStore.getInstance(appContext)
    private val api = BaleApi.getInstance()
    private val engine = SmsProcessingEngine.getInstance(appContext)
    private val chatStore = BaleChatStore.getInstance(appContext)
    private val communicationLog = CommunicationLog.getInstance(appContext)
    private val photoService = SerialPhotoVerificationService.getInstance(appContext)

    fun processIncoming(update: BaleApi.BaleMessageUpdate) {
        if (update.senderType.equals("Bot", ignoreCase = true)) return

        val token = settingsStore.getBaleToken()
        if (token.isBlank()) return

        val chatId = update.chatId
        val now = System.currentTimeMillis()
        val channel = SerialPhotoVerificationService.channelFor(BotMenuChannel.BALE)

        val knownPhone = chatStore.getContactPhone(chatId)
            ?: update.phoneNumber?.let { PhoneUtils.formatForDisplay(it) }
            ?: RubikaApi.extractPhoneFromText(update.text)

        captureReferralCode(channel, chatId, knownPhone, update.text)

        if (knownPhone.isNullOrBlank()) {
            handleMissingPhone(token, chatId, now)
            return
        }

        if (!update.phoneNumber.isNullOrBlank() || BaleApi.isPhoneShareText(update.text)) {
            handlePhoneCaptured(token, chatId, knownPhone, now)
            return
        }

        if (!update.photoFileId.isNullOrBlank()) {
            handleIncomingPhoto(token, chatId, knownPhone, update, channel, now)
            return
        }

        val text = update.text.trim()
        if (text.isBlank()) return

        if (text.startsWith("/start")) {
            try {
                val welcome = appContext.getString(R.string.bot_start_welcome)
                sendWithMenu(token, chatId, welcome, now, idPrefix = "start")
                logComm(knownPhone, text, welcome, now)
            } catch (error: Exception) {
                LanTraceLog.e("BaleProcessor", "Start welcome failed for $chatId", error)
            }
            return
        }

        val inSession = runBlocking { photoService.hasActiveSession(channel, chatId) }
        if (inSession) {
            val reply = runBlocking {
                photoService.handleTextWhileInSession(channel, chatId, knownPhone, text)
            } ?: return
            try {
                sendWithMenu(token, chatId, reply, now, extraButtons = photoSessionExtraButtons())
                logComm(knownPhone, text, reply, now)
            } catch (error: Exception) {
                LanTraceLog.e("BaleProcessor", "Photo session reply failed for $chatId", error)
            }
            return
        }

        val menuItem = BotMenuHelper.matchEnabledItem(text, settingsStore, BotMenuChannel.BALE)
        if (menuItem != null) {
            handleMenuItem(token, chatId, knownPhone, menuItem, text, channel, now)
            return
        }

        if (!settingsStore.isBaleSerialEnabled()) return

        try {
            val response = engine.process(
                knownPhone,
                text,
                registrationSource = RegistrationSource.BALE,
            )
            sendWithMenu(token, chatId, response.reply, now)
            logComm(knownPhone, text, response.reply, now)
            if (response.registrationSucceeded && response.imageVerificationRequired) {
                maybeStartPhotoFlowAfterRegister(
                    token = token,
                    chatId = chatId,
                    phone = knownPhone,
                    serial = response.registeredSerial,
                    channel = channel,
                    now = now,
                )
            }
            if (response.firstRegistration && settingsStore.isBaleInviteEnabled()) {
                sendInviteLink(token, chatId, knownPhone, now)
            }
        } catch (error: GatewayApi.GatewayException) {
            LanTraceLog.e("BaleProcessor", "Serial verification failed for $chatId", error)
            communicationLog.logFromProcessor(
                sender = knownPhone,
                inbound = text,
                reply = null,
                status = CommunicationLog.STATUS_GATEWAY_ERROR,
                error = error.message,
                simLabel = BALE_SIM_LABEL,
                timestamp = now,
                channel = CommunicationLog.CHANNEL_BALE,
            )
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Unexpected error for $chatId", error)
            communicationLog.logFromProcessor(
                sender = knownPhone,
                inbound = text,
                reply = null,
                status = CommunicationLog.STATUS_FAILED,
                error = error.message,
                simLabel = BALE_SIM_LABEL,
                timestamp = now,
                channel = CommunicationLog.CHANNEL_BALE,
            )
        }
    }

    private fun handleIncomingPhoto(
        token: String,
        chatId: String,
        phone: String,
        update: BaleApi.BaleMessageUpdate,
        channel: String,
        now: Long,
    ) {
        val fileId = update.photoFileId ?: return
        try {
            val (bytes, mime) = api.downloadFileBytes(token, fileId)
            val result = runBlocking {
                photoService.savePhotoBytes(
                    channel = channel,
                    chatId = chatId,
                    phone = phone,
                    bytes = bytes,
                    mimeType = mime ?: update.photoMimeType,
                    remoteFileId = fileId,
                )
            }
            sendWithMenu(token, chatId, result.reply, now)
            logComm(phone, "[photo]", result.reply, now)
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Photo handling failed for $chatId", error)
            try {
                val msg = appContext.getString(R.string.bot_photo_verify_download_failed)
                sendWithMenu(token, chatId, msg, now)
            } catch (_: Exception) {
            }
        }
    }

    private fun handleMenuItem(
        token: String,
        chatId: String,
        phone: String,
        item: BotMenuItem,
        inbound: String,
        channel: String,
        now: Long,
    ) {
        if (item == BotMenuItem.SEND_PRODUCT_PHOTO) {
            val start = runBlocking { photoService.startFlow(channel, chatId, phone) }
            try {
                sendWithMenu(
                    token,
                    chatId,
                    start.reply,
                    now,
                    extraButtons = start.serialButtons + listOf(
                        appContext.getString(R.string.bot_photo_verify_cancel),
                    ),
                )
                logComm(phone, inbound, start.reply, now)
            } catch (error: Exception) {
                LanTraceLog.e("BaleProcessor", "Photo menu failed for $chatId", error)
            }
            return
        }
        val reply = BotMenuReplies.buildReply(
            context = appContext,
            item = item,
            phone = phone,
            channel = BotMenuChannel.BALE,
            settingsStore = settingsStore,
        )
        try {
            sendWithMenu(token, chatId, reply, now)
            logComm(phone, inbound, reply, now)
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Menu reply failed for $chatId", error)
        }
    }

    private fun captureReferralCode(channel: String, chatId: String, phone: String?, text: String?) {
        val code = ReferralService.extractCode(text) ?: return
        try {
            runBlocking {
                val service = ReferralService.getInstance(appContext)
                if (!phone.isNullOrBlank()) {
                    service.attachReferral(phone, code)
                } else {
                    service.rememberPending(channel, chatId, code)
                }
            }
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Referral capture failed for $chatId", error)
        }
    }

    private fun handlePhoneCaptured(token: String, chatId: String, phone: String?, now: Long) {
        if (phone.isNullOrBlank()) return
        try {
            runBlocking {
                ReferralService.getInstance(appContext).attachPending(
                    SerialPhotoVerificationService.channelFor(BotMenuChannel.BALE),
                    chatId,
                    phone,
                )
            }
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Referral attach failed for $chatId", error)
        }
        val messageText = if (engine.isPhoneRegisteredWithMCode(phone)) {
            appContext.getString(R.string.bot_phone_confirmed_registered)
        } else {
            appContext.getString(R.string.bot_phone_confirmed_unregistered)
        }
        try {
            sendWithMenu(token, chatId, messageText, now, idPrefix = "phone-confirmed")
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Failed to send phone confirmation for $chatId", error)
        }
    }

    private fun handleMissingPhone(token: String, chatId: String, now: Long) {
        val isFirstRequest = chatStore.needsPhoneRequest(chatId)
        val messageText = if (isFirstRequest) {
            appContext.getString(R.string.bale_phone_request_message)
        } else {
            appContext.getString(R.string.bale_phone_reminder_message)
        }
        val buttonText = appContext.getString(R.string.bale_phone_request_button)
        try {
            val outboundMessageId = api.sendPhoneNumberRequest(token, chatId, messageText, buttonText)
            if (isFirstRequest) {
                chatStore.markPhoneRequestSent(chatId)
            }
            chatStore.appendOutgoing(
                chatId = chatId,
                messageId = outboundMessageId.ifBlank { "phone-req-$now" },
                text = messageText,
                timestamp = now + 1,
            )
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Failed to request phone for $chatId", error)
        }
    }

    private fun sendWithMenu(
        token: String,
        chatId: String,
        text: String,
        now: Long,
        idPrefix: String = "out",
        extraButtons: List<String> = emptyList(),
    ) {
        val labels = BotMenuHelper.enabledLabels(settingsStore, BotMenuChannel.BALE)
            .toMutableList()
        extraButtons.forEach { label ->
            if (label.isNotBlank() && labels.none { it == label }) labels.add(label)
        }
        val rows = BotMenuHelper.keyboardRows(labels)
        val outboundMessageId = api.sendMessageWithReplyKeyboard(token, chatId, text, rows)
        chatStore.appendOutgoing(
            chatId = chatId,
            messageId = outboundMessageId.ifBlank { "$idPrefix-$now" },
            text = text,
            timestamp = now + 1,
        )
    }

    private fun photoSessionExtraButtons(): List<String> {
        return listOf(appContext.getString(R.string.bot_photo_verify_cancel))
    }

    private fun logComm(phone: String, inbound: String, reply: String, now: Long) {
        communicationLog.logFromProcessor(
            sender = phone,
            inbound = inbound,
            reply = reply,
            status = CommunicationLog.STATUS_SENT,
            error = null,
            simLabel = BALE_SIM_LABEL,
            timestamp = now,
            channel = CommunicationLog.CHANNEL_BALE,
        )
    }

    private fun maybeStartPhotoFlowAfterRegister(
        token: String,
        chatId: String,
        phone: String,
        serial: String?,
        channel: String,
        now: Long,
    ) {
        if (!settingsStore.getBaleMenuSettings().sendProductPhoto) return
        val start = runBlocking {
            if (!serial.isNullOrBlank()) {
                photoService.startFlowForSerial(channel, chatId, phone, serial)
            } else {
                photoService.startFlow(channel, chatId, phone)
            }
        }
        try {
            sendWithMenu(
                token,
                chatId,
                start.reply,
                now,
                idPrefix = "photo-after-reg",
                extraButtons = start.serialButtons + listOf(
                    appContext.getString(R.string.bot_photo_verify_cancel),
                ),
            )
            logComm(phone, "[photo-prompt]", start.reply, now + 1)
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Photo prompt after register failed for $chatId", error)
        }
    }

    private fun sendInviteLink(token: String, chatId: String, phone: String, now: Long) {
        val code = if (settingsStore.isReferralEnabled()) {
            runCatching {
                runBlocking { ReferralService.getInstance(appContext).ensureCode(phone) }
            }.getOrNull()
        } else {
            null
        }
        val message = BotInviteHelper.baleInviteText(settingsStore, appContext, code)
        if (message == null) {
            val reason = when {
                !settingsStore.isBaleBotActive() -> "bot_inactive"
                settingsStore.getBaleBotInfo()?.username.isNullOrBlank() -> "username_missing"
                else -> "invite_text_unavailable"
            }
            LanTraceLog.w("BaleProcessor", "In-chat invite skipped for $chatId: $reason")
            return
        }
        try {
            sendWithMenu(token, chatId, message, now, idPrefix = "invite")
        } catch (error: Exception) {
            LanTraceLog.e("BaleProcessor", "Failed to send invite link for $chatId", error)
        }
    }

    companion object {
        const val BALE_SIM_LABEL = "بله"

        @Volatile
        private var instance: BaleProcessor? = null

        fun getInstance(context: Context): BaleProcessor {
            return instance ?: synchronized(this) {
                instance ?: BaleProcessor(context.applicationContext).also { instance = it }
            }
        }
    }
}
