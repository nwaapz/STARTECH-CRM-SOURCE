package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

data class SerialErrorClientSummaryRow(
    val phone: String,
    val latestAt: Long,
    val latestSerial: String,
    val latestReason: String,
    val attemptCount: Int,
)

@Dao
interface SerialRegistrationErrorDao {
    @Insert
    suspend fun insert(error: SerialRegistrationErrorEntity): Long

    @Query("SELECT COUNT(DISTINCT phone) FROM serial_registration_errors")
    suspend fun countDistinctClients(): Int

    @Query(
        """
        SELECT
            e.phone AS phone,
            MAX(e.at) AS latestAt,
            (
                SELECT serial FROM serial_registration_errors e2
                WHERE e2.phone = e.phone
                ORDER BY e2.at DESC
                LIMIT 1
            ) AS latestSerial,
            (
                SELECT reason FROM serial_registration_errors e2
                WHERE e2.phone = e.phone
                ORDER BY e2.at DESC
                LIMIT 1
            ) AS latestReason,
            COUNT(*) AS attemptCount
        FROM serial_registration_errors e
        GROUP BY e.phone
        ORDER BY latestAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listClientSummaries(offset: Int, limit: Int): List<SerialErrorClientSummaryRow>

    @Query(
        """
        SELECT * FROM serial_registration_errors
        WHERE phone = :phone
        ORDER BY at DESC
        """,
    )
    suspend fun listByPhone(phone: String): List<SerialRegistrationErrorEntity>

    @Query(
        """
        SELECT DISTINCT e.phone AS phone
        FROM serial_registration_errors e
        WHERE e.phone NOT IN (
            SELECT phone FROM gateway_db
            WHERE phone IS NOT NULL AND phone != ''
        )
        """,
    )
    suspend fun listDistinctErrorPhonesExcludingRegistered(): List<String>
}
