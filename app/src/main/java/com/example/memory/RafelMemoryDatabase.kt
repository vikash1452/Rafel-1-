package com.example.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationTurnEntity::class, UserMemoryEntity::class],
    version = 2,
    exportSchema = true
)
abstract class RafelMemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: RafelMemoryDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!tableExists(db, "conversation_turns")) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `conversation_turns` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `role` TEXT NOT NULL,
                            `text` TEXT NOT NULL,
                            `messageType` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                } else if (!columnExists(db, "conversation_turns", "messageType")) {
                    db.execSQL(
                        "ALTER TABLE `conversation_turns` ADD COLUMN `messageType` TEXT NOT NULL DEFAULT 'USER_TEXT'"
                    )
                }

                if (!tableExists(db, "user_memories")) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `user_memories` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `key` TEXT NOT NULL,
                            `value` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `updatedAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                } else if (!columnExists(db, "user_memories", "category")) {
                    db.execSQL(
                        "ALTER TABLE `user_memories` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'general'"
                    )
                }
            }

            private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
                db.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(tableName)
                ).use { cursor ->
                    return cursor.moveToFirst()
                }
            }

            private fun columnExists(
                db: SupportSQLiteDatabase,
                tableName: String,
                columnName: String
            ): Boolean {
                db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex < 0) return false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == columnName) {
                            return true
                        }
                    }
                    return false
                }
            }
        }

        fun getInstance(context: Context): RafelMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RafelMemoryDatabase::class.java,
                    "rafel_memory.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
