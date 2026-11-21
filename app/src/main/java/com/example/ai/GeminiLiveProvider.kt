package com.example.ai

import com.example.live.LiveSessionManager

/**
 * Adapter implementing [AiChatProvider] for Gemini Live native streaming.
 */
class GeminiLiveProvider(
    private val liveSessionManager: LiveSessionManager
) : AiChatProvider {

    override val backend: AiBackend = AiBackend.GEMINI_LIVE

    fun connectSession(overrideModel: String? = null) {
        liveSessionManager.startSession(overrideModel)
    }

    fun disconnectSession() {
        liveSessionManager.stopSession()
    }

    fun isSessionReady(): Boolean = liveSessionManager.isConnected()

    fun getActiveLiveModel(): String = liveSessionManager.getActiveModel()

    override suspend fun generateResponse(
        conversation: List<ChatMessage>
    ): Result<String> {
        val lastUserMessage = conversation.lastOrNull { it.role == "user" }?.content
        return if (!lastUserMessage.isNullOrBlank()) {
            if (liveSessionManager.isConnected()) {
                liveSessionManager.sendTextMessage(lastUserMessage)
                Result.success("Sent to Gemini Live stream")
            } else {
                Result.failure(IllegalStateException("Gemini Live session is not connected"))
            }
        } else {
            Result.failure(IllegalArgumentException("No user prompt provided"))
        }
    }
}
