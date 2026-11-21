package com.example.ai.router

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.ai.GeminiTextClient
import com.example.conversation.RafelConversationManager
import com.example.conversation.UnifiedMessage
import com.example.conversation.UnifiedMessageType
import com.example.live.LiveSessionManager
import com.example.tools.ToolExecutionEngine
import com.example.voice.RafelLiveDiagnostics
import com.example.voice.RafelVoiceState
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
import java.io.File
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
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniversalAiModelRouterTest {

    private lateinit var context: Context
    private lateinit var modelManager: AIModelManager
    private var tempWeightDir: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("rafel_settings", Context.MODE_PRIVATE).edit().clear().commit()
        AIModelManager.resetInstanceForTesting()
        modelManager = AIModelManager.getInstance(context)
        RafelConversationManager.getInstance(context).clearAllHistory()
    }

    @After
    fun tearDown() {
        tempWeightDir?.deleteRecursively()
        modelManager.registry.setOfflineWeightDirectoryOverrideForTesting(null)
        modelManager.healthMonitor.setSimulatedOnlineState(null)
        AIModelManager.resetInstanceForTesting()
    }

    @Test
    fun test1_discoverAllProjectModelsAndCapabilities() {
        val allModels = modelManager.registry.getAllModels()
        assertEquals(6, allModels.size)

        val liveModels = modelManager.registry.getLiveAudioModels()
        assertEquals(3, liveModels.size)
        assertEquals("models/gemini-3.8-live", liveModels[0].modelId)
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", liveModels[1].modelId)
        assertEquals("models/gemini-2.0-flash-exp", liveModels[2].modelId)

        val restModels = modelManager.registry.getRestTextModels()
        assertEquals(2, restModels.size)
        assertEquals("gemini-3.5-flash", restModels[0].modelId)
        assertEquals("gemini-2.5-flash", restModels[1].modelId)

        val offlineModel = modelManager.registry.getModel(AIModelRegistry.MODEL_OFFLINE_LOCAL_LLM)
        assertNotNull(offlineModel)
        assertEquals(ModelProviderType.OFFLINE_LOCAL_LLM, offlineModel!!.providerType)

        // Default Automatic Mode MUST be ON
        assertTrue(modelManager.isAutoModeEnabled.value)

        // Verify unmeasured models start with 0ms (no hardcoded benchmark numbers reported as measured latency)
        val snap = modelManager.refreshTelemetrySnapshot()
        assertEquals(0L, snap.recentLatencyMs)
        allModels.forEach { m ->
            assertFalse(modelManager.latencyTracker.hasMeasuredLatency(m.modelId))
            assertEquals(0L, snap.modelStatsMap[m.modelId]?.compositeScoreMs ?: -1L)
        }
    }

    @Test
    fun test2_neverSelectTextOnlyModelForLiveAudioTask() {
        val compatibleForLive = modelManager.capabilityManager.getCompatibleModels(
            taskType = ModelTaskType.LIVE_AUDIO,
            isOfflineWeightVerified = true
        )

        assertTrue(compatibleForLive.isNotEmpty())
        compatibleForLive.forEach { model ->
            assertEquals(ModelProviderType.GEMINI_LIVE_WS, model.providerType)
            assertTrue(model.capabilities.contains(ModelCapability.LIVE_AUDIO_BIDI))
            assertNotEquals("gemini-3.5-flash", model.modelId)
            assertNotEquals("gemini-2.5-flash", model.modelId)
            assertNotEquals("local/on-device-llm", model.modelId)
        }

        // Even if all 3 Live Audio models are marked unhealthy, router must NEVER select a REST model for LIVE_AUDIO
        val decisionWhenLiveHealthy = modelManager.selectModelForTask(
            taskType = ModelTaskType.LIVE_AUDIO,
            hasApiKey = true
        )
        assertNotNull(decisionWhenLiveHealthy.selectedModel)
        assertEquals(ModelProviderType.GEMINI_LIVE_WS, decisionWhenLiveHealthy.selectedModel!!.providerType)
        decisionWhenLiveHealthy.fallbackChain.forEach { fallbackModel ->
            assertEquals(ModelProviderType.GEMINI_LIVE_WS, fallbackModel.providerType)
        }
    }

    @Test
    fun test3_automaticFastestModelSelectionUsingRollingLatency() {
        repeat(5) {
            modelManager.latencyTracker.recordSuccess(
                modelId = "models/gemini-3.8-live",
                totalResponseTimeMs = 950L,
                ttfrMs = 680L,
                ttfaMs = 720L,
                connectTimeMs = 420L
            )
            modelManager.latencyTracker.recordSuccess(
                modelId = "models/gemini-2.5-flash-native-audio-preview-12-2025",
                totalResponseTimeMs = 380L,
                ttfrMs = 210L,
                ttfaMs = 240L,
                connectTimeMs = 180L
            )
        }

        val score38 = modelManager.latencyTracker.getEffectiveScoreMs(
            modelManager.registry.getModel("models/gemini-3.8-live")!!,
            ModelTaskType.LIVE_AUDIO
        )
        val score25 = modelManager.latencyTracker.getEffectiveScoreMs(
            modelManager.registry.getModel("models/gemini-2.5-flash-native-audio-preview-12-2025")!!,
            ModelTaskType.LIVE_AUDIO
        )
        assertTrue(score25 < score38)

        val decision = modelManager.selectModelForTask(
            taskType = ModelTaskType.LIVE_AUDIO,
            hasApiKey = true,
            currentActiveModelId = "models/gemini-3.8-live",
            isTurnInProgress = false
        )

        assertNotNull(decision.selectedModel)
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            decision.selectedModel!!.modelId
        )
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            modelManager.activeLiveModelId.value
        )
        // Never mark a model active in Diagnostics merely because it was selected
        assertEquals("NONE", RafelLiveDiagnostics.activeModel.value)
        modelManager.recordConnectionEstablished(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            180L
        )
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            RafelLiveDiagnostics.activeModel.value
        )
    }

    @Test
    fun test4_activeTurnSafetyGuardPreventsMidConversationSwitch() {
        repeat(4) {
            modelManager.latencyTracker.recordSuccess(
                modelId = "models/gemini-3.8-live",
                totalResponseTimeMs = 920L,
                ttfaMs = 750L,
                connectTimeMs = 400L
            )
            modelManager.latencyTracker.recordSuccess(
                modelId = "models/gemini-2.5-flash-native-audio-preview-12-2025",
                totalResponseTimeMs = 310L,
                ttfaMs = 220L,
                connectTimeMs = 160L
            )
        }

        val midTurnDecision = modelManager.selectModelForTask(
            taskType = ModelTaskType.LIVE_AUDIO,
            hasApiKey = true,
            currentActiveModelId = "models/gemini-3.8-live",
            isTurnInProgress = true
        )
        assertEquals("models/gemini-3.8-live", midTurnDecision.selectedModel?.modelId)
        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            modelManager.fallbackManager.getPendingSafeUpgrade()
        )

        val upgradedModel = modelManager.recordTurnCompleted(
            modelId = "models/gemini-3.8-live",
            totalResponseTimeMs = 890L,
            ttfaMs = 710L
        )
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", upgradedModel)
    }

    @Test
    fun test5_liveAudioCascadingFallbackChainAndCooldown() {
        val firstFallback = modelManager.onModelFailureAndSelectFallback(
            taskType = ModelTaskType.LIVE_AUDIO,
            failedModelId = "models/gemini-3.8-live",
            reason = "WS Closed (1008: Model not supported)",
            httpOrWsCode = 1008,
            hasApiKey = true,
            elapsedBeforeFailureMs = 145L
        )
        assertNotNull(firstFallback)
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", firstFallback!!.modelId)
        assertEquals(ModelHealthState.UNHEALTHY, modelManager.healthMonitor.getHealthState("models/gemini-3.8-live"))
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", modelManager.activeLiveModelId.value)
        assertEquals("NONE", RafelLiveDiagnostics.activeModel.value)

        val secondFallback = modelManager.onModelFailureAndSelectFallback(
            taskType = ModelTaskType.LIVE_AUDIO,
            failedModelId = "models/gemini-2.5-flash-native-audio-preview-12-2025",
            reason = "Connection timeout",
            httpOrWsCode = 408,
            hasApiKey = true,
            elapsedBeforeFailureMs = 8000L
        )
        assertNotNull(secondFallback)
        assertEquals("models/gemini-2.0-flash-exp", secondFallback!!.modelId)
        assertEquals("models/gemini-2.0-flash-exp", modelManager.activeLiveModelId.value)
        assertEquals("NONE", RafelLiveDiagnostics.activeModel.value)

        val history = modelManager.fallbackManager.fallbackHistory.value
        assertEquals(2, history.size)
        assertTrue(history.all { it.contextPreserved })
    }

    @Test
    fun test6_restTextClientAutoRoutingAndFallbackPreservesContextAndDiagnostics() = runBlocking {
        val capturedUrls = mutableListOf<String>()
        val capturedBodies = mutableListOf<String>()

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                capturedUrls.add(urlStr)

                val buffer = okio.Buffer()
                req.body?.writeTo(buffer)
                capturedBodies.add(buffer.readUtf8())

                if (urlStr.contains("gemini-3.5-flash")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("""{"error":{"code":429,"message":"Quota exceeded"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"candidates":[{"content":{"parts":[{"text":"Main bilkul theek hoon Vikash!"}]}}]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            })
            .build()

        val textClient = GeminiTextClient(client = mockHttpClient, aiModelManager = modelManager)
        val history = listOf(
            UnifiedMessage(
                id = "1",
                type = UnifiedMessageType.USER_VOICE,
                text = "Mera naam Vikash hai",
                timestamp = System.currentTimeMillis() - 5000
            )
        )

        val result = textClient.generateResponse(
            apiKey = "test-api-key",
            recentHistory = history,
            newPrompt = "Tum kaise ho?"
        )

        assertTrue(result.isSuccess)
        assertEquals("Main bilkul theek hoon Vikash!", result.getOrNull())
        assertEquals(2, capturedUrls.size)
        assertTrue(capturedUrls[0].contains("gemini-3.5-flash"))
        assertTrue(capturedUrls[1].contains("gemini-2.5-flash"))

        // Verify conversation context ("Mera naam Vikash hai") was preserved in both requests across model switches
        assertTrue(capturedBodies[0].contains("Mera naam Vikash hai"))
        assertTrue(capturedBodies[1].contains("Mera naam Vikash hai"))

        // Verify active model in both AIModelManager and RafelLiveDiagnostics matches the model that actually generated the response
        assertEquals("gemini-2.5-flash", modelManager.activeTextModelId.value)
        assertEquals("gemini-2.5-flash", RafelLiveDiagnostics.activeModel.value)
    }

    @Test
    fun test7_offlineDetectionDoesNotPretendMissingWeightsWorkAndRestoresOnline() {
        val verifyMissing = modelManager.registry.verifyOfflineModelWeights(context)
        assertFalse(verifyMissing.isWeightFilePresent)
        assertEquals(
            ModelHealthState.MISSING_WEIGHTS,
            modelManager.healthMonitor.getHealthState(AIModelRegistry.MODEL_OFFLINE_LOCAL_LLM)
        )

        modelManager.healthMonitor.setSimulatedOnlineState(false)
        assertFalse(modelManager.healthMonitor.isOnline.value)

        val offlineDecisionNoWeights = modelManager.selectModelForTask(
            taskType = ModelTaskType.TEXT_CHAT,
            hasApiKey = true
        )
        assertNull(offlineDecisionNoWeights.selectedModel)
        assertTrue(offlineDecisionNoWeights.isOfflineMode)

        val tempDir = File(context.cacheDir, "test_offline_models").apply { mkdirs() }
        tempWeightDir = tempDir
        val dummyWeights = File(tempDir, "rafel_local.gguf")
        dummyWeights.writeBytes(ByteArray(4096) { 0x42 })
        modelManager.registry.setOfflineWeightDirectoryOverrideForTesting(tempDir)

        val offlineDecisionWithWeights = modelManager.selectModelForTask(
            taskType = ModelTaskType.TEXT_CHAT,
            hasApiKey = true
        )
        assertNotNull(offlineDecisionWithWeights.selectedModel)
        assertEquals(AIModelRegistry.MODEL_OFFLINE_LOCAL_LLM, offlineDecisionWithWeights.selectedModel?.modelId)

        modelManager.healthMonitor.setSimulatedOnlineState(true)
        assertTrue(modelManager.healthMonitor.isOnline.value)

        val onlineRestoredDecision = modelManager.selectModelForTask(
            taskType = ModelTaskType.TEXT_CHAT,
            hasApiKey = true
        )
        assertNotNull(onlineRestoredDecision.selectedModel)
        assertEquals(ModelProviderType.GEMINI_REST_API, onlineRestoredDecision.selectedModel?.providerType)
    }

    @Test
    fun test8_httpReachabilityWithoutGenerationIsRejectedAndWebSocketProtocolVerified() = runBlocking {
        // 1. Verify that HTTP 200 without generated candidate text is REJECTED (never reported as successful AI generation)
        val metadataOnlyClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val restModel = modelManager.registry.getModel("gemini-2.5-flash")!!
        val metadataOnlyProbe = modelManager.probeSingleModel(
            model = restModel,
            apiKey = "test-key",
            httpClient = metadataOnlyClient
        )
        assertFalse("HTTP 200 without candidate generation must NOT be marked reachable", metadataOnlyProbe.isReachable)
        assertFalse(metadataOnlyProbe.generationVerified)
        assertTrue(metadataOnlyProbe.detailMessage.contains("no candidate text generated"))

        // 2. Run a real RFC-6455 WebSocket + HTTP local protocol server to test full protocol verification
        LocalGeminiProtocolServer().use { server ->
            val wsUrl = "ws://127.0.0.1:${server.port}/ws/BidiGenerateContent"
            val restUrl = "http://127.0.0.1:${server.port}/v1beta/models"

            val probeResults = modelManager.probeAllModels(
                customApiKey = "valid-test-key",
                liveWsEndpointOverride = wsUrl,
                restBaseUrlOverride = restUrl,
                liveTimeoutMs = 5000L
            )

            assertEquals(6, probeResults.size)
            probeResults.forEach { res ->
                println(
                    "[ROUTER-VALIDATION][LOCAL-PROTOCOL-PROBE] model=${res.modelId} " +
                        "provider=${res.providerType} reachable=${res.isReachable} code=${res.statusCode} " +
                        "connectMs=${res.connectTimeMs} ttfrMs=${res.ttfrMs} ttfaMs=${res.ttfaMs} " +
                        "totalMs=${res.latencyMs} setupComplete=${res.setupCompleteVerified} " +
                        "genVerified=${res.generationVerified} audioBytes=${res.audioBytesReceived} " +
                        "detail=\"${res.detailMessage}\""
                )
            }

            val r38 = probeResults.first { it.modelId == "models/gemini-3.8-live" }
            assertFalse(r38.isReachable)
            assertFalse(r38.setupCompleteVerified)
            assertFalse(r38.generationVerified)

            val r25Live = probeResults.first { it.modelId == "models/gemini-2.5-flash-native-audio-preview-12-2025" }
            assertTrue(r25Live.isReachable)
            assertTrue(r25Live.setupCompleteVerified)
            assertTrue(r25Live.generationVerified)
            assertTrue(r25Live.connectTimeMs >= 1L)
            assertTrue(r25Live.ttfrMs >= 1L)
            assertTrue(r25Live.ttfaMs >= 1L)
            assertEquals(4800L, r25Live.audioBytesReceived)

            val r35Rest = probeResults.first { it.modelId == "gemini-3.5-flash" }
            assertFalse(r35Rest.isReachable)
            assertEquals(404, r35Rest.statusCode)

            val r25Rest = probeResults.first { it.modelId == "gemini-2.5-flash" }
            assertTrue(r25Rest.isReachable)
            assertTrue(r25Rest.generationVerified)
            assertEquals("READY", r25Rest.responsePreview)
        }
    }

    @Test
    fun test9_liveSessionManagerRealWebSocketFailoverAudioGenerationAndContextSurvival() {
        LocalGeminiProtocolServer().use { server ->
            val wsUrl = "ws://127.0.0.1:${server.port}/ws/BidiGenerateContent"
            context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("api_key", "valid-test-key")
                .commit()

            val convManager = RafelConversationManager.getInstance(context)
            convManager.addUserTextMessage("Mera secret code 7788 hai")
            convManager.addRafelTextMessage("Theek hai, maine yaad rakha hai ki aapka secret code 7788 hai.")

            val turnCompleteLatch = CountDownLatch(1)
            val setupReadyLatch = CountDownLatch(1)
            val receivedAudioBytes = AtomicLong(0L)
            val statesObserved = CopyOnWriteArrayList<RafelVoiceState>()

            val liveManager = LiveSessionManager(
                context = context,
                toolEngine = ToolExecutionEngine(context),
                onAudioOut = { _, pcm ->
                    receivedAudioBytes.addAndGet(pcm.size.toLong())
                },
                onTurnComplete = {
                    turnCompleteLatch.countDown()
                },
                onStateChange = { st ->
                    statesObserved.add(st)
                    if (st == RafelVoiceState.LISTENING && RafelLiveDiagnostics.setupComplete.value) {
                        setupReadyLatch.countDown()
                    }
                }
            )
            liveManager.wsEndpointOverride = wsUrl

            liveManager.startSession()

            assertTrue("Expected LiveSessionManager to failover and reach setupComplete", setupReadyLatch.await(6, TimeUnit.SECONDS))
            assertEquals(
                "models/gemini-2.5-flash-native-audio-preview-12-2025",
                liveManager.getActiveModel()
            )
            assertEquals(
                "models/gemini-2.5-flash-native-audio-preview-12-2025",
                RafelLiveDiagnostics.activeModel.value
            )
            assertEquals(
                "models/gemini-2.5-flash-native-audio-preview-12-2025",
                modelManager.activeLiveModelId.value
            )

            val setupFrames = server.receivedSetupPayloads
            assertTrue("Expected at least 2 setup frames (initial + fallback)", setupFrames.size >= 2)
            assertTrue(setupFrames[0].contains("models/gemini-3.8-live"))
            assertTrue(setupFrames[1].contains("models/gemini-2.5-flash-native-audio-preview-12-2025"))
            assertTrue(
                "Expected conversation context '7788' to be preserved in fallback model setup",
                setupFrames[1].contains("7788")
            )

            liveManager.sendTextMessage("Namaste Rafel, mera secret code kya hai?")
            assertTrue("Expected turnComplete from fallback Live model", turnCompleteLatch.await(6, TimeUnit.SECONDS))

            assertEquals(4800L, receivedAudioBytes.get())
            val stats = modelManager.latencyTracker.getStatsForModel(
                modelManager.registry.getModel("models/gemini-2.5-flash-native-audio-preview-12-2025")!!
            )
            println(
                "[ROUTER-VALIDATION][LIVE-SESSION-FAILOVER] activeModel=${liveManager.getActiveModel()} " +
                    "diagnosticsModel=${RafelLiveDiagnostics.activeModel.value} " +
                    "connectMs=${stats.avgConnectTimeMs} ttfrMs=${stats.avgTimeToFirstResponseMs} " +
                    "ttfaMs=${stats.avgTimeToFirstAudioChunkMs} totalMs=${stats.avgTotalResponseTimeMs} " +
                    "audioBytes=${receivedAudioBytes.get()} contextPreservedInSetup=${setupFrames[1].contains("7788")}"
            )
            assertTrue(stats.avgConnectTimeMs >= 1L)
            assertTrue(stats.avgTimeToFirstResponseMs >= 1L)
            assertTrue(stats.avgTimeToFirstAudioChunkMs >= 1L)
            assertEquals(liveManager.getActiveModel(), RafelLiveDiagnostics.activeModel.value)

            liveManager.stopSession()
        }
    }

    @Test
    fun test10_realCloudEndpointRuntimeProbeAndSecurityAudit() = runBlocking {
        val syntheticApiKey = listOf("AIza", "Sy", "DummyTestKey", "00000000000000000000").joinToString("")
        val syntheticProxyToken = listOf("AQ", ".", "DummyProxyToken", "000000000000000000000000000").joinToString("")
        val rawErr = "Failed request to https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash?key=$syntheticApiKey with token $syntheticProxyToken"
        val redacted = AIModelManager.redactSecrets(rawErr)
        assertFalse(redacted.contains("DummyTestKey"))
        assertFalse(redacted.contains("DummyProxyToken"))
        assertTrue(redacted.contains("[REDACTED]"))

        val envKey = modelManager.resolveEffectiveApiKey()
        val keyKind = when {
            envKey.isBlank() -> "EMPTY"
            envKey.startsWith("AIza") -> "GOOGLE_AI_STUDIO_KEY (AIza..., len=${envKey.length})"
            envKey.startsWith("AQ.") -> "AISTUDIO_PROXY_TOKEN (AQ...., len=${envKey.length})"
            else -> "CUSTOM_KEY (len=${envKey.length})"
        }
        println("[ROUTER-VALIDATION][SECURITY-AUDIT] Effective runtime key type=$keyKind")

        val cloudResults = modelManager.probeAllModels(
            customApiKey = envKey,
            liveTimeoutMs = 6000L
        )
        cloudResults.forEach { res ->
            println(
                "[ROUTER-VALIDATION][CLOUD-LIVE-PROBE] model=${res.modelId} " +
                    "provider=${res.providerType} reachable=${res.isReachable} code=${res.statusCode} " +
                    "connectMs=${res.connectTimeMs} ttfrMs=${res.ttfrMs} ttfaMs=${res.ttfaMs} " +
                    "totalMs=${res.latencyMs} setupComplete=${res.setupCompleteVerified} " +
                    "genVerified=${res.generationVerified} audioBytes=${res.audioBytesReceived} " +
                    "health=${res.healthState} detail=\"${res.detailMessage}\""
            )
        }
        assertEquals(6, cloudResults.size)

        val liveDecision = modelManager.selectModelForTask(
            taskType = ModelTaskType.LIVE_AUDIO,
            hasApiKey = envKey.isNotBlank()
        )
        val textDecision = modelManager.selectModelForTask(
            taskType = ModelTaskType.TEXT_CHAT,
            hasApiKey = envKey.isNotBlank()
        )
        println(
            "[ROUTER-VALIDATION][POST-PROBE-ROUTING] LIVE_AUDIO selected=${liveDecision.selectedModel?.modelId} " +
                "scores=${liveDecision.candidateScoresMs} reason=\"${liveDecision.selectionReason}\""
        )
        println(
            "[ROUTER-VALIDATION][POST-PROBE-ROUTING] TEXT_CHAT selected=${textDecision.selectedModel?.modelId} " +
                "scores=${textDecision.candidateScoresMs} reason=\"${textDecision.selectionReason}\""
        )
    }

    /**
     * Real local TCP socket server implementing both RFC-6455 WebSockets (for Gemini Live BidiGenerateContent)
     * and HTTP/1.1 POST (for Gemini REST generateContent) to validate real network socket handshakes,
     * setupComplete, TTFR, TTFA, 24kHz PCM audio streaming, and model failover.
     */
    private class LocalGeminiProtocolServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val running = AtomicBoolean(true)
        private val executor = Executors.newCachedThreadPool()
        val receivedSetupPayloads = CopyOnWriteArrayList<String>()

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

        private fun handleClient(socket: Socket) {
            socket.use { sock ->
                sock.soTimeout = 5000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
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
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (r < 0) break
                        readTotal += r
                    }
                    handleHttpPost(requestLine, String(bodyChars, 0, readTotal.coerceAtLeast(0)), output)
                }
            }
        }

        private fun handleHttpPost(requestLine: String, body: String, output: OutputStream) {
            Thread.sleep(25) // Simulate realistic network RTT
            val is35Flash = requestLine.contains("gemini-3.5-flash")
            val (status, respJson) = if (is35Flash) {
                "404 Not Found" to """{"error":{"code":404,"message":"models/gemini-3.5-flash is not found for API version v1beta"}}"""
            } else {
                "200 OK" to """{"candidates":[{"content":{"parts":[{"text":"READY"}]}}]}"""
            }
            val bytes = respJson.toByteArray(Charsets.UTF_8)
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

            // 1. Read setup frame from client
            val setupFrame = readWsFrame(input) ?: return
            receivedSetupPayloads.add(setupFrame)

            // If model is models/gemini-3.8-live, reject with WebSocket close 1008 ("Model not found")
            if (setupFrame.contains("models/gemini-3.8-live")) {
                Thread.sleep(20)
                writeWsCloseFrame(output, 1008, "models/gemini-3.8-live is not found for API version v1beta")
                try { readWsFrame(input) } catch (_: Exception) {}
                return
            }

            // Otherwise send setupComplete after brief handshake delay
            Thread.sleep(30)
            writeWsTextFrame(output, """{"setupComplete":{}}""")

            // 2. Read prompt frame from client
            val promptFrame = readWsFrame(input) ?: return
            if (promptFrame.isEmpty()) return

            // Send modelTurn with 24kHz PCM audio chunk + transcript + turnComplete
            Thread.sleep(35)
            val pcm24k = ByteArray(4800) { (it % 64).toByte() } // 100ms of 24kHz 16-bit mono PCM
            val b64Audio = Base64.encodeToString(pcm24k, Base64.NO_WRAP)
            val modelTurnFrame = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64Audio"}},{"text":"Namaste! Aapka secret code 7788 hai."}]}}}"""
            writeWsTextFrame(output, modelTurnFrame)

            Thread.sleep(15)
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
            if (opcode == 8) return null // Close frame
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
            output.write(0x81) // FIN + text frame
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
            output.write(0x88) // FIN + close frame
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
