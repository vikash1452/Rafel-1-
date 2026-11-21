package com.example.live

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.tools.ToolExecutionEngine
import com.example.tools.UserTurnContext
import com.example.voice.RafelLiveDiagnostics
import com.example.voice.RafelLogger
import com.example.voice.RafelVoiceState
import com.example.voice.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the real-time bidirectional WebSocket session with Gemini Live API for Rafel.
 *
 * Capabilities:
 * - Native streaming audio input (16kHz PCM) and output (24kHz PCM).
 * - Response generation tracking to cleanly isolate responses and ignore interrupted turns.
 * - System instructions optimized for Hindi, Hinglish, and English real-time conversations.
 * - Tool calling for Android device interactions.
 */
class LiveSessionManager(
    private val context: Context,
    private val toolEngine: ToolExecutionEngine,
    private val onAudioOut: (generationId: Long, audioData: ByteArray) -> Unit,
    private val onTurnComplete: (generationId: Long) -> Unit = {},
    private val onServerInterrupted: () -> Unit = {},
    private val onStateChange: (RafelVoiceState) -> Unit = {}
) {
    private val _rafelState = MutableStateFlow(RafelVoiceState.IDLE)
    val rafelState: StateFlow<RafelVoiceState> = _rafelState.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var isSetupComplete = false
    val currentGenerationId = AtomicLong(1L)
    val currentSessionId = AtomicLong(100L)
    private var isConnecting = false
    @Volatile private var activeModelInUse = VoiceConfig.GEMINI_LIVE_MODEL
    private val aiModelManager get() = com.example.ai.router.AIModelManager.getInstance(context)
    private val sessionConnectStartTimeMs = AtomicLong(0L)
    private val turnRequestSentTimeMs = AtomicLong(0L)
    private val currentTurnTtfrMs = AtomicLong(0L)
    private val currentTurnTtfaMs = AtomicLong(0L)
    var wsEndpointOverride: String? = null

    val modelTurnChunkCount = java.util.concurrent.atomic.AtomicInteger(0)
    val modelTurnTotalAudioBytes = AtomicLong(0L)
    val modelTurnInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    var getPlaybackQueueBytes: (() -> Long)? = null
    var onSessionReady: (() -> Unit)? = null
    @Volatile private var forceNewUiAssistantLine = false

    private var lastReceivedServerMessageType = "NONE"
    private val recentServerMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun recordRecentServerMessage(summary: String) {
        recentServerMessages.offer(summary)
        while (recentServerMessages.size > 10) {
            recentServerMessages.poll()
        }
    }

    private fun extractMessageType(text: String): String {
        return try {
            val element = json.parseToJsonElement(text).jsonObject
            val keys = mutableListOf<String>()
            if (element.containsKey("serverContent") || element.containsKey("server_content")) {
                val sc = (element["serverContent"] ?: element["server_content"])?.jsonObject
                val scKeys = sc?.keys ?: emptySet()
                keys.add("serverContent(${scKeys.joinToString(",")})")
            }
            if (element.containsKey("setupComplete") || element.containsKey("setup_complete")) {
                keys.add("setupComplete")
            }
            if (element.containsKey("toolCall") || element.containsKey("tool_call")) {
                keys.add("toolCall")
            }
            if (element.containsKey("error")) {
                val err = element["error"]?.jsonObject
                val code = err?.get("code")?.jsonPrimitive?.content ?: "err"
                keys.add("error($code)")
            }
            if (keys.isEmpty()) {
                element.keys.joinToString(",")
            } else {
                keys.joinToString("|")
            }
        } catch (_: Exception) {
            "RAW"
        }
    }

    // Android device control tool definitions
    private val toolsJson = buildJsonObject {
        putJsonArray("functionDeclarations") {
            add(buildJsonObject {
                put("name", "openApp")
                put("description", "Open an installed Android application, such as WhatsApp, Instagram, YouTube, Settings, Phone, Camera, or Calculator.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("appName") {
                            put("type", "STRING")
                            put("description", "The name of the app to launch (e.g. 'WhatsApp', 'Instagram', 'YouTube', 'Settings', 'Phone')")
                        }
                        putJsonObject("packageName") {
                            put("type", "STRING")
                            put("description", "Optional Android package name (e.g. 'com.whatsapp')")
                        }
                    }
                }
            })
            add(buildJsonObject {
                put("name", "searchAndCallContact")
                put("description", "Search for a contact name on the device and call them. Can open dialer or use a specific SIM card.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The EXACT name of the contact as spoken by the user.")
                        }
                        putJsonObject("recipient") {
                            put("type", "STRING")
                            put("description", "Optional recipient name or phone number.")
                        }
                        putJsonObject("phoneNumber") {
                            put("type", "STRING")
                            put("description", "Optional phone number or digits.")
                        }
                        putJsonObject("useDialer") {
                            put("type", "BOOLEAN")
                            put("description", "Set to true if user wants to see number on dial pad before calling")
                        }
                        putJsonObject("simSlot") {
                            put("type", "INTEGER")
                            put("description", "1 for SIM 1, 2 for SIM 2 if specified.")
                        }
                    }
                    putJsonArray("required") { add("contactName") }
                }
            })
            add(buildJsonObject {
                put("name", "sendWhatsAppMessage")
                put("description", "Send a WhatsApp message to a specific contact with text.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The EXACT name of the contact as spoken by the user.")
                        }
                        putJsonObject("message") {
                            put("type", "STRING")
                        }
                    }
                    putJsonArray("required") { add("contactName"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "composeMessage")
                put("description", "Compose or draft a message across messaging apps (WhatsApp, Instagram, Telegram, Messenger, Signal, SMS).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("platform") {
                            put("type", "STRING")
                            put("description", "The app platform: 'WhatsApp', 'Instagram', 'Telegram', 'Messenger', 'Signal', or 'SMS'.")
                        }
                        putJsonObject("recipient") {
                            put("type", "STRING")
                            put("description", "Name of the recipient contact.")
                        }
                        putJsonObject("message") {
                            put("type", "STRING")
                            put("description", "The text message content.")
                        }
                    }
                    putJsonArray("required") { add("platform"); add("recipient"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "readRecentMessages")
                put("description", "Read aloud recent incoming message notifications from WhatsApp, Instagram, Telegram, Messenger, Signal, or SMS.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("platform") {
                            put("type", "STRING")
                            put("description", "Optional platform filter (e.g. 'Instagram', 'Telegram', 'WhatsApp', 'Messenger', 'SMS', or omit for all).")
                        }
                        putJsonObject("limit") {
                            put("type", "INTEGER")
                            put("description", "Max number of messages to read (default 3).")
                        }
                    }
                }
            })
            add(buildJsonObject {
                put("name", "explainMessage")
                put("description", "Explain the meaning of the latest read message or specified text in simple terms.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("messageText") {
                            put("type", "STRING")
                            put("description", "Optional text of the message to explain. If omitted, explains the last read message.")
                        }
                    }
                }
            })
            add(buildJsonObject {
                put("name", "clearMessageCache")
                put("description", "Clears temporary message notifications cache.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                }
            })
            add(buildJsonObject {
                put("name", "sendGmail")
                put("description", "Draft or send an email.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("recipientEmail") { put("type", "STRING") }
                        putJsonObject("subject") { put("type", "STRING") }
                        putJsonObject("body") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("recipientEmail"); add("subject"); add("body") }
                }
            })
            add(buildJsonObject {
                put("name", "searchYouTube")
                put("description", "Search for a query on YouTube app.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
            add(buildJsonObject {
                put("name", "adjustVolume")
                put("description", "Adjust device volume (up, down, mute, unmute, max).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("direction") {
                            put("type", "STRING")
                            put("description", "'up', 'down', 'mute', 'unmute', or 'max'")
                        }
                    }
                    putJsonArray("required") { add("direction") }
                }
            })
            add(buildJsonObject {
                put("name", "setVolumePercent")
                put("description", "Set device volume to a percentage (0 to 100).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("percent") {
                            put("type", "INTEGER")
                            put("description", "Volume percentage (0-100)")
                        }
                    }
                    putJsonArray("required") { add("percent") }
                }
            })
            add(buildJsonObject {
                put("name", "getSimCardInfo")
                put("description", "Check active SIM cards information.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openQuickSettings")
                put("description", "Pull down quick settings toggle panel.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "clickTextOnScreen")
                put("description", "Tap on any visible text on the active screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "STRING")
                            put("description", "Text to tap on screen")
                        }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "openNotificationPanel")
                put("description", "Pull down notification drawer.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "toggleTorch")
                put("description", "Turn flashlight on or off.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("state") {
                            put("type", "STRING")
                            put("description", "'on' or 'off'")
                        }
                    }
                    putJsonArray("required") { add("state") }
                }
            })
            add(buildJsonObject {
                put("name", "setBrightness")
                put("description", "Set screen brightness (0 to 100).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("level") {
                            put("type", "INTEGER")
                            put("description", "0 to 100")
                        }
                    }
                    putJsonArray("required") { add("level") }
                }
            })
            add(buildJsonObject {
                put("name", "sendSms")
                put("description", "Send an SMS message to a contact or phone number. Requires confirmation for safety.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "Contact name or phone number")
                        }
                        putJsonObject("message") {
                            put("type", "STRING")
                            put("description", "SMS text message content")
                        }
                        putJsonObject("confirmed") {
                            put("type", "BOOLEAN")
                            put("description", "Set to true if user already confirmed sending this SMS")
                        }
                    }
                    putJsonArray("required") { add("contactName"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "openCamera")
                put("description", "Launch the device camera.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openGallery")
                put("description", "Show user photos or open gallery/files.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "getCurrentLocation")
                put("description", "Get the user's current GPS location and address (Where am I).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openMaps")
                put("description", "Open Google Maps navigation to a specific destination.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("destination") {
                            put("type", "STRING")
                            put("description", "Place, address, or landmark")
                        }
                    }
                    putJsonArray("required") { add("destination") }
                }
            })
            add(buildJsonObject {
                put("name", "openSettings")
                put("description", "Open specific device settings, e.g. 'wifi', 'bluetooth', 'display', 'sound', 'battery', 'notifications', 'accessibility', 'main'.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("type") {
                            put("type", "STRING")
                            put("description", "Setting section: 'wifi', 'bluetooth', 'display', 'sound', 'battery', 'notifications', or 'main'")
                        }
                    }
                }
            })
            add(buildJsonObject {
                put("name", "addCalendarEvent")
                put("description", "Schedule or add an event to the calendar.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("title") {
                            put("type", "STRING")
                            put("description", "Event title")
                        }
                        putJsonObject("description") {
                            put("type", "STRING")
                            put("description", "Event description")
                        }
                    }
                    putJsonArray("required") { add("title") }
                }
            })
            add(buildJsonObject {
                put("name", "confirmPendingAction")
                put("description", "Execute the user-confirmed pending sensitive action (such as placing a phone call or sending an SMS after user says yes/confirm).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "cancelPendingAction")
                put("description", "Cancel the pending sensitive action when user says no/cancel/stop.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "describeScreen")
                put("description", "Capture and visually understand/describe what is currently visible on the phone screen during screen sharing.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "stopScreenShare")
                put("description", "Stop sharing the device screen with Rafel.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "playMedia")
                put("description", "Play media (song, video) by title or query.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", "STRING")
                            put("description", "Search query")
                        }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
        }
    }

    /**
     * Resolves the API key securely.
     * Prioritizes BuildConfig.GEMINI_API_KEY (injected from AI Studio secrets/.env).
     * Falls back to SharedPreferences for user-provided settings key.
     */
    fun getEffectiveApiKey(): String {
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
        if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY" && buildKey != "YOUR_API_KEY") {
            return buildKey
        }

        val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
        val prefKey = prefs.getString("api_key", "") ?: ""
        if (prefKey.isNotBlank()) return prefKey

        val settingsPrefs = context.getSharedPreferences("rafel_settings", Context.MODE_PRIVATE)
        val settingsKey = settingsPrefs.getString("gemini_api_key", "") ?: settingsPrefs.getString("api_key", "") ?: ""
        if (settingsKey.isNotBlank()) return settingsKey

        // Backwards compatibility with previous prefs name
        val oldPrefs = context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE)
        val oldKey = oldPrefs.getString("api_key", "") ?: ""
        if (oldKey.isNotBlank()) return oldKey

        return aiModelManager.resolveEffectiveApiKey()
    }

    fun startSession(overrideModel: String? = null) {
        if (webSocket != null) {
            RafelLogger.logDiagnostic("startSession ignored: webSocket already active")
            return
        }

        if (!aiModelManager.cloudInferenceEnabled.value || !aiModelManager.healthMonitor.isOnline.value) {
            RafelLiveDiagnostics.onlineOrOfflineMode.value = "OFFLINE"
            RafelLiveDiagnostics.webSocketState.value = "DISCONNECTED"
            RafelLiveDiagnostics.lastFallbackReason.value = "Offline mode active — cloud Live WebSocket disabled"
            RafelLogger.logDiagnostic("startSession blocked: device is in OFFLINE mode")
            updateState(RafelVoiceState.IDLE)
            return
        }

        val apiKey = getEffectiveApiKey()
        if (apiKey.isBlank()) {
            RafelLiveDiagnostics.apiKeyConfigured.value = "NO"
            RafelLiveDiagnostics.webSocketState.value = "DISCONNECTED"
            RafelLiveDiagnostics.lastFallbackReason.value = "Gemini API key not configured"
            RafelLogger.logError("No Gemini API Key available.")
            addMessage("Error: Gemini API Key missing. Please set it in Settings.")
            updateState(RafelVoiceState.ERROR)
            return
        }
        RafelLiveDiagnostics.apiKeyConfigured.value = "YES"

        val sessionId = currentSessionId.incrementAndGet()
        activeModelInUse = if (overrideModel != null) {
            overrideModel
        } else {
            val routed = aiModelManager.selectModelForTask(
                taskType = com.example.ai.router.ModelTaskType.LIVE_AUDIO,
                hasApiKey = true,
                currentActiveModelId = activeModelInUse,
                isTurnInProgress = modelTurnInProgress.get()
            )
            routed.selectedModel?.liveSetupModelName ?: VoiceConfig.GEMINI_LIVE_MODEL
        }
        sessionConnectStartTimeMs.set(System.currentTimeMillis())
        RafelLogger.logLiveSessionCreated(sessionId)
        isSetupComplete = false
        RafelLiveDiagnostics.setupComplete.value = false
        updateState(RafelVoiceState.WAITING_FOR_AI)

        val baseEndpoint = wsEndpointOverride ?: com.example.ai.router.AIModelRegistry.GEMINI_WS_ENDPOINT
        val sep = if (baseEndpoint.contains("?")) "&" else "?"
        val sanitizedEndpoint = "${baseEndpoint}${sep}key=[REDACTED]"
        RafelLogger.logGeminiConnection("Connecting to Gemini Live API WebSocket (sessionId=$sessionId)...")
        val url = "${baseEndpoint}${sep}key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (sessionId != currentSessionId.get()) {
                    RafelLogger.logDiagnostic("Stale onOpen for sessionId=$sessionId discarded (current=${currentSessionId.get()})")
                    try { webSocket.close(1000, "Stale session") } catch (e: Exception) {}
                    return
                }
                this@LiveSessionManager.webSocket = webSocket
                android.util.Log.i("RAFEL-WS", "[WS_OPEN] sessionId=$sessionId model=$activeModelInUse")
                android.util.Log.i("RAFEL-LIVE", "[LIVE] SESSION_CONNECTED")
                android.util.Log.i("RAFEL-DIAGNOSTIC", "SESSION_CONNECTED: sessionId=$sessionId, model=$activeModelInUse")
                RafelLiveDiagnostics.webSocketState.value = "CONNECTED"
                RafelLiveDiagnostics.activeProvider.value = "GEMINI_LIVE_WS"
                RafelLiveDiagnostics.onlineOrOfflineMode.value = "ONLINE"
                RafelLiveDiagnostics.apiKeyConfigured.value = "YES"
                RafelLiveDiagnostics.sessionId.value = sessionId
                RafelLogger.logLiveConnect()
                RafelLogger.logLiveConnectSuccess(sessionId)
                RafelLogger.logLiveSessionConnected(sessionId)
                RafelLogger.logRafelLive("SESSION_CONNECTED", "sessionId=$sessionId")
                RafelLogger.trace4GeminiSessionState("CONNECTED")
                RafelLogger.logLive("session connected (id=$sessionId)")
                RafelLogger.logGeminiConnection("[WS_OPEN] WebSocket connected successfully for session #$sessionId.")
                isSetupComplete = false
                sendSetupMessage(webSocket, sessionId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (sessionId != currentSessionId.get()) {
                    RafelLogger.logDiagnostic("Stale onMessage for sessionId=$sessionId discarded (current=${currentSessionId.get()})")
                    return
                }
                this@LiveSessionManager.webSocket = webSocket
                val msgType = extractMessageType(text)
                lastReceivedServerMessageType = msgType
                recordRecentServerMessage(msgType)
                android.util.Log.i("RAFEL-WS", "[WS_MESSAGE] type=$msgType (sessionId=$sessionId)")
                android.util.Log.i("RAFEL-LIVE", "[LIVE] SERVER_MESSAGE_RECEIVED")
                handleServerMessage(text, sessionId)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (sessionId != currentSessionId.get()) return
                this@LiveSessionManager.webSocket = webSocket
                val str = bytes.utf8()
                val msgType = extractMessageType(str)
                lastReceivedServerMessageType = msgType
                recordRecentServerMessage(msgType)
                android.util.Log.i("RAFEL-WS", "[WS_MESSAGE] (bytes) type=$msgType (sessionId=$sessionId)")
                handleServerMessage(str, sessionId)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (sessionId != currentSessionId.get()) return
                val recent = recentServerMessages.toList().joinToString(" -> ")
                android.util.Log.w("RAFEL-WS", "[WS_CLOSING] code=$code reason='$reason' setupComplete=$isSetupComplete lastMsgType=$lastReceivedServerMessageType recentMsgs=[$recent]")
                try {
                    webSocket.close(code, reason)
                } catch (_: Exception) {}

                if (!isSetupComplete && (code == 1008 || code == 1011 || reason.contains("not found", ignoreCase = true) || reason.contains("not supported", ignoreCase = true))) {
                    val elapsedMs = (System.currentTimeMillis() - sessionConnectStartTimeMs.get()).coerceAtLeast(1L)
                    val fallbackDescriptor = aiModelManager.onModelFailureAndSelectFallback(
                        taskType = com.example.ai.router.ModelTaskType.LIVE_AUDIO,
                        failedModelId = activeModelInUse,
                        reason = "WS Closing ($code: $reason)",
                        httpOrWsCode = code,
                        hasApiKey = true,
                        elapsedBeforeFailureMs = elapsedMs
                    )
                    val nextFallbackModel = fallbackDescriptor?.liveSetupModelName ?: when (activeModelInUse) {
                        VoiceConfig.GEMINI_LIVE_MODEL -> "models/gemini-2.5-flash-native-audio-preview-12-2025"
                        "models/gemini-2.5-flash-native-audio-preview-12-2025" -> "models/gemini-2.0-flash-exp"
                        else -> null
                    }
                    if (nextFallbackModel != null && nextFallbackModel != activeModelInUse) {
                        android.util.Log.i("RAFEL-WS", "Model $activeModelInUse rejected in onClosing ($code). Automatically falling back to $nextFallbackModel...")
                        this@LiveSessionManager.webSocket = null
                        isSetupComplete = false
                        startSession(overrideModel = nextFallbackModel)
                        return
                    }
                }

                val formattedErr = "Gemini Live Error:\nHTTP/code: $code\nmessage: Closing: $reason\ncloseCode: $code\ncloseReason: $reason"
                RafelLiveDiagnostics.lastWebSocketError.value = formattedErr
                RafelLogger.logLiveCloseDetailed(code, reason, code == 1000, false, _rafelState.value.name)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (sessionId != currentSessionId.get()) {
                    RafelLogger.logDiagnostic("Stale onFailure for sessionId=$sessionId ignored (current=${currentSessionId.get()})")
                    return
                }
                val excClass = t.javaClass.name
                val excMsg = t.message ?: "Unknown"
                val causeStr = t.cause?.toString() ?: "None"
                val stackTraceStr = android.util.Log.getStackTraceString(t)
                val responseCode = response?.code ?: 0
                val errorBody = try { response?.body?.string() ?: "None" } catch (_: Exception) { "None" }
                val recent = recentServerMessages.toList().joinToString(" -> ")

                android.util.Log.e("RAFEL-WS", """
                    [WS_FAILURE]
                    exceptionClass: $excClass
                    exceptionMessage: $excMsg
                    cause: $causeStr
                    currentModel: $activeModelInUse
                    setupComplete: $isSetupComplete
                    socketState: ${this@LiveSessionManager.webSocket != null}
                    responseCode: $responseCode
                    responseBody: $errorBody
                    recentMsgs: [$recent]
                    stackTrace: $stackTraceStr
                """.trimIndent())

                val formattedErr = "Gemini Live Error:\nHTTP/code: $responseCode\nmessage: $excMsg ($errorBody)\ncloseCode: $responseCode\ncloseReason: ${t.localizedMessage ?: "Connection Failure"}"
                cancelWatchdog()
                currentGenerationId.incrementAndGet()
                modelTurnInProgress.set(false)
                onServerInterrupted()
                android.util.Log.e("RAFEL-DIAGNOSTIC", "WEBSOCKET_ERROR: $formattedErr")
                RafelLiveDiagnostics.webSocketState.value = "ERROR"
                RafelLiveDiagnostics.lastWebSocketError.value = formattedErr
                RafelLogger.logLiveErrorDetailed(
                    exceptionClass = t.javaClass.simpleName,
                    exceptionMessage = excMsg,
                    responseCode = responseCode,
                    rawBody = errorBody,
                    model = activeModelInUse,
                    sanitizedEndpoint = sanitizedEndpoint
                )
                val elapsedBeforeFail = (System.currentTimeMillis() - sessionConnectStartTimeMs.get()).coerceAtLeast(1L)
                val nextFallback = if (!isSetupComplete && responseCode != 401 && responseCode != 403) {
                    aiModelManager.onModelFailureAndSelectFallback(
                        taskType = com.example.ai.router.ModelTaskType.LIVE_AUDIO,
                        failedModelId = activeModelInUse,
                        reason = "$excMsg (HTTP $responseCode)",
                        httpOrWsCode = responseCode,
                        hasApiKey = true,
                        elapsedBeforeFailureMs = elapsedBeforeFail
                    )
                } else {
                    aiModelManager.latencyTracker.recordFailure(activeModelInUse, elapsedBeforeFail)
                    aiModelManager.healthMonitor.recordModelFailure(activeModelInUse, excMsg, responseCode)
                    null
                }

                this@LiveSessionManager.webSocket = null
                isSetupComplete = false
                if (nextFallback != null) {
                    android.util.Log.i("RAFEL-WS", "Auto-switching Live model from $activeModelInUse to ${nextFallback.liveSetupModelName} after connection failure...")
                    startSession(overrideModel = nextFallback.liveSetupModelName)
                    return
                }

                RafelLogger.logLiveError(formattedErr)
                RafelLogger.logRafelError("LIVE_CONNECTION", formattedErr, t)
                addMessage(formattedErr)
                updateState(RafelVoiceState.ERROR)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (sessionId != currentSessionId.get()) {
                    RafelLogger.logDiagnostic("Stale onClosed for sessionId=$sessionId discarded")
                    return
                }
                val recent = recentServerMessages.toList().joinToString(" -> ")
                android.util.Log.w("RAFEL-WS", "[WS_CLOSED] code=$code reason='$reason' setupComplete=$isSetupComplete lastMsgType=$lastReceivedServerMessageType recentMsgs=[$recent]")

                // Automatic fallback via AIModelManager if primary model is rejected or closes unexpectedly during setup
                if (!isSetupComplete && (code == 1008 || code == 1011 || reason.contains("not found", ignoreCase = true) || reason.contains("not supported", ignoreCase = true))) {
                    val elapsedMs = (System.currentTimeMillis() - sessionConnectStartTimeMs.get()).coerceAtLeast(1L)
                    val fallbackDescriptor = aiModelManager.onModelFailureAndSelectFallback(
                        taskType = com.example.ai.router.ModelTaskType.LIVE_AUDIO,
                        failedModelId = activeModelInUse,
                        reason = "WS Closed ($code: $reason)",
                        httpOrWsCode = code,
                        hasApiKey = true,
                        elapsedBeforeFailureMs = elapsedMs
                    )
                    val nextFallbackModel = fallbackDescriptor?.liveSetupModelName ?: when (activeModelInUse) {
                        VoiceConfig.GEMINI_LIVE_MODEL -> "models/gemini-2.5-flash-native-audio-preview-12-2025"
                        "models/gemini-2.5-flash-native-audio-preview-12-2025" -> "models/gemini-2.0-flash-exp"
                        else -> null
                    }
                    if (nextFallbackModel != null && nextFallbackModel != activeModelInUse) {
                        android.util.Log.i("RAFEL-WS", "Model $activeModelInUse rejected ($code). Automatically falling back to $nextFallbackModel...")
                        this@LiveSessionManager.webSocket = null
                        isSetupComplete = false
                        startSession(overrideModel = nextFallbackModel)
                        return
                    }
                }

                RafelLiveDiagnostics.webSocketState.value = "CLOSED ($code: $reason)"
                val formattedErr = "Gemini Live Error:\nHTTP/code: $code\nmessage: Closed: $reason\ncloseCode: $code\ncloseReason: $reason"
                cancelWatchdog()
                currentGenerationId.incrementAndGet()
                modelTurnInProgress.set(false)
                onServerInterrupted()
                android.util.Log.e("RAFEL-DIAGNOSTIC", "WEBSOCKET_ERROR: $formattedErr")
                RafelLiveDiagnostics.lastWebSocketError.value = formattedErr
                RafelLogger.logLiveSessionClosed(sessionId, code, reason)
                RafelLogger.logLive("session closed (code=$code, reason=$reason, id=$sessionId)")
                addMessage(formattedErr)
                updateState(RafelVoiceState.ERROR)
                this@LiveSessionManager.webSocket = null
                isSetupComplete = false
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket, sessionId: Long) {
        val systemPrompt = com.example.ai.RafelPersona.SYSTEM_PROMPT + "\n\n" +
            com.example.voice.VoiceBehaviorSettings.VOICE_DELIVERY_DIRECTIVE + "\n\n" + """
CRITICAL COMPREHENSION & INTENT RULES:
- Understand the user's actual spoken request and respond directly and accurately to it. Never deliberately contradict or invert the user's meaning.
- STRICT CONVERSATIONAL DEFAULT: Rafel is first and foremost a conversational AI. Greetings, pleasantries, questions, or casual talk (e.g. "Hello", "Hi", "Hello Rafel", "Hey Rafel", "Good morning", "How are you?", "Are you there?", "What's up?", "Bharat ki rajdhani kya hai?") are NORMAL CONVERSATIONS and MUST NEVER trigger any tool calls! Simply respond naturally and conversationally in your female voice.
- NEVER interpret greetings (e.g. "Hello Rafel", "Hello Rahul", "Hi Mom") as a call/dial intent. "Hello" NEVER means "Call".
- ONLY execute `searchAndCallContact` if the user explicitly uses a calling action verb (e.g. "Call Rahul", "Amit ko call karo", "Papa ko phone lagao").
- ONLY execute `sendWhatsAppMessage` if the user explicitly asks to send a WhatsApp message (e.g. "Amit ko WhatsApp message bhejo", "Mummy ko message likho").
- ONLY execute `composeMessage` if the user asks to message on Instagram, Telegram, Messenger, Signal, or SMS (e.g. "Instagram par Rahul ko message draft karo", "Telegram par Neha ko message likho").
- ONLY execute `readRecentMessages` if the user explicitly asks to read messages or check notifications (e.g. "mere naye messages padho", "Instagram par kya message aaya", "Telegram notification padho").
- ONLY execute `explainMessage` if the user asks to explain what a message means.
- ONLY execute `sendSms` if the user explicitly asks to send an SMS or text message.
- ONLY execute `describeScreen` when the user asks about visible screen content or when screen sharing is active.
- ONLY execute `openApp` if the user explicitly uses an open/launch verb (e.g. "Open YouTube", "WhatsApp kholo", "Instagram kholo", "Settings kholo", "Phone kholo").
- When asked to open an app (e.g. "WhatsApp kholo", "Instagram kholo", "YouTube kholo", "Settings kholo", "Phone kholo"), invoke `openApp` with appName set to the requested app.
- AFTER a tool executes and returns a result:
  * When `openApp` returns 'SUCCESS:', say in natural Hindi/Hinglish: '[App Name] khol diya!'.
  * When `openApp` returns 'NOT_INSTALLED:', tell the user truthfully that the app is not installed, and suggest downloading it from Google Play Store. Do NOT say a technical problem occurred!
  * When `searchAndCallContact` returns 'SUCCESS:', say that the dialer has been opened with the contact's number ready to call, or that the call is being placed.
  * When `searchAndCallContact` returns 'AMBIGUOUS:', ask the user to clarify which matching contact they want to call.
  * When `searchAndCallContact` returns 'NOT_FOUND:', inform the user truthfully that no contact by that name was found in phone contacts.
  * When `sendWhatsAppMessage` returns 'SUCCESS:', say clearly: 'WhatsApp khul gaya hai aur message draft ho gaya hai. Bhejne ke liye Send button dabayein.' (Never claim message was already sent).
  * When `sendWhatsAppMessage` returns 'NOT_INSTALLED:', tell the user that WhatsApp is not installed.
  * When `sendSms` returns 'SUCCESS:', confirm that the SMS draft has been prepared or sent.
  * When `describeScreen` returns, describe the screen's visual content conversationally in Hindi/Hinglish.
  * Never claim that an action succeeded when the tool returned a failure or permission denied.
- DO NOT speak your internal plan before executing a tool. Execute tools in silence.
- NEVER invent phone numbers. NEVER dial 121. If the user asks to call someone (e.g. 'Shivank', 'Rahul'), you MUST pass their EXACT contactName parameter into searchAndCallContact.
        """.trimIndent()

        val memoryContext = com.example.conversation.RafelConversationManager.getInstance(context).formatPromptContext(limit = 14)
        val finalSystemPrompt = if (memoryContext.isNotBlank()) {
            "$systemPrompt\n\n$memoryContext"
        } else {
            systemPrompt
        }

        val setupMsg = buildJsonObject {
            putJsonObject("setup") {
                put("model", activeModelInUse)
                putJsonObject("generationConfig") {
                    put("temperature", 0.85)
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", VoiceConfig.GEMINI_VOICE_NAME)
                            }
                        }
                    }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", finalSystemPrompt) })
                    }
                }
                putJsonArray("tools") {
                    add(toolsJson)
                }
            }
        }

        RafelLogger.logGeminiConnection("Sending setup message with model $activeModelInUse (sessionId=$sessionId)")
        RafelLogger.logDiagnostic("Sending setup message with model $activeModelInUse (sessionId=$sessionId)")
        RafelLogger.logLiveSetupSent(activeModelInUse)
        ws.send(setupMsg.toString())
    }

    private var watchdogJob: Job? = null
    private val lastAudioChunkTimeMs = AtomicLong(0L)
    private val activeWatchedTurnId = java.util.concurrent.atomic.AtomicReference<String>("")
    private val activeWatchedGenId = AtomicLong(0L)

    var responseStartTimeoutMs: Long = 8000L
    var interChunkTimeoutMs: Long = 6000L
    var maxTurnDurationMs: Long = 30000L

    fun startWatchdog(turnId: String, genId: Long) {
        watchdogJob?.cancel()
        activeWatchedTurnId.set(turnId)
        activeWatchedGenId.set(genId)
        lastAudioChunkTimeMs.set(System.currentTimeMillis())

        watchdogJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                delay(300L)
                val now = System.currentTimeMillis()

                // Check 1: Old generation check: If currentGenerationId has moved forward, abort old watchdog
                if (currentGenerationId.get() != genId) {
                    break
                }

                // Check 2: If turn is already completed and state has returned to normal (not waiting or speaking), stop watchdog
                val state = _rafelState.value
                if (!modelTurnInProgress.get() && state != RafelVoiceState.WAITING_FOR_AI && state != RafelVoiceState.AI_SPEAKING) {
                    break
                }

                // Stage 1: Waiting for model to begin response
                if (state == RafelVoiceState.WAITING_FOR_AI && !modelTurnInProgress.get()) {
                    if (now - startMs > responseStartTimeoutMs) {
                        handleWatchdogTimeout(turnId, genId, "No response from Gemini Live within ${responseStartTimeoutMs}ms")
                        break
                    }
                }

                // Stage 2: Model response started, receiving audio chunks, waiting for turnComplete
                if (modelTurnInProgress.get() || state == RafelVoiceState.AI_SPEAKING) {
                    val lastChunk = lastAudioChunkTimeMs.get()
                    val timeSinceLastChunk = now - lastChunk
                    if (timeSinceLastChunk > interChunkTimeoutMs) {
                        handleWatchdogTimeout(turnId, genId, "Audio generation stalled (no chunks for ${interChunkTimeoutMs}ms)")
                        break
                    }
                    if (now - startMs > maxTurnDurationMs) {
                        handleWatchdogTimeout(turnId, genId, "Max turn duration exceeded (${maxTurnDurationMs}ms)")
                        break
                    }
                }
            }
        }
    }

    fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun startTurnTimeout(turnId: String, genId: Long) {
        startWatchdog(turnId, genId)
    }

    private fun cancelTurnTimeout() {
        cancelWatchdog()
    }

    fun handleWatchdogTimeout(turnId: String, genId: Long, reason: String) {
        // Strict generation check: prevent an old timeout from resetting a newer turn
        if (currentGenerationId.get() != genId) {
            android.util.Log.i("RAFEL-DIAGNOSTIC", "Old turn timeout ignored for turnId=$turnId (stale genId=$genId, current=${currentGenerationId.get()})")
            return
        }

        android.util.Log.w("RAFEL-DIAGNOSTIC", "TURN_TIMEOUT: turnId=$turnId genId=$genId, reason=$reason")
        RafelLogger.logDiagnostic("TURN_TIMEOUT: turnId=$turnId genId=$genId ($reason)")

        // 1. Invalidate the stalled generation so any late/in-flight chunks are dropped
        val newGen = currentGenerationId.incrementAndGet()
        modelTurnInProgress.set(false)

        // 2. Stop and flush queued audio
        onServerInterrupted()

        // 3. Reset voice state safely
        updateState(RafelVoiceState.LISTENING)

        // 4. Record timeout in AIModelManager and recover stalled Live session
        val timeoutElapsed = if (turnRequestSentTimeMs.get() > 0L) {
            (System.currentTimeMillis() - turnRequestSentTimeMs.get()).coerceAtLeast(1L)
        } else {
            responseStartTimeoutMs
        }
        val fallbackOnTimeout = aiModelManager.onModelFailureAndSelectFallback(
            taskType = com.example.ai.router.ModelTaskType.LIVE_AUDIO,
            failedModelId = activeModelInUse,
            reason = "Timeout: $reason",
            httpOrWsCode = 408,
            hasApiKey = getEffectiveApiKey().isNotBlank(),
            elapsedBeforeFailureMs = timeoutElapsed
        )

        if (webSocket != null) {
            reconnectSession("Watchdog timeout: $reason", overrideModel = fallbackOnTimeout?.liveSetupModelName)
        }
    }

    fun reconnectSession(reason: String, overrideModel: String? = null) {
        try {
            webSocket?.close(1000, reason)
        } catch (_: Exception) {}
        webSocket = null
        isSetupComplete = false
        startSession(overrideModel = overrideModel)
    }

    private fun handleServerMessage(text: String, sessionId: Long) {
        if (sessionId != currentSessionId.get()) {
            RafelLogger.logDiagnostic("handleServerMessage ignored for old sessionId=$sessionId (current=${currentSessionId.get()})")
            return
        }

        try {
            val jsonMsg = json.parseToJsonElement(text).jsonObject
            RafelLiveDiagnostics.serverMessageCount.value++

            if (jsonMsg.containsKey("error")) {
                RafelLogger.logLiveMessage("error")
                val errObj = jsonMsg["error"]?.jsonObject
                val errMsg = com.example.ai.router.AIModelManager.redactSecrets(
                    errObj?.get("message")?.jsonPrimitive?.content ?: text,
                    getEffectiveApiKey()
                )
                val errCode = errObj?.get("code")?.jsonPrimitive?.content ?: "UNKNOWN"
                val errCodeInt = errObj?.get("code")?.jsonPrimitive?.intOrNull ?: 500
                val formattedErr = "Gemini Live Error:\nHTTP/code: $errCode\nmessage: $errMsg\ncloseCode: $errCode\ncloseReason: Server Error"
                RafelLiveDiagnostics.lastWebSocketError.value = formattedErr
                android.util.Log.e("RAFEL-WS", "[WS_MESSAGE] Server returned error: $formattedErr")
                RafelLogger.logRafelError("GEMINI_RESPONSE", "code=$errCode message=$errMsg")
                RafelLogger.logLiveError(formattedErr)
                RafelLogger.logError("Gemini Live server error ($errCode): $errMsg")
                RafelLogger.logDiagnostic("Gemini Live server error ($errCode): $errMsg")

                val elapsedBeforeFail = if (sessionConnectStartTimeMs.get() > 0L) {
                    (System.currentTimeMillis() - sessionConnectStartTimeMs.get()).coerceAtLeast(1L)
                } else {
                    1L
                }
                if (errCodeInt != 401 && errCodeInt != 403) {
                    val fallbackDescriptor = aiModelManager.onModelFailureAndSelectFallback(
                        taskType = com.example.ai.router.ModelTaskType.LIVE_AUDIO,
                        failedModelId = activeModelInUse,
                        reason = "Server error ($errCode): $errMsg",
                        httpOrWsCode = errCodeInt,
                        hasApiKey = getEffectiveApiKey().isNotBlank(),
                        elapsedBeforeFailureMs = elapsedBeforeFail
                    )
                    if (fallbackDescriptor != null && fallbackDescriptor.liveSetupModelName != activeModelInUse) {
                        android.util.Log.i("RAFEL-WS", "Auto-switching Live model from $activeModelInUse to ${fallbackDescriptor.liveSetupModelName} after server error ($errCode)...")
                        reconnectSession("Server error fallback ($errCode)", overrideModel = fallbackDescriptor.liveSetupModelName)
                        return
                    }
                }

                addMessage(formattedErr)
                updateState(RafelVoiceState.ERROR)
                return
            }

            if (jsonMsg.containsKey("setupComplete") || jsonMsg.containsKey("setup_complete")) {
                RafelLogger.logLiveMessage("setupComplete")
                isSetupComplete = true
                val connectElapsedMs = if (sessionConnectStartTimeMs.get() > 0L) {
                    (System.currentTimeMillis() - sessionConnectStartTimeMs.get()).coerceAtLeast(1L)
                } else {
                    0L
                }
                aiModelManager.recordConnectionEstablished(activeModelInUse, connectElapsedMs)
                RafelLiveDiagnostics.activeModel.value = activeModelInUse
                android.util.Log.i("RAFEL-LIVE", "[LIVE] SETUP_COMPLETE (connectTime=${connectElapsedMs}ms)")
                android.util.Log.i("RAFEL-DIAGNOSTIC", "SETUP_COMPLETE: session ready, model=$activeModelInUse, connectTime=${connectElapsedMs}ms")
                RafelLiveDiagnostics.setupComplete.value = true
                RafelLiveDiagnostics.log("SETUP_COMPLETE")
                RafelLogger.logRafelLive("SETUP_COMPLETE", "sessionId=$sessionId model=$activeModelInUse")
                RafelLogger.logLiveSetupAcknowledged()
                RafelLogger.logLiveSessionSetupComplete(sessionId)
                RafelLogger.logGeminiConnection("Gemini Live setup complete for session #$sessionId. Ready for real-time conversation.")
                RafelLogger.logDiagnostic("Gemini Live setup complete (sessionId=$sessionId)")
                addMessage("Rafel is ready.")
                updateState(RafelVoiceState.LISTENING)
                onSessionReady?.invoke()
            }

            val serverContent = jsonMsg["serverContent"]?.jsonObject ?: jsonMsg["server_content"]?.jsonObject
            if (serverContent != null) {
                RafelLogger.logLiveMessage("serverContent")
                RafelLiveDiagnostics.serverContentReceived.value = true
                RafelLiveDiagnostics.log("SERVER_CONTENT_RECEIVED")

                // Parse input audio transcription from Gemini Live if available
                var inText: String? = null
                val inputTransObj = serverContent["inputTranscription"]?.jsonObject 
                    ?: serverContent["input_transcription"]?.jsonObject
                if (inputTransObj != null) {
                    inText = inputTransObj["text"]?.jsonPrimitive?.content
                        ?: inputTransObj["parts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                }
                if (inText.isNullOrBlank()) {
                    val interimObj = serverContent["interimInputTranscription"]?.jsonObject
                        ?: serverContent["interim_input_transcription"]?.jsonObject
                    if (interimObj != null) {
                        inText = interimObj["text"]?.jsonPrimitive?.content
                            ?: interimObj["parts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                    }
                }
                if (inText.isNullOrBlank()) {
                    val audioTransObj = serverContent["inputAudioTranscription"]?.jsonObject
                        ?: serverContent["input_audio_transcription"]?.jsonObject
                    if (audioTransObj != null) {
                        inText = audioTransObj["text"]?.jsonPrimitive?.content
                    }
                }
                if (!inText.isNullOrBlank()) {
                    liveInputTranscript = inText
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "INPUT_TRANSCRIPT: text=\"$inText\"")
                    onUserTranscriptReceived(inText)
                }

                val isInterrupted = serverContent.get("interrupted")?.jsonPrimitive?.content == "true" ||
                        serverContent.get("interrupted")?.jsonPrimitive?.booleanOrNull == true

                if (isInterrupted) {
                    RafelLogger.logInterruption("Server acknowledged interruption.")
                    cancelWatchdog()
                    val newGen = currentGenerationId.incrementAndGet()
                    RafelLogger.logDiagnostic("Server acknowledged interruption -> incremented genId=$newGen")
                    modelTurnInProgress.set(false)
                    onServerInterrupted()
                    updateState(RafelVoiceState.INTERRUPTED)
                    return
                }

                val modelTurn = serverContent.get("modelTurn")?.jsonObject ?: serverContent.get("model_turn")?.jsonObject
                if (modelTurn != null) {
                    val nowMs = System.currentTimeMillis()
                    lastAudioChunkTimeMs.set(nowMs)
                    RafelLiveDiagnostics.activeModel.value = activeModelInUse
                    RafelLiveDiagnostics.activeProvider.value = "GEMINI_LIVE_WS"
                    RafelLiveDiagnostics.actualResponseSource.value = "GEMINI_LIVE"
                    RafelLiveDiagnostics.lastSuccessfulModelResponseTimestamp.value = nowMs
                    aiModelManager.recordRuntimeResponseDelivered(
                        source = "GEMINI_LIVE",
                        provider = "GEMINI_LIVE_WS",
                        modelId = activeModelInUse,
                        explicitApiKey = getEffectiveApiKey()
                    )
                    if (modelTurnInProgress.compareAndSet(false, true)) {
                        currentTurnAssistantText.clear()
                        forceNewUiAssistantLine = true
                        com.example.conversation.RafelConversationManager.getInstance(context).startNewAssistantVoiceTurn()
                        val reqStart = turnRequestSentTimeMs.get()
                        val ttfr = if (reqStart > 0L) (nowMs - reqStart).coerceAtLeast(1L) else 0L
                        currentTurnTtfrMs.set(ttfr)
                        aiModelManager.recordTimeToFirstResponse(activeModelInUse, ttfr)
                        updateState(RafelVoiceState.AI_SPEAKING)
                        android.util.Log.i("RAFEL-TURN", "[TURN] MODEL_TURN_STARTED (TTFR=${ttfr}ms)")
                        android.util.Log.i("RAFEL-PIPE", "5. SERVER_RESPONSE_RECEIVED (modelTurn started, TTFR=${ttfr}ms)")
                        RafelLogger.logDiagnostic("[TURN] MODEL_TURN_STARTED (TTFR=${ttfr}ms)")
                    }
                    RafelLiveDiagnostics.modelTurnReceived.value = true
                    RafelLiveDiagnostics.log("MODEL_TURN_RECEIVED")
                    RafelLogger.trace5GeminiResponseReceived("modelTurn received")
                    RafelLogger.logLive("response received")
                    RafelLogger.trackGeminiResponseStarted()
                }

                modelTurn?.get("parts")?.jsonArray?.forEach { partElement ->
                    val part = partElement.jsonObject

                    val inlineObj = part["inlineData"]?.jsonObject 
                        ?: part["inline_data"]?.jsonObject
                        ?: part["audio"]?.jsonObject
                    val dataBase64 = inlineObj?.get("data")?.jsonPrimitive?.content
                        ?: part["data"]?.jsonPrimitive?.content
                    if (!dataBase64.isNullOrEmpty()) {
                        try {
                            val rawBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                            val chunkIndex = modelTurnChunkCount.incrementAndGet()
                            val totalBytes = modelTurnTotalAudioBytes.addAndGet(rawBytes.size.toLong())
                            val nowChunkMs = System.currentTimeMillis()
                            lastAudioChunkTimeMs.set(nowChunkMs)
                            RafelLiveDiagnostics.audioPartCount.value = chunkIndex
                            RafelLiveDiagnostics.totalAudioBytesReceived.value = totalBytes

                            if (chunkIndex == 1) {
                                RafelLiveDiagnostics.firstAudioChunkReceived.value = true
                                val reqStart = turnRequestSentTimeMs.get()
                                val ttfa = if (reqStart > 0L) (nowChunkMs - reqStart).coerceAtLeast(1L) else 0L
                                currentTurnTtfaMs.set(ttfa)
                                aiModelManager.recordTimeToFirstAudioChunk(activeModelInUse, ttfa)
                            }

                            android.util.Log.i("RAFEL-DIAGNOSTIC", "MODEL_AUDIO_RECEIVED: chunk=#$chunkIndex, bytes=${rawBytes.size}, total=$totalBytes, MIME=audio/pcm;rate=24000")
                            android.util.Log.i("RAFEL-AUDIO", "[AUDIO] CHUNK #$chunkIndex")
                            android.util.Log.i("RAFEL-AUDIO", "[AUDIO] totalChunks: $chunkIndex")
                            android.util.Log.i("RAFEL-AUDIO", "[AUDIO] totalBytes: $totalBytes")
                            android.util.Log.i("RAFEL-PIPE", "7. MODEL_AUDIO_RECEIVED: ${rawBytes.size} bytes (chunk=#$chunkIndex, genId=${currentGenerationId.get()})")
                            RafelLogger.logDiagnostic("[AUDIO] CHUNK #$chunkIndex (${rawBytes.size} bytes, total=$totalBytes)")

                            val genId = currentGenerationId.get()
                            onAudioOut(genId, rawBytes)
                        } catch (e: Exception) {
                            RafelLogger.logError("Audio Base64 decode error: ${e.message}", e)
                        }
                    }

                    if (part.containsKey("text")) {
                        val textContent = part["text"]?.jsonPrimitive?.content
                        if (!textContent.isNullOrBlank()) {
                            val cleanPart = textContent.trim()
                            val existingTurnText = currentTurnAssistantText.toString().trim()
                            if (!existingTurnText.endsWith(cleanPart, ignoreCase = true)) {
                                if (currentTurnAssistantText.isNotEmpty() && !currentTurnAssistantText.endsWith(" ")) {
                                    currentTurnAssistantText.append(" ")
                                }
                                currentTurnAssistantText.append(cleanPart)
                                android.util.Log.i("RAFEL-PIPE", "6. MODEL_TEXT_TRANSCRIPT: \"$cleanPart\"")
                                RafelLogger.logRafelLive("TEXT_RESPONSE_RECEIVED", "text=\"$cleanPart\"")
                                appendAssistantText(cleanPart)
                            }
                        }
                    }
                }

                val isTurnComplete = serverContent.get("turnComplete")?.jsonPrimitive?.content == "true" ||
                        serverContent.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true ||
                        serverContent.get("turn_complete")?.jsonPrimitive?.content == "true" ||
                        serverContent.get("turn_complete")?.jsonPrimitive?.booleanOrNull == true ||
                        serverContent.get("generationComplete")?.jsonPrimitive?.content == "true" ||
                        serverContent.get("generationComplete")?.jsonPrimitive?.booleanOrNull == true ||
                        jsonMsg.get("turnComplete")?.jsonPrimitive?.content == "true" ||
                        jsonMsg.get("turnComplete")?.jsonPrimitive?.booleanOrNull == true

                if (isTurnComplete) {
                    val genId = currentGenerationId.get()
                    val totalChunks = modelTurnChunkCount.get()
                    val totalBytes = modelTurnTotalAudioBytes.get()
                    val queueBytes = getPlaybackQueueBytes?.invoke() ?: 0L
                    val effectiveTurnId = if (lastTurnId.isNotBlank()) lastTurnId else (currentTurn?.turnId ?: "turn_$genId")
                    val respText = currentTurnAssistantText.toString().trim()
                    val sttResult = serverInputTranscript
                    val sttErr = debugSttError ?: "No transcript generated"

                    android.util.Log.i("RAFEL-DEBUG", "=== TURN DIAGNOSTIC LOG ===")
                    android.util.Log.i("RAFEL-DEBUG", "[REAL_TURN]\ngenerationId=$genId\nturnId=$effectiveTurnId")
                    android.util.Log.i("RAFEL-DEBUG", "[MIC]\nbytes=$lastTurnPcmBytes\ndurationMs=$lastTurnDurationMs\nsampleRate=16000\nchannels=1")
                    if (sttResult != null) {
                        android.util.Log.i("RAFEL-DEBUG", "[DEBUG_STT]\ntranscript=$sttResult")
                    } else {
                        android.util.Log.i("RAFEL-DEBUG", "[DEBUG_STT_ERROR]\nerror=$sttErr")
                    }
                    android.util.Log.i("RAFEL-DEBUG", "[GEMINI]\nmodel=$activeModelInUse\nresponseText=$respText")
                    android.util.Log.i("RAFEL-DEBUG", "[AUDIO]\nchunks=$totalChunks\nbytes=$totalBytes\ngenerationId=$genId")
                    android.util.Log.i("RAFEL-DEBUG", "[TURN]\ncomplete=true\ninterrupted=false")

                    android.util.Log.i("RAFEL-EVIDENCE", "=== RAW CONVERSATION TURN EVIDENCE ===")
                    if (sttResult != null) {
                        android.util.Log.i("RAFEL-EVIDENCE", "DEBUG STT: $sttResult")
                    } else {
                        android.util.Log.i("RAFEL-EVIDENCE", "DEBUG STT ERROR: $sttErr")
                    }
                    android.util.Log.i("RAFEL-EVIDENCE", "GEMINI RESPONSE: $respText")
                    android.util.Log.i("RAFEL-EVIDENCE", "AUDIO CHUNKS: $totalChunks")
                    android.util.Log.i("RAFEL-EVIDENCE", "TURN ID: $effectiveTurnId")
                    android.util.Log.i("RAFEL-EVIDENCE", "GENERATION ID: $genId")
                    android.util.Log.i("RAFEL-EVIDENCE", "TURN COMPLETE: YES")
                    android.util.Log.i("RAFEL-EVIDENCE", "INTERRUPTED: NO")
                    android.util.Log.i("RAFEL-EVIDENCE", "ERROR: NONE")

                    val liveTranscript = liveInputTranscript ?: "TRANSCRIPTION_UNAVAILABLE"
                    val debugTranscript = sttResult ?: "DEBUG_STT_UNAVAILABLE"
                    val debugHistory = com.example.conversation.RafelConversationManager.getInstance(context).getDebugHistoryRedacted(limit = 10)
                    val sysPromptSummary = "Rafel Persona (Young adult Indian female AI companion, natural Hindi/Hinglish, contextual continuity without repetition)"
                    val routerDec = if (lastToolName != null) "TOOL_EXECUTION ($lastToolName)" else "DIRECT_CONVERSATION"
                    val finalResp = if (lastToolResult != null) lastToolResult!! else respText

                    android.util.Log.i("RAFEL-DIAGNOSTIC", "=== LIVE CONVERSATION DEBUG TRACE ===")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "TURN_ID: $effectiveTurnId")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "RAW_AUDIO_METADATA: sampleRate=16000, channels=1, bytes=$lastTurnPcmBytes, durationMs=$lastTurnDurationMs")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "GEMINI_AUDIO_INPUT: realtimeInput.audio (audio/pcm;rate=16000, chunks=$currentTurnChunkCount)")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "LIVE_INPUT_TRANSCRIPT: $liveTranscript")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "DEBUG_STT_TRANSCRIPT: $debugTranscript")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "ACTIVE_SYSTEM_INSTRUCTION: $sysPromptSummary")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "CONVERSATION_HISTORY: [${debugHistory.joinToString(" | ")}]")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "ROUTER_DECISION: $routerDec")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "MODEL_RESPONSE: $respText")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "FINAL_SPOKEN_TEXT: $finalResp")
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "PLAYBACK_GENERATION_ID: $genId")

                    if (lastToolName == null) {
                        val inputReported = if (liveTranscript != "TRANSCRIPTION_UNAVAILABLE") liveTranscript else debugTranscript
                        android.util.Log.i("RAFEL-ROUTER", "=== REQUEST ROUTER DIAGNOSTIC ===")
                        android.util.Log.i("RAFEL-ROUTER", "[REQUEST]\ntranscript=$inputReported\nintent=CONVERSATIONAL_CHAT\nrouterDecision=DIRECT_CONVERSATION\ntoolSelected=NONE\ntoolArguments={}\nconversationContextUsed=true")
                        android.util.Log.i("RAFEL-ROUTER", "[RESPONSE]\nmodelText=$respText\ntoolResult=NONE\nfinalResponse=$respText")
                    }

                    cancelTurnTimeout()
                    android.util.Log.i("RAFEL-DIAGNOSTIC", "TURN_COMPLETE: genId=$genId, totalChunks=$totalChunks, totalBytes=$totalBytes")
                    android.util.Log.i("RAFEL-TURN", "[TURN] MODEL_TURN_COMPLETED")
                    android.util.Log.i("RAFEL-PIPE", "8. TURN_COMPLETE (genId=$genId, totalChunks=$totalChunks, totalBytes=$totalBytes, queueBytes=$queueBytes)")
                    android.util.Log.i("RAFEL-AUDIO", "[AUDIO] totalChunks: $totalChunks")
                    android.util.Log.i("RAFEL-AUDIO", "[AUDIO] totalBytes: $totalBytes")
                    android.util.Log.i("RAFEL-AUDIO", "[AUDIO] playbackQueueBytes: $queueBytes")
                    RafelLogger.logDiagnostic("[TURN] MODEL_TURN_COMPLETED (chunks=$totalChunks, bytes=$totalBytes, queueBytes=$queueBytes)")

                    modelTurnInProgress.set(false)
                    val totalTurnResponseMs = if (turnRequestSentTimeMs.get() > 0L) {
                        (System.currentTimeMillis() - turnRequestSentTimeMs.get()).coerceAtLeast(1L)
                    } else {
                        -1L
                    }
                    aiModelManager.recordTurnCompleted(
                        modelId = activeModelInUse,
                        totalResponseTimeMs = totalTurnResponseMs,
                        ttfrMs = currentTurnTtfrMs.get().takeIf { it > 0L },
                        ttfaMs = currentTurnTtfaMs.get().takeIf { it > 0L }
                    )
                    if (totalChunks == 0) {
                        updateState(RafelVoiceState.LISTENING)
                    }
                    onTurnComplete(genId)
                }
            }

            if (jsonMsg.containsKey("toolCall")) {
                RafelLogger.logLiveMessage("toolCall")
                val toolCallObj = jsonMsg["toolCall"]?.jsonObject
                val functionCalls = toolCallObj?.get("functionCalls")?.jsonArray

                functionCalls?.forEach { callElement ->
                    val callObj = callElement.jsonObject
                    val id = callObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = callObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = callObj["args"]?.jsonObject ?: buildJsonObject { }

                    executeToolAndRespond(id, name, args)
                }
            }

            if (jsonMsg.containsKey("toolCallCancellation")) {
                RafelLogger.logLiveMessage("toolCallCancellation")
            }

            if (jsonMsg.containsKey("goAway")) {
                RafelLogger.logLiveMessage("goAway")
                RafelLogger.logLiveError("Server sent goAway signal (session expiring).")
                RafelLiveDiagnostics.lastFallbackReason.value = "Server goAway (session expiring) — reconnecting"
                if (!modelTurnInProgress.get()) {
                    reconnectSession("Server goAway session expiry", overrideModel = activeModelInUse)
                }
            }
        } catch (e: Exception) {
            RafelLogger.logError("Error parsing server message", e)
        }
    }

    data class ActiveUserTurn(
        val turnId: String,
        val startTimeMs: Long,
        var endTimeMs: Long = 0L,
        @Volatile var isFrozen: Boolean = false,
        @Volatile var transcript: String? = null,
        val consumed: AtomicBoolean = AtomicBoolean(false)
    )

    private val turnSeqCounter = AtomicLong(0L)
    @Volatile private var currentTurn: ActiveUserTurn? = null
    private val turnSyncLock = Any()

    fun getCurrentGenerationId(): Long = currentGenerationId.get()
    fun getCurrentSessionId(): Long = currentSessionId.get()
    fun getActiveModel(): String = activeModelInUse

    private val currentTurnDigest = java.security.MessageDigest.getInstance("SHA-256")
    private var currentTurnChunkCount = 0
    private val sentChunkHashes = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var serverInputTranscript: String? = null
    @Volatile private var liveInputTranscript: String? = null
    @Volatile private var debugSttError: String? = null
    @Volatile private var currentTurnAssistantText = StringBuilder()
    private val currentTurnPcmBuffer = ByteArrayOutputStream()
    @Volatile private var lastTurnDurationMs: Long = 0L
    @Volatile private var lastTurnPcmBytes: Long = 0L
    @Volatile private var lastTurnId: String = ""

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16 // 16 bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        val wav = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wav, 0, 44)
        System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
        return wav
    }

    private fun triggerDebugStt(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) {
            debugSttError = "PCM buffer was empty (0 bytes)"
            serverInputTranscript = null
            android.util.Log.e("RAFEL-DEBUG", "[DEBUG_STT_ERROR] error=$debugSttError")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val apiKey = getEffectiveApiKey()
                if (apiKey.isBlank()) {
                    debugSttError = "Gemini API Key missing/blank"
                    serverInputTranscript = null
                    android.util.Log.e("RAFEL-DEBUG", "[DEBUG_STT_ERROR] error=$debugSttError")
                    return@launch
                }
                val wavBytes = addWavHeader(pcmBytes, 16000, 1)
                val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val reqJson = buildJsonObject {
                    putJsonArray("contents") {
                        addJsonObject {
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", "Transcribe this audio verbatim in the exact original language spoken (Hindi/English/Hinglish). Output ONLY the transcript, no other text.")
                                }
                                addJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", "audio/wav")
                                        put("data", base64Wav)
                                    }
                                }
                            }
                        }
                    }
                }

                val sttDecision = aiModelManager.selectModelForTask(
                    taskType = com.example.ai.router.ModelTaskType.AUDIO_TRANSCRIPTION,
                    hasApiKey = true
                )
                val sttModelName = sttDecision.selectedModel?.restModelName ?: "gemini-2.5-flash"
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = reqJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$sttModelName:generateContent?key=$apiKey")
                    .post(requestBody)
                    .build()

                val sttStartMs = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    val sttElapsedMs = (System.currentTimeMillis() - sttStartMs).coerceAtLeast(1L)
                    if (response.isSuccessful) {
                        aiModelManager.recordTurnCompleted(sttModelName, sttElapsedMs, sttElapsedMs)
                        val bodyString = response.body?.string() ?: ""
                        val root = json.parseToJsonElement(bodyString).jsonObject
                        val candidates = root["candidates"]?.jsonArray
                        val firstCandidate = candidates?.getOrNull(0)?.jsonObject
                        val parts = firstCandidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        val transcript = parts?.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim()
                        if (!transcript.isNullOrBlank()) {
                            serverInputTranscript = transcript
                            debugSttError = null
                            android.util.Log.i("RAFEL-DEBUG", "[DEBUG_STT]\ntranscript=$transcript")
                        } else {
                            val candidateError = firstCandidate?.toString() ?: "Empty candidate content"
                            debugSttError = "No text candidate returned: $candidateError"
                            serverInputTranscript = null
                            android.util.Log.e("RAFEL-DEBUG", "[DEBUG_STT_ERROR] error=$debugSttError")
                        }
                    } else {
                        val errBody = response.body?.string() ?: ""
                        aiModelManager.onModelFailureAndSelectFallback(
                            taskType = com.example.ai.router.ModelTaskType.AUDIO_TRANSCRIPTION,
                            failedModelId = sttModelName,
                            reason = "HTTP ${response.code}",
                            httpOrWsCode = response.code,
                            elapsedBeforeFailureMs = sttElapsedMs
                        )
                        debugSttError = "HTTP ${response.code}: $errBody"
                        serverInputTranscript = null
                        android.util.Log.e("RAFEL-DEBUG", "[DEBUG_STT_ERROR] error=$debugSttError")
                    }
                }
            } catch (e: Exception) {
                debugSttError = "${e.javaClass.simpleName}: ${e.message}"
                serverInputTranscript = null
                android.util.Log.e("RAFEL-DEBUG", "[DEBUG_STT_ERROR] error=$debugSttError")
            }
        }
    }

    fun onTurnAudioStart(): String = synchronized(turnSyncLock) {
        val newTurnId = "turn_live_${System.currentTimeMillis()}_${turnSeqCounter.incrementAndGet()}"
        activeTurnStartTime = System.currentTimeMillis()
        activeTurnAudioBytes = 0L
        currentTurnDigest.reset()
        currentTurnChunkCount = 0
        sentChunkHashes.clear()
        serverInputTranscript = null
        liveInputTranscript = null
        debugSttError = null
        lastToolName = null
        lastToolArgs = null
        lastToolResult = null
        currentTurnAssistantText.clear()
        synchronized(currentTurnPcmBuffer) {
            currentTurnPcmBuffer.reset()
        }
        val turn = ActiveUserTurn(
            turnId = newTurnId,
            startTimeMs = activeTurnStartTime
        )
        currentTurn = turn
        val newGen = currentGenerationId.incrementAndGet()
        modelTurnChunkCount.set(0)
        modelTurnTotalAudioBytes.set(0L)
        modelTurnInProgress.set(false)
        android.util.Log.i("RAFEL-TURN", "[TURN] USER_TURN_STARTED")
        android.util.Log.i("RAFEL-PIPE", "1. USER_AUDIO_START (turnId=$newTurnId, genId=$newGen, sessionId=${currentSessionId.get()})")
        RafelLogger.logDiagnostic("[TURN] USER_TURN_STARTED: $newTurnId (genId=$newGen)")
        newTurnId
    }

    fun onTurnAudioEnd() = synchronized(turnSyncLock) {
        currentTurn?.let {
            it.isFrozen = true
            it.endTimeMs = System.currentTimeMillis()
            android.util.Log.i("RAFEL-TURN", "[TURN] USER_TURN_ENDED")
            android.util.Log.i("RAFEL-PIPE", "2. USER_AUDIO_END (turnId=${it.turnId})")
            RafelLogger.logDiagnostic("[TURN] USER_TURN_ENDED: ${it.turnId}")
        }
    }

    fun onUserTranscriptReceived(transcript: String) = synchronized(turnSyncLock) {
        val turn = currentTurn
        if (turn != null) {
            turn.transcript = transcript
            RafelLogger.logUserTranscript(transcript)
            addMessage("You: $transcript")
            com.example.conversation.RafelConversationManager.getInstance(context).addUserVoiceMessage(transcript)
        }
    }

    fun setUserTranscript(transcript: String) {
        onUserTranscriptReceived(transcript)
    }

    fun getCurrentTurnSnapshot(toolCallId: String): UserTurnContext = synchronized(turnSyncLock) {
        val turn = currentTurn
        if (turn == null) {
            UserTurnContext(
                userTurnId = "turn_none",
                transcript = null,
                toolCallId = toolCallId,
                consumedFlag = AtomicBoolean(false)
            )
        } else {
            UserTurnContext(
                userTurnId = turn.turnId,
                transcript = turn.transcript,
                toolCallId = toolCallId,
                consumedFlag = turn.consumed
            )
        }
    }

    @Volatile private var lastToolName: String? = null
    @Volatile private var lastToolArgs: String? = null
    @Volatile private var lastToolResult: String? = null

    private fun executeToolAndRespond(id: String, name: String, args: JsonObject) {
        // Create an immutable decision envelope for this tool call from current turn
        val turnSnapshot = getCurrentTurnSnapshot(toolCallId = id)
        lastToolName = name
        lastToolArgs = args.toString()
        android.util.Log.i("RAFEL-DIAGNOSTIC", "TOOL_CALL_RECEIVED: id=$id, name=$name, args=$args")
        updateState(RafelVoiceState.WAITING_FOR_AI)
        scope.launch {
            RafelLogger.logGeminiResponse("Evaluating tool execution request: $name with turn=${turnSnapshot.userTurnId}, transcript: '${turnSnapshot.transcript}'")
            val resultStr = toolEngine.execute(name, args, turnSnapshot)
            lastToolResult = resultStr
            android.util.Log.i("RAFEL-DIAGNOSTIC", "TOOL_RESULT_SENT: id=$id, name=$name, result=$resultStr")

            val effectiveTranscript = turnSnapshot.transcript ?: serverInputTranscript ?: "VOICE_INPUT"
            android.util.Log.i("RAFEL-ROUTER", "=== REQUEST ROUTER DIAGNOSTIC ===")
            android.util.Log.i("RAFEL-ROUTER", "[REQUEST]\ntranscript=$effectiveTranscript\nintent=$name\nrouterDecision=TOOL_EXECUTION\ntoolSelected=$name\ntoolArguments=$args\nconversationContextUsed=true")
            android.util.Log.i("RAFEL-ROUTER", "[RESPONSE]\nmodelText=${currentTurnAssistantText.toString().trim()}\ntoolResult=$resultStr\nfinalResponse=$resultStr")

            val responseMsg = buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        add(buildJsonObject {
                            put("id", id)
                            put("name", name)
                            putJsonObject("response") {
                                put("result", resultStr)
                            }
                        })
                    }
                }
            }
            turnRequestSentTimeMs.set(System.currentTimeMillis())
            currentTurnTtfrMs.set(0L)
            currentTurnTtfaMs.set(0L)
            webSocket?.send(responseMsg.toString())
            startWatchdog(turnSnapshot.userTurnId, currentGenerationId.get())
        }
    }

    /**
     * Sends a proactive conversation turn to Gemini Live without reconnecting.
     * Returns true if sent over the active Live WebSocket, or false if unavailable.
     */
    fun sendProactiveTurn(prompt: String): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupComplete) return false
        val newGen = currentGenerationId.incrementAndGet()
        modelTurnChunkCount.set(0)
        modelTurnTotalAudioBytes.set(0L)
        modelTurnInProgress.set(false)
        currentTurnAssistantText.clear()
        forceNewUiAssistantLine = true
        com.example.conversation.RafelConversationManager.getInstance(context).startNewAssistantVoiceTurn()
        turnRequestSentTimeMs.set(System.currentTimeMillis())
        currentTurnTtfrMs.set(0L)
        currentTurnTtfaMs.set(0L)
        val turnId = "turn_proactive_${System.currentTimeMillis()}"
        startWatchdog(turnId, newGen)
        updateState(RafelVoiceState.WAITING_FOR_AI)

        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", prompt) })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        return ws.send(msg.toString())
    }

    private var activeTurnStartTime = 0L
    private var activeTurnAudioBytes = 0L

    /**
     * Streams an input PCM audio chunk (16kHz, mono) directly into Gemini Live.
     */
    fun sendAudioData(pcmData: ShortArray, length: Int) {
        val ws = webSocket ?: return
        if (!isSetupComplete) return

        if (activeTurnStartTime == 0L) {
            activeTurnStartTime = System.currentTimeMillis()
        }

        val byteArray = ByteArray(length * 2)
        for (i in 0 until length) {
            val sample = pcmData[i]
            byteArray[i * 2] = (sample.toInt() and 0x00FF).toByte()
            byteArray[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        activeTurnAudioBytes += byteArray.size

        synchronized(currentTurnPcmBuffer) {
            currentTurnPcmBuffer.write(byteArray)
        }

        currentTurnDigest.update(byteArray)
        currentTurnChunkCount++
        val chunkDigest = java.security.MessageDigest.getInstance("SHA-256")
        chunkDigest.update(byteArray)
        val chunkHash = chunkDigest.digest().take(6).joinToString("") { "%02x".format(it) }
        val isDuplicate = !sentChunkHashes.add(chunkHash)

        val base64Data = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val jsonString = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64Data\"}}}"
        RafelLiveDiagnostics.inputPcmBytesSent.value += byteArray.size
        android.util.Log.i("RAFEL-EVIDENCE", "realtimeInput sent: chunk #$currentTurnChunkCount, bytes=${byteArray.size}, hash=$chunkHash, duplicate=$isDuplicate")
        ws.send(jsonString)
    }

    /**
     * Streams a captured screen frame (JPEG Base64) to the active Gemini Live Multimodal session.
     */
    fun sendImageFrame(base64Jpeg: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete) return
        try {
            val jsonString = buildJsonObject {
                putJsonObject("realtimeInput") {
                    putJsonArray("mediaChunks") {
                        add(buildJsonObject {
                            put("mimeType", "image/jpeg")
                            put("data", base64Jpeg)
                        })
                    }
                }
            }.toString()
            ws.send(jsonString)
            android.util.Log.i("RAFEL-SCREEN", "Sent screen frame to Gemini Live (${base64Jpeg.length} chars)")
        } catch (e: Exception) {
            android.util.Log.e("RAFEL-SCREEN", "Failed to send image frame: ${e.message}")
        }
    }

    /**
     * Called when the user has finished speaking an utterance.
     * Let server-side VAD detect end of speech from continuous audio.
     * Starts the turn timeout watchdog to ensure UI never stays stuck on Thinking.
     */
    fun onUserSpeechFinished() {
        sendTurnComplete()
    }

    fun sendTurnComplete() {
        val ws = webSocket ?: return
        if (!isSetupComplete) {
            RafelLogger.logDiagnostic("sendTurnComplete deferred: setup not complete yet")
            return
        }

        val endTime = System.currentTimeMillis()
        val startTime = if (activeTurnStartTime > 0L) activeTurnStartTime else endTime
        val duration = endTime - startTime

        RafelLiveDiagnostics.turnCompleteSent.value = true
        RafelLiveDiagnostics.log("TURN_COMPLETE_SENT")
        RafelLogger.logRafelLive("TURN_COMPLETE_SENT", "duration=${duration}ms audioBytes=$activeTurnAudioBytes")
        RafelLogger.logUserTurn(
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            audioBytes = activeTurnAudioBytes,
            turnComplete = true
        )

        val bytesSent = activeTurnAudioBytes
        val turnId = currentTurn?.turnId ?: "turn_${System.currentTimeMillis()}"
        val genId = currentGenerationId.get()
        val fullHash = try {
            currentTurnDigest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "none" }

        lastTurnId = turnId
        lastTurnDurationMs = duration
        lastTurnPcmBytes = bytesSent

        val pcmSnapshot = synchronized(currentTurnPcmBuffer) {
            currentTurnPcmBuffer.toByteArray()
        }
        triggerDebugStt(pcmSnapshot)

        android.util.Log.i("RAFEL-PIPE", "3. EXACT_AUDIO_BYTES_SENT: $bytesSent bytes")
        android.util.Log.i("RAFEL-PIPE", "4. USER_TURN_SENT (model=$activeModelInUse, endpoint=v1beta.GenerativeService.BidiGenerateContent, sessionId=${currentSessionId.get()})")

        android.util.Log.i("RAFEL-DEBUG", "=== RAW USER TURN DEBUG ===")
        android.util.Log.i("RAFEL-DEBUG", "TURN ID: $turnId")
        android.util.Log.i("RAFEL-DEBUG", "GENERATION ID: $genId")
        android.util.Log.i("RAFEL-DEBUG", "PCM BYTES: $bytesSent")
        android.util.Log.i("RAFEL-DEBUG", "AUDIO DURATION: ${duration}ms")
        android.util.Log.i("RAFEL-DEBUG", "SAMPLE RATE: 16000Hz")
        android.util.Log.i("RAFEL-DEBUG", "CHANNELS: 1 (MONO)")
        android.util.Log.i("RAFEL-DEBUG", "AUDIO PAYLOAD BYTES: $bytesSent")
        android.util.Log.i("RAFEL-DEBUG", "PCM SHA-256: $fullHash")
        android.util.Log.i("RAFEL-DEBUG", "TOTAL CHUNKS SENT: $currentTurnChunkCount")
        android.util.Log.i("RAFEL-DEBUG", "TURN COMPLETE SENT: YES")
        android.util.Log.i("RAFEL-DEBUG", "GEMINI RESPONSE RECEIVED: PENDING")

        // Reset turn state and record turn send timestamp for TTFR & TTFA measurement
        turnRequestSentTimeMs.set(System.currentTimeMillis())
        currentTurnTtfrMs.set(0L)
        currentTurnTtfaMs.set(0L)
        activeTurnStartTime = 0L
        activeTurnAudioBytes = 0L
        onTurnAudioEnd()

        RafelLogger.trackTurnCompleteSent()
        RafelLogger.trace3UserTurnSent()
        RafelLogger.logLive("user audio sent, awaiting server VAD model turn")
        RafelLogger.logDiagnostic("[LIVE] user audio complete, awaiting model turn")
        android.util.Log.i("RAFEL-DIAGNOSTIC", "MIC_AUDIO_SENT: bytes=$bytesSent, duration=${duration}ms, chunks=$currentTurnChunkCount, turnId=$turnId")
        android.util.Log.i("RAFEL-TURN", "[TURN] USER_TURN_AUDIO_STREAMED")
        android.util.Log.i("RAFEL-LIVE", "[LIVE] WAITING_FOR_MODEL_RESPONSE")

        startTurnTimeout(turnId, genId)
    }

    fun sendTextMessage(text: String) {
        if (webSocket == null || !isSetupComplete) return
        val newGen = currentGenerationId.incrementAndGet()
        modelTurnChunkCount.set(0)
        modelTurnTotalAudioBytes.set(0L)
        modelTurnInProgress.set(false)
        turnRequestSentTimeMs.set(System.currentTimeMillis())
        currentTurnTtfrMs.set(0L)
        currentTurnTtfaMs.set(0L)
        android.util.Log.i("RAFEL-TURN", "[TURN] USER_TURN_STARTED")
        synchronized(turnSyncLock) {
            val id = "turn_${System.currentTimeMillis()}_${turnSeqCounter.incrementAndGet()}"
            val turn = ActiveUserTurn(
                turnId = id,
                startTimeMs = System.currentTimeMillis(),
                endTimeMs = System.currentTimeMillis(),
                isFrozen = true,
                transcript = text
            )
            currentTurn = turn
            RafelLogger.logDiagnostic("Text UserTurn started: $id (genId=$newGen)")
        }
        android.util.Log.i("RAFEL-TURN", "[TURN] USER_TURN_ENDED")
        android.util.Log.i("RAFEL-TURN", "[TURN] USER_TURN_SENT")
        addMessage("You: $text")
        com.example.conversation.RafelConversationManager.getInstance(context).addUserVoiceMessage(text)
        updateState(RafelVoiceState.WAITING_FOR_AI)
        val turnIdVal = currentTurn?.turnId ?: "turn_text_$newGen"
        startWatchdog(turnIdVal, newGen)
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", text) })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        webSocket?.send(msg.toString())
    }

    fun notifyBargeIn() {
        cancelWatchdog()
        // Increment generation ID so that incoming audio chunks for current response are discarded
        val newGen = currentGenerationId.incrementAndGet()
        modelTurnInProgress.set(false)
        onServerInterrupted()
        RafelLogger.logDiagnostic("Barge-in notified to LiveSessionManager -> new genId=$newGen")
        updateState(RafelVoiceState.INTERRUPTED)
    }

    fun handleServerInterruption() {
        cancelWatchdog()
        currentGenerationId.incrementAndGet()
        modelTurnInProgress.set(false)
        onServerInterrupted()
        updateState(RafelVoiceState.INTERRUPTED)
    }

    fun updateState(newState: RafelVoiceState) {
        val oldState = _rafelState.value
        if (oldState != newState) {
            if (oldState == RafelVoiceState.WAITING_FOR_AI) {
                android.util.Log.i("RAFEL-STATE", "[STATE] THINKING_EXIT")
            }
            if (oldState == RafelVoiceState.AI_SPEAKING) {
                android.util.Log.i("RAFEL-STATE", "[STATE] SPEAKING_EXIT")
            }
            if (newState == RafelVoiceState.WAITING_FOR_AI) {
                android.util.Log.i("RAFEL-STATE", "[STATE] THINKING_ENTER")
            }
            if (newState == RafelVoiceState.AI_SPEAKING) {
                android.util.Log.i("RAFEL-STATE", "[STATE] SPEAKING_ENTER")
            }

            _rafelState.value = newState
            RafelLogger.logVoiceState(oldState, newState)
            onStateChange(newState)
        }
    }

    fun appendAssistantText(text: String) {
        val sanitized = com.example.voice.VoiceBehaviorSettings.sanitizeForNaturalDelivery(text)
        if (sanitized.isBlank()) return
        com.example.conversation.RafelConversationManager.getInstance(context).appendRafelVoiceChunk(sanitized)
        val current = _messages.value
        val last = current.lastOrNull()
        if (!forceNewUiAssistantLine && last != null && last.startsWith("Rafel: ")) {
            val prevBody = last.removePrefix("Rafel: ").trim()
            if (prevBody.endsWith(sanitized.trim(), ignoreCase = true)) {
                return
            }
            val updated = current.toMutableList()
            val sep = if (prevBody.isEmpty() || prevBody.endsWith(" ") || sanitized.startsWith(" ")) "" else " "
            val dedupedBody = com.example.ai.PersonaSettings.deduplicateSentencesInTurn(prevBody + sep + sanitized)
            updated[updated.lastIndex] = "Rafel: $dedupedBody"
            _messages.value = updated
        } else {
            forceNewUiAssistantLine = false
            addMessage("Rafel: ${com.example.ai.PersonaSettings.deduplicateSentencesInTurn(sanitized)}")
        }
    }

    private fun addMessage(msg: String) {
        val current = _messages.value
        // Bound conversation UI history to the most recent 50 messages to prevent memory growth in long sessions
        val updated = if (current.size >= 50) {
            current.drop(current.size - 49) + msg
        } else {
            current + msg
        }
        _messages.value = updated
    }

    fun stopSession() {
        currentSessionId.incrementAndGet()
        cancelWatchdog()
        try {
            webSocket?.close(1000, "User stopped")
        } catch (e: Exception) {}
        webSocket = null
        isSetupComplete = false
        RafelLiveDiagnostics.webSocketState.value = "DISCONNECTED"
        RafelLiveDiagnostics.setupComplete.value = false
        updateState(RafelVoiceState.IDLE)
        addMessage("Session ended.")
    }

    fun isConnected(): Boolean = webSocket != null && isSetupComplete
}
