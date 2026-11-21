package com.example.ai.router

import android.content.Context
import com.example.voice.VoiceConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of all AI models discovered and configured in the RAFEL project.
 * Does not invent unverified model IDs; registers the exact models present in
 * VoiceConfig, LiveSessionManager, GeminiTextClient, and local offline storage checks.
 */
class AIModelRegistry {

    companion object {
        const val MODEL_GEMINI_3_8_LIVE = VoiceConfig.GEMINI_LIVE_MODEL // "models/gemini-3.8-live" (legacy candidate; auto-fails over to 2.5-flash-native-audio)
        const val MODEL_GEMINI_2_5_FLASH_NATIVE_AUDIO = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val MODEL_GEMINI_2_0_FLASH_LIVE_001 = "models/gemini-2.0-flash-live-001"
        const val MODEL_GEMINI_2_0_FLASH_EXP = "models/gemini-2.0-flash-exp"

        const val MODEL_GEMINI_3_5_FLASH_REST = "gemini-3.5-flash" // (legacy candidate; auto-fails over to gemini-2.5-flash)
        const val MODEL_GEMINI_2_5_FLASH_REST = "gemini-2.5-flash"
        const val MODEL_GEMINI_2_5_FLASH_LITE_REST = "gemini-2.5-flash-lite"
        const val MODEL_GEMINI_2_0_FLASH_REST = "gemini-2.0-flash"

        const val MODEL_OFFLINE_LOCAL_LLM = "local/on-device-llm"

        val OBSOLETE_OR_UNSUPPORTED_MODEL_IDS = setOf(
            "models/gemini-3.8-live",
            "gemini-3.8-live",
            "gemini-3.5-flash",
            "models/gemini-3.5-flash",
            "gemini-1.0-pro",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-pro"
        )

        val VERIFIED_SUPPORTED_REST_MODELS = listOf(
            MODEL_GEMINI_2_5_FLASH_REST,
            MODEL_GEMINI_2_5_FLASH_LITE_REST,
            MODEL_GEMINI_2_0_FLASH_REST
        )

        val VERIFIED_SUPPORTED_LIVE_MODELS = listOf(
            MODEL_GEMINI_2_5_FLASH_NATIVE_AUDIO,
            MODEL_GEMINI_2_0_FLASH_LIVE_001,
            MODEL_GEMINI_2_0_FLASH_EXP
        )

        fun isObsoleteOrUnsupportedModel(modelId: String): Boolean {
            val clean = modelId.trim()
            return OBSOLETE_OR_UNSUPPORTED_MODEL_IDS.any { it.equals(clean, ignoreCase = true) }
        }

        fun sanitizeSupportedModelId(modelId: String, providerType: ModelProviderType): String {
            if (isObsoleteOrUnsupportedModel(modelId)) {
                return when (providerType) {
                    ModelProviderType.GEMINI_LIVE_WS -> MODEL_GEMINI_2_5_FLASH_NATIVE_AUDIO
                    ModelProviderType.GEMINI_REST_API -> MODEL_GEMINI_2_5_FLASH_REST
                    ModelProviderType.OFFLINE_LOCAL_LLM -> MODEL_OFFLINE_LOCAL_LLM
                }
            }
            return modelId
        }

        const val GEMINI_WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val GEMINI_REST_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"

        val DEFAULT_EXPECTED_WEIGHT_EXTENSIONS = listOf(".gguf", ".tflite", ".task", ".bin", ".onnx")
    }

    private val modelsMap = ConcurrentHashMap<String, AIModelDescriptor>()

    @Volatile
    private var customOfflineWeightDirectoryOverride: File? = null

    init {
        registerDefaultProjectModels()
    }

