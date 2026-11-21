package com.example.conversation

import java.util.UUID

/**
 * Message types for the unified voice + chat conversation in Rafel.
 */
enum class UnifiedMessageType {
    USER_TEXT,
    RAFEL_TEXT,
    USER_VOICE,
    RAFEL_VOICE
}

/**
 * Single immutable conversational message entity shared between Voice and Chat.
 */
data class UnifiedMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: UnifiedMessageType,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isUser: Boolean get() = type == UnifiedMessageType.USER_TEXT || type == UnifiedMessageType.USER_VOICE
    val isVoice: Boolean get() = type == UnifiedMessageType.USER_VOICE || type == UnifiedMessageType.RAFEL_VOICE
    val role: String get() = if (isUser) "user" else "assistant"

    val displaySourceTag: String
        get() = when (type) {
            UnifiedMessageType.USER_TEXT -> "Text"
            UnifiedMessageType.USER_VOICE -> "Voice"
            UnifiedMessageType.RAFEL_TEXT -> "Text"
            UnifiedMessageType.RAFEL_VOICE -> "Voice"
        }
}
