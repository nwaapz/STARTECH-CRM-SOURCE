package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.CustomerReferralEntity
import com.example.smsgateway.data.CustomerScoreEventEntity
import com.example.smsgateway.data.CustomerScoreReason
import com.example.smsgateway.data.CustomerScoreSourceType
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.ReferralPendingEntity
import com.example.smsgateway.lan.LanTraceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Tunable parameters for the multi-level referral reward system. */
data class ReferralSettings(
    val enabled: Boolean = false,
    val basePoints: Int = DEFAULT_BASE_POINTS,
    /** Percent of the previous level's points awarded at the next level up (0..100). */
    val decayPercent: Int = DEFAULT_DECAY_PERCENT,
    /** Safety cap on how many levels up the chain gets rewarded. */
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    fun normalized(): ReferralSettings = copy(
        basePoints = basePoints.coerceIn(0, 1_000_000),
        decayPercent = decayPercent.coerceIn(0, 100),
        maxDepth = maxDepth.coerceIn(1, MAX_ALLOWED_DEPTH),
    )

    /** Points awarded to the upline member sitting [level] steps above the buyer (level >= 1). */
    fun pointsForLevel(level: Int): Int {
        if (level < 1) return 0
        var points = basePoints.toDouble()
        repeat(level - 1) { points = points * decayPercent / 100.0 }
        return floor(points).toInt()
    }

    companion object {
        const val DEFAULT_BASE_POINTS = 50
        const val DEFAULT_DECAY_PERCENT = 50
        const val DEFAULT_MAX_DEPTH = 10
        const val MAX_ALLOWED_DEPTH = 50
    }
}

/**
 * Multi-level ("pyramid") referral engine.
 *
 * Each customer stores only their DIRECT inviter ([CustomerReferralEntity.referredBy]);
 * the endless upline chain is reconstructed by walking those pointers. When a referred
 * customer makes a purchase, [onPurchase] walks up the chain and awards decaying points
 * to every ancestor (bounded by [ReferralSettings.maxDepth] and a zero-points floor).
 */
