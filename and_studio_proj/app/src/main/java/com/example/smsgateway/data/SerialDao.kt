package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SerialDao {
    /**
     * Exact match on the unique [serial] index.
     * Callers must pass [com.example.smsgateway.SerialNormalizer.normalizeSerial] output
     * (trimmed uppercase) — do not wrap the column in UPPER() or SQLite cannot use the index.
     */
    @Query("SELECT * FROM gateway_db WHERE serial = :serial LIMIT 1")
    suspend fun findBySerial(serial: String): SerialEntity?

    /** Exact match; [upperSerials] must already be normalized uppercase. */
    @Query("SELECT * FROM gateway_db WHERE serial IN (:upperSerials)")
    suspend fun findByUpperSerials(upperSerials: List<String>): List<SerialEntity>

    @Query("SELECT * FROM gateway_db WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SerialEntity?

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE phone = :phone
          AND phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND registrationFieldsComplete = 0
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun findIncompleteByPhone(phone: String): SerialEntity?

    @Query(
        """
        SELECT city FROM gateway_db
        WHERE phone = :phone
          AND phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND city IS NOT NULL AND TRIM(city) != ''
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestCityByPhone(phone: String): String?

    @Query(
        """
        SELECT phone, city FROM gateway_db
        WHERE phone IN (:phones)
          AND city IS NOT NULL AND TRIM(city) != ''
        ORDER BY id DESC
        """,
    )
    suspend fun listCitiesByPhones(phones: List<String>): List<RegisteredPhoneCityRow>

    @Query(
        """
        UPDATE gateway_db
        SET city = :city, km = :km, registrationFieldsComplete = :fieldsComplete
        WHERE serial = :serial
          AND phone = :phone
        """,
    )
    suspend fun updateRegistrationFields(
        phone: String,
        serial: String,
        city: String?,
        km: Int?,
        fieldsComplete: Boolean,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET serial = :serial,
            date = :date,
            city = :city,
            km = :km,
            registrationFieldsComplete = CASE
                WHEN phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
                     AND (:city IS NOT NULL AND :city != '')
                     AND (:requiresKm = 0 OR :km IS NOT NULL) THEN 1
                WHEN phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0' THEN 0
                ELSE registrationFieldsComplete
            END
        WHERE id = :id
        """,
    )
    suspend fun updateRegistrationById(
        id: Long,
        serial: String,
        date: String?,
        city: String?,
        km: Int?,
        requiresKm: Int,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET completionReminderSentAt = :sentAt
        WHERE serial = :serial
        """,
    )
    suspend fun markCompletionReminderSent(serial: String, sentAt: Long): Int

    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone, date = :date, registrationFieldsComplete = 1,
            registrationSubscriptionId = :subscriptionId, registrationSource = :registrationSource
        WHERE serial = :serial
          AND (phone IS NULL OR TRIM(phone) = '' OR TRIM(phone) = '0')
        """,
    )
    suspend fun registerSerial(
        phone: String,
        date: String,
        serial: String,
        subscriptionId: Int?,
        registrationSource: String?,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone, date = :date, registrationFieldsComplete = 0,
            city = NULL, km = NULL,
            registrationSubscriptionId = :subscriptionId, registrationSource = :registrationSource
        WHERE serial = :serial
          AND (phone IS NULL OR TRIM(phone) = '' OR TRIM(phone) = '0')
        """,
    )
    suspend fun registerPartialSerial(
        phone: String,
        date: String,
        serial: String,
        subscriptionId: Int?,
        registrationSource: String?,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET registrationFieldsComplete = 0, registrationSubscriptionId = :subscriptionId
        WHERE serial = :serial
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
                WHEN (:city IS NOT NULL AND :city != '')
                     AND (:requiresKm = 0 OR :km IS NOT NULL) THEN 1
                ELSE 0
            END,
            registrationSubscriptionId = :subscriptionId,
            registrationSource = :registrationSource
        WHERE serial = :serial
          AND (phone IS NULL OR TRIM(phone) = '' OR TRIM(phone) = '0')
        """,
    )
    suspend fun registerSerialWithFields(
        phone: String,
        date: String,
        serial: String,
        city: String?,
        km: Int?,
        subscriptionId: Int?,
        registrationSource: String?,
        requiresKm: Int,
    ): Int

    /**
     * Lowest serial row id registered to a phone, matched by its 10-digit national suffix
     * (e.g. "9123456789") so it works regardless of stored prefix (09.., +98.., 98..).
     * Mirrors the id used by DeviceContactHelper to name device contacts "client<id>".
     */
    @Query(
        """
        SELECT MIN(id) FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND phone LIKE '%' || :national
        """,
    )
    suspend fun findFirstRowIdByNationalSuffix(national: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<SerialEntity>): List<Long>

    @Query("SELECT * FROM gateway_db WHERE serial IN (:serials)")
    suspend fun findBySerials(serials: List<String>): List<SerialEntity>

    /** Returns serial values already in DB (input should be normalized uppercase). */
    @Query("SELECT serial FROM gateway_db WHERE serial IN (:serials)")
    suspend fun findExistingUpperSerials(serials: List<String>): List<String>

    @Query(
        """
        UPDATE gateway_db
        SET groupId = :groupId
        WHERE serial IN (:serials)
          AND groupId IS NULL
        """,
    )
    suspend fun linkGroupIdIfNullBatch(serials: List<String>, groupId: Long): Int

    @Query("SELECT COUNT(*) FROM gateway_db")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM gateway_db WHERE " + RegisteredPhoneSql.IS_UNUSED)
    suspend fun countUnused(): Int

    @Query("SELECT COUNT(*) FROM gateway_db WHERE " + RegisteredPhoneSql.IS_REGISTERED)
    suspend fun countRegistered(): Int

    /**
     * One pass over [gateway_db] for all group totals (avoids N×COUNT per group card).
     */
    @Query(
        """
        SELECT groupId AS groupId,
               COUNT(*) AS total,
               CAST(
                 SUM(
                   CASE WHEN phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
                   THEN 1 ELSE 0 END
                 ) AS INTEGER
               ) AS used
        FROM gateway_db
        WHERE groupId IS NOT NULL
        GROUP BY groupId
        """,
    )
    suspend fun countTotalsByGroup(): List<GroupIdCountRow>

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
        SELECT gateway_db.* FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND (:fromDate = '' OR substr(gateway_db.date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(gateway_db.date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :filterSms = 0 AND :filterRubika = 0 AND :filterTelegram = 0 AND :filterBale = 0
            OR (:filterSms = 1 AND (gateway_db.registrationSource = 'sms' OR gateway_db.registrationSource IS NULL OR gateway_db.registrationSource = ''))
            OR (:filterRubika = 1 AND gateway_db.registrationSource = 'rubika')
            OR (:filterTelegram = 1 AND gateway_db.registrationSource = 'telegram')
            OR (:filterBale = 1 AND gateway_db.registrationSource = 'bale')
          )
          AND (
            :filterEndUser = 0 AND :filterSeller = 0
            OR (
              :filterEndUser = 1 AND (
                serial_groups.category = 'end_user_client'
                OR serial_groups.category IS NULL
                OR serial_groups.category = ''
                OR gateway_db.groupId IS NULL
              )
            )
            OR (:filterSeller = 1 AND serial_groups.category = 'seller_to_end_user')
          )
          AND (:useGroupFilter = 0 OR gateway_db.groupId IN (:groupIds))
          AND (
            :search = ''
            OR gateway_db.serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                gateway_db.phone LIKE '%' || :phoneNorm || '%'
                OR gateway_db.phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR gateway_db.phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND gateway_db.phone LIKE '%' || :search || '%')
          )
        ORDER BY gateway_db.date DESC, gateway_db.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listRegisteredFiltered(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
        filterSms: Int,
        filterRubika: Int,
        filterTelegram: Int,
        filterBale: Int,
        filterEndUser: Int,
        filterSeller: Int,
        useGroupFilter: Int,
        groupIds: List<Long>,
        limit: Int,
        offset: Int,
    ): List<SerialEntity>

    @Query(
        """
        SELECT gateway_db.* FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND (:fromDate = '' OR substr(gateway_db.date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(gateway_db.date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :filterSms = 0 AND :filterRubika = 0 AND :filterTelegram = 0 AND :filterBale = 0
            OR (:filterSms = 1 AND (gateway_db.registrationSource = 'sms' OR gateway_db.registrationSource IS NULL OR gateway_db.registrationSource = ''))
            OR (:filterRubika = 1 AND gateway_db.registrationSource = 'rubika')
            OR (:filterTelegram = 1 AND gateway_db.registrationSource = 'telegram')
            OR (:filterBale = 1 AND gateway_db.registrationSource = 'bale')
          )
          AND (
            :filterEndUser = 0 AND :filterSeller = 0
            OR (
              :filterEndUser = 1 AND (
                serial_groups.category = 'end_user_client'
                OR serial_groups.category IS NULL
                OR serial_groups.category = ''
                OR gateway_db.groupId IS NULL
              )
            )
            OR (:filterSeller = 1 AND serial_groups.category = 'seller_to_end_user')
          )
          AND (:useGroupFilter = 0 OR gateway_db.groupId IN (:groupIds))
          AND (
            :search = ''
            OR gateway_db.serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                gateway_db.phone LIKE '%' || :phoneNorm || '%'
                OR gateway_db.phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR gateway_db.phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND gateway_db.phone LIKE '%' || :search || '%')
          )
        ORDER BY gateway_db.date DESC, gateway_db.id DESC
        """,
    )
    suspend fun listRegisteredFilteredUnpaged(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
        filterSms: Int,
        filterRubika: Int,
        filterTelegram: Int,
        filterBale: Int,
        filterEndUser: Int,
        filterSeller: Int,
        useGroupFilter: Int,
        groupIds: List<Long>,
    ): List<SerialEntity>

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND (:fromDate = '' OR substr(gateway_db.date, 1, 10) >= substr(:fromDate, 1, 10))
          AND (:toDate = '' OR substr(gateway_db.date, 1, 10) <= substr(:toDate, 1, 10))
          AND (
            :filterSms = 0 AND :filterRubika = 0 AND :filterTelegram = 0 AND :filterBale = 0
            OR (:filterSms = 1 AND (gateway_db.registrationSource = 'sms' OR gateway_db.registrationSource IS NULL OR gateway_db.registrationSource = ''))
            OR (:filterRubika = 1 AND gateway_db.registrationSource = 'rubika')
            OR (:filterTelegram = 1 AND gateway_db.registrationSource = 'telegram')
            OR (:filterBale = 1 AND gateway_db.registrationSource = 'bale')
          )
          AND (
            :filterEndUser = 0 AND :filterSeller = 0
            OR (
              :filterEndUser = 1 AND (
                serial_groups.category = 'end_user_client'
                OR serial_groups.category IS NULL
                OR serial_groups.category = ''
                OR gateway_db.groupId IS NULL
              )
            )
            OR (:filterSeller = 1 AND serial_groups.category = 'seller_to_end_user')
          )
          AND (:useGroupFilter = 0 OR gateway_db.groupId IN (:groupIds))
          AND (
            :search = ''
            OR gateway_db.serial LIKE '%' || :search || '%'
            OR (
              :phoneNorm != ''
              AND (
                gateway_db.phone LIKE '%' || :phoneNorm || '%'
                OR gateway_db.phone LIKE '%+98' || substr(:phoneNorm, 2) || '%'
                OR gateway_db.phone LIKE '%98' || substr(:phoneNorm, 2) || '%'
              )
            )
            OR (:phoneNorm = '' AND :search != '' AND gateway_db.phone LIKE '%' || :search || '%')
          )
        """,
    )
    suspend fun countRegisteredFiltered(
        fromDate: String,
        toDate: String,
        search: String,
        phoneNorm: String,
        filterSms: Int,
        filterRubika: Int,
        filterTelegram: Int,
        filterBale: Int,
        filterEndUser: Int,
        filterSeller: Int,
        useGroupFilter: Int,
        groupIds: List<Long>,
    ): Int

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE (phone IS NULL OR TRIM(phone) = '' OR TRIM(phone) = '0')
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
        WHERE (phone IS NULL OR TRIM(phone) = '' OR TRIM(phone) = '0')
          AND (:search = '' OR serial LIKE '%' || :search || '%')
        """,
    )
    suspend fun countUnusedFiltered(search: String): Int

    @Query("DELETE FROM gateway_db WHERE groupId = :groupId")
    suspend fun deleteByGroupId(groupId: Long): Int

    /**
     * Serials with no matching [serial_groups] card: null groupId, or a stale groupId
     * left after sync / incomplete delete.
     */
    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE groupId IS NULL
           OR NOT EXISTS (
             SELECT 1 FROM serial_groups g WHERE g.id = gateway_db.groupId
           )
        """,
    )
    suspend fun countOrphanSerials(): Int

    @Query(
        "SELECT COUNT(*) FROM gateway_db WHERE (" + RegisteredPhoneSql.IS_REGISTERED + """)
          AND (
            groupId IS NULL
            OR NOT EXISTS (
              SELECT 1 FROM serial_groups g WHERE g.id = gateway_db.groupId
            )
          )
        """,
    )
    suspend fun countOrphanRegisteredSerials(): Int

    @Query(
        """
        DELETE FROM gateway_db
        WHERE groupId IS NULL
           OR NOT EXISTS (
             SELECT 1 FROM serial_groups g WHERE g.id = gateway_db.groupId
           )
        """,
    )
    suspend fun deleteOrphanSerials(): Int

    @Query(
        """
        SELECT serial FROM gateway_db
        WHERE groupId = :groupId
        ORDER BY id ASC
        """,
    )
    suspend fun listSerialValuesByGroupId(groupId: Long): List<String>

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE groupId = :groupId
        ORDER BY id ASC
        """,
    )
    suspend fun listByGroupId(groupId: Long): List<SerialEntity>

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE groupId = :groupId
        ORDER BY id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listByGroupIdPaged(groupId: Long, limit: Int, offset: Int): List<SerialEntity>

    @Query(
        """
        UPDATE gateway_db
        SET syncUpdatedMs = :syncUpdatedMs
        WHERE serial = :serial
        """,
    )
    suspend fun touchSyncUpdatedMs(serial: String, syncUpdatedMs: Long): Int

    /**
     * Apply buffer sync fields with force overwrite (no LWW).
     */
    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone,
            date = :date,
            city = :city,
            km = :km,
            registrationFieldsComplete = :fieldsComplete,
            registrationSource = :registrationSource,
            groupId = :groupId,
            syncUpdatedMs = :syncUpdatedMs
        WHERE serial = :serial
        """,
    )
    suspend fun applyBufferUpsert(
        serial: String,
        phone: String?,
        date: String?,
        city: String?,
        km: Int?,
        fieldsComplete: Boolean,
        registrationSource: String?,
        groupId: Long?,
        syncUpdatedMs: Long,
    ): Int

    /**
     * Apply remote/cloud LWW fields when incoming syncUpdatedMs is >= local.
     */
    @Query(
        """
        UPDATE gateway_db
        SET phone = :phone,
            date = :date,
            city = :city,
            km = :km,
            registrationFieldsComplete = :fieldsComplete,
            registrationSource = :registrationSource,
            groupId = :groupId,
            syncUpdatedMs = :syncUpdatedMs
        WHERE serial = :serial
          AND syncUpdatedMs <= :syncUpdatedMs
        """,
    )
    suspend fun applySyncUpsert(
        serial: String,
        phone: String?,
        date: String?,
        city: String?,
        km: Int?,
        fieldsComplete: Boolean,
        registrationSource: String?,
        groupId: Long?,
        syncUpdatedMs: Long,
    ): Int

    @Query(
        """
        UPDATE gateway_db
        SET groupId = :groupId
        WHERE serial = :serial
          AND groupId IS NULL
        """,
    )
    suspend fun setGroupIdIfNull(serial: String, groupId: Long): Int

    @Query(
        """
        SELECT phone, city FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
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
        SELECT phone FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND substr(date, 1, 10) = :targetDate
        GROUP BY phone
        """,
    )
    suspend fun listDistinctPhonesRegisteredOnDate(targetDate: String): List<String>

    @Query(
        """
        SELECT gateway_db.phone FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND substr(gateway_db.date, 1, 10) = :targetDate
          AND (
            (:serialCategory = 'end_user_client' AND (
              serial_groups.category = 'end_user_client'
              OR serial_groups.category IS NULL
              OR serial_groups.category = ''
              OR gateway_db.groupId IS NULL
            ))
            OR (:serialCategory = 'seller_to_end_user' AND serial_groups.category = 'seller_to_end_user')
          )
        GROUP BY gateway_db.phone
        """,
    )
    suspend fun listDistinctPhonesRegisteredOnDateForCategory(
        targetDate: String,
        serialCategory: String,
    ): List<String>

    @Query(
        """
        SELECT phone FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND substr(date, 1, 10) = :targetDate
          AND groupId IN (:groupIds)
        GROUP BY phone
        """,
    )
    suspend fun listDistinctPhonesRegisteredOnDateForGroups(
        targetDate: String,
        groupIds: List<Long>,
    ): List<String>

    /**
     * Phones whose registration date is on or before [onOrBeforeDate] (Jalali YYYY/MM/DD),
     * used for club auto-message catch-up (eligible once offset has passed).
     *
     * Date filtering is done in Kotlin via [JalaliHelper.isJalaliDateOnOrBefore] so Persian/Arabic
     * digit dates (stored under fa_IR locale) still compare correctly against Latin cutoffs.
     */
    @Query(
        """
        SELECT gateway_db.phone AS phone, gateway_db.date AS date FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND (
            (:serialCategory = 'end_user_client' AND (
              serial_groups.category = 'end_user_client'
              OR serial_groups.category IS NULL
              OR serial_groups.category = ''
              OR gateway_db.groupId IS NULL
            ))
            OR (:serialCategory = 'seller_to_end_user' AND serial_groups.category = 'seller_to_end_user')
          )
        """,
    )
    suspend fun listRegisteredPhoneDatesForCategory(
        serialCategory: String,
    ): List<PhoneDateRow>

    @Query(
        """
        SELECT phone, date FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND groupId IN (:groupIds)
        """,
    )
    suspend fun listRegisteredPhoneDatesForGroups(
        groupIds: List<Long>,
    ): List<PhoneDateRow>

    /**
     * Serials registered for [phone], matching the given category.
     * Callers must filter by registration date with [JalaliHelper.isJalaliDateOnOrBefore].
     */
    @Query(
        """
        SELECT gateway_db.serial AS serial, gateway_db.date AS date, gateway_db.km AS km FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone = :phone
          AND gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND (
            (:serialCategory = 'end_user_client' AND (
              serial_groups.category = 'end_user_client'
              OR serial_groups.category IS NULL
              OR serial_groups.category = ''
              OR gateway_db.groupId IS NULL
            ))
            OR (:serialCategory = 'seller_to_end_user' AND serial_groups.category = 'seller_to_end_user')
          )
        ORDER BY gateway_db.id ASC
        """,
    )
    suspend fun listRegisteredSerialDatesForCategory(
        phone: String,
        serialCategory: String,
    ): List<SerialDateRow>

    @Query(
        """
        SELECT serial, date, km FROM gateway_db
        WHERE phone = :phone
          AND phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND groupId IN (:groupIds)
        ORDER BY id ASC
        """,
    )
    suspend fun listRegisteredSerialDatesForGroups(
        phone: String,
        groupIds: List<Long>,
    ): List<SerialDateRow>

    /**
     * All registered serials in groups (phone + serial + date + km).
     * Callers filter by registration date and sort oldest-first.
     */
    @Query(
        """
        SELECT phone, serial, date, km FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND serial IS NOT NULL AND serial != ''
          AND groupId IN (:groupIds)
        """,
    )
    suspend fun listRegisteredPhoneSerialDatesForGroups(
        groupIds: List<Long>,
    ): List<PhoneSerialDateRow>

    @Query(
        """
        SELECT gateway_db.phone AS phone, gateway_db.serial AS serial,
               gateway_db.date AS date, gateway_db.km AS km FROM gateway_db
        LEFT JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND gateway_db.serial IS NOT NULL AND gateway_db.serial != ''
          AND (
            (:serialCategory = 'end_user_client' AND (
              serial_groups.category = 'end_user_client'
              OR serial_groups.category IS NULL
              OR serial_groups.category = ''
              OR gateway_db.groupId IS NULL
            ))
            OR (:serialCategory = 'seller_to_end_user' AND serial_groups.category = 'seller_to_end_user')
          )
        """,
    )
    suspend fun listRegisteredPhoneSerialDatesForCategory(
        serialCategory: String,
    ): List<PhoneSerialDateRow>

    @Query(
        """
        SELECT phone, city FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
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

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE phone = :phone AND phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND serial LIKE 'M%'
        """,
    )
    suspend fun countRegisteredMCode(phone: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE phone = :phone AND phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND registrationFieldsComplete = 1
        """,
    )
    suspend fun countCompletedRegistrationsByPhone(phone: String): Int

    @Query(
        """
        SELECT * FROM gateway_db
        WHERE phone = :phone AND phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND registrationFieldsComplete = 1
        ORDER BY id DESC
        """,
    )
    suspend fun listCompletedByPhone(phone: String): List<SerialEntity>

    /**
     * Completed registrations for [phone] whose serial group requires image verification
     * and that do not yet have a pending/approved photo submission.
     */
    @Query(
        """
        SELECT gateway_db.* FROM gateway_db
        INNER JOIN serial_groups ON gateway_db.groupId = serial_groups.id
        WHERE gateway_db.phone = :phone
          AND gateway_db.phone IS NOT NULL AND TRIM(gateway_db.phone) != '' AND TRIM(gateway_db.phone) != '0'
          AND gateway_db.registrationFieldsComplete = 1
          AND serial_groups.imageVerificationRequired = 1
          AND NOT EXISTS (
            SELECT 1 FROM serial_photo_submissions s
            WHERE s.serial = gateway_db.serial
              AND s.phone = gateway_db.phone
              AND s.status IN ('pending', 'approved')
          )
        ORDER BY gateway_db.id DESC
        """,
    )
    suspend fun listNeedingImageVerificationByPhone(phone: String): List<SerialEntity>

    /**
     * Daily registration counts by Jalali date prefix (YYYY/MM/DD).
     * [fromDate]/[toDate] are Jalali YYYY/MM/DD (Latin digits).
     */
    @Query(
        """
        SELECT substr(date, 1, 10) AS day, COUNT(*) AS count
        FROM gateway_db
        WHERE phone IS NOT NULL AND TRIM(phone) != '' AND TRIM(phone) != '0'
          AND date IS NOT NULL AND length(date) >= 10
          AND (:fromDate = '' OR substr(date, 1, 10) >= :fromDate)
          AND (:toDate = '' OR substr(date, 1, 10) <= :toDate)
        GROUP BY substr(date, 1, 10)
        ORDER BY day ASC
        """,
    )
    suspend fun countRegisteredByJalaliDay(fromDate: String, toDate: String): List<DayCountRow>
}

/** Phone + raw registration date for club auto-message eligibility (date compared in Kotlin). */
data class PhoneDateRow(
    val phone: String,
    val date: String?,
)

/** Serial + registration fields for club auto-message placeholder substitution. */
data class SerialDateRow(
    val serial: String,
    val date: String?,
    val km: Int? = null,
)

/** One registration row for per-serial club auto messaging. */
data class PhoneSerialDateRow(
    val phone: String,
    val serial: String,
    val date: String?,
    val km: Int? = null,
)

data class DayCountRow(
    val day: String,
    val count: Int,
)

/** Aggregated inventory counts for one serial group. */
data class GroupIdCountRow(
    val groupId: Long,
    val total: Int,
    val used: Int,
)
