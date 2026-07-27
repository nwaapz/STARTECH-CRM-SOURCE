package com.example.smsgateway

import android.content.Context
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.LotterySuspendedPhoneEntity
import com.example.smsgateway.data.LotteryWinnerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class LotteryParticipant(
    val phone: String,
    val score: Int,
    val city: String?,
)

class LotteryService(context: Context) {
    private val appContext = context.applicationContext
    private val db = GatewayDatabase.getInstance(appContext)
    private val scoreDao = db.customerScoreEventDao()
    private val winnerDao = db.lotteryWinnerDao()
    private val suspendedDao = db.lotterySuspendedPhoneDao()
    private val serialDao = db.serialDao()
    private val settings = SettingsStore.getInstance(appContext)

    private fun normPhone(phone: String): String = PhoneUtils.normalizeForSmsSend(phone)

    suspend fun listParticipants(
        city: String? = null,
        searchPhone: String? = null,
    ): List<LotteryParticipant> = withContext(Dispatchers.IO) {
        val referral = ReferralService.getInstance(appContext)
        val suspended = suspendedDao.listPhones().map { normPhone(it) }.toSet()
        val rows = scoreDao.listPositiveBalances()
        rows.mapNotNull { row ->
            val phone = normPhone(row.phone)
            if (phone in suspended) return@mapNotNull null
            val score = referral.balanceFor(phone).takeIf { it > 0 } ?: row.score
            if (score <= 0) return@mapNotNull null
            val cityName = serialDao.listCompletedByPhone(phone)
                .mapNotNull { it.city?.trim()?.takeIf { c -> c.isNotEmpty() } }
                .firstOrNull()
            if (!city.isNullOrBlank() && !cityName.orEmpty().contains(city.trim(), ignoreCase = true)) {
                return@mapNotNull null
            }
            if (!searchPhone.isNullOrBlank()) {
                val needle = PhoneUtils.normalizeSearchPrefix(searchPhone)
                val hay = PhoneUtils.normalizeSearchPrefix(phone)
                if (needle.isNotEmpty() && !hay.contains(needle)) return@mapNotNull null
            }
            LotteryParticipant(phone = phone, score = score, city = cityName)
        }.sortedByDescending { it.score }
    }

    suspend fun drawWeighted(participants: List<LotteryParticipant>): LotteryParticipant? {
        if (participants.isEmpty()) return null
        val preset = settings.getLotteryPresetPhone()?.let { normPhone(it) }?.takeIf { it.isNotBlank() }
        if (!preset.isNullOrBlank()) {
            participants.firstOrNull { it.phone == preset }?.let { return it }
        }
        val total = participants.sumOf { it.score.toLong() }
        if (total <= 0L) return participants.random()
        var ticket = Random.nextLong(total)
        for (p in participants) {
            ticket -= p.score
            if (ticket < 0) return p
        }
        return participants.last()
    }

    suspend fun confirmWinner(phone: String): LotteryWinnerEntity = withContext(Dispatchers.IO) {
        val normalized = normPhone(phone)
        val participants = listParticipants()
        val match = participants.firstOrNull { it.phone == normalized }
        val score = match?.score ?: ReferralService.getInstance(appContext).balanceFor(normalized)
        val city = match?.city
        val now = System.currentTimeMillis()
        val entity = LotteryWinnerEntity(
            phone = normalized,
            city = city,
            scoreAtWin = score,
            dateJalali = JalaliHelper.nowJalaliDateTime(),
            wonAt = now,
            syncUpdatedMs = now,
        )
        val id = winnerDao.insert(entity)
        val balance = scoreDao.balanceFor(normalized)
        if (balance > 0) {
            scoreDao.insert(
                com.example.smsgateway.data.CustomerScoreEventEntity(
                    phone = normalized,
                    points = -balance,
                    reason = com.example.smsgateway.data.CustomerScoreReason.MANUAL,
                    level = 0,
                    sourcePhone = null,
                    sourceType = com.example.smsgateway.data.CustomerScoreSourceType.MANUAL,
                    sourceId = now,
                    createdAt = now,
                ),
            )
        }
        settings.clearLotteryPresetPhone()
        winnerDao.list(1, 0).firstOrNull { it.id == id } ?: entity.copy(id = id)
    }

    suspend fun listWinners(page: Int, limit: Int): Pair<List<LotteryWinnerEntity>, Int> =
        withContext(Dispatchers.IO) {
            val offset = (page - 1) * limit
            winnerDao.list(limit, offset) to winnerDao.count()
        }

    suspend fun deleteWinner(id: Long) = withContext(Dispatchers.IO) {
        winnerDao.deleteById(id)
    }

    suspend fun suspendPhone(phone: String, reason: String?) = withContext(Dispatchers.IO) {
        val normalized = normPhone(phone)
        suspendedDao.upsert(
            LotterySuspendedPhoneEntity(
                phone = normalized,
                reason = reason,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unsuspendPhone(phone: String) = withContext(Dispatchers.IO) {
        suspendedDao.delete(normPhone(phone))
    }

    suspend fun listSuspended(): List<LotterySuspendedPhoneEntity> = withContext(Dispatchers.IO) {
        suspendedDao.listAll()
    }

    companion object {
        @Volatile
        private var instance: LotteryService? = null

        fun getInstance(context: Context): LotteryService {
            return instance ?: synchronized(this) {
                instance ?: LotteryService(context.applicationContext).also { instance = it }
            }
        }
    }
}
