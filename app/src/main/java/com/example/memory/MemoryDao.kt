package com.example.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurnEntity): Long

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int = 10): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp ASC")
    fun observeAllTurns(): Flow<List<ConversationTurnEntity>>

    @Query("DELETE FROM conversation_turns")
    suspend fun clearAllTurns()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: UserMemoryEntity): Long

    @Query("SELECT * FROM user_memories WHERE `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): UserMemoryEntity?

    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC")
    suspend fun getAllMemories(): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<UserMemoryEntity>>

    @Query("DELETE FROM user_memories WHERE `key` = :key")
    suspend fun deleteMemory(key: String)
}
