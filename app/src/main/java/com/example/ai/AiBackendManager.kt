package com.example.ai

import android.content.Context
import com.example.ai.router.AIModelManager
import com.example.ai.router.ModelTaskType
import com.example.voice.RafelLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates AI backend connection state for Rafel and bridges with [AIModelManager].
 */
class AiBackendManager(
    private val context: Context,
    private val geminiKeyProvider: () -> String?
) {
    val modelManager: AIModelManager = AIModelManager.getInstance(context)

    private val _activeBackend = MutableStateFlow(AiBackend.GEMINI_LIVE)
    val activeBackend: StateFlow<AiBackend> = _activeBackend.asStateFlow()

    private val _connectionStatus = MutableStateFlow("GEMINI_DISCONNECTED")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    var onBackendChanged: ((AiBackend) -> Unit)? = null

    init {
        updateActiveBackend()
    }

    fun updateActiveBackend() {
        _activeBackend.value = AiBackend.GEMINI_LIVE
        val hasKey = !geminiKeyProvider().isNullOrBlank()
        modelManager.selectModelForTask(
            taskType = ModelTaskType.LIVE_AUDIO,
            hasApiKey = hasKey
        )
        RafelLogger.logLive("AI Backend: ${AiBackend.GEMINI_LIVE} (Active Model: ${modelManager.activeLiveModelId.value})")
    }

    fun setGeminiConnected(connected: Boolean) {
        val status = if (connected) "GEMINI_CONNECTED" else "GEMINI_DISCONNECTED"
        _connectionStatus.value = status
        modelManager.updateConnectionStatus(status)
    }

    fun isGeminiActive(): Boolean = true
}
