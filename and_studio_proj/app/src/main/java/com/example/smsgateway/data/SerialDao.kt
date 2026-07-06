package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SerialDao {
    @Query("SELECT * FROM gateway_db WHERE UPPER(serial) = UPPER(:serial) LIMIT 1")
    suspend fun findBySerial(serial: String): SerialEntity?

    @Query("SELECT * FROM gateway_db WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SerialEntity?

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE phone = :phone
          AND phone IS NOT NULL AND phone != ''
          AND (city IS NULL OR city = '' OR km IS NULL)
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun findIncompleteByPhone(phone: String): SerialEntity?

    @Query(
        """
        UPDATE gateway_db
        SET city = :city, km = :km, registrationFieldsComplete = 1
        WHERE UPPER(serial) = UPPER(:serial)
          AND phone = :phone
        """,
    )
    suspend fun updateRegistrationFields(
        phone: String,
        serial: String,
        city: String?,
        km: Int?,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET completionReminderSentAt = :sentAt
        WHERE UPPER(serial) = UPPER(:serial)
        """,
    )
    suspend fun markCompletionReminderSent(serial: String, sentAt: Long): Int

    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone, date = :date, registrationFieldsComplete = 1, registrationSubscriptionId = :subscriptionId
        WHERE UPPER(serial) = UPPER(:serial)
          AND (phone IS NULL OR phone = '')
        """,
    )
    suspend fun registerSerial(
        phone: String,
        date: String,
        serial: String,
        subscriptionId: Int?,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone, date = :date, registrationFieldsComplete = 0, registrationSubscriptionId = :subscriptionId
        WHERE UPPER(serial) = UPPER(:serial)
          AND (phone IS NULL OR phone = '')
        """,
    )
    suspend fun registerPartialSerial(
        phone: String,
        date: String,
        serial: String,
        subscriptionId: Int?,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET registrationFieldsComplete = 0, registrationSubscriptionId = :subscriptionId
        WHERE UPPER(serial) = UPPER(:serial)
          AND phone = :phone
        """,
    )
    suspend fun markRegistrationIncomplete(
        serial: String,
        phone: String,
        subscriptionId: Int?,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone, date = :date, city = :city, km = :km,
            registrationFieldsComplete = CASE
                WHEN (:city IS NOT NULL AND :city != '') AND (:km IS NOT NULL) THEN 1
                ELSE 0
            END,
            registrationSubscriptionId = :subscriptionId
        WHERE UPPER(serial) = UPPER(:serial)
          AND (phone IS NULL OR phone = '')
        """,
    )
    suspend fun registerSerialWithFields(
        phone: String,
        date: String,
        serial: String,
        city: String?,
        km: Int?,
        subscriptionId: Int?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<SerialEntity>): List<Long>

    @Query("SELECT UPPER(serial) FROM gateway_db WHERE UPPER(serial) IN (:upperSerials)")
    suspend fun findExistingUpperSerials(upperSerials: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM gateway_db")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM gateway_db WHERE phone IS NULL OR phone = ''")
    suspend fun countUnused(): Int

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE phone IS NOT NULL AND phone != ''
        """,
    )
    suspend fun countRegistered(): Int

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE (:fromDate = '' OR substr(date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :search = ''
            OR serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                phone LIKE '%' || :phoneNorm || '%'
                OR phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND phone LIKE '%' || :search || '%')
          )
        ORDER BY id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listAllFiltered(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
        limit: Int,
        offset: Int,
    ): List<SerialEntity>

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE (:fromDate = '' OR substr(date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :search = ''
            OR serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                phone LIKE '%' || :phoneNorm || '%'
                OR phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND phone LIKE '%' || :search || '%')
          )
        """,
    )
    suspend fun countAllFiltered(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
    ): Int

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE phone IS NOT NULL AND phone != ''
          AND (:fromDate = '' OR substr(date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :search = ''
            OR serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                phone LIKE '%' || :phoneNorm || '%'
                OR phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND phone LIKE '%' || :search || '%')
          )
        ORDER BY id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listRegisteredFiltered(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
        limit: Int,
        offset: Int,
    ): List<SerialEntity>

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE phone IS NOT NULL AND phone != ''
          AND (:fromDate = '' OR substr(date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :search = ''
            OR serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                phone LIKE '%' || :phoneNorm || '%'
                OR phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND phone LIKE '%' || :search || '%')
          )
        """,
    )
    suspend fun countRegisteredFiltered(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
    ): Int

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE (phone IS NULL OR phone = '')
          AND (:search = '' OR serial LIKE '%' || :search || '%')
        ORDER BY id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listUnusedFiltered(
        search: String,
        limit: Int,
        offset: Int,
    ): List<SerialEntity>

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE (phone IS NULL OR phone = '')
          AND (:search = '' OR serial LIKE '%' || :search || '%')
        """,
    )
    suspend fun countUnusedFiltered(search: String): Int

    @Query("DELETE FROM gateway_db WHERE groupId = :groupId")
    suspend fun deleteByGroupId(groupId: Long): Int

    @Query(
        """
        SELECT phone, city FROM gateway_db
        WHERE phone IS NOT NULL AND phone != ''
          AND (:fromDate = '' OR substr(date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(date, 1, 10) <= substr(:toDate, 1, 10))
        GROUP BY phone
        """,
    )
    suspend fun listDistinctRegisteredPhones(
        fromDate: String = "",
        toDate: String = "",
    ): List<RegisteredPhoneCityRow>

    @Query(
        """
        SELECT phone, city FROM gateway_db
        WHERE phone IS NOT NULL AND phone != ''
          AND groupId IN (:groupIds)
          AND (:fromDate = '' OR substr(date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(date, 1, 10) <= substr(:toDate, 1, 10))
        GROUP BY phone
        """,
    )
    suspend fun listDistinctRegisteredPhonesByGroupIds(
        groupIds: List<Long>,
        fromDate: String = "",
        toDate: String = "",
    ): List<RegisteredPhoneCityRow>
}
