package com.example.ai.router

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ChatMessage
import com.example.ai.GeminiLiveProvider
import com.example.ai.GeminiTextClient
import com.example.ai.PersonaSettings
import com.example.conversation.RafelConversationManager
import com.example.live.LiveSessionManager
import com.example.tools.ToolExecutionEngine
import com.example.voice.RafelLiveDiagnostics
import com.example.voice.RafelVoiceState
import com.example.voice.VoiceBehaviorSettings
import com.example.voice.VoiceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiRuntimeValidationTest {

    private lateinit var context: Context
    private lateinit var modelManager: AIModelManager
    private lateinit var conversationManager: RafelConversationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("rafel_settings", Context.MODE_PRIVATE).edit().clear().commit()
        AIModelManager.resetInstanceForTesting()
        modelManager = AIModelManager.getInstance(context)
        conversationManager = RafelConversationManager.getInstance(context)
        conversationManager.geminiTextClientOverride = null
        conversationManager.clearAllHistory()
    }

    @After
    fun tearDown() {
        conversationManager.geminiTextClientOverride = null
        modelManager.healthMonitor.setSimulatedOnlineState(null)
        modelManager.setCloudInferenceEnabled(true)
        AIModelManager.resetInstanceForTesting()
    }

    @Test
    fun test1_runtimeModelDiagnosticsNeverClaimGeminiWhenLocalFallbackUsed() = runBlocking {
        // 1. When cloud inference is disabled or no key is provided, verify diagnostics report source = LOCAL_FALLBACK
        modelManager.setCloudInferenceEnabled(false)

        val fallbackReply = conversationManager.generateNaturalReply(
            userText = "Aaj office mein bahut hectic din tha.",
            apiKey = ""
        )
        assertTrue(fallbackReply.isNotBlank())
        assertEquals("LOCAL_FALLBACK", RafelLiveDiagnostics.actualResponseSource.value)
        assertEquals("LOCAL_FALLBACK", RafelLiveDiagnostics.activeProvider.value)
        assertEquals("rafel-local-contextual-v1", RafelLiveDiagnostics.activeModel.value)

        val snap = modelManager.telemetrySnapshot.value
        assertEquals("LOCAL_FALLBACK", snap.actualResponseSource)
        assertEquals("LOCAL_FALLBACK", snap.activeProvider)
        assertEquals("rafel-local-contextual-v1", snap.activeModel)

        // 2. Verify a second turn with cloud inference disabled continues to report LOCAL_FALLBACK
        modelManager.setCloudInferenceEnabled(false)
        val disabledReply = conversationManager.generateNaturalReply(
            userText = "Upar se boss ne extra kaam de diya.",
            apiKey = "test-valid-key"
        )
        assertTrue(disabledReply.isNotBlank())
        assertEquals("LOCAL_FALLBACK", RafelLiveDiagnostics.actualResponseSource.value)
        assertEquals("LOCAL_FALLBACK", RafelLiveDiagnostics.activeProvider.value)
        assertEquals("rafel-local-contextual-v1", RafelLiveDiagnostics.activeModel.value)
        assertEquals("OFFLINE", RafelLiveDiagnostics.onlineOrOfflineMode.value)
        assertTrue(RafelLiveDiagnostics.lastFallbackReason.value.contains("disabled", ignoreCase = true))
    }

    @Test
    fun test2_geminiRestRuntimeFiveTurnOfficeToMovieConversationAndCategories() = runBlocking {
        LocalRuntimeGeminiServer().use { server ->
            val restBaseUrl = "http://127.0.0.1:${server.port}/v1beta/models"
            val textClient = GeminiTextClient(
                aiModelManager = modelManager,
                baseUrl = restBaseUrl
            )
            conversationManager.geminiTextClientOverride = textClient

            val fiveTurnPrompts = listOf(
                "Aaj office mein bahut hectic din tha.",
                "Upar se boss ne extra kaam de diya.",
                "Waise chhodo, aaj movie dekhne ka mann hai.",
                "Mujhe suspense thriller pasand hai.",
                "Koi movie suggest karo."
            )

            val fiveTurnReplies = mutableListOf<String>()
            for ((idx, prompt) in fiveTurnPrompts.withIndex()) {
                val reply = conversationManager.generateNaturalReply(
                    userText = prompt,
                    apiKey = "runtime-test-key"
                )
                fiveTurnReplies.add(reply)
                println(
                    "[GEMINI-REST-TURN-${idx + 1}] User=\"$prompt\" -> Rafel=\"$reply\" " +
                        "(provider=${RafelLiveDiagnostics.activeProvider.value}, model=${RafelLiveDiagnostics.activeModel.value}, " +
                        "source=${RafelLiveDiagnostics.actualResponseSource.value})"
                )

                // Every turn MUST be served by GEMINI_REST (gemini-2.5-flash after gemini-3.5-flash 404 failover)
                assertEquals("GEMINI_REST", RafelLiveDiagnostics.actualResponseSource.value)
                assertEquals("GEMINI_REST_API", RafelLiveDiagnostics.activeProvider.value)
                assertEquals("gemini-2.5-flash", RafelLiveDiagnostics.activeModel.value)
                assertEquals("ONLINE", RafelLiveDiagnostics.onlineOrOfflineMode.value)
                assertEquals("YES", RafelLiveDiagnostics.apiKeyConfigured.value)
                assertTrue(RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value > 0L)
            }

            // Verify Turn 1-5 actual model responses reached conversation output without offline template replacement
            assertTrue(fiveTurnReplies[0].contains("energy nichod", ignoreCase = true) || fiveTurnReplies[0].contains("office", ignoreCase = true))
            assertTrue(fiveTurnReplies[1].contains("boss", ignoreCase = true) || fiveTurnReplies[1].contains("extra kaam", ignoreCase = true))
            assertTrue(fiveTurnReplies[2].contains("movie", ignoreCase = true) || fiveTurnReplies[2].contains("genre", ignoreCase = true))
            assertTrue(fiveTurnReplies[3].contains("suspense", ignoreCase = true) || fiveTurnReplies[3].contains("raaz", ignoreCase = true))
            assertTrue(
                "Expected Turn 5 to suggest suspense thriller movies based on Turn 3 & 4 context",
                fiveTurnReplies[4].contains("Andhadhun", ignoreCase = true) ||
                    fiveTurnReplies[4].contains("Drishyam", ignoreCase = true) ||
                    fiveTurnReplies[4].contains("Kahaani", ignoreCase = true)
            )

            // Verify Turn 5 HTTP payload sent to Gemini contained the earlier conversation context
            val turn5Payload = server.receivedHttpBodies.last()
            assertTrue(turn5Payload.contains("Aaj office mein bahut hectic din tha."))
            assertTrue(turn5Payload.contains("Waise chhodo, aaj movie dekhne ka mann hai."))
            assertTrue(turn5Payload.contains("Mujhe suspense thriller pasand hai."))
            assertTrue(turn5Payload.contains("Koi movie suggest karo."))

            // Also test Hindi, Emotional, Joke, Disagreement, and Technical question through Gemini REST runtime
            val additionalTurns = listOf(
                "आज मौसम बहुत सुहाना है और बारिश हो रही है।" to "barish",
                "Office mein pure din itni mehnat ki, par kisi ne notice hi nahi kiya." to "mehnat",
                "Tu toh badi smart banti hai, badam khati hai kya?" to "processor",
                "Main nahi manta, raat bhar jaag ke padhna subah uthne se behtar hai." to "neend",
                "Kotlin coroutines mein StateFlow aur SharedFlow mein kya farq hai?" to "StateFlow"
            )

            for ((prompt, expectedKeyword) in additionalTurns) {
                val reply = conversationManager.generateNaturalReply(
                    userText = prompt,
                    apiKey = "runtime-test-key"
                )
                println(
                    "[GEMINI-REST-CATEGORY] User=\"$prompt\" -> Rafel=\"$reply\" " +
                        "(source=${RafelLiveDiagnostics.actualResponseSource.value}, model=${RafelLiveDiagnostics.activeModel.value})"
                )
                assertEquals("GEMINI_REST", RafelLiveDiagnostics.actualResponseSource.value)
                assertEquals("gemini-2.5-flash", RafelLiveDiagnostics.activeModel.value)
                assertTrue(
                    "Expected reply for '$prompt' to contain '$expectedKeyword', got: '$reply'",
                    reply.contains(expectedKeyword, ignoreCase = true)
                )
            }

            println("[GEMINI-RUNTIME-REST] Verified 10 multi-turn REST turns on model=${RafelLiveDiagnostics.activeModel.value}, source=${RafelLiveDiagnostics.actualResponseSource.value}")
        }
    }

    @Test
    fun test3_liveAudioAoedeVoiceStreamingContinuousPlaybackInterruptionAndNoDuplication() {
        // Verify Sulafat voice configuration constants
        assertEquals("Sulafat", VoiceConfig.GEMINI_VOICE_NAME)
        assertEquals("Sulafat", VoiceBehaviorSettings.voiceName)
        assertTrue("First audio chunk must start streaming immediately (0 startup buffer)", VoiceBehaviorSettings.startStreamingOnFirstChunk)
        assertTrue(VoiceBehaviorSettings.stopImmediatelyOnInterruption)

        LocalRuntimeGeminiServer().use { server ->
            val wsUrl = "ws://127.0.0.1:${server.port}/ws/BidiGenerateContent"
            context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("api_key", "live-test-key")
                .commit()

            val setupLatch = CountDownLatch(1)
            val turnCompleteLatch = CountDownLatch(1)
            val audioChunksReceived = AtomicInteger(0)
            val totalAudioBytes = AtomicLong(0L)
            val interruptedCount = AtomicInteger(0)
            val observedStates = CopyOnWriteArrayList<RafelVoiceState>()

            val liveSessionManager = LiveSessionManager(
                context = context,
                toolEngine = ToolExecutionEngine(context),
                onAudioOut = { _, pcmBytes ->
                    audioChunksReceived.incrementAndGet()
                    totalAudioBytes.addAndGet(pcmBytes.size.toLong())
                },
                onTurnComplete = {
                    turnCompleteLatch.countDown()
                },
                onServerInterrupted = {
                    interruptedCount.incrementAndGet()
                },
                onStateChange = { state ->
                    observedStates.add(state)
                    if (state == RafelVoiceState.LISTENING && RafelLiveDiagnostics.setupComplete.value) {
                        setupLatch.countDown()
                    }
                }
            )
            liveSessionManager.wsEndpointOverride = wsUrl

            val liveProvider = GeminiLiveProvider(liveSessionManager)
            liveProvider.connectSession()

            assertTrue("Expected GeminiLiveProvider WebSocket setupComplete", setupLatch.await(6, TimeUnit.SECONDS))
            assertTrue(
                "Expected liveProvider.isSessionReady() == true; states=$observedStates, wsState=${RafelLiveDiagnostics.webSocketState.value}, setupComplete=${RafelLiveDiagnostics.setupComplete.value}, model=${liveSessionManager.getActiveModel()}, err=${RafelLiveDiagnostics.lastWebSocketError.value}",
                liveProvider.isSessionReady()
            )
            assertEquals("CONNECTED", RafelLiveDiagnostics.webSocketState.value)
            assertEquals("GEMINI_LIVE_WS", RafelLiveDiagnostics.activeProvider.value)
            assertEquals("YES", RafelLiveDiagnostics.apiKeyConfigured.value)

            // Verify Sulafat voice was sent in WebSocket setup payload
            val acceptedSetup = server.receivedWsSetupFrames.last()
            assertTrue("Setup frame must configure Sulafat voice", acceptedSetup.contains("\"voiceName\":\"Sulafat\""))

            // Send a live conversational prompt via GeminiLiveProvider
            val sendRes = runBlocking {
                liveProvider.generateResponse(
                    listOf(ChatMessage(role = "user", content = "Aaj office mein bahut hectic din tha."))
                )
            }
            assertTrue(sendRes.isSuccess)
            assertTrue("Expected multi-chunk live audio turnComplete", turnCompleteLatch.await(6, TimeUnit.SECONDS))

            // Verify continuous multi-chunk audio playback (3 chunks x 4800 bytes = 14400 bytes)
            assertEquals(3, audioChunksReceived.get())
            assertEquals(14400L, totalAudioBytes.get())
            assertTrue(RafelLiveDiagnostics.firstAudioChunkReceived.value)
            assertEquals("GEMINI_LIVE", RafelLiveDiagnostics.actualResponseSource.value)
            assertEquals("GEMINI_LIVE_WS", RafelLiveDiagnostics.activeProvider.value)
            assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", RafelLiveDiagnostics.activeModel.value)
            assertTrue(RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value > 0L)

            // Verify no repeated or duplicated sentences in live transcript even when server sent duplicate chunk
            val rafelMessages = liveSessionManager.messages.value.filter { it.startsWith("Rafel: ") }
            assertTrue(rafelMessages.isNotEmpty())
            val lastSpokenText = rafelMessages.last().removePrefix("Rafel: ").trim()
            val sentences = PersonaSettings.splitIntoSentences(lastSpokenText)
            assertEquals("Duplicate sentences in a turn must be deduplicated", sentences.distinct().size, sentences.size)

            // Verify immediate interruption (barge-in) stops audio and increments generation ID
            val genBeforeBargeIn = liveSessionManager.getCurrentGenerationId()
            liveSessionManager.notifyBargeIn()
            assertTrue(liveSessionManager.getCurrentGenerationId() > genBeforeBargeIn)
            assertTrue(interruptedCount.get() >= 1)
            assertEquals(RafelVoiceState.INTERRUPTED, liveSessionManager.rafelState.value)

            liveProvider.disconnectSession()
            assertEquals("DISCONNECTED", RafelLiveDiagnostics.webSocketState.value)
        }
    }

    @Test
    fun test4_offlineFallbackFiveTurnOfficeToMovieAndSecurityRedaction() = runBlocking {
        // Test the 5-turn conversation in offline fallback mode and verify diagnostics + context continuity
        modelManager.setCloudInferenceEnabled(false)
        val prompts = listOf(
            "Aaj office mein bahut hectic din tha.",
            "Upar se boss ne extra kaam de diya.",
            "Waise chhodo, aaj movie dekhne ka mann hai.",
            "Mujhe suspense thriller pasand hai.",
            "Koi movie suggest karo."
        )
        val replies = prompts.map { p ->
            conversationManager.generateNaturalReply(p, apiKey = "")
        }

        assertEquals("LOCAL_FALLBACK", RafelLiveDiagnostics.actualResponseSource.value)
        assertEquals("rafel-local-contextual-v1", RafelLiveDiagnostics.activeModel.value)
        assertTrue(replies[1].contains("boss", ignoreCase = true) || replies[1].contains("kaam", ignoreCase = true))
        assertTrue(replies[2].contains("movie", ignoreCase = true) || replies[2].contains("thriller", ignoreCase = true))
        assertTrue(replies[3].contains("twist", ignoreCase = true) || replies[3].contains("kahani", ignoreCase = true))
        assertTrue(
            "Offline fallback Turn 5 must suggest suspense thriller movies from context",
            replies[4].contains("Andhadhun", ignoreCase = true) ||
                replies[4].contains("Drishyam", ignoreCase = true) ||
                replies[4].contains("Kahaani", ignoreCase = true)
        )

        // Verify API key redaction in diagnostics
        val syntheticTestKey = listOf("AIza", "Sy", "TestSecretKey", "9876543210abcdef").joinToString("")
        val sensitiveError = "HTTP 400 Bad Request for https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash?key=$syntheticTestKey"
        modelManager.recordLocalFallbackResponse(sensitiveError, explicitApiKey = syntheticTestKey)
        assertFalse(RafelLiveDiagnostics.lastFallbackReason.value.contains("TestSecretKey"))
        assertTrue(RafelLiveDiagnostics.lastFallbackReason.value.contains("[REDACTED"))
    }

    @Test
    fun test5_realCloudEnvironmentCheckAndHonestReporting() = runBlocking {
        val configuredKey = modelManager.resolveEffectiveApiKey()
        val hasRealCloudKey = configuredKey.isNotBlank()
        println("[REAL-CLOUD-CHECK] apiKeyConfigured=${if (hasRealCloudKey) "YES" else "NO"}")

        if (hasRealCloudKey) {
            val client = GeminiTextClient(aiModelManager = modelManager)
            val res = client.generateResponse(
                apiKey = configuredKey,
                recentHistory = emptyList(),
                newPrompt = "Aaj office mein bahut hectic din tha."
            )
            println("[REAL-CLOUD-CHECK] Live Gemini REST result success=${res.isSuccess}, source=${RafelLiveDiagnostics.actualResponseSource.value}, model=${RafelLiveDiagnostics.activeModel.value}")
        } else {
            assertEquals("NO", modelManager.telemetrySnapshot.value.apiKeyConfigured)
            println("[REAL-CLOUD-CHECK] No live GEMINI_API_KEY configured in build/test container environment -> Real Google cloud inference marked NOT VERIFIED.")
        }
    }

    @Test
    fun test6_httpErrorCodeHandling5xxRetryKeyRotationBackendProxyAndAllTenConversationCategories() = runBlocking {
        // 1. Verify HTTP 400 (API_KEY_INVALID vs BAD_REQUEST), 401, 403, 404, 429, 5xx classification
        assertEquals(
            com.example.ai.GeminiRestErrorKind.INVALID_API_KEY_400,
            GeminiTextClient.classifyHttpError(400, """{"error":{"message":"API key not valid. Please pass a valid API key.","reason":"API_KEY_INVALID"}}""")
        )
        assertEquals(
            com.example.ai.GeminiRestErrorKind.BAD_REQUEST_400,
            GeminiTextClient.classifyHttpError(400, """{"error":{"message":"Invalid JSON payload"}}""")
        )
        assertEquals(
            com.example.ai.GeminiRestErrorKind.UNAUTHORIZED_401,
            GeminiTextClient.classifyHttpError(401, "Unauthorized")
        )
        assertEquals(
            com.example.ai.GeminiRestErrorKind.FORBIDDEN_403,
            GeminiTextClient.classifyHttpError(403, "Forbidden")
        )
        assertEquals(
            com.example.ai.GeminiRestErrorKind.MODEL_NOT_FOUND_404,
            GeminiTextClient.classifyHttpError(404, "Model not found")
        )
        assertEquals(
            com.example.ai.GeminiRestErrorKind.RATE_LIMITED_429,
            GeminiTextClient.classifyHttpError(429, "Resource has been exhausted")
        )
        assertEquals(
            com.example.ai.GeminiRestErrorKind.SERVER_ERROR_5XX,
            GeminiTextClient.classifyHttpError(503, "Service Unavailable")
        )

        // 2. Verify HTTP 401 aborts failover immediately (does not hammer remaining models)
        val attempts401 = AtomicInteger(0)
        val client401 = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts401.incrementAndGet()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("""{"error":{"code":401,"message":"Invalid credentials"}}""".toByteArray().let {
                        okhttp3.ResponseBody.create(null, it)
                    })
                    .build()
            }
            .build()
        val textClient401 = GeminiTextClient(client = client401, aiModelManager = modelManager, transientRetryDelayMs = 10L)
        val syntheticInvalidKey = listOf("AIza", "Sy", "InvalidKeyTest", "1234567890123456").joinToString("")
        val res401 = textClient401.generateResponse(apiKey = syntheticInvalidKey, recentHistory = emptyList(), newPrompt = "Hello")
        assertTrue(res401.isFailure)
        assertEquals("Fatal 401 auth error must abort after 1 attempt without hammering fallback models", 1, attempts401.get())

        // 3. Verify HTTP 503 transient server error retries once on the same model and succeeds
        val attempts503 = AtomicInteger(0)
        val client503 = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val count = attempts503.incrementAndGet()
                if (count == 1) {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("""{"error":{"code":503,"message":"Backend overloaded"}}""".toByteArray().let {
                            okhttp3.ResponseBody.create(null, it)
                        })
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"candidates":[{"content":{"parts":[{"text":"Ab sab theek chal raha hai!"}]}}]}""".toByteArray().let {
                            okhttp3.ResponseBody.create(null, it)
                        })
                        .build()
                }
            }
            .build()
        val textClient503 = GeminiTextClient(client = client503, aiModelManager = modelManager, transientRetryDelayMs = 10L)
        val syntheticRetryKey = listOf("AIza", "Sy", "ValidKeyForRetryTest", "1234567890").joinToString("")
        val res503 = textClient503.generateResponse(apiKey = syntheticRetryKey, recentHistory = emptyList(), newPrompt = "Kaisi ho?")
        assertTrue(res503.isSuccess)
        assertEquals(2, attempts503.get())
        assertEquals("GEMINI_REST", RafelLiveDiagnostics.actualResponseSource.value)

        // 4. Verify GeminiSecureGateway backend proxy & key rotation
        com.example.ai.security.GeminiSecureGateway.clearBackendProxy(context)
        assertFalse("Unconfigured backend proxy must never report operational", com.example.ai.security.GeminiSecureGateway.isBackendProxyOperational(context))
        assertTrue(com.example.ai.security.GeminiSecureGateway.configureBackendProxy(context, "https://proxy.example.com", "session-token-xyz"))
        assertTrue(com.example.ai.security.GeminiSecureGateway.isBackendProxyOperational(context))
        com.example.ai.security.GeminiSecureGateway.clearBackendProxy(context)
        assertFalse(com.example.ai.security.GeminiSecureGateway.isBackendProxyOperational(context))

        val syntheticRotatedKey = listOf("AIza", "Sy", "RotatedRuntimeKey", "1234567890abcdef").joinToString("")
        assertTrue(com.example.ai.security.GeminiSecureGateway.rotateApiKey(context, syntheticRotatedKey))
        assertEquals("YES", RafelLiveDiagnostics.apiKeyConfigured.value)
        com.example.ai.security.GeminiSecureGateway.clearRuntimeApiKey(context)
        val expectedAfterClear = if (modelManager.resolveEffectiveApiKey().isNotBlank()) "YES" else "NO"
        assertEquals(expectedAfterClear, RafelLiveDiagnostics.apiKeyConfigured.value)

        // 5. Verify all 10 conversation categories in Section 6 (including No-Advice and Memory Retrieval)
        modelManager.setCloudInferenceEnabled(false)
        val convManager = RafelConversationManager.getInstance(context)
        convManager.clearAllHistory()
        val noAdviceReply = convManager.generateNaturalReply("Aaj bahut bura din tha, please koi salah ya lecture mat dena, bas sun lo.")
        assertTrue(noAdviceReply.isNotBlank())
        assertFalse(noAdviceReply.contains("should", ignoreCase = true))

        convManager.generateNaturalReply("Mera naam Vikram hai aur mujhe thriller movies pasand hain.")
        val memoryRecallReply = convManager.generateNaturalReply("Tumhe mera naam yaad hai?")
        assertTrue("Expected memory recall reply to mention Vikram, got: $memoryRecallReply", memoryRecallReply.contains("Vikram", ignoreCase = true))
        assertEquals("LOCAL_FALLBACK", RafelLiveDiagnostics.actualResponseSource.value)

        // 7. Verify SSE streaming (:streamGenerateContent?alt=sse) and activeModel selection invariant
        AIModelManager.resetInstanceForTesting()
        val freshManager = AIModelManager.getInstance(context)
        assertEquals("NONE", RafelLiveDiagnostics.activeModel.value)
        assertEquals("IDLE", RafelLiveDiagnostics.restRequestStatus.value)
        freshManager.selectModelForTask(ModelTaskType.TEXT_CHAT, hasApiKey = true)
        assertEquals(
            "Never mark a model active merely because it was selected",
            "NONE",
            RafelLiveDiagnostics.activeModel.value
        )

        val sseChunks = mutableListOf<String>()
        val sseClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                assertTrue(req.url.toString().contains(":streamGenerateContent?alt=sse"))
                val sseBody = """
                    data: {"candidates":[{"content":{"parts":[{"text":"Haan yaar, "}]}}]}

                    data: {"candidates":[{"content":{"parts":[{"text":"SSE streaming bilkul sahi kaam kar rahi hai!"}]}}]}

                    data: [DONE]
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sseBody.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            })
            .build()
        val streamingTextClient = GeminiTextClient(client = sseClient, aiModelManager = freshManager)
        val syntheticDevKey = listOf("AIza", "Sy", "ValidDevelopmentTestKey", "1234567890").joinToString("")
        val sseRes = streamingTextClient.generateStreamingResponse(
            apiKey = syntheticDevKey,
            recentHistory = emptyList(),
            newPrompt = "Stream test",
            modelName = "gemini-2.5-flash",
            onPartialChunk = { sseChunks.add(it) }
        )
        assertTrue(sseRes.isSuccess)
        assertEquals(2, sseChunks.size)
        assertEquals("Haan yaar, SSE streaming bilkul sahi kaam kar rahi hai!", sseRes.getOrNull())
        assertEquals("SUCCESS (HTTP 200)", RafelLiveDiagnostics.restRequestStatus.value)
        assertEquals("gemini-2.5-flash", RafelLiveDiagnostics.activeModel.value)
        assertEquals("GEMINI_REST", RafelLiveDiagnostics.actualResponseSource.value)
    }

    /**
     * Local RFC-6455 WebSocket + HTTP/1.1 server implementing the exact Gemini v1beta REST and Live BidiGenerateContent wire protocols.
     */
    private class LocalRuntimeGeminiServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val running = AtomicBoolean(true)
        private val executor = Executors.newCachedThreadPool()
        val receivedHttpBodies = CopyOnWriteArrayList<String>()
        val receivedWsSetupFrames = CopyOnWriteArrayList<String>()

        init {
            executor.submit {
                while (running.get() && !serverSocket.isClosed) {
                    try {
                        val client = serverSocket.accept()
                        executor.submit { handleClient(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }

        private fun readHttpHeaderLine(input: InputStream): String? {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
                }
                if (b == '\n'.code) {
                    break
                }
                if (b != '\r'.code) {
                    buf.write(b)
                }
            }
            return buf.toString(Charsets.UTF_8.name())
        }

        private fun handleClient(socket: Socket) {
            socket.use { sock ->
                sock.soTimeout = 5000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val requestLine = readHttpHeaderLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = readHttpHeaderLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim().lowercase()
                        val v = line.substring(idx + 1).trim()
                        headers[k] = v
                        if (k == "content-length") {
                            contentLength = v.toIntOrNull() ?: 0
                        }
                    }
                }

                if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                    handleWebSocket(headers, input, output)
                } else {
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (r < 0) break
                        readTotal += r
                    }
                    val body = String(bodyBytes, 0, readTotal.coerceAtLeast(0), Charsets.UTF_8)
                    receivedHttpBodies.add(body)
                    handleHttpPost(requestLine, body, output)
                }
            }
        }

        private fun handleHttpPost(requestLine: String, body: String, output: OutputStream) {
            if (requestLine.contains("gemini-3.5-flash")) {
                val errJson = """{"error":{"code":404,"message":"models/gemini-3.5-flash is not found for API version v1beta"}}"""
                writeHttpResponse(output, "404 Not Found", errJson)
                return
            }

            val replyText = when {
                body.contains("StateFlow aur SharedFlow") ->
                    "StateFlow hamesha latest state value hold karta hai aur UI state ke liye bana hai, jabki SharedFlow one-time events broadcast karne ke kaam aata hai."
                body.contains("raat bhar jaag ke padhna") ->
                    "Dekho main is baat se agree nahi karti, neend kharab karke padhoge toh agle din dimag thaka rahega."
                body.contains("badam khati hai kya") ->
                    "Badam ki zarurat tumhe padti hogi, mera processor toh bina badam ke hi tez daudta hai!"
                body.contains("kisi ne notice hi nahi kiya") ->
                    "Pura din dil se mehnat karne ke baad jab koi notice na kare toh bura lagna ekdam laazmi hai."
                body.contains("आज मौसम बहुत सुहाना है") ->
                    "Haan yaar, aisi thandi barish ki hawa mein khidki ke paas baithne ka apna hi sukoon hai."
                body.contains("Koi movie suggest karo.") && body.contains("Mujhe suspense thriller pasand hai.") ->
                    "Agar suspense thriller dekhni hai toh 'Andhadhun', 'Drishyam', ya 'Kahaani' try karo, teeno ke twists lajawab hain!"
                body.contains("Mujhe suspense thriller pasand hai.") ->
                    "Suspense thriller ka maza hi alag hai jab aakhiri pal tak asli raaz chhupa rahe."
                body.contains("Waise chhodo, aaj movie dekhne ka mann hai.") ->
                    "Office ki tension bhulane ke liye movie best plan hai! Kaunsa genre dekhne ka mood hai aaj?"
                body.contains("Upar se boss ne extra kaam de diya.") ->
                    "Pehle se hi din itna hectic tha aur upar se boss ne extra kaam pakda diya, ye toh sach mein zyada ho gaya yaar."
                body.contains("Aaj office mein bahut hectic din tha.") ->
                    "Lagta hai aaj office ke kaam ne puri energy nichod li tumhari, ab thoda aaram kar lo."
                else ->
                    "Main sun rahi hoon, aage batao kya hua."
            }

            val respJson = """{"candidates":[{"content":{"parts":[{"text":"$replyText"}]}}]}"""
            writeHttpResponse(output, "200 OK", respJson)
        }

        private fun writeHttpResponse(output: OutputStream, status: String, jsonBody: String) {
            val bytes = jsonBody.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }

        private fun handleWebSocket(headers: Map<String, String>, input: InputStream, output: OutputStream) {
            val key = headers["sec-websocket-key"] ?: return
            val acceptSeed = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val sha1 = MessageDigest.getInstance("SHA-1").digest(acceptSeed.toByteArray(Charsets.UTF_8))
            val acceptKey = Base64.encodeToString(sha1, Base64.NO_WRAP)

            val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
            output.write(handshake.toByteArray(Charsets.UTF_8))
            output.flush()

            val setupFrame = readWsFrame(input) ?: return
            receivedWsSetupFrames.add(setupFrame)

            if (setupFrame.contains("models/gemini-3.8-live")) {
                writeWsCloseFrame(output, 1008, "models/gemini-3.8-live is not found for API version v1beta")
                try { readWsFrame(input) } catch (_: Exception) {}
                return
            }

            writeWsTextFrame(output, """{"setupComplete":{}}""")

            val promptFrame = readWsFrame(input) ?: return
            if (promptFrame.isEmpty()) return

            val pcmChunk = ByteArray(4800) { (it % 64).toByte() }
            val b64Audio = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)

            // Stream 3 continuous audio chunks + text (including a duplicate sentence to test deduplication)
            val chunk1 = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64Audio"}},{"text":"Aaj office ka din sach mein kafi thaka dene wala raha."}]}}}"""
            val chunk2 = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64Audio"}},{"text":"Aaj office ka din sach mein kafi thaka dene wala raha."}]}}}"""
            val chunk3 = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64Audio"}},{"text":"Ab laptop band karke aaram karo."}]}}}"""

            writeWsTextFrame(output, chunk1)
            Thread.sleep(10)
            writeWsTextFrame(output, chunk2)
            Thread.sleep(10)
            writeWsTextFrame(output, chunk3)
            Thread.sleep(10)
            writeWsTextFrame(output, """{"serverContent":{"turnComplete":true}}""")
            try { readWsFrame(input) } catch (_: Exception) {}
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var offset = 0
            while (offset < buf.size) {
                val r = input.read(buf, offset, buf.size - offset)
                if (r < 0) return false
                offset += r
            }
            return true
        }

        private fun readWsFrame(input: InputStream): String? {
            val b0 = input.read()
            if (b0 < 0) return null
            val opcode = b0 and 0x0F
            if (opcode == 8) return null
            val b1 = input.read()
            if (b1 < 0) return null
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val ext = ByteArray(2)
                if (!readFully(input, ext)) return null
                len = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                val ext = ByteArray(8)
                if (!readFully(input, ext)) return null
                var l = 0L
                for (b in ext) {
                    l = (l shl 8) or (b.toLong() and 0xFF)
                }
                len = l
            }
            val mask = ByteArray(4)
            if (masked) {
                if (!readFully(input, mask)) return null
            }
            val payload = ByteArray(len.toInt())
            if (!readFully(input, payload)) return null
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return String(payload, Charsets.UTF_8)
        }

        private fun writeWsTextFrame(output: OutputStream, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write(0x81)
            if (bytes.size <= 125) {
                output.write(bytes.size)
            } else if (bytes.size <= 65535) {
                output.write(126)
                output.write((bytes.size shr 8) and 0xFF)
                output.write(bytes.size and 0xFF)
            } else {
                output.write(127)
                val len = bytes.size.toLong()
                for (i in 7 downTo 0) {
                    output.write(((len shr (i * 8)) and 0xFF).toInt())
                }
            }
            output.write(bytes)
            output.flush()
        }

        private fun writeWsCloseFrame(output: OutputStream, code: Int, reason: String) {
            val reasonBytes = reason.toByteArray(Charsets.UTF_8)
            val payload = ByteArray(2 + reasonBytes.size)
            payload[0] = ((code shr 8) and 0xFF).toByte()
            payload[1] = (code and 0xFF).toByte()
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
            output.write(0x88)
            output.write(payload.size)
            output.write(payload)
            output.flush()
        }

        override fun close() {
            running.set(false)
            try { serverSocket.close() } catch (_: Exception) {}
            executor.shutdownNow()
        }
    }
}
