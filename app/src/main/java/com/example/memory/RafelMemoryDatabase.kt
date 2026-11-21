package com.example.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationTurnEntity::class, UserMemoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class RafelMemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: RafelMemoryDatabase? = null

        fun getInstance(context: Context): RafelMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RafelMemoryDatabase::class.java,
                    "rafel_memory.db"
                ).fallbackToDestructiveMigration(true)
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
