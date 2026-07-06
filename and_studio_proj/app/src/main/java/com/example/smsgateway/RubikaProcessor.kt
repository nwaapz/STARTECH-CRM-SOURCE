package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.lan.LanTraceLog

class RubikaProcessor private constructor(
    private val appContext: Context,
) {
    private val settingsStore = SettingsStore.getInstance(appContext)
    private val api = RubikaApi.getInstance()
    private val engine = SmsProcessingEngine.getInstance(appContext)
    private val chatStore = RubikaChatStore.getInstance(appContext)
    private val communicationLog = CommunicationLog.getInstance(appContext)

    fun processIncoming(update: RubikaApi.RubikaMessageUpdate) {
        if (update.senderType.equals("Bot", ignoreCase = true)) return

        val token = settingsStore.getRubikaToken()
        if (token.isBlank()) return

        val chatId = update.chatId
        val now = System.currentTimeMillis()

        val knownPhone = chatStore.getContactPhone(chatId)
            ?: update.phoneNumber?.let { PhoneUtils.formatForDisplay(it) }
            ?: RubikaApi.extractPhoneFromText(update.text)

        if (knownPhone.isNullOrBlank()) {
            handleMissingPhone(token, chatId, now)
            return
        }

        if (!update.phoneNumber.isNullOrBlank() || RubikaApi.isPhoneShareText(update.text)) {
            return
        }

        if (!settingsStore.isRubikaSerialEnabled()) return

        val text = update.text.trim()
        if (text.isBlank()) return

        try {
            val response = engine.process(knownPhone, text)
            val outboundMessageId = api.sendMessage(token, chatId, response.reply)
            chatStore.appendOutgoing(
                chatId = chatId,
                messageId = outboundMessageId.ifBlank { "out-$now" },
                text = response.reply,
                timestamp = now + 1,
            )
            communicationLog.logFromProcessor(
                sender = knownPhone,
                inbound = text,
                reply = response.reply,
                status = CommunicationLog.STATUS_SENT,
                error = null,
                simLabel = RUBIKA_SIM_LABEL,
                timestamp = now,
            )
        } catch (error: GatewayApi.GatewayException) {
            LanTraceLog.e("RubikaProcessor", "Serial verification failed for $chatId", error)
            communicationLog.logFromProcessor(
                sender = knownPhone,
                inbound = text,
                reply = null,
                status = CommunicationLog.STATUS_GATEWAY_ERROR,
                error = error.message,
                simLabel = RUBIKA_SIM_LABEL,
                timestamp = now,
            )
        } catch (error: Exception) {
            LanTraceLog.e("RubikaProcessor", "Unexpected error for $chatId", error)
            communicationLog.logFromProcessor(
                sender = knownPhone,
                inbound = text,
                reply = null,
                status = CommunicationLog.STATUS_FAILED,
                error = error.message,
                simLabel = RUBIKA_SIM_LABEL,
                timestamp = now,
            )
        }
    }

    private fun handleMissingPhone(token: String, chatId: String, now: Long) {
        val isFirstRequest = chatStore.needsPhoneRequest(chatId)
        val messageText = if (isFirstRequest) {
            appContext.getString(R.string.rubika_phone_request_message)
        } else {
            appContext.getString(R.string.rubika_phone_reminder_message)
        }
        val buttonText = appContext.getString(R.string.rubika_phone_request_button)
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
            LanTraceLog.e("RubikaProcessor", "Failed to request phone for $chatId", error)
        }
    }

    companion object {
        const val RUBIKA_SIM_LABEL = "روبیکا"

        @Volatile
        private var instance: RubikaProcessor? = null

        fun getInstance(context: Context): RubikaProcessor {
            return instance ?: synchronized(this) {
                instance ?: RubikaProcessor(context.applicationContext).also { instance = it }
            }
        }
    }
}
