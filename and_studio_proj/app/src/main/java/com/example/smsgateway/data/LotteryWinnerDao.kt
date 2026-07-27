package com.example.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LotteryWinnerDao {
    @Insert
    suspend fun insert(entity: LotteryWinnerEntity): Long

    @Query("SELECT * FROM lottery_winners ORDER BY wonAt DESC LIMIT :limit OFFSET :offset")
    suspend fun list(limit: Int, offset: Int): List<LotteryWinnerEntity>

    @Query("SELECT COUNT(*) FROM lottery_winners")
    suspend fun count(): Int

    @Query("DELETE FROM lottery_winners WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM lottery_winners WHERE syncUpdatedMs > :since ORDER BY syncUpdatedMs ASC LIMIT :limit")
    suspend fun listChangedSince(since: Long, limit: Int = 500): List<LotteryWinnerEntity>
}
