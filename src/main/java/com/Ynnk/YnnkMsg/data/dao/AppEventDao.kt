package com.Ynnk.YnnkMsg.data.dao

import androidx.room.*
import com.Ynnk.YnnkMsg.data.entity.AppEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AppEventDao {
    @Query("""
        SELECT app_events.id, app_events.timestamp, app_events.message 
        FROM app_events 
        JOIN (SELECT id, vulnerableMode FROM users LIMIT 1) as user
        WHERE user.vulnerableMode = 0
        ORDER BY timestamp DESC
    """)
    fun getAllEvents(): Flow<List<AppEvent>>

    @Insert
    suspend fun insert(event: AppEvent)

    @Query("DELETE FROM app_events WHERE id NOT IN (SELECT id FROM app_events ORDER BY timestamp DESC LIMIT 50)")
    suspend fun cleanupOldEvents()

    @Transaction
    suspend fun logEvent(message: String) {
        insert(AppEvent(message = message))
        cleanupOldEvents()
    }
}
