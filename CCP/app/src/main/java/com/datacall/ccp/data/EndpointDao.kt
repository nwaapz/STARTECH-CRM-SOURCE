package com.datacall.ccp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EndpointDao {
    @Query("SELECT * FROM endpoints ORDER BY label ASC")
    fun observeAll(): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints ORDER BY label ASC")
    suspend fun getAll(): List<EndpointEntity>

    @Query("SELECT * FROM endpoints WHERE phone = :phone LIMIT 1")
    suspend fun findByPhone(phone: String): EndpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EndpointEntity): Long

    @Delete
    suspend fun delete(entity: EndpointEntity)
}
