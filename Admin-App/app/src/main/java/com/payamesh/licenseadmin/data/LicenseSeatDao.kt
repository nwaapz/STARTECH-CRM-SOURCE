package com.payamesh.licenseadmin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface LicenseSeatDao {
    @Query("SELECT * FROM license_seats ORDER BY updatedAt DESC")
    suspend fun listAll(): List<LicenseSeatEntity>

    @Query("SELECT * FROM license_seats WHERE msisdn = :msisdn LIMIT 1")
    suspend fun findByMsisdn(msisdn: String): LicenseSeatEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: LicenseSeatEntity): Long

    @Update
    suspend fun update(entity: LicenseSeatEntity)

    @Insert
    suspend fun insertLog(entity: LicenseCheckLogEntity): Long

    @Query("SELECT * FROM license_check_logs ORDER BY at DESC LIMIT :limit")
    suspend fun recentLogs(limit: Int = 100): List<LicenseCheckLogEntity>
}
