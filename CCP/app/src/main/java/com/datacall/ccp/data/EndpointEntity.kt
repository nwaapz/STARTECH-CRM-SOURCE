package com.datacall.ccp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "endpoints")
data class EndpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phone: String,
    val label: String
)
