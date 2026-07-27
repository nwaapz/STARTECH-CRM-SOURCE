package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CustomerScoreEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CustomerScoreEventEntity): Long

    @Query("SELECT COALESCE(SUM(points), 0) FROM customer_score_events WHERE phone = :phone")
    suspend fun balanceFor(phone: String): Int

    @Query(
        """
        SELECT COALESCE(SUM(points), 0) FROM customer_score_events
        WHERE phone = :phone AND reason = :reason
        """,
    )
    suspend fun balanceForReason(phone: String, reason: String): Int

    @Query(
        """
        SELECT * FROM customer_score_events
        WHERE phone = :phone
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listForPhone(phone: String, limit: Int, offset: Int): List<CustomerScoreEventEntity>

    /** Phones with positive lottery/club score for draw pool. */
    @Query(
        """
        SELECT phone AS phone, COALESCE(SUM(points), 0) AS score
        FROM customer_score_events
        GROUP BY phone
        HAVING COALESCE(SUM(points), 0) > 0
        ORDER BY score DESC
        """,
    )
    suspend fun listPositiveBalances(): List<PhoneScoreRow>
}

data class PhoneScoreRow(
    val phone: String,
    val score: Int,
)
