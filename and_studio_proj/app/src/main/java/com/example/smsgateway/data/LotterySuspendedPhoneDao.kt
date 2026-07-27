package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LotterySuspendedPhoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LotterySuspendedPhoneEntity)

    @Query("SELECT * FROM lottery_suspended_phones ORDER BY createdAt DESC")
    suspend fun listAll(): List<LotterySuspendedPhoneEntity>

    @Query("SELECT phone FROM lottery_suspended_phones")
    suspend fun listPhones(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM lottery_suspended_phones WHERE phone = :phone)")
    suspend fun isSuspended(phone: String): Boolean

    @Query("DELETE FROM lottery_suspended_phones WHERE phone = :phone")
    suspend fun delete(phone: String)
}
