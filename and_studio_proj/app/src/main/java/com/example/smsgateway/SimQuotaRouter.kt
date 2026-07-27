package com.example.smsgateway

import android.content.Context
import android.telephony.SubscriptionManager

/**
 * Picks a SIM for outbound SMS with rolling daily quota and optional backup failover.
 */
object SimQuotaRouter {
    data class SimQuotaStatus(
        val subscriptionId: Int,
        val label: String,
        val role: String,
        val sent: Int,
        val limit: Int,
        val remaining: Int,
    )

    fun estimateConversationBudget(context: Context): Int {
        val settings = SettingsStore.getInstance(context)
        val config = settings.getSmsProcessingConfig()
        val replyBudget = when (config.mode) {
            SmsProcessingMode.SERIAL_ONLY -> 1
            SmsProcessingMode.COMBINED_PATTERN -> 2
            SmsProcessingMode.STEP_BY_STEP -> when (config.stepFlowType) {
                StepFlowType.COMBINED_SECOND -> 2
                StepFlowType.SEQUENTIAL -> {
                    val afterSerial = config.stepFieldsAfterSerial().size
                    1 + afterSerial.coerceAtLeast(0)
                }
            }
        }
        return replyBudget + countEnabledInviteSms(settings)
    }

    fun countEnabledInviteSms(settings: SettingsStore): Int {
        var count = 0
        if (settings.isRubikaInviteEnabled()) count++
        if (settings.isTelegramInviteEnabled()) count++
        if (settings.isBaleInviteEnabled()) count++
        // Photo-verify invite is per-serial; reserve one slot for registration flows.
        return count + 1
    }

    /**
     * Eligible SIMs for outbound: primary (enabled) first, then backups when allowed.
     */
    fun eligibleSims(context: Context): List<Pair<SimInfo, String>> {
        val settings = SettingsStore.getInstance(context)
        val available = SimCardHelper.getAvailableSims(context)
        if (available.isEmpty()) return emptyList()

        val primaryIds = settings.getEnabledSubscriptionIds()
        val primary = available.filter { sim ->
            primaryIds.isEmpty() || settings.isSubscriptionEnabled(sim.subscriptionId)
        }.map { it to "primary" }

        if (!settings.isBackupSimsEnabled()) {
            return primary.ifEmpty { available.map { it to "primary" } }
        }

        val primaryKeys = primary.flatMap {
            SimCardHelper.subscriptionMatchKeys(context, it.first.subscriptionId)
        }.toSet()

        val backups = available.filter { sim ->
            SimCardHelper.subscriptionMatchKeys(context, sim.subscriptionId)
                .none { it in primaryKeys }
        }.map { it to "backup" }

        return primary + backups
    }

    fun statusList(context: Context): List<SimQuotaStatus> {
        val quota = SimSmsQuotaStore.getInstance(context)
        val limit = quota.getLimit()
        return eligibleSims(context).map { (sim, role) ->
            val sent = quota.sentInLast24hBlocking(sim.subscriptionId)
            SimQuotaStatus(
                subscriptionId = sim.subscriptionId,
                label = sim.displayName,
                role = role,
                sent = sent,
                limit = limit,
                remaining = (limit - sent).coerceAtLeast(0),
            )
        }
    }

    /**
     * @return subscription id with enough remaining quota, or null if none can send.
     */
    fun pickForBudget(
        context: Context,
        preferred: Int?,
        budget: Int,
    ): Int? {
        val quota = SimSmsQuotaStore.getInstance(context)
        val needed = budget.coerceAtLeast(1)
        val eligible = eligibleSims(context)
        if (eligible.isEmpty()) {
            return preferred?.takeIf {
                it != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                    quota.remainingBlocking(it) > 0
            }
        }

        fun matchesPreferred(sim: SimInfo): Boolean {
            if (preferred == null || preferred == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return false
            }
            val preferredKeys = SimCardHelper.subscriptionMatchKeys(context, preferred)
            return SimCardHelper.subscriptionMatchKeys(context, sim.subscriptionId)
                .any { it in preferredKeys }
        }

        val preferredSim = eligible.firstOrNull { matchesPreferred(it.first) }
        if (preferredSim != null) {
            val remaining = quota.remainingBlocking(preferredSim.first.subscriptionId)
            if (remaining >= needed) {
                return preferredSim.first.subscriptionId
            }
        }

        val withBudget = eligible
            .map { it.first to quota.remainingBlocking(it.first.subscriptionId) }
            .filter { it.second >= needed }
            .maxByOrNull { it.second }
        if (withBudget != null) {
            return withBudget.first.subscriptionId
        }

        // Fall back to the SIM with the most remaining (must be > 0 to send at least one).
        return eligible
            .map { it.first to quota.remainingBlocking(it.first.subscriptionId) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?.subscriptionId
    }

    fun pickForSingleSend(context: Context, preferred: Int? = null): Int? =
        pickForBudget(context, preferred, budget = 1)

    fun hasAnyQuota(context: Context): Boolean =
        eligibleSims(context).any {
            SimSmsQuotaStore.getInstance(context).remainingBlocking(it.first.subscriptionId) > 0
        }
}
