package com.Ynnk.YnnkMsg.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.Ynnk.YnnkMsg.data.entity.Message
import java.io.File
import org.json.JSONArray

@Dao
abstract class MessageDao {
    @Query("SELECT * FROM messages WHERE userId = :userId AND contactEmail = :email ORDER BY timestamp ASC")
    abstract fun getMessagesForContact(userId: Long, email: String): LiveData<List<Message>>

    @Query("SELECT * FROM (SELECT * FROM messages WHERE userId = :userId AND contactEmail = :email ORDER BY timestamp DESC LIMIT :limit OFFSET :offset) ORDER BY timestamp ASC")
    abstract fun getPagedMessagesForContact(userId: Long, email: String, limit: Int, offset: Int): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE userId = :userId AND contactEmail = :email ORDER BY timestamp ASC")
    abstract suspend fun getMessagesForContactSync(userId: Long, email: String): List<Message>

    @Query("SELECT * FROM messages WHERE userId = :userId AND contactEmail = :email AND text LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    abstract fun searchMessages(userId: Long, email: String, query: String): LiveData<List<Message>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(message: Message): Long

    @Delete
    protected abstract suspend fun deleteInternal(message: Message)

    @Transaction
    open suspend fun delete(message: Message) {
        deleteInternal(message)
        deletePhysicalAttachments(message.attachments)
    }

    @Query("DELETE FROM messages WHERE userId = :userId AND contactEmail = :email")
    protected abstract suspend fun deleteAllForContactInternal(userId: Long, email: String)

    @Transaction
    open suspend fun deleteAllForContact(userId: Long, email: String) {
        val messages = getMessagesForContactSync(userId, email)
        messages.forEach { deletePhysicalAttachments(it.attachments) }
        deleteAllForContactInternal(userId, email)
    }

    @Query("SELECT COUNT(*) FROM messages WHERE userId = :userId AND messageId = :messageId")
    abstract suspend fun countByMessageId(userId: Long, messageId: String): Int

    @Query("SELECT * FROM messages WHERE userId = :userId AND contactEmail = :email ORDER BY timestamp DESC LIMIT 1")
    abstract suspend fun getLastMessage(userId: Long, email: String): Message?

    @Query("SELECT COUNT(*) FROM messages WHERE userId = :userId AND contactEmail = :email AND isRead = 0 AND isOutgoing = 0")
    abstract fun getUnreadCount(userId: Long, email: String): LiveData<Int>

    @Query("UPDATE messages SET isRead = 1 WHERE userId = :userId AND contactEmail = :email AND isOutgoing = 0")
    abstract suspend fun markAsRead(userId: Long, email: String)

    @Query("UPDATE messages SET isRead = 1 WHERE userId = :userId AND contactEmail = :email AND isOutgoing = 1 AND timestamp <= :beforeTimestamp")
    abstract suspend fun markOutgoingAsRead(userId: Long, email: String, beforeTimestamp: Long)

    @Query("DELETE FROM messages WHERE userId = :userId")
    abstract suspend fun deleteAllForUser(userId: Long)

    private fun deletePhysicalAttachments(attachmentsJson: String) {
        if (attachmentsJson.isEmpty()) return
        try {
            val arr = JSONArray(attachmentsJson)
            for (i in 0 until arr.length()) {
                val path = arr.getString(i)
                val file = File(path)
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
