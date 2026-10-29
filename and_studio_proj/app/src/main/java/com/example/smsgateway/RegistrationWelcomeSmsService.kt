package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.lan.LanTraceLog

/**
 * Optional second SMS after successful serial registration (configured in باشگاه مشتریان).
 */
object RegistrationWelcomeSmsService {

    private const val TAG = "RegWelcomeSms"

    suspend fun sendIfEnabled(context: Context, phone: String, serial: String?) {
        val normalizedSerial = serial
            ?.let { SerialNormalizer.normalizeSerial(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val settings = SettingsStore.getInstance(context).getClubWelcomeSettings()
        if (!settings.enabled || settings.message.isBlank()) {
            return
        }
        val destination = PhoneUtils.formatForDisplay(phone)?.trim().orEmpty()
        if (!PhoneUtils.isRegisteredPhone(destination)) {
            return
        }
        val row = GatewayDatabase.getInstance(context).serialDao().findBySerial(normalizedSerial)
            ?: return
        val body = ClubAutoMessageScheduler.applyPlaceholders(
            settings.message,
            listOf(
                ClubAutoMessageScheduler.RegistrationPlaceholder(
                    serial = row.serial,
                    date = row.date.orEmpty(),
                    km = row.km,
                ),
            ),
        )
        if (body.isBlank()) {
            return
        }
        try {
            val subscriptionId = SmsOutbound.resolveSubscriptionIdOrThrow(
                context,
                row.registrationSubscriptionId,
            )
            val simLabel = SimCardHelper.simLabelForSubscription(context, subscriptionId)
            SmsOutbound.send(context, subscriptionId, destination, body)
            CommunicationLog.getInstance(context).logOutgoing(
                phone = destination,
                body = body,
                status = CommunicationLog.STATUS_SENT,
                simLabel = simLabel,
            )
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "Welcome SMS failed for serial ${row.serial}", error)
            CommunicationLog.getInstance(context).logOutgoing(
                phone = destination,
                body = body,
                status = CommunicationLog.STATUS_FAILED,
                simLabel = null,
            )
        }
    }
}
