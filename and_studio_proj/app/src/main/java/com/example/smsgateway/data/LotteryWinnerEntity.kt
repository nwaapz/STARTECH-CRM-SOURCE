package com.example.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lottery_winners",
    indices = [Index(value = ["wonAt"])],
)
data class LotteryWinnerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phone: String,
    val city: String? = null,
    val scoreAtWin: Int = 0,
    /** Jalali datetime when confirmed, Latin digits. */
    val dateJalali: String,
    val wonAt: Long,
    val syncUpdatedMs: Long = 0,
)
