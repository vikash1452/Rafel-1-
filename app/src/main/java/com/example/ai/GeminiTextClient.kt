package com.example.ai

import com.example.ai.router.AIModelManager
import com.example.ai.router.AIModelRegistry
import com.example.ai.router.ModelTaskType
import com.example.ai.security.GeminiSecureGateway
import com.example.conversation.UnifiedMessage
import com.example.voice.RafelLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Structured HTTP error classification for Gemini REST calls so that 400, 401, 403, 404, 429,
 * 5xx, and network timeouts are handled distinctly.
 */
enum class GeminiRestErrorKind {
    INVALID_API_KEY_400,
    BAD_REQUEST_400,
    UNAUTHORIZED_401,
    FORBIDDEN_403,
    MODEL_NOT_FOUND_404,
    RATE_LIMITED_429,
    SERVER_ERROR_5XX,
    TIMEOUT_OR_NETWORK,
    EMPTY_RESPONSE
}

class GeminiRestException(
    val httpCode: Int,
    val errorKind: GeminiRestErrorKind,
    val modelName: String,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    val isFatalAuthError: Boolean
        get() = errorKind == GeminiRestErrorKind.INVALID_API_KEY_400 ||
            errorKind == GeminiRestErrorKind.UNAUTHORIZED_401 ||
            errorKind == GeminiRestErrorKind.FORBIDDEN_403

    val isRetryableOnSameModel: Boolean
        get() = errorKind == GeminiRestErrorKind.SERVER_ERROR_5XX
}

/**
 * Direct Gemini REST client for typed text chat responses, integrated with [AIModelManager].
 *
 * Capabilities:
 * - Resolves runtime API key or protected backend proxy via [GeminiSecureGateway].
 * - Handles HTTP 400, 401, 403, 404, 429, and 5xx separately:
 *   * 401 / 403 / 400 (API_KEY_INVALID): Aborts further cloud retries immediately and records auth diagnostic.
 *   * 404 (MODEL_NOT_FOUND): Marks model unsupported (60s cooldown) and fails over immediately to next supported model.
 *   * 429 (RATE_LIMITED): Marks model rate-limited and fails over to next model quota bucket.
 *   * 5xx (SERVER_ERROR): Retries once with backoff on same model before failing over to next candidate.
 * - Preserves full chronological conversation history across model failovers.
 * - Respects coroutine cancellation and never overwrites Gemini responses with offline templates.
 */
class GeminiTextClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val aiModelManager: AIModelManager? = null,
    private val baseUrl: String = AIModelRegistry.GEMINI_REST_BASE_URL,
    var maxTransientRetriesPerModel: Int = 1,
    var transientRetryDelayMs: Long = 200L
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()
    var restBaseUrlOverride: String? = null

    companion object {
        fun classifyHttpError(httpCode: Int, rawBody: String): GeminiRestErrorKind {
            return when {
                httpCode == 400 && (
                    rawBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                        rawBody.contains("key not valid", ignoreCase = true) ||
                        rawBody.contains("invalid api key", ignoreCase = true)
                    ) -> GeminiRestErrorKind.INVALID_API_KEY_400
                httpCode == 400 -> GeminiRestErrorKind.BAD_REQUEST_400
                httpCode == 401 -> GeminiRestErrorKind.UNAUTHORIZED_401
                httpCode == 403 -> GeminiRestErrorKind.FORBIDDEN_403
                httpCode == 404 -> GeminiRestErrorKind.MODEL_NOT_FOUND_404
                httpCode == 429 -> GeminiRestErrorKind.RATE_LIMITED_429
                httpCode in 500..599 -> GeminiRestErrorKind.SERVER_ERROR_5XX
                else -> GeminiRestErrorKind.TIMEOUT_OR_NETWORK
            }
        }
    }

    suspend fun generateResponse(
        apiKey: String,
        recentHistory: List<UnifiedMessage>,
        newPrompt: String,
        relevantMemorySummary: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        if (aiModelManager != null && !aiModelManager.cloudInferenceEnabled.value) {
            com.example.voice.RafelLiveDiagnostics.onlineOrOfflineMode.value = "OFFLINE"
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "OFFLINE"
            return@withContext Result.failure(
                IllegalStateException("OFFLINE: Cloud inference explicitly disabled")
            )
        }

        // Check offline status first via AIModelManager if available
        if (aiModelManager != null && !aiModelManager.healthMonitor.isOnline.value) {
            com.example.voice.RafelLiveDiagnostics.onlineOrOfflineMode.value = "OFFLINE"
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "OFFLINE"
            val offlineDecision = aiModelManager.selectModelForTask(
                taskType = ModelTaskType.TEXT_CHAT,
                hasApiKey = apiKey.isNotBlank()
            )
            return@withContext Result.failure(
                IllegalStateException("OFFLINE: ${offlineDecision.selectionReason}")
            )
        }

        val proxyOperational = GeminiSecureGateway.isBackendProxyOperational()
        if (apiKey.isBlank() && !proxyOperational) {
            com.example.voice.RafelLiveDiagnostics.apiKeyConfigured.value = "NO"
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "FAILED (MISSING_API_KEY)"
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is missing. Please set it in Settings.")
            )
        }
        com.example.voice.RafelLiveDiagnostics.apiKeyConfigured.value = "YES"
        com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "IN_PROGRESS"

        val chronologicalHistory = recentHistory
            .let { list ->
                if (list.lastOrNull()?.isUser == true &&
                    list.lastOrNull()?.text?.trim().equals(newPrompt.trim(), ignoreCase = true)
                ) {
                    list.dropLast(1)
                } else {
                    list
                }
            }
        val turnCategory = PersonaSettings.classifyTurnCategory(newPrompt)
        val dynamicSystemPrompt = RafelPersona.buildDynamicSystemInstruction(
            latestUserMessage = newPrompt,
            recentHistory = chronologicalHistory,
            relevantMemorySummary = relevantMemorySummary,
            includeVoiceDeliveryRules = false
        )
        val recentAssistantReplies = chronologicalHistory.filter { !it.isUser }.takeLast(4).map { it.text }

        // Build contents array with conversation history in strict chronological order (oldest -> newest)
        val contentsArray = buildJsonArray {
            chronologicalHistory.takeLast(24).forEach { msg ->
                if (msg.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", if (msg.isUser) "user" else "model")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", msg.text) })
                        }
                    })
                }
            }
            // Append latest user prompt at the end
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", newPrompt) })
                }
            })
        }

        val targetTemp = when (turnCategory) {
            TurnCategory.SIMPLE_QUESTION, TurnCategory.TECHNICAL_OR_DETAILED -> 0.55
            else -> 0.85
        }

        val requestBodyJson = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", dynamicSystemPrompt) })
                }
            }
            put("contents", contentsArray)
            putJsonObject("generationConfig") {
                put("temperature", targetTemp)
                put("topP", 0.95)
                put("maxOutputTokens", turnCategory.maxOutputTokens)
            }
        }.toString()

        // Determine candidate order from AIModelManager router if available, and append verified supported REST fallbacks
        val routingDecision = aiModelManager?.selectModelForTask(
            taskType = ModelTaskType.TEXT_CHAT,
            hasApiKey = true
        )

        val routedList = routingDecision?.fallbackChain
            ?.map { it.restModelName }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                AIModelRegistry.MODEL_GEMINI_3_5_FLASH_REST,
                AIModelRegistry.MODEL_GEMINI_2_5_FLASH_REST
            )
        val candidateChain = (routedList + AIModelRegistry.VERIFIED_SUPPORTED_REST_MODELS).distinct()

        var lastResult: Result<String> = Result.failure(IllegalStateException("No REST candidate models available"))
        var lastFailureReason: String? = null

        for ((index, candidateModel) in candidateChain.withIndex()) {
            currentCoroutineContext().ensureActive()

            var attemptCount = 0
            var modelAttemptResult: Result<String>
            var elapsedMs: Long

            while (true) {
                currentCoroutineContext().ensureActive()
                attemptCount++
                val startMs = System.currentTimeMillis()
                modelAttemptResult = executeGenerate(candidateModel, apiKey, requestBodyJson)
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

                val ex = modelAttemptResult.exceptionOrNull()
                if (ex is CancellationException) {
                    throw ex
                }

                // Retry once on transient 5xx server error before failing over to next model
                if (modelAttemptResult.isFailure &&
                    ex is GeminiRestException &&
                    ex.isRetryableOnSameModel &&
                    attemptCount <= maxTransientRetriesPerModel
                ) {
                    RafelLogger.logDiagnostic(
                        "Transient 5xx (${ex.httpCode}) on $candidateModel (attempt $attemptCount). Retrying after ${transientRetryDelayMs}ms..."
                    )
                    delay(transientRetryDelayMs)
                    continue
                }
                break
            }

            lastResult = modelAttemptResult

            if (modelAttemptResult.isSuccess) {
                val rawReply = modelAttemptResult.getOrNull().orEmpty()
                val cleanedReply = PersonaSettings.postProcessResponse(
                    rawResponse = com.example.voice.VoiceBehaviorSettings.sanitizeForNaturalDelivery(rawReply),
                    userPrompt = newPrompt,
                    recentAssistantMessages = recentAssistantReplies,
                    recentHistory = chronologicalHistory
                )
                aiModelManager?.recordTurnCompleted(
                    modelId = candidateModel,
                    totalResponseTimeMs = elapsedMs,
                    ttfrMs = elapsedMs,
                    connectTimeMs = elapsedMs
                )
                aiModelManager?.recordRuntimeResponseDelivered(
                    source = "GEMINI_REST",
                    provider = "GEMINI_REST_API",
                    modelId = candidateModel,
                    fallbackReason = lastFailureReason,
                    explicitApiKey = apiKey
                )
                com.example.voice.RafelLiveDiagnostics.activeModel.value = candidateModel
                com.example.voice.RafelLiveDiagnostics.activeProvider.value = "GEMINI_REST_API"
                com.example.voice.RafelLiveDiagnostics.actualResponseSource.value = "GEMINI_REST"
                com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "SUCCESS (HTTP 200)"
                com.example.voice.RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value = System.currentTimeMillis()
                com.example.voice.RafelLiveDiagnostics.onlineOrOfflineMode.value = "ONLINE"
                com.example.voice.RafelLiveDiagnostics.apiKeyConfigured.value = "YES"
                return@withContext Result.success(cleanedReply.ifBlank { rawReply })
            }

            val ex = modelAttemptResult.exceptionOrNull()
            val restEx = ex as? GeminiRestException
            val httpCode = restEx?.httpCode
                ?: Regex("HTTP (\\d{3})").find(ex?.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 0
            val errReason = AIModelManager.redactSecrets(
                ex?.message ?: "REST model $candidateModel failed",
                apiKey
            )
            lastFailureReason = errReason
            com.example.voice.RafelLiveDiagnostics.lastFallbackReason.value = errReason
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value =
                if (httpCode > 0) "FAILED (HTTP $httpCode)" else "FAILED (NETWORK)"

            // Fatal auth / permission error (401, 403, or 400 API_KEY_INVALID): do not hammer subsequent models
            if (restEx?.isFatalAuthError == true) {
                RafelLogger.logDiagnostic("Fatal auth error (${restEx.errorKind}, HTTP $httpCode) on $candidateModel; aborting failover.")
                aiModelManager?.latencyTracker?.recordFailure(candidateModel, elapsedMs)
                aiModelManager?.healthMonitor?.recordModelFailure(candidateModel, errReason, httpCode)
                break
            }

            val nextCandidate = candidateChain.getOrNull(index + 1)
            if (nextCandidate != null) {
                RafelLogger.logDiagnostic("REST model $candidateModel failed ($errReason). Auto-switching to $nextCandidate...")
            }
            aiModelManager?.onModelFailureAndSelectFallback(
                taskType = ModelTaskType.TEXT_CHAT,
                failedModelId = candidateModel,
                reason = errReason,
                httpOrWsCode = httpCode,
                hasApiKey = apiKey.isNotBlank() || proxyOperational,
                elapsedBeforeFailureMs = elapsedMs
            )
        }

        lastResult
    }

    /**
     * Streams a Gemini REST response via Server-Sent Events (`:streamGenerateContent?alt=sse`),
     * invoking [onPartialChunk] as each SSE text delta arrives and returning the assembled reply.
     */
    suspend fun generateStreamingResponse(
        apiKey: String,
        recentHistory: List<UnifiedMessage>,
        newPrompt: String,
        relevantMemorySummary: String = "",
        modelName: String = AIModelRegistry.MODEL_GEMINI_2_5_FLASH_REST,
        onPartialChunk: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        if (aiModelManager != null && (!aiModelManager.cloudInferenceEnabled.value || !aiModelManager.healthMonitor.isOnline.value)) {
            com.example.voice.RafelLiveDiagnostics.onlineOrOfflineMode.value = "OFFLINE"
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "OFFLINE"
            return@withContext Result.failure(IllegalStateException("OFFLINE: Cannot stream while offline"))
        }

        val proxyOperational = GeminiSecureGateway.isBackendProxyOperational()
        if (apiKey.isBlank() && !proxyOperational) {
            com.example.voice.RafelLiveDiagnostics.apiKeyConfigured.value = "NO"
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "FAILED (MISSING_API_KEY)"
            return@withContext Result.failure(IllegalStateException("Gemini API key is missing."))
        }
        com.example.voice.RafelLiveDiagnostics.apiKeyConfigured.value = "YES"
        com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "IN_PROGRESS"

        val chronologicalHistory = recentHistory.let { list ->
            if (list.lastOrNull()?.isUser == true &&
                list.lastOrNull()?.text?.trim().equals(newPrompt.trim(), ignoreCase = true)
            ) {
                list.dropLast(1)
            } else {
                list
            }
        }
        val turnCategory = PersonaSettings.classifyTurnCategory(newPrompt)
        val dynamicSystemPrompt = RafelPersona.buildDynamicSystemInstruction(
            latestUserMessage = newPrompt,
            recentHistory = chronologicalHistory,
            relevantMemorySummary = relevantMemorySummary,
            includeVoiceDeliveryRules = false
        )

        val contentsArray = buildJsonArray {
            chronologicalHistory.takeLast(24).forEach { msg ->
                if (msg.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", if (msg.isUser) "user" else "model")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", msg.text) })
                        }
                    })
                }
            }
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", newPrompt) })
                }
            })
        }

        val requestBodyJson = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", dynamicSystemPrompt) })
                }
            }
            put("contents", contentsArray)
            putJsonObject("generationConfig") {
                put("temperature", 0.8)
                put("topP", 0.95)
                put("maxOutputTokens", turnCategory.maxOutputTokens)
            }
        }.toString()

        val cleanName = modelName.removePrefix("models/")
        val proxyUrl = GeminiSecureGateway.backendProxyUrl.value
        val useProxy = restBaseUrlOverride == null && !proxyUrl.isNullOrBlank()
        val url = if (useProxy) {
            "${proxyUrl!!.trimEnd('/')}/v1beta/models/$cleanName:streamGenerateContent?alt=sse"
        } else {
            val trimmedBase = (restBaseUrlOverride ?: baseUrl).trimEnd('/')
            "$trimmedBase/$cleanName:streamGenerateContent?alt=sse&key=$apiKey"
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(requestBodyJson.toRequestBody(mediaTypeJson))

        if (useProxy) {
            val sessionToken = GeminiSecureGateway.getBackendSessionToken()
            if (!sessionToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $sessionToken")
            }
        } else if (apiKey.isNotBlank()) {
            reqBuilder.header("x-goog-api-key", apiKey)
        }

        val startMs = System.currentTimeMillis()
        var firstChunkMs = 0L
        val assembled = StringBuilder()

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val rawBody = response.body?.string().orEmpty()
                    val kind = classifyHttpError(code, rawBody)
                    val safeSnippet = AIModelManager.redactSecrets(rawBody.take(240), apiKey)
                    com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "FAILED (HTTP $code)"
                    return@withContext Result.failure(
                        GeminiRestException(
                            httpCode = code,
                            errorKind = kind,
                            modelName = cleanName,
                            message = "Gemini SSE HTTP $code (${kind.name}): $safeSnippet"
                        )
                    )
                }

                val reader = response.body?.charStream()?.buffered()
                if (reader != null) {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        currentCoroutineContext().ensureActive()
                        val trimmed = line?.trim().orEmpty()
                        if (trimmed.isEmpty()) continue
                        val jsonPayload = when {
                            trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                            trimmed.startsWith("{") -> trimmed
                            else -> continue
                        }
                        if (jsonPayload == "[DONE]" || jsonPayload.isEmpty()) continue
                        try {
                            val chunkObj = json.parseToJsonElement(jsonPayload).jsonObject
                            val delta = chunkObj["candidates"]
                                ?.jsonArray?.firstOrNull()
                                ?.jsonObject?.get("content")
                                ?.jsonObject?.get("parts")
                                ?.jsonArray?.firstOrNull()
                                ?.jsonObject?.get("text")
                                ?.jsonPrimitive?.content.orEmpty()
                            if (delta.isNotEmpty()) {
                                if (firstChunkMs == 0L) {
                                    firstChunkMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                }
                                assembled.append(delta)
                                onPartialChunk(delta)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val safeErr = AIModelManager.redactSecrets("${e.javaClass.simpleName}: ${e.message}", apiKey)
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "FAILED (NETWORK)"
            return@withContext Result.failure(
                GeminiRestException(
                    httpCode = 0,
                    errorKind = GeminiRestErrorKind.TIMEOUT_OR_NETWORK,
                    modelName = cleanName,
                    message = safeErr,
                    cause = e
                )
            )
        }

        val finalText = assembled.toString().trim()
        val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        if (finalText.isNotBlank()) {
            aiModelManager?.recordTurnCompleted(
                modelId = cleanName,
                totalResponseTimeMs = totalMs,
                ttfrMs = if (firstChunkMs > 0L) firstChunkMs else totalMs,
                connectTimeMs = if (firstChunkMs > 0L) firstChunkMs else totalMs
            )
            aiModelManager?.recordRuntimeResponseDelivered(
                source = "GEMINI_REST",
                provider = "GEMINI_REST_API",
                modelId = cleanName,
                explicitApiKey = apiKey
            )
            com.example.voice.RafelLiveDiagnostics.activeModel.value = cleanName
            com.example.voice.RafelLiveDiagnostics.activeProvider.value = "GEMINI_REST_API"
            com.example.voice.RafelLiveDiagnostics.actualResponseSource.value = "GEMINI_REST"
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "SUCCESS (HTTP 200)"
            com.example.voice.RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value = System.currentTimeMillis()
            return@withContext Result.success(finalText)
        } else {
            com.example.voice.RafelLiveDiagnostics.restRequestStatus.value = "FAILED (EMPTY_RESPONSE)"
            return@withContext Result.failure(
                GeminiRestException(
                    httpCode = 200,
                    errorKind = GeminiRestErrorKind.EMPTY_RESPONSE,
                    modelName = cleanName,
                    message = "Empty SSE stream returned from Gemini ($cleanName)"
                )
            )
        }
    }

    private fun executeGenerate(
        modelName: String,
        apiKey: String,
        jsonBody: String
    ): Result<String> {
        val cleanName = modelName.removePrefix("models/")
        val proxyUrl = GeminiSecureGateway.backendProxyUrl.value
        val useProxy = restBaseUrlOverride == null && !proxyUrl.isNullOrBlank()

        val url = if (useProxy) {
            "${proxyUrl!!.trimEnd('/')}/v1beta/models/$cleanName:generateContent"
        } else {
            val trimmedBase = (restBaseUrlOverride ?: baseUrl).trimEnd('/')
            "$trimmedBase/$cleanName:generateContent?key=$apiKey"
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(mediaTypeJson))

        if (useProxy) {
            val sessionToken = GeminiSecureGateway.getBackendSessionToken()
            if (!sessionToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $sessionToken")
            }
        } else if (apiKey.isNotBlank()) {
            reqBuilder.header("x-goog-api-key", apiKey)
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val responseStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = response.code
                    val kind = classifyHttpError(code, responseStr)
                    val safeSnippet = AIModelManager.redactSecrets(responseStr.take(240), apiKey)
                    return Result.failure(
                        GeminiRestException(
                            httpCode = code,
                            errorKind = kind,
                            modelName = cleanName,
                            message = "Gemini HTTP $code (${kind.name}): $safeSnippet"
                        )
                    )
                }

                val jsonResponse = json.parseToJsonElement(responseStr).jsonObject
                val text = jsonResponse["candidates"]
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")
                    ?.jsonObject?.get("parts")
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.content

                if (!text.isNullOrBlank()) {
                    Result.success(text.trim())
                } else {
                    Result.failure(
                        GeminiRestException(
                            httpCode = response.code,
                            errorKind = GeminiRestErrorKind.EMPTY_RESPONSE,
                            modelName = cleanName,
                            message = "Empty candidate returned from Gemini ($cleanName)"
                        )
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ste: SocketTimeoutException) {
            val msg = AIModelManager.redactSecrets("SocketTimeoutException on $cleanName: ${ste.message}", apiKey)
            RafelLogger.logError("Gemini REST timeout ($cleanName): $msg", ste)
            Result.failure(
                GeminiRestException(
                    httpCode = 408,
                    errorKind = GeminiRestErrorKind.TIMEOUT_OR_NETWORK,
                    modelName = cleanName,
                    message = "Gemini HTTP 408 (TIMEOUT): $msg",
                    cause = ste
                )
            )
        } catch (e: Exception) {
            val msg = AIModelManager.redactSecrets("${e.javaClass.simpleName} on $cleanName: ${e.message}", apiKey)
            RafelLogger.logError("Failed to generate Gemini text response ($cleanName): $msg", e)
            Result.failure(
                GeminiRestException(
                    httpCode = 0,
                    errorKind = GeminiRestErrorKind.TIMEOUT_OR_NETWORK,
                    modelName = cleanName,
                    message = msg,
                    cause = e
                )
            )
        }
    }
}
