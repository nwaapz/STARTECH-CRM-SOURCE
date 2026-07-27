package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object BotMenuReplies {
    fun buildReply(
        context: Context,
        item: BotMenuItem,
        phone: String,
        channel: BotMenuChannel,
        settingsStore: SettingsStore,
    ): String {
        return when (item) {
            BotMenuItem.REGISTER_WARRANTY -> context.getString(R.string.bot_menu_register_prompt)
            BotMenuItem.WARRANTY_STATUS -> warrantyStatusText(context, phone)
            BotMenuItem.SEND_PRODUCT_PHOTO -> context.getString(R.string.bot_photo_verify_none)
            BotMenuItem.INVITE_FRIENDS -> inviteFriendsText(context, channel, phone, settingsStore)
            BotMenuItem.MY_SCORE -> myScoreText(context, phone, settingsStore)
            BotMenuItem.SUPPORT -> context.getString(R.string.bot_menu_support)
            BotMenuItem.HELP -> context.getString(R.string.bot_menu_help)
        }
    }

    private fun myScoreText(
        context: Context,
        phone: String,
        settingsStore: SettingsStore,
    ): String {
        val referralService = ReferralService.getInstance(context)
        val stats = runBlocking {
            withContext(Dispatchers.IO) {
                val code = referralService.ensureCode(phone)
                val balance = referralService.balanceFor(phone)
                val downline = referralService.directDownlineCount(phone)
                Triple(code, balance, downline)
            }
        }
        return context.getString(
            R.string.bot_menu_my_score,
            stats.second.toString(),
            stats.third.toString(),
            stats.first ?: "—",
        )
    }

    private fun inviteFriendsText(
        context: Context,
        channel: BotMenuChannel,
        phone: String,
        settingsStore: SettingsStore,
    ): String {
        val referralEnabled = settingsStore.isReferralEnabled()
        val referralService = ReferralService.getInstance(context)
        val code = if (referralEnabled) {
            runBlocking { withContext(Dispatchers.IO) { referralService.ensureCode(phone) } }
        } else {
            null
        }
        val linkText = when (channel) {
            BotMenuChannel.RUBIKA -> BotInviteHelper.rubikaInviteText(settingsStore, context, code)
            BotMenuChannel.TELEGRAM -> BotInviteHelper.telegramInviteText(settingsStore, context, code)
            BotMenuChannel.BALE -> BotInviteHelper.baleInviteText(settingsStore, context, code)
        }
        val intro = context.getString(R.string.bot_menu_invite_friends_intro)
        val builder = StringBuilder(intro)
        if (!linkText.isNullOrBlank()) {
            builder.append("\n\n").append(linkText)
        }
        if (referralEnabled) {
            val stats = runBlocking {
                withContext(Dispatchers.IO) {
                    val balance = referralService.balanceFor(phone)
                    val downline = referralService.directDownlineCount(phone)
                    balance to downline
                }
            }
            builder.append("\n\n").append(
                context.getString(
                    R.string.bot_menu_invite_referral_stats,
                    stats.first.toString(),
                    stats.second.toString(),
                ),
            )
        }
        return builder.toString()
    }

    private fun warrantyStatusText(context: Context, phone: String): String {
        val normalized = PhoneUtils.formatForDisplay(phone) ?: phone.trim()
        val rows = runBlocking {
            withContext(Dispatchers.IO) {
                GatewayDatabase.getInstance(context).serialDao().listCompletedByPhone(normalized)
            }
        }
        if (rows.isEmpty()) {
            return context.getString(R.string.bot_menu_warranty_empty)
        }
        val body = rows.joinToString("\n\n") { formatSerialRow(context, it) }
        return context.getString(R.string.bot_menu_warranty_header) + "\n\n" + body
    }

    private fun formatSerialRow(context: Context, row: SerialEntity): String {
        val date = row.date?.takeIf { it.isNotBlank() } ?: "—"
        val city = row.city?.takeIf { it.isNotBlank() }
        val km = row.km
        val warranty = runBlocking {
            withContext(Dispatchers.IO) {
                val groupId = row.groupId ?: return@withContext null
                val group = GatewayDatabase.getInstance(context).serialGroupDao().findById(groupId)
                SerialGroupWarranty.formatFa(group?.warrantyMonths, group?.warrantyKm)
            }
        }
        val base = if (city != null || km != null) {
            context.getString(
                R.string.bot_menu_warranty_item_extra,
                row.serial,
                date,
                city ?: "—",
                km?.toString() ?: "—",
            )
        } else {
            context.getString(R.string.bot_menu_warranty_item, row.serial, date)
        }
        return if (warranty.isNullOrBlank()) {
            base
        } else {
            "$base\n  گارانتی: $warranty"
        }
    }
}
