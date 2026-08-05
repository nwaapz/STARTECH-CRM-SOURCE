package com.example.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.smsgateway.lan.LanTraceLog
import com.example.smsgateway.license.LicenseChecker
import com.example.smsgateway.license.LicenseGate
import com.example.smsgateway.license.LicenseSmsProtocol
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val first = messages.first()
        val originating = first.originatingAddress
        val display = first.displayOriginatingAddress
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val subscriptionId = SimCardHelper.extractSubscriptionId(context, intent)

        // Customer: only process allow/deny replies from admin (never registry/admin mode).
        if (LicenseSmsProtocol.isReplyMessage(body)) {
            val pendingResult = goAsync()
            executor.execute {
                try {
                    val rawSender = originating?.takeIf { it.isNotBlank() } ?: display.orEmpty()
                    LicenseChecker.getInstance(context).handleInboundReply(
                        rawSender,
                        body,
                        subscriptionId,
                    )
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        if (LicenseSmsProtocol.isLicenseMessage(body)) {
            // Ignore outbound-style CHK echoes or unknown license traffic on customer device.
            return
        }

        if (!SettingsStore.getInstance(context).isServiceEnabled()) return
        if (!SettingsStore.getInstance(context).isSubscriptionAllowedForInbound(subscriptionId)) return
        if (!LicenseGate.allowsOperationalUse(context)) return

        val settingsStore = SettingsStore.getInstance(context)
        val customerSender: String
        val customerBody: String

        if (settingsStore.isMelipayamakPanelMode()) {
            // Panel mode: only accept the configured panel line — the "from" textbox
            // (sender line) plus any extra inbound codes — then extract the customer
            // mobile from the forwarded body.
            val allowedCodes = buildSet {
                addAll(settingsStore.getMelipayamakInboundShortCodes())
                settingsStore.getMelipayamakSenderLine().takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val senderMatchesPanel = listOfNotNull(originating, display)
                .filter { it.isNotBlank() }
                .any { PanelInboundParser.isPanelSender(it, allowedCodes) }
            if (!senderMatchesPanel) {
                LanTraceLog.w(
                    "SmsReceiver",
                    "Panel mode: ignore non-panel sender originating=$originating display=$display",
                )
                return
            }
            val parsed = PanelInboundParser.parse(body) ?: return
            if (!PhoneUtils.isRegisteredPhone(parsed.customerPhone)) {
                LanTraceLog.w(
                    "SmsReceiver",
                    "Panel mode: body customer is not mobile phone=${parsed.customerPhone}",
                )
                return
            }
            if (settingsStore.isMelipayamakTestModeEnabled()) {
                val testPhone = PhoneUtils.formatForDisplay(settingsStore.getMelipayamakTestPhone())
                    ?: settingsStore.getMelipayamakTestPhone().trim()
                if (testPhone.isBlank()) return
                val clientPhone = PhoneUtils.formatForDisplay(parsed.customerPhone)
                    ?: parsed.customerPhone
                if (clientPhone != testPhone) return
            }
            customerSender = PhoneUtils.formatForDisplay(parsed.customerPhone)
                ?: parsed.customerPhone
            customerBody = parsed.message
        } else {
            // Device SIM mode: ONLY Iranian mobiles. Contact names → look up real number;
            // short codes / bank lines → ignore (even if saved as a contact).
            val mobile = DeviceContactHelper.resolveDeviceSimMobileSender(
                context = context,
                originatingAddress = originating,
                displayOriginatingAddress = display,
            )
            if (mobile == null) {
                LanTraceLog.w(
                    "SmsReceiver",
                    "Device mode: ignore non-mobile sender originating=$originating display=$display",
                )
                return
            }
            customerSender = mobile
            customerBody = body
        }

        // Final hard gate before any processing / reply.
        if (!PhoneUtils.isRegisteredPhone(customerSender)) {
            LanTraceLog.w("SmsReceiver", "Refusing non-mobile customerSender=$customerSender")
            return
        }

        val pendingResult = goAsync()
        executor.execute {
            try {
                SmsProcessor.getInstance(context).process(
                    sender = customerSender,
                    body = customerBody,
                    subscriptionId = subscriptionId,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
