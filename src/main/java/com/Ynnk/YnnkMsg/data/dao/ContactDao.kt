package com.Ynnk.YnnkMsg.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.Ynnk.YnnkMsg.data.entity.Contact

@Dao
interface ContactDao {
    @Query("""
        SELECT contacts.id, contacts.userId, contacts.email, contacts.secondaryEmail, 
               contacts.exclusivePrimaryEmail, contacts.name, contacts.avatarPath, 
               contacts.publicKey, contacts.addedAt, contacts.newMessageDraft,
               last_msg_time as lastMsgTime
        FROM contacts 
        JOIN users ON contacts.userId = users.id
        LEFT JOIN (
            SELECT contactEmail, MAX(timestamp) AS last_msg_time 
            FROM messages 
            WHERE userId = :userId 
            GROUP BY contactEmail
        ) ON contacts.email = contactEmail 
        WHERE contacts.userId = :userId AND users.vulnerableMode = 0
        ORDER BY last_msg_time DESC, contacts.name ASC
    """)
    fun getAllContacts(userId: Long): LiveData<List<Contact>>

    @Query("SELECT * FROM contacts WHERE userId = :userId AND (email = :email OR secondaryEmail = :email)")
    suspend fun getContactByEmail(userId: Long, email: String): Contact?

    @Query("SELECT * FROM contacts WHERE userId = :userId AND (email = :email OR secondaryEmail = :email)")
    fun getContactByEmailLiveData(userId: Long, email: String): LiveData<Contact?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("""
        SELECT contacts.id, contacts.userId, contacts.email, contacts.secondaryEmail, 
               contacts.exclusivePrimaryEmail, contacts.name, contacts.avatarPath, 
               contacts.publicKey, contacts.addedAt, contacts.newMessageDraft,
               last_msg_time as lastMsgTime
        FROM contacts
        JOIN users ON contacts.userId = users.id
        LEFT JOIN (
            SELECT contactEmail, MAX(timestamp) AS last_msg_time 
            FROM messages 
            WHERE userId = :userId 
            GROUP BY contactEmail
        ) ON contacts.email = contactEmail 
        WHERE contacts.userId = :userId AND users.vulnerableMode = 0
        ORDER BY last_msg_time DESC, contacts.name ASC
    """)
    suspend fun getAllContactsSync(userId: Long): List<Contact>

    @Query("DELETE FROM contacts WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: Long)

    @Query("UPDATE contacts SET newMessageDraft = :draft WHERE userId = :userId AND (email = :email OR secondaryEmail = :email)")
    suspend fun updateDraft(userId: Long, email: String, draft: String?)
}
