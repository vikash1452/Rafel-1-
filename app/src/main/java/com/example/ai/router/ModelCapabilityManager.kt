package com.example.ai.router

/**
 * Evaluates whether a given [AIModelDescriptor] satisfies the capability and protocol
 * requirements of a requested [ModelTaskType].
 *
 * Strictly enforces:
 * - Never select a text-only or REST-only model for Live Audio tasks.
 * - Never select an offline local LLM model when its weight files are missing on disk.
 */
class ModelCapabilityManager(
    private val registry: AIModelRegistry
) {

    /**
     * Checks if [model] supports [taskType] and any [additionalCapabilities].
     *
     * @param isOfflineWeightVerified Whether on-device model weight files have been verified on disk.
     */
    fun supportsTask(
        model: AIModelDescriptor,
        taskType: ModelTaskType,
        additionalCapabilities: Set<ModelCapability> = emptySet(),
        isOfflineWeightVerified: Boolean = false
    ): Boolean {
        // Hard Rule 1: Live Audio tasks MUST use a bidirectional native audio Live model, NEVER a text-only or REST model.
        if (taskType == ModelTaskType.LIVE_AUDIO) {
            if (model.providerType != ModelProviderType.GEMINI_LIVE_WS) {
                return false
            }
            if (!model.capabilities.contains(ModelCapability.LIVE_AUDIO_BIDI)) {
                return false
            }
        }

        // Hard Rule 2: Offline local LLM models are ONLY capable if weight files actually exist on disk.
        if (model.providerType == ModelProviderType.OFFLINE_LOCAL_LLM) {
            if (!isOfflineWeightVerified) {
                return false
            }
        }

        // Hard Rule 3: For standard REST Text Chat or Audio Transcription, prefer REST/Local models
        // rather than opening a stateful WebSocket connection just for a one-shot REST call.
        if (taskType == ModelTaskType.TEXT_CHAT || taskType == ModelTaskType.AUDIO_TRANSCRIPTION) {
            if (model.providerType == ModelProviderType.GEMINI_LIVE_WS) {
                return false
            }
        }

        // Check all required task capabilities
        if (!model.capabilities.containsAll(taskType.requiredCapabilities)) {
            return false
        }

        // Check any additional requested capabilities
        if (additionalCapabilities.isNotEmpty() && !model.capabilities.containsAll(additionalCapabilities)) {
            return false
        }

        return true
    }

    /**
     * Returns all registered models compatible with [taskType].
     */
    fun getCompatibleModels(
        taskType: ModelTaskType,
        additionalCapabilities: Set<ModelCapability> = emptySet(),
        isOfflineWeightVerified: Boolean = false
    ): List<AIModelDescriptor> {
        return registry.getAllModels().filter { model ->
            supportsTask(
                model = model,
                taskType = taskType,
                additionalCapabilities = additionalCapabilities,
                isOfflineWeightVerified = isOfflineWeightVerified
            )
        }
    }

    /**
     * Human-readable summary of a model's capabilities for Diagnostics and Reporting.
     */
    fun formatCapabilitiesSummary(model: AIModelDescriptor): String {
        return model.capabilities.joinToString(", ") { cap ->
            when (cap) {
                ModelCapability.LIVE_AUDIO_BIDI -> "Live Audio (16kHz In / 24kHz Out)"
                ModelCapability.TEXT_GENERATION -> "Text Chat"
                ModelCapability.AUDIO_TRANSCRIPTION -> "Audio Transcription (STT)"
                ModelCapability.TOOL_CALLING -> "Tool Calling"
                ModelCapability.VISION_MULTIMODAL -> "Vision/Screen Share"
                ModelCapability.OFFLINE_EXECUTION -> "Offline Local"
            }
        }
    }
}
