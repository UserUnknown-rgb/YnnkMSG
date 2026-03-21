package com.Ynnk.YnnkMsg.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.Ynnk.YnnkMsg.data.dao.ContactDao
import com.Ynnk.YnnkMsg.data.dao.MessageDao
import com.Ynnk.YnnkMsg.data.dao.UserDao
import com.Ynnk.YnnkMsg.data.dao.AppEventDao
import com.Ynnk.YnnkMsg.data.entity.Contact
import com.Ynnk.YnnkMsg.data.entity.Message
import com.Ynnk.YnnkMsg.data.entity.User
import com.Ynnk.YnnkMsg.data.entity.AppEvent
import com.Ynnk.YnnkMsg.util.SecurePrefs
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [User::class, Contact::class, Message::class, AppEvent::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun appEventDao(): AppEventDao

    companion object {
        private const val DB_NAME = "ynnkmsg_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        fun destroyInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val passphrase = SecurePrefs.getDatabasePassword(context).toByteArray()
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}
