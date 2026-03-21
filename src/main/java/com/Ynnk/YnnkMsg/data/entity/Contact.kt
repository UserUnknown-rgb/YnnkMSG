package com.Ynnk.YnnkMsg.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId", "email"], unique = true)]
)
data class Contact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long, // Binds contact to a specific user
    val email: String,
    val secondaryEmail: String? = null,
    val exclusivePrimaryEmail: Boolean = false,
    val name: String,
    val avatarPath: String? = null,
    val publicKey: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val newMessageDraft: String? = null,
    val lastMsgTime: Long? = null
)
