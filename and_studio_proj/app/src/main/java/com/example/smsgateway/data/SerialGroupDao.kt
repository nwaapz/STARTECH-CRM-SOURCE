package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SerialGroupDao {
    @Insert
    suspend fun insert(group: SerialGroupEntity): Long

    @Query("SELECT * FROM serial_groups ORDER BY createdAt DESC")
    suspend fun listAll(): List<SerialGroupEntity>

    @Query("SELECT * FROM serial_groups WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): SerialGroupEntity?

    @Query("DELETE FROM serial_groups WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query(
        """
        UPDATE serial_groups
        SET description = :description, score = :score
        WHERE id = :id
        """,
    )
    suspend fun updateMetadata(id: Long, description: String?, score: Int): Int

    @Query(
        """
        UPDATE serial_groups
        SET startNumber = :startNumber, endNumber = :endNumber
        WHERE id = :id
        """,
    )
    suspend fun updateRange(id: Long, startNumber: Int, endNumber: Int): Int

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
