package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity

/**
 * Sends the standard registration-confirmation SMS (same template as successful SMS registration).
 */
object RegistrationConfirmationSmsService {

    suspend fun buildMessage(
        context: Context,
        row: SerialEntity,
        templates: SmsMessageTemplates,
    ): String {
        val warrantyLabel = resolveWarrantyLabel(context, row.serial)
        val base = templates.resolve(
            SmsMessageId.REPLY_VALID,
            mapOf("@1" to warrantyLabel),
        )
        val withSerial = "$base\nسریال: ${row.serial}"
        if (!isImageVerificationRequired(context, row.serial)) {
            return withSerial
        }
        val photoHint = context.getString(
            R.string.bot_photo_verify_after_register,
            SerialNormalizer.normalizeSerial(row.serial),
        )
        return "$withSerial\n\n$photoHint"
    }

    suspend fun sendToPhone(
        context: Context,
        row: SerialEntity,
        phone: String,
        templates: SmsMessageTemplates,
    ) {
        val destination = PhoneUtils.formatForDisplay(phone)?.trim().orEmpty()
        require(PhoneUtils.isRegisteredPhone(destination)) { "Invalid phone number" }

        val message = buildMessage(context, row, templates)
        val subscriptionId = SmsOutbound.resolveSubscriptionIdOrThrow(
            context,
            row.registrationSubscriptionId,
        )
        val simLabel = SimCardHelper.simLabelForSubscription(context, subscriptionId)

        SmsOutbound.send(context, subscriptionId, destination, message)

        CommunicationLog.getInstance(context).logOutgoing(
            phone = destination,
            body = message,
            status = CommunicationLog.STATUS_SENT,
            simLabel = simLabel,
        )
    }

    private suspend fun resolveWarrantyLabel(context: Context, serial: String): String {
        val db = GatewayDatabase.getInstance(context)
        val row = db.serialDao().findBySerial(SerialNormalizer.normalizeSerial(serial))
            ?: return "طبق شرایط محصول"
        val groupId = row.groupId ?: return "طبق شرایط محصول"
        val group = db.serialGroupDao().findById(groupId) ?: return "طبق شرایط محصول"
        return SerialGroupWarranty.formatFa(group.warrantyMonths, group.warrantyKm)
            ?: "طبق شرایط محصول"
    }

    private suspend fun isImageVerificationRequired(context: Context, serial: String): Boolean {
        val db = GatewayDatabase.getInstance(context)
        val row = db.serialDao().findBySerial(SerialNormalizer.normalizeSerial(serial))
            ?: return false
        val groupId = row.groupId ?: return false
        val group = db.serialGroupDao().findById(groupId) ?: return false
        return group.imageVerificationRequired
    }
}
