package com.Ynnk.YnnkMsg.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val primaryEmail: String,
    val name: String? = null,
    val exclusivePrimaryEmail: Boolean = false,
    val avatarPath: String? = null,
    val vulnerableMode: Boolean = false
)
