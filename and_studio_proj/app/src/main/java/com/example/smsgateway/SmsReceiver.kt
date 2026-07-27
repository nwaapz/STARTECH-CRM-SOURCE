package com.example.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val subscriptionId = SimCardHelper.extractSubscriptionId(context, intent)

        // Customer: only process allow/deny replies from admin (never registry/admin mode).
        if (LicenseSmsProtocol.isReplyMessage(body)) {
            val pendingResult = goAsync()
            executor.execute {
                try {
                    LicenseChecker.getInstance(context).handleInboundReply(sender, body, subscriptionId)
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
            val shortCodes = settingsStore.getMelipayamakInboundShortCodes()
            if (!PanelInboundParser.isPanelSender(sender, shortCodes)) return
            val parsed = PanelInboundParser.parse(body) ?: return
            customerSender = parsed.customerPhone
            customerBody = parsed.message
        } else {
            customerSender = sender
            customerBody = body
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
