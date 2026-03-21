package com.Ynnk.YnnkMsg.data.dao

import androidx.room.*
import com.Ynnk.YnnkMsg.data.entity.User

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE primaryEmail = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: Long): User?

    @Upsert
    suspend fun upsert(user: User): Long

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<User>

    @Delete
    suspend fun delete(user: User)

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteById(userId: Long)

    @Query("UPDATE users SET vulnerableMode = :isVulnerable WHERE id = :userId")
    suspend fun setVulnerableMode(userId: Long, isVulnerable: Boolean)
}
