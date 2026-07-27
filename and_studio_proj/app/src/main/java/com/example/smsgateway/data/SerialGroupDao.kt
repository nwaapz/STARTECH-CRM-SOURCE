package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SerialGroupDao {
    @Insert
    suspend fun insert(group: SerialGroupEntity): Long

    @Update
    suspend fun update(group: SerialGroupEntity): Int

    @Query("SELECT * FROM serial_groups ORDER BY createdAt DESC")
    suspend fun listAll(): List<SerialGroupEntity>

    @Query("SELECT * FROM serial_groups WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SerialGroupEntity?

    @Query("SELECT * FROM serial_groups WHERE syncOriginGroupId = :remoteId LIMIT 1")
    suspend fun findBySyncOriginGroupId(remoteId: Long): SerialGroupEntity?

    @Query("DELETE FROM serial_groups WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query(
        """
        UPDATE serial_groups
        SET description = :description,
            score = :score,
            category = :category,
            warrantyMonths = :warrantyMonths,
            warrantyKm = :warrantyKm,
            imageVerificationRequired = :imageVerificationRequired,
            syncUpdatedMs = :syncUpdatedMs
        WHERE id = :id
        """,
    )
    suspend fun updateMetadata(
        id: Long,
        description: String?,
        score: Int,
        category: String,
        warrantyMonths: Int?,
        warrantyKm: Int?,
        imageVerificationRequired: Boolean,
        syncUpdatedMs: Long,
    ): Int

    @Query(
        """
        UPDATE serial_groups
        SET startNumber = :startNumber, endNumber = :endNumber, syncUpdatedMs = :syncUpdatedMs
        WHERE id = :id
        """,
    )
    suspend fun updateRange(id: Long, startNumber: Int, endNumber: Int, syncUpdatedMs: Long): Int

    @Query(
        """
        UPDATE serial_groups
        SET syncOriginGroupId = :remoteId, syncUpdatedMs = :syncUpdatedMs
        WHERE id = :id
        """,
    )
    suspend fun setSyncOriginGroupId(id: Long, remoteId: Long, syncUpdatedMs: Long): Int

    @Query("SELECT COUNT(*) FROM gateway_db WHERE groupId = :groupId")
    suspend fun countByGroupId(groupId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM gateway_db
        WHERE groupId = :groupId
          AND phone IS NOT NULL AND phone != ''
        """,
    )
    suspend fun countUsedByGroupId(groupId: Long): Int
}
