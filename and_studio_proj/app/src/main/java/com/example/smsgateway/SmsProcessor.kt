package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

class SmsProcessor(private val context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore.getInstance(appContext)
    private val communicationLog = CommunicationLog.getInstance(appContext)
    private val verifierFactory = SerialVerifierFactory.getInstance(appContext)
    private val rubikaChatStore = RubikaChatStore.getInstance(appContext)
    private val telegramChatStore = TelegramChatStore.getInstance(appContext)
    private val baleChatStore = BaleChatStore.getInstance(appContext)

    private val recentMessages = ConcurrentHashMap<String, Long>()

    fun process(sender: String, body: String, subscriptionId: Int) {
        if (sender.isBlank() || body.isBlank()) return
        // Hard stop: never verify/reply to short codes or non-Iranian-mobile senders.
        // (SmsReceiver also filters; this covers older builds / alternate entry points.)
        if (!PhoneUtils.isRegisteredPhone(sender)) {
            LanTraceLog.w(TAG, "Ignoring non-mobile SMS sender=$sender")
            return
        }

        val dedupKey = "$subscriptionId::$sender::$body"
        val now = System.currentTimeMillis()
        val lastSeen = recentMessages[dedupKey]
        if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
            return
        }
        recentMessages[dedupKey] = now
        pruneDedupCache(now)

        val inboundSimLabel = SimCardHelper.simLabelForSubscription(context, subscriptionId)

        try {
            val response = verifierFactory.get().verify(sender, body, subscriptionId)
            val replySubscriptionId = response.replySubscriptionId ?: subscriptionId
            val simLabel = SimCardHelper.simLabelForSubscription(context, replySubscriptionId)
            try {
                SmsOutbound.send(context, replySubscriptionId, sender, response.reply)
            } catch (quotaError: SmsOutboundQuotaExceededException) {
                communicationLog.logFromProcessor(
                    sender = sender,
                    inbound = body,
                    reply = null,
                    status = CommunicationLog.STATUS_FAILED,
                    error = quotaError.message,
                    simLabel = simLabel,
                    timestamp = now,
                )
                return
            }
            communicationLog.logFromProcessor(
                sender = sender,
                inbound = body,
                reply = response.reply,
                status = CommunicationLog.STATUS_SENT,
                error = null,
                simLabel = simLabel,
                timestamp = now,
            )
            if (response.registrationSucceeded) {
                runBlocking {
                    RegistrationWelcomeSmsService.sendIfEnabled(
                        appContext,
                        sender,
                        response.registeredSerial,
                    )
                }
            }
            if (response.registrationSucceeded || response.firstRegistration || response.imageVerificationRequired) {
                maybeSendBotInvites(
                    sender = sender,
                    subscriptionId = replySubscriptionId,
                    simLabel = simLabel,
                    timestamp = now,
                    registrationSucceeded = response.registrationSucceeded,
                    firstRegistration = response.firstRegistration,
                    imageVerificationRequired = response.imageVerificationRequired,
                )
            }
        } catch (error: GatewayApi.GatewayException) {
            communicationLog.logFromProcessor(
                sender = sender,
                inbound = body,
                reply = null,
                status = CommunicationLog.STATUS_GATEWAY_ERROR,
                error = error.message,
                simLabel = inboundSimLabel,
                timestamp = now,
            )
        } catch (error: Exception) {
            communicationLog.logFromProcessor(
                sender = sender,
                inbound = body,
                reply = null,
                status = CommunicationLog.STATUS_FAILED,
                error = error.message,
                simLabel = inboundSimLabel,
                timestamp = now,
            )
        }
    }

    private fun maybeSendBotInvites(
        sender: String,
        subscriptionId: Int,
        simLabel: String?,
        timestamp: Long,
        registrationSucceeded: Boolean,
        firstRegistration: Boolean,
        imageVerificationRequired: Boolean,
    ) {
        val phone = PhoneUtils.formatForDisplay(sender) ?: sender.trim()

        if (registrationSucceeded && settingsStore.isRubikaInviteEnabled()) {
            sendRubikaInviteIfNeeded(phone, sender, subscriptionId, simLabel, timestamp)
        }

        if (firstRegistration && settingsStore.isTelegramInviteEnabled()) {
            sendTelegramInviteIfNeeded(phone, sender, subscriptionId, simLabel, timestamp)
        }

        if (firstRegistration && settingsStore.isBaleInviteEnabled()) {
            sendBaleInviteIfNeeded(phone, sender, subscriptionId, simLabel, timestamp)
        }

        if ((registrationSucceeded || firstRegistration) && imageVerificationRequired) {
            sendPhotoVerificationInviteIfNeeded(phone, sender, subscriptionId, simLabel, timestamp)
        }
    }

    private fun sendPhotoVerificationInviteIfNeeded(
        phone: String,
        sender: String,
        subscriptionId: Int,
        simLabel: String?,
        timestamp: Long,
    ) {
        val inviteText = BotInviteHelper.photoVerificationInviteText(settingsStore, appContext)
            ?: return
        sendInviteSms(
            channel = "PhotoVerify",
            phone = phone,
            sender = sender,
            subscriptionId = subscriptionId,
            simLabel = simLabel,
            inviteText = inviteText,
            timestamp = timestamp + 2,
        )
    }

    private fun sendRubikaInviteIfNeeded(
        phone: String,
        sender: String,
        subscriptionId: Int,
        simLabel: String?,
        timestamp: Long,
    ) {
        if (settingsStore.wasRubikaInviteSent(phone)) {
            LanTraceLog.i(TAG, "Rubika invite skipped for $phone: invite_already_sent")
            return
        }

        if (rubikaChatStore.isPhonePaired(phone)) {
            LanTraceLog.i(TAG, "Rubika invite skipped for $phone: already paired")
            return
        }

        val inviteText = BotInviteHelper.rubikaInviteText(settingsStore, appContext)
        if (inviteText == null) {
            val reason = when {
                !settingsStore.isRubikaBotActive() -> "bot_inactive"
                settingsStore.getRubikaBotInfo()?.username.isNullOrBlank() -> "username_missing"
                else -> "invite_text_unavailable"
            }
            LanTraceLog.w(TAG, "Rubika invite skipped for $phone: $reason")
            return
        }

        if (sendInviteSms(
                channel = "Rubika",
                phone = phone,
                sender = sender,
                subscriptionId = subscriptionId,
                simLabel = simLabel,
                inviteText = inviteText,
                timestamp = timestamp,
            )
        ) {
            settingsStore.markRubikaInviteSent(phone)
        }
    }

    private fun sendTelegramInviteIfNeeded(
        phone: String,
        sender: String,
        subscriptionId: Int,
        simLabel: String?,
        timestamp: Long,
    ) {
        if (telegramChatStore.isPhonePaired(phone)) {
            LanTraceLog.i(TAG, "Telegram invite skipped for $phone: already paired")
            return
        }

        val inviteText = BotInviteHelper.telegramInviteText(settingsStore, appContext)
        if (inviteText == null) {
            val reason = when {
                !settingsStore.isTelegramBotActive() -> "bot_inactive"
                settingsStore.getTelegramBotInfo()?.username.isNullOrBlank() -> "username_missing"
                else -> "invite_text_unavailable"
            }
            LanTraceLog.w(TAG, "Telegram invite skipped for $phone: $reason")
            return
        }

        sendInviteSms(
            channel = "Telegram",
            phone = phone,
            sender = sender,
            subscriptionId = subscriptionId,
            simLabel = simLabel,
            inviteText = inviteText,
            timestamp = timestamp,
        )
    }

    private fun sendBaleInviteIfNeeded(
        phone: String,
        sender: String,
        subscriptionId: Int,
        simLabel: String?,
        timestamp: Long,
    ) {
        if (baleChatStore.isPhonePaired(phone)) {
            LanTraceLog.i(TAG, "Bale invite skipped for $phone: already paired")
            return
        }

        val inviteText = BotInviteHelper.baleInviteText(settingsStore, appContext)
        if (inviteText == null) {
            val reason = when {
                !settingsStore.isBaleBotActive() -> "bot_inactive"
                settingsStore.getBaleBotInfo()?.username.isNullOrBlank() -> "username_missing"
                else -> "invite_text_unavailable"
            }
            LanTraceLog.w(TAG, "Bale invite skipped for $phone: $reason")
            return
        }

        sendInviteSms(
            channel = "Bale",
            phone = phone,
            sender = sender,
            subscriptionId = subscriptionId,
            simLabel = simLabel,
            inviteText = inviteText,
            timestamp = timestamp,
        )
    }

    private fun sendInviteSms(
        channel: String,
        phone: String,
        sender: String,
        subscriptionId: Int,
        simLabel: String?,
        inviteText: String,
        timestamp: Long,
    ): Boolean {
        return try {
            SmsOutbound.send(context, subscriptionId, sender, inviteText)
            communicationLog.logOutgoing(
                phone = phone,
                body = inviteText,
                status = CommunicationLog.STATUS_SENT,
                simLabel = simLabel,
                timestamp = timestamp + 1,
            )
            LanTraceLog.i(TAG, "$channel invite SMS sent to $phone")
            true
        } catch (error: Exception) {
            communicationLog.logOutgoing(
                phone = phone,
                body = inviteText,
                status = CommunicationLog.STATUS_FAILED,
                simLabel = simLabel,
                timestamp = timestamp + 1,
            )
            LanTraceLog.e(TAG, "$channel invite SMS failed for $phone", error)
            false
        }
    }

    private fun pruneDedupCache(now: Long) {
        recentMessages.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
    }

    companion object {
        private const val TAG = "SmsProcessor"
        private const val DEDUP_WINDOW_MS = 10_000L

        @Volatile
        private var instance: SmsProcessor? = null

        fun getInstance(context: Context): SmsProcessor {
            return instance ?: synchronized(this) {
                instance ?: SmsProcessor(context.applicationContext).also { instance = it }
            }
        }
    }
}
