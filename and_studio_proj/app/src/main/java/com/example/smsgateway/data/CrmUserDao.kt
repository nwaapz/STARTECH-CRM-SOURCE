package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CrmUserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CrmUserEntity): Long

    @Update
    suspend fun update(entity: CrmUserEntity)

    @Query("SELECT * FROM crm_users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CrmUserEntity?

    @Query("SELECT * FROM crm_users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): CrmUserEntity?

    @Query("SELECT * FROM crm_users ORDER BY id ASC")
    suspend fun listAll(): List<CrmUserEntity>

    @Query("SELECT COUNT(*) FROM crm_users")
    suspend fun count(): Int

    @Query("DELETE FROM crm_users WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM crm_users WHERE syncUpdatedMs > :since ORDER BY syncUpdatedMs ASC LIMIT :limit")
    suspend fun listChangedSince(since: Long, limit: Int = 500): List<CrmUserEntity>
}
