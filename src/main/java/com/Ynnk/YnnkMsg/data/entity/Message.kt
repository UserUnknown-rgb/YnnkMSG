package com.Ynnk.YnnkMsg.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId"])]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long, // Binds message to a specific user
    val contactEmail: String,
    val authorEmail: String = "", // Email of the user who wrote and sent the message
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: String = "", // JSON array of attachment paths
    val messageId: String = "", // Mail Message-ID to avoid duplicates
    val isRead: Boolean = false
)