    /**
     * Populates the registry with all models configured in the RAFEL codebase.
     */
    fun registerDefaultProjectModels() {
        modelsMap.clear()

        // 1. Primary Gemini Live WebSocket Model (VoiceConfig.GEMINI_LIVE_MODEL)
        registerModel(
            AIModelDescriptor(
                modelId = MODEL_GEMINI_3_8_LIVE,
                displayName = "Gemini 3.8 Live (Native Audio)",
                providerType = ModelProviderType.GEMINI_LIVE_WS,
                endpointUrl = GEMINI_WS_ENDPOINT,
                capabilities = setOf(
                    ModelCapability.LIVE_AUDIO_BIDI,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION_MULTIMODAL,
                    ModelCapability.TEXT_GENERATION
                ),
                priorityRank = 1,
                baselineExpectedLatencyMs = 320L,
                requiresInternet = true,
                requiresApiKey = true
            )
        )

        // 2. First Gemini Live WebSocket Fallback (Configured in LiveSessionManager)
        registerModel(
            AIModelDescriptor(
                modelId = MODEL_GEMINI_2_5_FLASH_NATIVE_AUDIO,
                displayName = "Gemini 2.5 Flash Native Audio Live",
                providerType = ModelProviderType.GEMINI_LIVE_WS,
                endpointUrl = GEMINI_WS_ENDPOINT,
                capabilities = setOf(
                    ModelCapability.LIVE_AUDIO_BIDI,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION_MULTIMODAL,
                    ModelCapability.TEXT_GENERATION
                ),
                priorityRank = 2,
                baselineExpectedLatencyMs = 380L,
                requiresInternet = true,
                requiresApiKey = true
            )
        )

        // 3. Second Gemini Live WebSocket Fallback (Configured in LiveSessionManager)
        registerModel(
            AIModelDescriptor(
                modelId = MODEL_GEMINI_2_0_FLASH_EXP,
                displayName = "Gemini 2.0 Flash Live",
                providerType = ModelProviderType.GEMINI_LIVE_WS,
                endpointUrl = GEMINI_WS_ENDPOINT,
                capabilities = setOf(
                    ModelCapability.LIVE_AUDIO_BIDI,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION_MULTIMODAL,
                    ModelCapability.TEXT_GENERATION
                ),
                priorityRank = 3,
                baselineExpectedLatencyMs = 430L,
                requiresInternet = true,
                requiresApiKey = true
            )
        )

        // 4. Primary Gemini Flash REST Model (Configured in GeminiTextClient)
        registerModel(
            AIModelDescriptor(
                modelId = MODEL_GEMINI_3_5_FLASH_REST,
                displayName = "Gemini 3.5 Flash (REST)",
                providerType = ModelProviderType.GEMINI_REST_API,
                endpointUrl = "$GEMINI_REST_BASE_URL/$MODEL_GEMINI_3_5_FLASH_REST:generateContent",
                capabilities = setOf(
                    ModelCapability.TEXT_GENERATION,
                    ModelCapability.AUDIO_TRANSCRIPTION,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION_MULTIMODAL
                ),
                priorityRank = 1,
                baselineExpectedLatencyMs = 410L,
                requiresInternet = true,
                requiresApiKey = true
            )
        )

        // 5. Fallback Gemini Flash REST & Audio Transcription Model (GeminiTextClient & LiveSessionManager)
        registerModel(
            AIModelDescriptor(
                modelId = MODEL_GEMINI_2_5_FLASH_REST,
                displayName = "Gemini 2.5 Flash (REST & STT)",
                providerType = ModelProviderType.GEMINI_REST_API,
                endpointUrl = "$GEMINI_REST_BASE_URL/$MODEL_GEMINI_2_5_FLASH_REST:generateContent",
                capabilities = setOf(
                    ModelCapability.TEXT_GENERATION,
                    ModelCapability.AUDIO_TRANSCRIPTION,
                    ModelCapability.TOOL_CALLING,
                    ModelCapability.VISION_MULTIMODAL
                ),
                priorityRank = 2,
                baselineExpectedLatencyMs = 450L,
                requiresInternet = true,
                requiresApiKey = true
            )
        )

        // 6. Local / Offline LLM Slot (Verified against real on-device weight files)
        registerModel(
            AIModelDescriptor(
                modelId = MODEL_OFFLINE_LOCAL_LLM,
                displayName = "On-Device Local LLM",
                providerType = ModelProviderType.OFFLINE_LOCAL_LLM,
                endpointUrl = "file:///android_asset/models",
                capabilities = setOf(
                    ModelCapability.OFFLINE_EXECUTION,
                    ModelCapability.TEXT_GENERATION
                ),
                priorityRank = 10,
                baselineExpectedLatencyMs = 600L,
                requiresInternet = false,
                requiresApiKey = false,
                expectedWeightFileNames = listOf(
                    "model.gguf",
                    "gemma.tflite",
                    "gemma.task",
                    "rafel_local.gguf",
                    "rafel_local.tflite"
                )
            )
        )
    }