class ReferralService private constructor(
    private val appContext: Context,
    private val database: GatewayDatabase,
    private val settingsStore: SettingsStore,
) {
    private val referralDao get() = database.customerReferralDao()
    private val scoreDao get() = database.customerScoreEventDao()
    private val pendingDao get() = database.referralPendingDao()
    private val serialDao get() = database.serialDao()

    fun settings(): ReferralSettings = settingsStore.getReferralSettings()

    /** Returns (creating if needed) the customer's own referral code. */
    suspend fun ensureCode(phone: String): String? = withContext(Dispatchers.IO) {
        val normalized = normalize(phone) ?: return@withContext null
        referralDao.findByPhone(normalized)?.let { return@withContext it.referralCode }
        var code = generateCode()
        var attempts = 0
        while (referralDao.findByCode(code) != null && attempts < 12) {
            code = generateCode()
            attempts++
        }
        val now = System.currentTimeMillis()
        referralDao.insert(
            CustomerReferralEntity(
                phone = normalized,
                referralCode = code,
                createdAt = now,
                updatedAt = now,
            ),
        )
        // Re-read to survive a concurrent insert race (IGNORE conflict).
        referralDao.findByPhone(normalized)?.referralCode ?: code
    }

    suspend fun balanceFor(phone: String): Int = withContext(Dispatchers.IO) {
        val normalized = normalize(phone) ?: return@withContext 0
        // Photo approvals historically only stored scoreAwarded on the submission row;
        // sync them into the ledger so "My Score" (and CRM) stay consistent.
        syncApprovedPhotoScores(normalized)
        // Registrations in non-verify groups may predate the club-score-on-register path.
        syncRegistrationScores(normalized)
        scoreDao.balanceFor(normalized)
    }

    /**
     * Awards club/product points to the buyer themselves (level 0).
     * Idempotent via the unique (phone, sourceType, sourceId, level) index.
     */
    suspend fun awardOwnPoints(
        phone: String,
        points: Int,
        sourceType: String,
        sourceId: Long,
        reason: String = CustomerScoreReason.PURCHASE,
    ): Boolean = withContext(Dispatchers.IO) {
        if (points < 1 || sourceId < 1) return@withContext false
        val normalized = normalize(phone) ?: return@withContext false
        ensureCode(normalized)
        val insertedId = scoreDao.insert(
            CustomerScoreEventEntity(
                phone = normalized,
                points = points,
                reason = reason,
                level = 0,
                sourcePhone = normalized,
                sourceType = sourceType,
                sourceId = sourceId,
                createdAt = System.currentTimeMillis(),
            ),
        )
        insertedId != -1L
    }

    /** Backfills ledger rows for approved photo submissions that never wrote events. */
    private suspend fun syncApprovedPhotoScores(phone: String) {
        val rows = database.serialPhotoSubmissionDao().listApprovedWithScore(phone)
        for (row in rows) {
            if (row.scoreAwarded < 1) continue
            scoreDao.insert(
                CustomerScoreEventEntity(
                    phone = phone,
                    points = row.scoreAwarded,
                    reason = CustomerScoreReason.PURCHASE,
                    level = 0,
                    sourcePhone = phone,
                    sourceType = CustomerScoreSourceType.PHOTO,
                    sourceId = row.id,
                    createdAt = row.reviewedAt ?: row.createdAt,
                ),
            )
        }
    }

    /**
     * Backfills club score for completed registrations whose group does not require
     * image verification (score should have been awarded at registration time).
     */
    private suspend fun syncRegistrationScores(phone: String) {
        val serials = serialDao.listCompletedByPhone(phone)
        for (serial in serials) {
            val groupId = serial.groupId ?: continue
            val group = database.serialGroupDao().findById(groupId) ?: continue
            if (group.imageVerificationRequired) continue
            val score = group.score.coerceAtLeast(0)
            if (score < 1) continue
            scoreDao.insert(
                CustomerScoreEventEntity(
                    phone = phone,
                    points = score,
                    reason = CustomerScoreReason.PURCHASE,
                    level = 0,
                    sourcePhone = phone,
                    sourceType = CustomerScoreSourceType.SERIAL,
                    sourceId = serial.id,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun directDownlineCount(phone: String): Int = withContext(Dispatchers.IO) {
        val normalized = normalize(phone) ?: return@withContext 0
        referralDao.countDirectDownline(normalized)
    }

    /** Persists a referral code seen before the customer's phone is known. */
    suspend fun rememberPending(channel: String, chatId: String, rawCode: String) {
        val code = sanitizeCode(rawCode) ?: return
        withContext(Dispatchers.IO) {
            pendingDao.insert(
                ReferralPendingEntity(
                    channel = channel,
                    chatId = chatId,
                    code = code,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Attaches any pending referral code for this chat to the now-known phone. */
    suspend fun attachPending(channel: String, chatId: String, phone: String) {
        withContext(Dispatchers.IO) {
            val code = pendingDao.findCode(channel, chatId) ?: return@withContext
            val attached = attachReferral(phone, code)
            if (attached || normalize(phone) != null) {
                pendingDao.deleteFor(channel, chatId)
            }
        }
    }

    /**
     * Sets the direct referral edge for [newUserPhone] using [rawCode].
     * Guards: referral enabled, valid code, not self, target is a brand-new customer,
     * edge not already set, and no cycle. Returns true when the edge was written.
     */
    suspend fun attachReferral(newUserPhone: String, rawCode: String): Boolean = withContext(Dispatchers.IO) {
        if (!settingsStore.isReferralEnabled()) return@withContext false
        val child = normalize(newUserPhone) ?: return@withContext false
        val code = sanitizeCode(rawCode) ?: return@withContext false
        val parentRow = referralDao.findByCode(code) ?: return@withContext false
        val parent = parentRow.phone
        if (parent == child) return@withContext false

        ensureCode(child)
        val childRow = referralDao.findByPhone(child) ?: return@withContext false
        if (!childRow.referredBy.isNullOrBlank()) return@withContext false

        // Only genuinely new customers can be attributed to an inviter.
        if (serialDao.countCompletedRegistrationsByPhone(child) > 0) return@withContext false

        // Cycle guard: child must not already be an ancestor of parent.
        var cursor: String? = parent
        var depth = 0
        while (cursor != null && depth < ReferralSettings.MAX_ALLOWED_DEPTH + 5) {
            if (cursor == child) return@withContext false
            cursor = referralDao.findByPhone(cursor)?.referredBy
            depth++
        }

        val updated = referralDao.setReferredBy(child, parent, code, System.currentTimeMillis())
        if (updated > 0) {
            LanTraceLog.i(TAG, "Referral edge set child=$child parent=$parent")
            true
        } else {
            false
        }
    }

    /**
     * Awards decaying points up the referral chain for a purchase made by [buyerPhone].
     * Idempotent per (recipient, sourceType, sourceId, level).
     */
    suspend fun onPurchase(
        buyerPhone: String,
        sourceType: String,
        sourceId: Long,
    ) = withContext(Dispatchers.IO) {
        val config = settingsStore.getReferralSettings()
        if (!config.enabled) return@withContext
        val buyer = normalize(buyerPhone) ?: return@withContext
        // Ensure the buyer has a code so they can invite others too.
        ensureCode(buyer)

        val now = System.currentTimeMillis()
        val visited = mutableSetOf(buyer)
        var current: String? = referralDao.findByPhone(buyer)?.referredBy?.let { normalize(it) }
        var level = 1
        while (current != null && level <= config.maxDepth) {
            val recipient = current
            if (!visited.add(recipient)) break
            val points = config.pointsForLevel(level)
            if (points < 1) break
            val insertedId = scoreDao.insert(
                CustomerScoreEventEntity(
                    phone = recipient,
                    points = points,
                    reason = CustomerScoreReason.REFERRAL,
                    level = level,
                    sourcePhone = buyer,
                    sourceType = sourceType,
                    sourceId = sourceId,
                    createdAt = now,
                ),
            )
            // insertedId == -1 means the award already existed (idempotent retry): skip notify.
            if (insertedId != -1L) {
                notifyUpline(recipient, points, level)
            }
            current = referralDao.findByPhone(recipient)?.referredBy?.let { normalize(it) }
            level++
        }
    }

    /** Best-effort push to an upline member on whichever bot channel they are reachable. */
    private suspend fun notifyUpline(phone: String, points: Int, level: Int) {
        try {
            val total = scoreDao.balanceFor(phone)
            val message = appContext.getString(
                R.string.referral_award_notification,
                points.toString(),
                level.toString(),
                total.toString(),
            )
            sendOnFirstAvailableChannel(phone, message)
        } catch (error: Exception) {
            LanTraceLog.e(TAG, "Referral notify failed for $phone", error)
        }
    }

    private fun sendOnFirstAvailableChannel(phone: String, message: String): Boolean {
        val rubikaToken = settingsStore.getRubikaToken().trim()
        if (rubikaToken.isNotEmpty()) {
            BotChatIdResolver.resolveRubika(database, phone)?.let { chatId ->
                if (runCatching { RubikaApi.getInstance().sendMessage(rubikaToken, chatId, message) }.isSuccess) {
                    return true
                }
            }
        }
        val telegramToken = settingsStore.getTelegramToken().trim()
        if (telegramToken.isNotEmpty()) {
            BotChatIdResolver.resolveTelegram(database, phone)?.let { chatId ->
                if (runCatching { TelegramApi.getInstance().sendMessage(telegramToken, chatId, message) }.isSuccess) {
                    return true
                }
            }
        }
        val baleToken = settingsStore.getBaleToken().trim()
        if (baleToken.isNotEmpty()) {
            BotChatIdResolver.resolveBale(database, phone)?.let { chatId ->
                if (runCatching { BaleApi.getInstance().sendMessage(baleToken, chatId, message) }.isSuccess) {
                    return true
                }
            }
        }
        return false
    }

    private fun normalize(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        return PhoneUtils.formatForDisplay(phone.trim())?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val withoutPrefix = if (trimmed.startsWith(CODE_PREFIX, ignoreCase = true)) {
            trimmed.substring(CODE_PREFIX.length)
        } else {
            trimmed
        }
        val cleaned = withoutPrefix.filter { it.isLetterOrDigit() }
        return cleaned.takeIf { it.length in 4..24 }?.uppercase()
    }

    private fun generateCode(): String {
        return buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET.random()) }
        }
    }

    companion object {
        private const val TAG = "ReferralService"
        const val CODE_PREFIX = "ref_"
        private const val CODE_LENGTH = 8
        // Base32-ish alphabet without easily confused characters (0/O, 1/I).
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private val startPayloadRegex = Regex(
            """ref_([A-Za-z0-9]{4,24})""",
            RegexOption.IGNORE_CASE,
        )

        /** Extracts a "ref_XXXX" code from arbitrary bot text (incl. "/start ref_XXXX"). */
        fun extractCode(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val match = startPayloadRegex.find(text) ?: return null
            return CODE_PREFIX + match.groupValues[1]
        }

        @Volatile
        private var instance: ReferralService? = null

        fun getInstance(context: Context): ReferralService {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: ReferralService(
                    appContext,
                    GatewayDatabase.getInstance(appContext),
                    SettingsStore.getInstance(appContext),
                ).also { instance = it }
            }
        }
    }
}
