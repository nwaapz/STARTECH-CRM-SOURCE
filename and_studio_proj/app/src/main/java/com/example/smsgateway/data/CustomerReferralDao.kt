package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Aggregated referral row for CRM listing. */
data class ReferralCustomerRow(
    val phone: String,
    val referralCode: String,
    val referredBy: String?,
    val directDownline: Int,
    val score: Int,
    val createdAt: Long,
)

@Dao
interface CustomerReferralDao {
    @Query("SELECT * FROM customer_referrals WHERE phone = :phone LIMIT 1")
    suspend fun findByPhone(phone: String): CustomerReferralEntity?

    @Query("SELECT * FROM customer_referrals WHERE referralCode = :code LIMIT 1")
    suspend fun findByCode(code: String): CustomerReferralEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CustomerReferralEntity): Long

    @Query(
        """
        UPDATE customer_referrals
        SET referredBy = :referredBy, referredByCode = :referredByCode, updatedAt = :updatedAt
        WHERE phone = :phone
        """,
    )
    suspend fun setReferredBy(
        phone: String,
        referredBy: String,
        referredByCode: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM customer_referrals WHERE referredBy = :phone")
    suspend fun countDirectDownline(phone: String): Int

    @Query("SELECT * FROM customer_referrals WHERE referredBy = :phone ORDER BY createdAt DESC")
    suspend fun listDirectDownline(phone: String): List<CustomerReferralEntity>

    @Query("SELECT COUNT(*) FROM customer_referrals")
    suspend fun countAll(): Int

    @Query(
        """
        SELECT r.phone AS phone,
               r.referralCode AS referralCode,
               r.referredBy AS referredBy,
               (SELECT COUNT(*) FROM customer_referrals c WHERE c.referredBy = r.phone) AS directDownline,
               COALESCE((SELECT SUM(e.points) FROM customer_score_events e WHERE e.phone = r.phone), 0) AS score,
               r.createdAt AS createdAt
        FROM customer_referrals r
        WHERE (:search = '' OR r.phone LIKE '%' || :search || '%' OR r.referralCode LIKE '%' || :search || '%')
        ORDER BY score DESC, r.createdAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listReferralCustomers(
        search: String,
        limit: Int,
        offset: Int,
    ): List<ReferralCustomerRow>

    @Query(
        """
        SELECT COUNT(*) FROM customer_referrals r
        WHERE (:search = '' OR r.phone LIKE '%' || :search || '%' OR r.referralCode LIKE '%' || :search || '%')
        """,
    )
    suspend fun countReferralCustomers(search: String): Int
}