    fun registerModel(descriptor: AIModelDescriptor) {
        modelsMap[descriptor.modelId] = descriptor
    }

    fun getModel(modelId: String): AIModelDescriptor? {
        modelsMap[modelId]?.let { return it }
        val normalized = if (modelId.startsWith("models/")) {
            modelId.removePrefix("models/")
        } else {
            "models/$modelId"
        }
        return modelsMap[normalized]
    }

    fun getAllModels(): List<AIModelDescriptor> {
        return modelsMap.values.sortedWith(
            compareBy<AIModelDescriptor> { it.providerType.ordinal }.thenBy { it.priorityRank }
        )
    }

    fun getModelsByProvider(providerType: ModelProviderType): List<AIModelDescriptor> {
        return modelsMap.values
            .filter { it.providerType == providerType }
            .sortedBy { it.priorityRank }
    }

    fun getLiveAudioModels(): List<AIModelDescriptor> {
        return getModelsByProvider(ModelProviderType.GEMINI_LIVE_WS)
    }

    fun getRestTextModels(): List<AIModelDescriptor> {
        return getModelsByProvider(ModelProviderType.GEMINI_REST_API)
    }

    fun setOfflineWeightDirectoryOverrideForTesting(dir: File?) {
        customOfflineWeightDirectoryOverride = dir
    }

    /**
     * Checks whether any real local LLM model weight files (.gguf, .tflite, .task, .bin, .onnx)
     * exist in the app's internal storage, external files dir, or APK assets.
     * Never pretends offline AI is working when model weight files are missing.
     */
    fun verifyOfflineModelWeights(context: Context): OfflineModelVerificationResult {
        val checkedPaths = mutableListOf<String>()

        val candidateDirs = mutableListOf<File>()
        customOfflineWeightDirectoryOverride?.let { candidateDirs.add(it) }
        candidateDirs.add(File(context.filesDir, "models"))
        candidateDirs.add(context.filesDir)
        context.getExternalFilesDir("models")?.let { candidateDirs.add(it) }

        for (dir in candidateDirs) {
            checkedPaths.add(dir.absolutePath)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles().orEmpty()
                val match = files.firstOrNull { file ->
                    file.isFile && file.length() > 1024L && DEFAULT_EXPECTED_WEIGHT_EXTENSIONS.any { ext ->
                        file.name.lowercase().endsWith(ext)
                    }
                }
                if (match != null) {
                    return OfflineModelVerificationResult(
                        modelId = MODEL_OFFLINE_LOCAL_LLM,
                        isWeightFilePresent = true,
                        checkedPaths = checkedPaths,
                        detectedFilePath = match.absolutePath,
                        detectedFileSizeBytes = match.length(),
                        localIntentRouterAvailable = true,
                        statusMessage = "Offline LLM weight file verified: ${match.name} (${match.length() / 1024} KB)"
                    )
                }
            }
        }

        // Also check APK assets/models if present
        try {
            checkedPaths.add("assets/models")
            val assetFiles = context.assets.list("models").orEmpty()
            val matchingAsset = assetFiles.firstOrNull { name ->
                DEFAULT_EXPECTED_WEIGHT_EXTENSIONS.any { ext -> name.lowercase().endsWith(ext) }
            }
            if (matchingAsset != null) {
                return OfflineModelVerificationResult(
                    modelId = MODEL_OFFLINE_LOCAL_LLM,
                    isWeightFilePresent = true,
                    checkedPaths = checkedPaths,
                    detectedFilePath = "assets/models/$matchingAsset",
                    detectedFileSizeBytes = 1024L,
                    localIntentRouterAvailable = true,
                    statusMessage = "Offline LLM asset verified: assets/models/$matchingAsset"
                )
            }
        } catch (_: Exception) {
            // Asset folder not present
        }

        return OfflineModelVerificationResult(
            modelId = MODEL_OFFLINE_LOCAL_LLM,
            isWeightFilePresent = false,
            checkedPaths = checkedPaths,
            detectedFilePath = null,
            detectedFileSizeBytes = 0L,
            localIntentRouterAvailable = true,
            statusMessage = "Offline LLM unavailable (no .gguf/.tflite/.task weight files installed). Local device command router remains active."
        )
    }
}
