package com.example.ai

/**
 * Supported AI execution backends for Rafel.
 */
enum class AiBackend(val displayName: String) {
    GEMINI_LIVE("GEMINI LIVE")
}

/**
 * Standard conversational message representation.
 */
data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

/**
 * Clean abstraction for chat completion providers.
 */
interface AiChatProvider {
    val backend: AiBackend
    suspend fun generateResponse(
        conversation: List<ChatMessage>
    ): Result<String>
}
