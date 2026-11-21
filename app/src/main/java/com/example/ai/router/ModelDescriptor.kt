package com.example.ai.router

/**
 * Provider transport / execution type for an AI model in RAFEL.
 */
enum class ModelProviderType(val displayLabel: String) {
    GEMINI_LIVE_WS("Gemini Live WebSocket (Bidi Audio)"),
    GEMINI_REST_API("Gemini REST API (generateContent)"),
    OFFLINE_LOCAL_LLM("On-Device Offline LLM")
}

/**
 * Granular capabilities supported by each registered AI model.
 */
enum class ModelCapability {
    LIVE_AUDIO_BIDI,
    TEXT_GENERATION,
    AUDIO_TRANSCRIPTION,
    TOOL_CALLING,
    VISION_MULTIMODAL,
    OFFLINE_EXECUTION
}

/**
 * Task categories requested by RAFEL subsystems.
 */
enum class ModelTaskType(
    val displayName: String,
    val requiredCapabilities: Set<ModelCapability>
) {
    LIVE_AUDIO(
        displayName = "Live Bidirectional Voice",
        requiredCapabilities = setOf(ModelCapability.LIVE_AUDIO_BIDI, ModelCapability.TOOL_CALLING)
    ),
    TEXT_CHAT(
        displayName = "Conversational Text Chat",
        requiredCapabilities = setOf(ModelCapability.TEXT_GENERATION)
    ),
    AUDIO_TRANSCRIPTION(
        displayName = "Audio Transcription (STT)",
        requiredCapabilities = setOf(ModelCapability.AUDIO_TRANSCRIPTION)
    ),
    TOOL_EXECUTION(
        displayName = "Tool & Intent Reasoning",
        requiredCapabilities = setOf(ModelCapability.TOOL_CALLING)
    ),
    VISION_ANALYSIS(
        displayName = "Screen & Image Vision",
        requiredCapabilities = setOf(ModelCapability.VISION_MULTIMODAL)
    ),
    OFFLINE_FALLBACK(
        displayName = "Offline Local Execution",
        requiredCapabilities = setOf(ModelCapability.OFFLINE_EXECUTION, ModelCapability.TEXT_GENERATION)
    )
}

/**
 * Real-time health state of a registered model.
 */
enum class ModelHealthState(val badgeLabel: String) {
    HEALTHY("HEALTHY"),
    DEGRADED("DEGRADED"),
    UNHEALTHY("UNHEALTHY"),
    OFFLINE("OFFLINE"),
    MISSING_WEIGHTS("MISSING WEIGHTS"),
    UNVERIFIED("READY")
}

/**
 * Immutable descriptor of a discovered AI model in the RAFEL project.
 */
data class AIModelDescriptor(
    val modelId: String,
    val displayName: String,
    val providerType: ModelProviderType,
    val endpointUrl: String,
    val capabilities: Set<ModelCapability>,
    val priorityRank: Int,
    val baselineExpectedLatencyMs: Long,
    val requiresInternet: Boolean = true,
    val requiresApiKey: Boolean = true,
    val expectedWeightFileNames: List<String> = emptyList()
) {
    /**
     * Returns the model path formatted for REST URLs (without duplicate "models/" prefix).
     */
    val restModelName: String
        get() = modelId.removePrefix("models/")

    /**
     * Returns the model path formatted for Gemini Live WebSocket setup (with "models/" prefix).
     */
    val liveSetupModelName: String
        get() = if (modelId.startsWith("models/")) modelId else "models/$modelId"
}

/**
 * Result of verifying whether local offline LLM model weight files exist on disk.
 */
data class OfflineModelVerificationResult(
    val modelId: String,
    val isWeightFilePresent: Boolean,
    val checkedPaths: List<String>,
    val detectedFilePath: String? = null,
    val detectedFileSizeBytes: Long = 0L,
    val localIntentRouterAvailable: Boolean = true,
    val statusMessage: String
)

/**
 * Snapshot of rolling latency and reliability metrics for a single model.
 */
data class ModelPerformanceStats(
    val modelId: String,
    val avgConnectTimeMs: Long = 0L,
    val avgTimeToFirstResponseMs: Long = 0L,
    val avgTimeToFirstAudioChunkMs: Long = 0L,
    val avgTotalResponseTimeMs: Long = 0L,
    val lastResponseLatencyMs: Long = 0L,
    val totalRequests: Int = 0,
    val successfulRequests: Int = 0,
    val failedRequests: Int = 0,
    val errorRate: Double = 0.0,
    val availabilityPercent: Double = 100.0,
    val compositeScoreMs: Long = 0L,
    val lastUpdatedMs: Long = 0L
)

/**
 * Record of an automatic fallback transition between two models.
 */
data class FallbackEvent(
    val id: String,
    val timestampMs: Long,
    val taskType: ModelTaskType,
    val fromModelId: String,
    val toModelId: String,
    val reason: String,
    val httpOrWsCode: Int = 0,
    val contextPreserved: Boolean = true
)

/**
 * Result of an automatic routing decision.
 */
data class ModelRoutingDecision(
    val taskType: ModelTaskType,
    val selectedModel: AIModelDescriptor?,
    val fallbackChain: List<AIModelDescriptor>,
    val candidateScoresMs: Map<String, Long>,
    val selectionReason: String,
    val isAutoMode: Boolean,
    val isOfflineMode: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Result of probing a model endpoint or local weight file using its real protocol.
 */
data class ModelEndpointProbeResult(
    val modelId: String,
    val providerType: ModelProviderType,
    val isReachable: Boolean,
    val statusCode: Int,
    val latencyMs: Long,
    val healthState: ModelHealthState,
    val detailMessage: String,
    val connectTimeMs: Long = 0L,
    val ttfrMs: Long = 0L,
    val ttfaMs: Long = 0L,
    val setupCompleteVerified: Boolean = false,
    val generationVerified: Boolean = false,
    val audioBytesReceived: Long = 0L,
    val responsePreview: String = ""
)

/**
 * Aggregated telemetry state exposed to UI (Settings / Diagnostics).
 */
data class RouterTelemetrySnapshot(
    val isAutoModeEnabled: Boolean = true,
    val isOnline: Boolean = true,
    val activeProvider: String = "NONE",
    val activeModel: String = "NONE",
    val activeLiveModelId: String = "models/gemini-3.8-live",
    val activeTextModelId: String = "gemini-3.5-flash",
    val onlineOrOfflineMode: String = "ONLINE",
    val apiKeyConfigured: String = "NO",
    val restRequestStatus: String = "IDLE",
    val webSocketState: String = "DISCONNECTED",
    val setupComplete: Boolean = false,
    val firstAudioChunkReceived: Boolean = false,
    val lastSuccessfulModelResponseTimestamp: Long = 0L,
    val lastFallbackReason: String = "NONE",
    val actualResponseSource: String = "NONE",
    val connectionStatus: String = "READY",
    val recentLatencyMs: Long = 0L,
    val recentTtfaMs: Long = 0L,
    val recentTtfrMs: Long = 0L,
    val recentConnectMs: Long = 0L,
    val modelHealthMap: Map<String, ModelHealthState> = emptyMap(),
    val modelStatsMap: Map<String, ModelPerformanceStats> = emptyMap(),
    val fallbackEvents: List<FallbackEvent> = emptyList(),
    val offlineVerification: OfflineModelVerificationResult? = null,
    val lastRoutingDecisionSummary: String = "Auto-selected fastest compatible model"
)
