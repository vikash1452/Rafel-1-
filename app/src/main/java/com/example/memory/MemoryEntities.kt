package com.example.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_turns")
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" or "assistant"
    val text: String,
    val messageType: String = "USER_TEXT", // "USER_TEXT", "RAFEL_TEXT", "USER_VOICE", "RAFEL_VOICE"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_memories")
data class UserMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String, // e.g. "user_name", "user_preference", "user_city"
    val value: String,
    val category: String = "general",
    val updatedAt: Long = System.currentTimeMillis()
)
