package com.Ynnk.YnnkMsg.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_events")
data class AppEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val message: String
)
