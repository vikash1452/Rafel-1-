package com.example.ai.router

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.voice.RafelLiveDiagnostics
import com.example.voice.RafelLogger
import com.example.voice.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified AI Engine & Automatic Model Router Manager for RAFEL.
 *
 * Centralizes:
 * - [AIModelRegistry]
 * - [ModelCapabilityManager]
 * - [ModelHealthMonitor]
 * - [ModelLatencyTracker]
 * - [ModelFallbackManager]
 * - [AIModelRouter]
 *
 * All Live Audio, Text Chat, and Audio Transcription requests pass through this coordinator.
 */
class AIModelManager(
    private val context: Context,
    val registry: AIModelRegistry = AIModelRegistry(),
    val capabilityManager: ModelCapabilityManager = ModelCapabilityManager(registry),
    val latencyTracker: ModelLatencyTracker = ModelLatencyTracker(),
    val healthMonitor: ModelHealthMonitor = ModelHealthMonitor(context.applicationContext ?: context, registry),
    val fallbackManager: ModelFallbackManager = ModelFallbackManager(
        registry = registry,
        capabilityManager = capabilityManager,
        healthMonitor = healthMonitor,
        latencyTracker = latencyTracker
    ),
    val router: AIModelRouter = AIModelRouter(
        registry = registry,
        capabilityManager = capabilityManager,
        healthMonitor = healthMonitor,
        latencyTracker = latencyTracker,
        fallbackManager = fallbackManager
    )
) {

    companion object {
        private const val PREFS_NAME = "RafelPrefs"
        private const val KEY_AUTO_MODE = "ai_router_auto_mode"
        private const val KEY_PINNED_LIVE_MODEL = "ai_router_pinned_live_model"
        private const val KEY_PINNED_TEXT_MODEL = "ai_router_pinned_text_model"

        @Volatile
        private var INSTANCE: AIModelManager? = null

        fun getInstance(context: Context): AIModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AIModelManager(context.applicationContext ?: context).also { INSTANCE = it }
            }
        }

        fun resetInstanceForTesting() {
            INSTANCE = null
            RafelLiveDiagnostics.activeProvider.value = "NONE"
            RafelLiveDiagnostics.activeModel.value = "NONE"
            RafelLiveDiagnostics.onlineOrOfflineMode.value = "ONLINE"
            RafelLiveDiagnostics.apiKeyConfigured.value = "NO"
            RafelLiveDiagnostics.restRequestStatus.value = "IDLE"
            RafelLiveDiagnostics.webSocketState.value = "DISCONNECTED"
            RafelLiveDiagnostics.setupComplete.value = false
            RafelLiveDiagnostics.firstAudioChunkReceived.value = false
            RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value = 0L
            RafelLiveDiagnostics.lastFallbackReason.value = "NONE"
            RafelLiveDiagnostics.actualResponseSource.value = "NONE"
        }

        /**
         * Redacts API keys and tokens from diagnostic strings and URLs so secrets never leak to logs.
         */
        fun redactSecrets(input: String, explicitKey: String? = null): String {
            var sanitized = input
                .replace(Regex("([?&]key=)[^&\\s\"']+"), "$1[REDACTED]")
                .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "[REDACTED_API_KEY]")
                .replace(Regex("AQ\\.[0-9A-Za-z._-]{20,}"), "[REDACTED_TOKEN]")
            if (!explicitKey.isNullOrBlank() && explicitKey.length >= 8) {
                sanitized = sanitized.replace(explicitKey, "[REDACTED]")
            }
            return sanitized
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // Automatic mode is ON by default (Requirement 7)
    private val _isAutoModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_MODE, true))
    val isAutoModeEnabled: StateFlow<Boolean> = _isAutoModeEnabled.asStateFlow()

    // Cloud inference is ON by default; can be explicitly disabled for offline fallback testing
    private val _cloudInferenceEnabled = MutableStateFlow(true)
    val cloudInferenceEnabled: StateFlow<Boolean> = _cloudInferenceEnabled.asStateFlow()

    private val _activeLiveModelId = MutableStateFlow(VoiceConfig.GEMINI_LIVE_MODEL)
    val activeLiveModelId: StateFlow<String> = _activeLiveModelId.asStateFlow()

    private val _activeTextModelId = MutableStateFlow(AIModelRegistry.MODEL_GEMINI_3_5_FLASH_REST)
    val activeTextModelId: StateFlow<String> = _activeTextModelId.asStateFlow()

    private val _connectionStatus = MutableStateFlow("READY")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _lastRoutingDecisionSummary = MutableStateFlow("Auto-selected priority compatible model")
    val lastRoutingDecisionSummary: StateFlow<String> = _lastRoutingDecisionSummary.asStateFlow()

    private val _telemetrySnapshot = MutableStateFlow(buildSnapshot())
    val telemetrySnapshot: StateFlow<RouterTelemetrySnapshot> = _telemetrySnapshot.asStateFlow()

    init {
        healthMonitor.onConnectivityChanged = { isOnline ->
            RafelLogger.logDiagnostic("AIModelManager connectivity changed: isOnline=$isOnline")
            RafelLiveDiagnostics.onlineOrOfflineMode.value =
                if (isOnline && _cloudInferenceEnabled.value) "ONLINE" else "OFFLINE"
            if (!isOnline) {
                _connectionStatus.value = "OFFLINE"
            } else if (_connectionStatus.value == "OFFLINE") {
                _connectionStatus.value = "ONLINE_RESTORED"
            }
            refreshTelemetrySnapshot()
        }
        RafelLiveDiagnostics.apiKeyConfigured.value = if (resolveEffectiveApiKey().isNotBlank()) "YES" else "NO"
        RafelLiveDiagnostics.onlineOrOfflineMode.value =
            if (healthMonitor.isOnline.value && _cloudInferenceEnabled.value) "ONLINE" else "OFFLINE"
        refreshTelemetrySnapshot()
    }

    fun setAutoModeEnabled(enabled: Boolean) {
        _isAutoModeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_MODE, enabled).apply()
        RafelLogger.logDiagnostic("AIModelManager Automatic Mode set to: $enabled")
        refreshTelemetrySnapshot()
    }

    fun setCloudInferenceEnabled(enabled: Boolean) {
        _cloudInferenceEnabled.value = enabled
        RafelLiveDiagnostics.onlineOrOfflineMode.value =
            if (healthMonitor.isOnline.value && enabled) "ONLINE" else "OFFLINE"
        if (!enabled) {
            RafelLiveDiagnostics.lastFallbackReason.value = "Cloud inference explicitly disabled"
        }
        refreshTelemetrySnapshot()
    }

    fun setPinnedLiveModel(modelId: String) {
        prefs.edit().putString(KEY_PINNED_LIVE_MODEL, modelId).apply()
        if (!_isAutoModeEnabled.value) {
            val descriptor = registry.getModel(modelId)
            _activeLiveModelId.value = descriptor?.liveSetupModelName
                ?: if (modelId.startsWith("models/")) modelId else "models/$modelId"
            refreshTelemetrySnapshot()
        }
    }

    fun setPinnedTextModel(modelId: String) {
        prefs.edit().putString(KEY_PINNED_TEXT_MODEL, modelId).apply()
        if (!_isAutoModeEnabled.value) {
            val descriptor = registry.getModel(modelId)
            _activeTextModelId.value = descriptor?.restModelName ?: modelId.removePrefix("models/")
            refreshTelemetrySnapshot()
        }
    }

    /**
     * Synchronizes the active model in both [AIModelManager] and [RafelLiveDiagnostics]
     * so Diagnostics always reflects the exact model currently generating the response.
     */
    fun syncActiveGeneratingModel(modelId: String, taskType: ModelTaskType? = null) {
        val descriptor = registry.getModel(modelId)
        when {
            descriptor?.providerType == ModelProviderType.GEMINI_LIVE_WS || taskType == ModelTaskType.LIVE_AUDIO -> {
                val liveName = descriptor?.liveSetupModelName ?: if (modelId.startsWith("models/")) modelId else "models/$modelId"
                _activeLiveModelId.value = liveName
                RafelLiveDiagnostics.activeModel.value = liveName
            }
            descriptor?.providerType == ModelProviderType.GEMINI_REST_API || taskType == ModelTaskType.TEXT_CHAT || taskType == ModelTaskType.AUDIO_TRANSCRIPTION -> {
                val restName = descriptor?.restModelName ?: modelId.removePrefix("models/")
                _activeTextModelId.value = restName
                RafelLiveDiagnostics.activeModel.value = restName
            }
            descriptor?.providerType == ModelProviderType.OFFLINE_LOCAL_LLM || taskType == ModelTaskType.OFFLINE_FALLBACK -> {
                _activeTextModelId.value = descriptor?.modelId ?: modelId
                RafelLiveDiagnostics.activeModel.value = descriptor?.modelId ?: modelId
            }
        }
        refreshTelemetrySnapshot()
    }

    fun resolveEffectiveApiKey(overrideKey: String? = null): String {
        val candidate = when {
            !overrideKey.isNullOrBlank() -> overrideKey.trim()
            else -> com.example.ai.security.SecureApiKeyStore.getApiKey(context)
        }
        return if (com.example.ai.security.GeminiSecureGateway.isUsableApiKey(candidate)) candidate else ""
    }

    fun isApiKeyConfigured(overrideKey: String? = null): Boolean {
        val configured = resolveEffectiveApiKey(overrideKey).isNotBlank() ||
            com.example.ai.security.GeminiSecureGateway.isBackendProxyOperational(context)
        RafelLiveDiagnostics.apiKeyConfigured.value = if (configured) "YES" else "NO"
        return configured
    }

    fun auditApiKeySecurity(overrideKey: String? = null): com.example.ai.security.ApiKeyAuditReport {
        return com.example.ai.security.GeminiSecureGateway.auditSecurity(
            context = context,
            effectiveKey = resolveEffectiveApiKey(overrideKey)
        )
    }

    /**
     * Selects the fastest healthy compatible model for the given [taskType].
     * Strictly enforces OFFLINE mode when [_cloudInferenceEnabled] is false.
     */
    fun selectModelForTask(
        taskType: ModelTaskType,
        hasApiKey: Boolean = isApiKeyConfigured(),
        currentActiveModelId: String? = null,
        isTurnInProgress: Boolean = false
    ): ModelRoutingDecision {
        if (!_cloudInferenceEnabled.value) {
            RafelLiveDiagnostics.onlineOrOfflineMode.value = "OFFLINE"
            val offlineVerify = healthMonitor.refreshOfflineModelState()
            if (taskType != ModelTaskType.LIVE_AUDIO && offlineVerify.isWeightFilePresent) {
                val localModel = registry.getModel(AIModelRegistry.MODEL_OFFLINE_LOCAL_LLM)
                if (localModel != null) {
                    val decision = ModelRoutingDecision(
                        taskType = taskType,
                        selectedModel = localModel,
                        fallbackChain = listOf(localModel),
                        candidateScoresMs = emptyMap(),
                        selectionReason = "Offline mode enforced: Selected verified on-device LLM (${offlineVerify.statusMessage})",
                        isAutoMode = _isAutoModeEnabled.value,
                        isOfflineMode = true
                    )
                    _lastRoutingDecisionSummary.value = decision.selectionReason
                    refreshTelemetrySnapshot()
                    return decision
                }
            }
            val decision = ModelRoutingDecision(
                taskType = taskType,
                selectedModel = null,
                fallbackChain = emptyList(),
                candidateScoresMs = emptyMap(),
                selectionReason = "Offline mode enforced: Cloud inference is disabled.",
                isAutoMode = _isAutoModeEnabled.value,
                isOfflineMode = true
            )
            _lastRoutingDecisionSummary.value = decision.selectionReason
            refreshTelemetrySnapshot()
            return decision
        }

        val currentId = currentActiveModelId ?: when (taskType) {
            ModelTaskType.LIVE_AUDIO -> _activeLiveModelId.value
            else -> _activeTextModelId.value
        }

        val preferredManual = when (taskType) {
            ModelTaskType.LIVE_AUDIO -> prefs.getString(KEY_PINNED_LIVE_MODEL, VoiceConfig.GEMINI_LIVE_MODEL)
            else -> prefs.getString(KEY_PINNED_TEXT_MODEL, AIModelRegistry.MODEL_GEMINI_3_5_FLASH_REST)
        }

        val decision = router.routeRequest(
            taskType = taskType,
            isAutoMode = _isAutoModeEnabled.value,
            hasApiKey = hasApiKey,
            currentActiveModelId = currentId,
            isTurnInProgress = isTurnInProgress,
            preferredManualModelId = preferredManual
        )

        decision.selectedModel?.let { selected ->
            if (taskType == ModelTaskType.LIVE_AUDIO) {
                _activeLiveModelId.value = selected.liveSetupModelName
            } else if (taskType == ModelTaskType.TEXT_CHAT) {
                _activeTextModelId.value = selected.restModelName
            }
        }
        _lastRoutingDecisionSummary.value = decision.selectionReason
        refreshTelemetrySnapshot()
        return decision
    }

    /**
     * Handles a model failure or timeout and returns the next healthy compatible model in the fallback chain.
     */
    fun onModelFailureAndSelectFallback(
        taskType: ModelTaskType,
        failedModelId: String,
        reason: String,
        httpOrWsCode: Int = 0,
        hasApiKey: Boolean = isApiKeyConfigured(),
        elapsedBeforeFailureMs: Long = 0L
    ): AIModelDescriptor? {
        val sanitizedReason = redactSecrets(reason)
        RafelLiveDiagnostics.lastFallbackReason.value = sanitizedReason
        val nextModel = fallbackManager.onModelFailedAndSelectNext(
            taskType = taskType,
            failedModelId = failedModelId,
            reason = sanitizedReason,
            httpOrWsCode = httpOrWsCode,
            hasApiKey = hasApiKey,
            elapsedBeforeFailureMs = elapsedBeforeFailureMs
        )
        if (nextModel != null) {
            if (taskType == ModelTaskType.LIVE_AUDIO) {
                _activeLiveModelId.value = nextModel.liveSetupModelName
            } else if (taskType == ModelTaskType.TEXT_CHAT) {
                _activeTextModelId.value = nextModel.restModelName
            }
            _lastRoutingDecisionSummary.value =
                "Fallback switched from $failedModelId to ${nextModel.modelId} ($sanitizedReason)"
        } else {
            _lastRoutingDecisionSummary.value =
                "Model $failedModelId failed ($sanitizedReason); no further fallback available"
        }
        refreshTelemetrySnapshot()
        return nextModel
    }

    /**
     * Records connection handshake latency and updates health state & Diagnostics activeModel.
     */
    fun recordConnectionEstablished(modelId: String, connectTimeMs: Long) {
        if (connectTimeMs > 0L) {
            latencyTracker.recordConnectionTime(modelId, connectTimeMs)
        }
        healthMonitor.recordModelSuccess(modelId)
        syncActiveGeneratingModel(modelId)
        RafelLiveDiagnostics.activeProvider.value = "GEMINI_LIVE_WS"
        RafelLiveDiagnostics.webSocketState.value = "CONNECTED"
        RafelLiveDiagnostics.onlineOrOfflineMode.value = "ONLINE"
        RafelLiveDiagnostics.apiKeyConfigured.value = if (resolveEffectiveApiKey().isNotBlank()) "YES" else "NO"
        _connectionStatus.value = "CONNECTED"
        refreshTelemetrySnapshot()
    }

    /**
     * Records that an actual cloud Gemini model (GEMINI_LIVE or GEMINI_REST) delivered a response.
     */
    fun recordRuntimeResponseDelivered(
        source: String,
        provider: String,
        modelId: String,
        fallbackReason: String? = null,
        explicitApiKey: String? = null
    ) {
        val now = System.currentTimeMillis()
        RafelLiveDiagnostics.actualResponseSource.value = source
        RafelLiveDiagnostics.activeProvider.value = provider
        RafelLiveDiagnostics.activeModel.value = modelId
        RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value = now
        RafelLiveDiagnostics.onlineOrOfflineMode.value =
            if (healthMonitor.isOnline.value && _cloudInferenceEnabled.value) "ONLINE" else "OFFLINE"
        RafelLiveDiagnostics.apiKeyConfigured.value =
            if (resolveEffectiveApiKey(explicitApiKey).isNotBlank()) "YES" else "NO"
        if (!fallbackReason.isNullOrBlank()) {
            RafelLiveDiagnostics.lastFallbackReason.value = redactSecrets(fallbackReason, explicitApiKey)
        }
        if (source == "GEMINI_LIVE") {
            _activeLiveModelId.value = modelId
        } else if (source == "GEMINI_REST") {
            _activeTextModelId.value = modelId.removePrefix("models/")
        }
        refreshTelemetrySnapshot()
    }

    /**
     * Records that the response was generated by the local contextual fallback engine
     * ("rafel-local-contextual-v1") so Diagnostics NEVER falsely claims a Gemini response.
     */
    fun recordLocalFallbackResponse(
        reason: String,
        explicitApiKey: String? = null
    ) {
        val sanitizedReason = redactSecrets(reason, explicitApiKey)
        RafelLiveDiagnostics.actualResponseSource.value = "LOCAL_FALLBACK"
        RafelLiveDiagnostics.activeProvider.value = "LOCAL_FALLBACK"
        RafelLiveDiagnostics.activeModel.value = "rafel-local-contextual-v1"
        _activeTextModelId.value = "rafel-local-contextual-v1"
        RafelLiveDiagnostics.lastFallbackReason.value = sanitizedReason
        RafelLiveDiagnostics.onlineOrOfflineMode.value =
            if (healthMonitor.isOnline.value && _cloudInferenceEnabled.value) "ONLINE" else "OFFLINE"
        RafelLiveDiagnostics.apiKeyConfigured.value =
            if (resolveEffectiveApiKey(explicitApiKey).isNotBlank()) "YES" else "NO"
        _lastRoutingDecisionSummary.value = "Local fallback used ($sanitizedReason)"
        refreshTelemetrySnapshot()
    }

    /**
     * Records Time To First Response (TTFR) for a model turn.
     */
    fun recordTimeToFirstResponse(modelId: String, ttfrMs: Long) {
        if (ttfrMs > 0L) {
            latencyTracker.recordTimeToFirstResponse(modelId, ttfrMs)
        }
        syncActiveGeneratingModel(modelId)
        refreshTelemetrySnapshot()
    }

    /**
     * Records Time To First Audio Chunk (TTFA) for a Gemini Live audio turn.
     */
    fun recordTimeToFirstAudioChunk(modelId: String, ttfaMs: Long) {
        if (ttfaMs > 0L) {
            latencyTracker.recordTimeToFirstAudioChunk(modelId, ttfaMs)
        }
        syncActiveGeneratingModel(modelId)
        refreshTelemetrySnapshot()
    }

    /**
     * Records a completed turn/request and evaluates whether any deferred safe model upgrade is waiting.
     */
    fun recordTurnCompleted(
        modelId: String,
        totalResponseTimeMs: Long,
        ttfrMs: Long? = null,
        ttfaMs: Long? = null,
        connectTimeMs: Long? = null
    ): String? {
        latencyTracker.recordSuccess(
            modelId = modelId,
            totalResponseTimeMs = totalResponseTimeMs,
            ttfrMs = ttfrMs,
            ttfaMs = ttfaMs,
            connectTimeMs = connectTimeMs
        )
        healthMonitor.recordModelSuccess(modelId)
        syncActiveGeneratingModel(modelId)

        // Check if a pending safe upgrade was queued while the turn was active
        val pendingUpgrade = fallbackManager.consumePendingSafeUpgrade()
        refreshTelemetrySnapshot()
        return pendingUpgrade
    }

    fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
        refreshTelemetrySnapshot()
    }

    /**
     * Periodically or on-demand evaluates whether another compatible model is consistently faster
     * than the currently active model when no turn is in progress.
     */
    fun evaluatePeriodicModelOptimization(
        taskType: ModelTaskType,
        isTurnInProgress: Boolean,
        hasApiKey: Boolean = resolveEffectiveApiKey().isNotBlank()
    ): AIModelDescriptor? {
        if (!_isAutoModeEnabled.value || isTurnInProgress) return null
        val currentId = if (taskType == ModelTaskType.LIVE_AUDIO) {
            _activeLiveModelId.value
        } else {
            _activeTextModelId.value
        }
        val decision = selectModelForTask(
            taskType = taskType,
            hasApiKey = hasApiKey,
            currentActiveModelId = currentId,
            isTurnInProgress = false
        )
        val selected = decision.selectedModel ?: return null
        return if (selected.modelId != currentId && selected.restModelName != currentId.removePrefix("models/")) {
            selected
        } else {
            null
        }
    }

    /**
     * Validates every registered model using its actual supported API protocol:
     * - GEMINI_LIVE_WS: Establishes a real WebSocket session, sends setup, verifies setupComplete,
     *   sends a test prompt, and measures real TTFR, TTFA, and audio generation.
     * - GEMINI_REST_API: Sends a real POST :generateContent request and verifies non-empty generated text.
     * - OFFLINE_LOCAL_LLM: Verifies whether real on-device model weight files exist on disk.
     *
     * Never reports mere HTTP reachability as successful AI generation.
     */
    suspend fun probeAllModels(
        customApiKey: String? = null,
        httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build(),
        liveWsEndpointOverride: String? = null,
        restBaseUrlOverride: String? = null,
        liveTimeoutMs: Long = 10_000L
    ): List<ModelEndpointProbeResult> = withContext(Dispatchers.IO) {
        val apiKey = resolveEffectiveApiKey(customApiKey)
        val results = mutableListOf<ModelEndpointProbeResult>()

        for (model in registry.getAllModels()) {
            val res = probeSingleModel(
                model = model,
                apiKey = apiKey,
                httpClient = httpClient,
                liveWsEndpointOverride = liveWsEndpointOverride,
                restBaseUrlOverride = restBaseUrlOverride,
                liveTimeoutMs = liveTimeoutMs
            )
            results.add(res)
        }
        refreshTelemetrySnapshot()
        results
    }

    suspend fun probeSingleModel(
        model: AIModelDescriptor,
        apiKey: String = resolveEffectiveApiKey(),
        httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build(),
        liveWsEndpointOverride: String? = null,
        restBaseUrlOverride: String? = null,
        liveTimeoutMs: Long = 10_000L
    ): ModelEndpointProbeResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // 1. Offline Local LLM check: verify real weight files on disk
        if (model.providerType == ModelProviderType.OFFLINE_LOCAL_LLM) {
            val verify = healthMonitor.refreshOfflineModelState()
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            return@withContext ModelEndpointProbeResult(
                modelId = model.modelId,
                providerType = model.providerType,
                isReachable = verify.isWeightFilePresent,
                statusCode = if (verify.isWeightFilePresent) 200 else 404,
                latencyMs = elapsed,
                healthState = if (verify.isWeightFilePresent) ModelHealthState.HEALTHY else ModelHealthState.MISSING_WEIGHTS,
                detailMessage = verify.statusMessage,
                generationVerified = verify.isWeightFilePresent
            )
        }

        // 2. Check online state & API key
        if (!healthMonitor.isOnline.value) {
            return@withContext ModelEndpointProbeResult(
                modelId = model.modelId,
                providerType = model.providerType,
                isReachable = false,
                statusCode = 0,
                latencyMs = 0L,
                healthState = ModelHealthState.OFFLINE,
                detailMessage = "Device is offline"
            )
        }

        if (apiKey.isBlank()) {
            return@withContext ModelEndpointProbeResult(
                modelId = model.modelId,
                providerType = model.providerType,
                isReachable = false,
                statusCode = 401,
                latencyMs = 0L,
                healthState = ModelHealthState.UNVERIFIED,
                detailMessage = "API key not configured; endpoint requires GEMINI_API_KEY"
            )
        }

        // 3. Dispatch to protocol-specific real validator
        if (model.providerType == ModelProviderType.GEMINI_LIVE_WS) {
            return@withContext validateLiveWebSocketModel(
                model = model,
                apiKey = apiKey,
                httpClient = httpClient,
                endpointOverride = liveWsEndpointOverride,
                timeoutMs = liveTimeoutMs
            )
        } else {
            return@withContext validateRestGenerationModel(
                model = model,
                apiKey = apiKey,
                httpClient = httpClient,
                baseUrlOverride = restBaseUrlOverride
            )
        }
    }

    /**
     * Validates a GEMINI_REST_API model by sending an actual `generateContent` prompt
     * and verifying that a non-blank text candidate is generated.
     */
    private fun validateRestGenerationModel(
        model: AIModelDescriptor,
        apiKey: String,
        httpClient: OkHttpClient,
        baseUrlOverride: String? = null
    ): ModelEndpointProbeResult {
        val t0 = System.currentTimeMillis()
        val baseUrl = (baseUrlOverride ?: AIModelRegistry.GEMINI_REST_BASE_URL).trimEnd('/')
        val url = "$baseUrl/${model.restModelName}:generateContent"
        val payload = """{"contents":[{"role":"user","parts":[{"text":"Reply with the single word READY."}]}],"generationConfig":{"maxOutputTokens":16,"temperature":0.1}}"""

        return try {
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                val code = response.code
                val rawBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val generatedText = try {
                        val root = json.parseToJsonElement(rawBody).jsonObject
                        root["candidates"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("content")
                            ?.jsonObject?.get("parts")
                            ?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("text")
                            ?.jsonPrimitive?.content?.trim().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }

                    if (generatedText.isNotBlank()) {
                        latencyTracker.recordSuccess(
                            modelId = model.modelId,
                            totalResponseTimeMs = elapsed,
                            ttfrMs = elapsed,
                            connectTimeMs = elapsed
                        )
                        healthMonitor.recordModelSuccess(model.modelId)
                        ModelEndpointProbeResult(
                            modelId = model.modelId,
                            providerType = model.providerType,
                            isReachable = true,
                            statusCode = code,
                            latencyMs = elapsed,
                            healthState = ModelHealthState.HEALTHY,
                            detailMessage = "Verified REST generation (HTTP $code, TTFR=${elapsed}ms, reply=\"${generatedText.take(32)}\")",
                            connectTimeMs = elapsed,
                            ttfrMs = elapsed,
                            generationVerified = true,
                            responsePreview = generatedText.take(80)
                        )
                    } else {
                        val reason = "HTTP $code reachable but no candidate text generated"
                        healthMonitor.recordModelFailure(model.modelId, reason, code)
                        latencyTracker.recordFailure(model.modelId, elapsed)
                        ModelEndpointProbeResult(
                            modelId = model.modelId,
                            providerType = model.providerType,
                            isReachable = false,
                            statusCode = code,
                            latencyMs = elapsed,
                            healthState = healthMonitor.getHealthState(model.modelId),
                            detailMessage = reason,
                            connectTimeMs = elapsed,
                            generationVerified = false
                        )
                    }
                } else {
                    val sanitizedErr = redactSecrets(rawBody.take(160), apiKey)
                    val errMsg = try {
                        val errObj = json.parseToJsonElement(rawBody).jsonObject["error"]?.jsonObject
                        errObj?.get("message")?.jsonPrimitive?.content ?: sanitizedErr
                    } catch (_: Exception) {
                        sanitizedErr
                    }
                    val reason = "HTTP $code: ${redactSecrets(errMsg, apiKey)}"
                    healthMonitor.recordModelFailure(model.modelId, reason, code)
                    latencyTracker.recordFailure(model.modelId, elapsed)
                    ModelEndpointProbeResult(
                        modelId = model.modelId,
                        providerType = model.providerType,
                        isReachable = false,
                        statusCode = code,
                        latencyMs = elapsed,
                        healthState = healthMonitor.getHealthState(model.modelId),
                        detailMessage = reason
                    )
                }
            }
        } catch (e: Exception) {
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val errReason = redactSecrets("${e.javaClass.simpleName}: ${e.message ?: "Network error"}", apiKey)
            healthMonitor.recordModelFailure(model.modelId, errReason, 0)
            latencyTracker.recordFailure(model.modelId, elapsed)
            ModelEndpointProbeResult(
                modelId = model.modelId,
                providerType = model.providerType,
                isReachable = false,
                statusCode = 0,
                latencyMs = elapsed,
                healthState = healthMonitor.getHealthState(model.modelId),
                detailMessage = "Network error: $errReason"
            )
        }
    }

    /**
     * Validates a GEMINI_LIVE_WS model by establishing a real WebSocket session,
     * sending the setup frame, verifying `setupComplete`, sending a test prompt,
     * and measuring real `connectTimeMs`, `ttfrMs`, `ttfaMs`, and audio/text output.
     */
    private fun validateLiveWebSocketModel(
        model: AIModelDescriptor,
        apiKey: String,
        httpClient: OkHttpClient,
        endpointOverride: String? = null,
        timeoutMs: Long = 10_000L
    ): ModelEndpointProbeResult {
        val connectStartMs = System.currentTimeMillis()
        val baseEndpoint = endpointOverride ?: model.endpointUrl
        val separator = if (baseEndpoint.contains("?")) "&" else "?"
        val wsUrl = "${baseEndpoint}${separator}key=$apiKey"

        val latch = CountDownLatch(1)
        val setupCompleteVerified = AtomicBoolean(false)
        val generationVerified = AtomicBoolean(false)
        val connectTimeMs = AtomicLong(0L)
        val promptSentAtMs = AtomicLong(0L)
        val ttfrMs = AtomicLong(0L)
        val ttfaMs = AtomicLong(0L)
        val audioBytesCount = AtomicLong(0L)
        val statusCodeRef = AtomicInteger(0)
        val errorDetailRef = AtomicReference<String?>(null)
        val responsePreviewSb = StringBuilder()

        val request = Request.Builder().url(wsUrl).build()
        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                statusCodeRef.set(response.code)
                val setupMsg = buildJsonObject {
                    putJsonObject("setup") {
                        put("model", model.liveSetupModelName)
                        putJsonObject("generationConfig") {
                            putJsonArray("responseModalities") { add("AUDIO") }
                            putJsonObject("speechConfig") {
                                putJsonObject("voiceConfig") {
                                    putJsonObject("prebuiltVoiceConfig") {
                                        put("voiceName", VoiceConfig.GEMINI_VOICE_NAME)
                                    }
                                }
                            }
                        }
                    }
                }.toString()
                webSocket.send(setupMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingFrame(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleIncomingFrame(webSocket, bytes.utf8())
            }

            private fun handleIncomingFrame(webSocket: WebSocket, frameText: String) {
                try {
                    val jsonMsg = json.parseToJsonElement(frameText).jsonObject

                    if (jsonMsg.containsKey("error")) {
                        val errObj = jsonMsg["error"]?.jsonObject
                        val errCode = errObj?.get("code")?.jsonPrimitive?.intOrNull ?: 500
                        val errMsg = errObj?.get("message")?.jsonPrimitive?.content ?: frameText.take(120)
                        statusCodeRef.set(errCode)
                        errorDetailRef.set("WS server error ($errCode): ${redactSecrets(errMsg, apiKey)}")
                        try { webSocket.close(1000, "Error received") } catch (_: Exception) {}
                        latch.countDown()
                        return
                    }

                    if (jsonMsg.containsKey("setupComplete") || jsonMsg.containsKey("setup_complete")) {
                        val now = System.currentTimeMillis()
                        connectTimeMs.set((now - connectStartMs).coerceAtLeast(1L))
                        setupCompleteVerified.set(true)
                        promptSentAtMs.set(now)

                        val promptMsg = buildJsonObject {
                            putJsonObject("clientContent") {
                                putJsonArray("turns") {
                                    add(buildJsonObject {
                                        put("role", "user")
                                        putJsonArray("parts") {
                                            add(buildJsonObject {
                                                put("text", "Say hello in one word.")
                                            })
                                        }
                                    })
                                }
                                put("turnComplete", true)
                            }
                        }.toString()
                        webSocket.send(promptMsg)
                    }

                    val serverContent = jsonMsg["serverContent"]?.jsonObject ?: jsonMsg["server_content"]?.jsonObject
                    if (serverContent != null) {
                        val now = System.currentTimeMillis()
                        val sentAt = promptSentAtMs.get().takeIf { it > 0L } ?: connectStartMs
                        if (ttfrMs.get() == 0L) {
                            ttfrMs.set((now - sentAt).coerceAtLeast(1L))
                        }

                        val modelTurn = serverContent["modelTurn"]?.jsonObject ?: serverContent["model_turn"]?.jsonObject
                        modelTurn?.get("parts")?.jsonArray?.forEach { partEl ->
                            val part = partEl.jsonObject
                            val inlineObj = part["inlineData"]?.jsonObject
                                ?: part["inline_data"]?.jsonObject
                                ?: part["audio"]?.jsonObject
                            val b64 = inlineObj?.get("data")?.jsonPrimitive?.content
                                ?: part["data"]?.jsonPrimitive?.content
                            if (!b64.isNullOrEmpty()) {
                                val decoded = try {
                                    Base64.decode(b64, Base64.DEFAULT)
                                } catch (_: Exception) {
                                    ByteArray(0)
                                }
                                if (decoded.isNotEmpty()) {
                                    if (ttfaMs.get() == 0L) {
                                        ttfaMs.set((System.currentTimeMillis() - sentAt).coerceAtLeast(1L))
                                    }
                                    audioBytesCount.addAndGet(decoded.size.toLong())
                                    generationVerified.set(true)
                                }
                            }
                            val txt = part["text"]?.jsonPrimitive?.content
                            if (!txt.isNullOrBlank()) {
                                synchronized(responsePreviewSb) {
                                    responsePreviewSb.append(txt).append(" ")
                                }
                                generationVerified.set(true)
                            }
                        }

                        val isTurnDone = serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true ||
                            serverContent["turn_complete"]?.jsonPrimitive?.booleanOrNull == true ||
                            serverContent["turnComplete"]?.jsonPrimitive?.content == "true"
                        if (isTurnDone) {
                            try { webSocket.close(1000, "Validation complete") } catch (_: Exception) {}
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    errorDetailRef.set("JSON parse error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!generationVerified.get()) {
                    statusCodeRef.set(code)
                    if (errorDetailRef.get() == null) {
                        errorDetailRef.set("WS closed ($code): ${redactSecrets(reason, apiKey)}")
                    }
                }
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!generationVerified.get()) {
                    statusCodeRef.set(code)
                    if (errorDetailRef.get() == null) {
                        errorDetailRef.set("WS closed ($code): ${redactSecrets(reason, apiKey)}")
                    }
                }
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code ?: statusCodeRef.get()
                statusCodeRef.set(httpCode)
                val bodyStr = try { response?.body?.string().orEmpty().take(140) } catch (_: Exception) { "" }
                val rawReason = "${t.javaClass.simpleName}: ${t.message ?: "Connection failed"}" +
                    (if (bodyStr.isNotBlank()) " ($bodyStr)" else "")
                errorDetailRef.set(redactSecrets(rawReason, apiKey))
                latch.countDown()
            }
        })

        val completedInTime = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!completedInTime) {
            try { ws.cancel() } catch (_: Exception) {}
        }

        val totalElapsed = (System.currentTimeMillis() - connectStartMs).coerceAtLeast(1L)
        val isSuccess = setupCompleteVerified.get() && generationVerified.get()

        return if (isSuccess) {
            val cMs = connectTimeMs.get().takeIf { it > 0L } ?: totalElapsed
            val firstRespMs = ttfrMs.get().takeIf { it > 0L } ?: totalElapsed
            val firstAudioMs = ttfaMs.get().takeIf { it > 0L }
            latencyTracker.recordSuccess(
                modelId = model.modelId,
                totalResponseTimeMs = totalElapsed,
                ttfrMs = firstRespMs,
                ttfaMs = firstAudioMs,
                connectTimeMs = cMs
            )
            healthMonitor.recordModelSuccess(model.modelId)
            ModelEndpointProbeResult(
                modelId = model.modelId,
                providerType = model.providerType,
                isReachable = true,
                statusCode = 101,
                latencyMs = totalElapsed,
                healthState = ModelHealthState.HEALTHY,
                detailMessage = "Verified Live WS setupComplete (${cMs}ms), TTFR=${firstRespMs}ms" +
                    (if (firstAudioMs != null) ", TTFA=${firstAudioMs}ms, audio=${audioBytesCount.get()}B" else ""),
                connectTimeMs = cMs,
                ttfrMs = firstRespMs,
                ttfaMs = firstAudioMs ?: 0L,
                setupCompleteVerified = true,
                generationVerified = true,
                audioBytesReceived = audioBytesCount.get(),
                responsePreview = synchronized(responsePreviewSb) { responsePreviewSb.toString().trim().take(80) }
            )
        } else {
            val code = statusCodeRef.get().takeIf { it != 0 } ?: if (!completedInTime) 408 else 503
            val reason = errorDetailRef.get() ?: when {
                !completedInTime && !setupCompleteVerified.get() -> "Timeout (${timeoutMs}ms) waiting for WebSocket setupComplete"
                !completedInTime -> "Timeout (${timeoutMs}ms) waiting for Live model response after setupComplete"
                setupCompleteVerified.get() && !generationVerified.get() -> "setupComplete received, but model produced no response/audio"
                else -> "Live WebSocket session failed (code=$code)"
            }
            healthMonitor.recordModelFailure(model.modelId, reason, code)
            latencyTracker.recordFailure(model.modelId, totalElapsed)
            ModelEndpointProbeResult(
                modelId = model.modelId,
                providerType = model.providerType,
                isReachable = false,
                statusCode = code,
                latencyMs = totalElapsed,
                healthState = healthMonitor.getHealthState(model.modelId),
                detailMessage = reason,
                connectTimeMs = connectTimeMs.get(),
                ttfrMs = ttfrMs.get(),
                ttfaMs = ttfaMs.get(),
                setupCompleteVerified = setupCompleteVerified.get(),
                generationVerified = false,
                audioBytesReceived = audioBytesCount.get()
            )
        }
    }

    fun refreshTelemetrySnapshot(): RouterTelemetrySnapshot {
        val snap = buildSnapshot()
        _telemetrySnapshot.value = snap
        return snap
    }

    private fun buildSnapshot(): RouterTelemetrySnapshot {
        val allModels = registry.getAllModels()
        val healthMap = allModels.associate { it.modelId to healthMonitor.getHealthState(it.modelId) }
        val statsMap = allModels.associate { model ->
            val avail = healthMonitor.getAvailabilityPercent(model.modelId)
            model.modelId to latencyTracker.getStatsForModel(model, avail)
        }

        val activeLiveStats = statsMap[_activeLiveModelId.value]
        val activeTextStats = statsMap[_activeTextModelId.value]

        val recentLatency = when {
            (activeLiveStats?.lastResponseLatencyMs ?: 0L) > 0L -> activeLiveStats!!.lastResponseLatencyMs
            (activeTextStats?.lastResponseLatencyMs ?: 0L) > 0L -> activeTextStats!!.lastResponseLatencyMs
            else -> 0L
        }

        val offlineVerify = registry.verifyOfflineModelWeights(context)
        val hasConfiguredKey = try {
            resolveEffectiveApiKey().isNotBlank()
        } catch (_: Exception) {
            false
        }
        val keyConfiguredStr = if (hasConfiguredKey) "YES" else "NO"
        RafelLiveDiagnostics.apiKeyConfigured.value = keyConfiguredStr
        val modeStr = if (healthMonitor.isOnline.value && _cloudInferenceEnabled.value) "ONLINE" else "OFFLINE"
        RafelLiveDiagnostics.onlineOrOfflineMode.value = modeStr

        return RouterTelemetrySnapshot(
            isAutoModeEnabled = _isAutoModeEnabled.value,
            isOnline = healthMonitor.isOnline.value && _cloudInferenceEnabled.value,
            activeProvider = RafelLiveDiagnostics.activeProvider.value,
            activeModel = RafelLiveDiagnostics.activeModel.value,
            activeLiveModelId = _activeLiveModelId.value,
            activeTextModelId = _activeTextModelId.value,
            onlineOrOfflineMode = modeStr,
            apiKeyConfigured = keyConfiguredStr,
            restRequestStatus = RafelLiveDiagnostics.restRequestStatus.value,
            webSocketState = RafelLiveDiagnostics.webSocketState.value,
            setupComplete = RafelLiveDiagnostics.setupComplete.value,
            firstAudioChunkReceived = RafelLiveDiagnostics.firstAudioChunkReceived.value,
            lastSuccessfulModelResponseTimestamp = RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value,
            lastFallbackReason = RafelLiveDiagnostics.lastFallbackReason.value,
            actualResponseSource = RafelLiveDiagnostics.actualResponseSource.value,
            connectionStatus = if (!healthMonitor.isOnline.value || !_cloudInferenceEnabled.value) "OFFLINE" else _connectionStatus.value,
            recentLatencyMs = recentLatency,
            recentTtfaMs = activeLiveStats?.avgTimeToFirstAudioChunkMs ?: 0L,
            recentTtfrMs = if ((activeLiveStats?.avgTimeToFirstResponseMs ?: 0L) > 0L) {
                activeLiveStats!!.avgTimeToFirstResponseMs
            } else {
                activeTextStats?.avgTimeToFirstResponseMs ?: 0L
            },
            recentConnectMs = activeLiveStats?.avgConnectTimeMs ?: 0L,
            modelHealthMap = healthMap,
            modelStatsMap = statsMap,
            fallbackEvents = fallbackManager.fallbackHistory.value,
            offlineVerification = offlineVerify,
            lastRoutingDecisionSummary = _lastRoutingDecisionSummary.value
        )
    }
}
