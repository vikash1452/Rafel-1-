package com.example.conversation

import android.content.Context
import com.example.ai.GeminiTextClient
import com.example.ai.PersonaSettings
import com.example.memory.ConversationTurnEntity
import com.example.memory.MemoryManager
import com.example.memory.RafelMemoryDatabase
import com.example.tools.AssistantIntent
import com.example.tools.AssistantIntentRouter
import com.example.tools.SemanticIntentResult
import com.example.tools.ToolExecutionEngine
import com.example.tools.ToolSafetyGate
import com.example.tools.UserTurnContext
import com.example.voice.RafelLogger
import com.example.voice.VoiceBehaviorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Authoritative Central Conversation Manager for Rafel.
 *
 * UNIFIES Voice and Chat conversations into a SINGLE underlying conversation stream:
 * - Spoken turns (MIC -> STT -> Gemini Live audio) appear in the chat timeline with "Voice" badge.
 * - Typed turns (Chat -> Text -> Gemini) appear in the chat timeline with "Text" badge.
 * - Both share the same Room database persistence, intent routing, and memory context.
 * - Gemini Live is injected with recent typed messages so voice continuity is preserved.
 * - Gemini Text is injected with recent voice messages so chat continuity is preserved.
 */
class RafelConversationManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db = RafelMemoryDatabase.getInstance(appContext)
    private val memoryDao = db.memoryDao()
    val memoryManager: MemoryManager = MemoryManager.getInstance(appContext)
    val aiModelManager: com.example.ai.router.AIModelManager
        get() = com.example.ai.router.AIModelManager.getInstance(appContext)
    var geminiTextClientOverride: GeminiTextClient? = null
    private val defaultTextClient: GeminiTextClient by lazy {
        GeminiTextClient(aiModelManager = aiModelManager)
    }
    private fun resolveTextClient(): GeminiTextClient =
        geminiTextClientOverride ?: defaultTextClient

    private val _messages = MutableStateFlow<List<UnifiedMessage>>(emptyList())
    val messages: StateFlow<List<UnifiedMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    init {
        loadHistoryFromDatabase()
        scope.launch {
            defaultTextClient.prewarmConnection()
        }
    }

    private fun loadHistoryFromDatabase() {
        scope.launch {
            try {
                val entities = memoryDao.getRecentTurns(limit = 40)
                val loaded = entities.reversed().map { entity ->
                    val type = when (entity.messageType) {
                        "USER_VOICE" -> UnifiedMessageType.USER_VOICE
                        "RAFEL_VOICE" -> UnifiedMessageType.RAFEL_VOICE
                        "USER_TEXT" -> UnifiedMessageType.USER_TEXT
                        "RAFEL_TEXT" -> UnifiedMessageType.RAFEL_TEXT
                        else -> if (entity.role == "user") UnifiedMessageType.USER_TEXT else UnifiedMessageType.RAFEL_TEXT
                    }
                    UnifiedMessage(
                        id = "db_${entity.id}",
                        type = type,
                        text = entity.text,
                        timestamp = entity.timestamp
                    )
                }
                if (loaded.isNotEmpty()) {
                    _messages.value = loaded
                    RafelLogger.logVoice("Loaded ${loaded.size} unified conversation turns from memory.")
                }
            } catch (e: Exception) {
                RafelLogger.logError("Failed to load conversation history from database", e)
            }
        }
    }

    fun addUserVoiceMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        memoryManager.observeUserUtterance(clean)

        // Deduplicate rapid consecutive identical voice transcripts
        val current = _messages.value
        val last = current.lastOrNull()
        if (last != null && last.type == UnifiedMessageType.USER_VOICE && last.text.equals(clean, ignoreCase = true)) {
            return
        }

        val msg = UnifiedMessage(
            id = UUID.randomUUID().toString(),
            type = UnifiedMessageType.USER_VOICE,
            text = clean,
            timestamp = System.currentTimeMillis()
        )
        appendMessage(msg)
        persistTurn("user", clean, "USER_VOICE", msg.timestamp)
    }

    @Volatile
    private var forceNewAssistantVoiceBubble = false

    /**
     * Marks that the next assistant voice chunk belongs to a brand-new assistant turn
     * (e.g., a proactive voice turn or a new model response turn) so consecutive
     * assistant turns are never merged into the previous message bubble.
     */
    fun startNewAssistantVoiceTurn() {
        forceNewAssistantVoiceBubble = true
    }

    fun addRafelVoiceMessage(text: String): UnifiedMessage {
        val rawClean = text.trim()
        if (rawClean.isBlank()) {
            return UnifiedMessage(
                id = UUID.randomUUID().toString(),
                type = UnifiedMessageType.RAFEL_VOICE,
                text = "",
                timestamp = System.currentTimeMillis()
            )
        }
        forceNewAssistantVoiceBubble = false

        val historySnapshot = _messages.value
        val lastUserPrompt = historySnapshot.lastOrNull { it.isUser }?.text.orEmpty()
        val priorHistory = if (historySnapshot.lastOrNull()?.isUser == true) {
            historySnapshot.dropLast(1)
        } else {
            historySnapshot
        }
        val recentAssistant = historySnapshot.filter { !it.isUser }.takeLast(4).map { it.text }
        val clean = PersonaSettings.postProcessResponse(
            rawResponse = VoiceBehaviorSettings.sanitizeForNaturalDelivery(rawClean),
            userPrompt = lastUserPrompt,
            recentAssistantMessages = recentAssistant,
            recentHistory = priorHistory
        ).ifBlank { rawClean }

        val msg = UnifiedMessage(
            id = UUID.randomUUID().toString(),
            type = UnifiedMessageType.RAFEL_VOICE,
            text = clean,
            timestamp = System.currentTimeMillis()
        )
        appendMessage(msg)
        persistTurn("assistant", clean, "RAFEL_VOICE", msg.timestamp)
        return msg
    }

    fun appendRafelVoiceChunk(chunk: String) {
        val cleanChunk = VoiceBehaviorSettings.sanitizeForNaturalDelivery(chunk)
        if (cleanChunk.isBlank()) return
        val current = _messages.value
        val last = current.lastOrNull()
        if (!forceNewAssistantVoiceBubble && last != null && last.type == UnifiedMessageType.RAFEL_VOICE) {
            if (last.text.trim().endsWith(cleanChunk.trim(), ignoreCase = true)) {
                return
            }
            val updated = current.toMutableList()
            val sep = if (last.text.endsWith(" ") || cleanChunk.startsWith(" ")) "" else " "
            val combined = PersonaSettings.deduplicateSentencesInTurn(
                VoiceBehaviorSettings.sanitizeForNaturalDelivery(last.text + sep + cleanChunk)
            )
            updated[updated.lastIndex] = last.copy(text = combined)
            _messages.value = updated
        } else {
            addRafelVoiceMessage(cleanChunk)
        }
    }

    /**
     * Replaces or creates the active RAFEL_VOICE bubble with the full accumulated transcript
     * from Gemini Live outputAudioTranscription so word-level deltas never split words or duplicate text.
     * Set persistToDb = true on turnComplete to save the finalized spoken sentence to Room history.
     */
    fun updateRafelVoiceTranscript(fullTurnText: String, persistToDb: Boolean = false) {
        val clean = PersonaSettings.deduplicateSentencesInTurn(
            VoiceBehaviorSettings.sanitizeForNaturalDelivery(fullTurnText)
        ).trim()
        if (clean.isBlank()) return

        val current = _messages.value
        val last = current.lastOrNull()
        val now = System.currentTimeMillis()
        if (!forceNewAssistantVoiceBubble && last != null && last.type == UnifiedMessageType.RAFEL_VOICE) {
            val updated = current.toMutableList()
            updated[updated.lastIndex] = last.copy(text = clean)
            _messages.value = updated
            if (persistToDb) {
                persistTurn("assistant", clean, "RAFEL_VOICE", now)
            }
        } else {
            forceNewAssistantVoiceBubble = false
            val msg = UnifiedMessage(
                id = UUID.randomUUID().toString(),
                type = UnifiedMessageType.RAFEL_VOICE,
                text = clean,
                timestamp = now
            )
            appendMessage(msg)
            if (persistToDb) {
                persistTurn("assistant", clean, "RAFEL_VOICE", now)
            }
        }
    }

    fun addUserTextMessage(text: String): UnifiedMessage {
        val clean = text.trim()
        memoryManager.observeUserUtterance(clean)
        val msg = UnifiedMessage(
            id = UUID.randomUUID().toString(),
            type = UnifiedMessageType.USER_TEXT,
            text = clean,
            timestamp = System.currentTimeMillis()
        )
        appendMessage(msg)
        persistTurn("user", clean, "USER_TEXT", msg.timestamp)
        return msg
    }

    fun addRafelTextMessage(text: String): UnifiedMessage {
        val rawClean = text.trim()
        val historySnapshot = _messages.value
        val lastUserPrompt = historySnapshot.lastOrNull { it.isUser }?.text.orEmpty()
        val priorHistory = if (historySnapshot.lastOrNull()?.isUser == true) {
            historySnapshot.dropLast(1)
        } else {
            historySnapshot
        }
        val recentAssistant = historySnapshot.filter { !it.isUser }.takeLast(4).map { it.text }
        val clean = PersonaSettings.postProcessResponse(
            rawResponse = VoiceBehaviorSettings.sanitizeForNaturalDelivery(rawClean),
            userPrompt = lastUserPrompt,
            recentAssistantMessages = recentAssistant,
            recentHistory = priorHistory
        ).ifBlank { rawClean }
        val msg = UnifiedMessage(
            id = UUID.randomUUID().toString(),
            type = UnifiedMessageType.RAFEL_TEXT,
            text = clean,
            timestamp = System.currentTimeMillis()
        )
        appendMessage(msg)
        persistTurn("assistant", clean, "RAFEL_TEXT", msg.timestamp)
        return msg
    }

    /**
     * Complete lifecycle processor for a typed user chat message.
     * Evaluates intents, executes tools through the authoritative ToolSafetyGate,
     * or queries Gemini with full shared conversation context.
     */
    fun processUserTextMessage(
        text: String,
        apiKey: String,
        toolEngine: ToolExecutionEngine?
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return

        addUserTextMessage(clean)
        _isThinking.value = true

        scope.launch {
            try {
                // 1. Identify intent
                val intentResult = AssistantIntentRouter.classifyIntent(clean)
                RafelLogger.logGeminiResponse("Chat typed intent classified: ${intentResult.intent} (explicitAction=${intentResult.explicitAction})")

                // 2. If it's a device/phone tool action
                if (toolEngine != null && isActionIntent(intentResult.intent)) {
                    val handled = handleChatToolAction(clean, intentResult, toolEngine)
                    if (handled) {
                        _isThinking.value = false
                        return@launch
                    }
                }

                // 3. Normal conversation query: Let the LLM drive with full context, persona, and memories
                val manager = aiModelManager
                val effectiveKey = apiKey.ifBlank { manager.resolveEffectiveApiKey() }
                val history = _messages.value.dropLast(1) // exclude the just-added message
                val transition = NaturalConversationEngine.detectTopicTransition(clean, history)
                val recentTopic = if (transition == TopicTransition.EXPLICIT_TOPIC_SHIFT || transition == TopicTransition.NEW_TOPIC) {
                    ""
                } else {
                    history.takeLast(2).joinToString(" ") { it.text }
                }
                val relevantMemPrompt = memoryManager.formatRelevantMemoryPrompt(clean, recentTopic)
                val textClient = resolveTextClient()
                val streamingMsgId = UUID.randomUUID().toString()
                val streamBuffer = StringBuilder()
                var streamingBubbleCreated = false

                val responseResult = if (geminiTextClientOverride == null &&
                    manager.cloudInferenceEnabled.value &&
                    manager.healthMonitor.isOnline.value &&
                    (effectiveKey.isNotBlank() || com.example.ai.security.GeminiSecureGateway.isBackendProxyOperational())
                ) {
                    val sseResult = textClient.generateStreamingResponseWithFailover(
                        apiKey = effectiveKey,
                        recentHistory = history,
                        newPrompt = clean,
                        relevantMemorySummary = relevantMemPrompt,
                        onPartialChunk = { delta ->
                            if (delta.isNotEmpty()) {
                                streamBuffer.append(delta)
                                val currentDraft = VoiceBehaviorSettings.sanitizeForNaturalDelivery(streamBuffer.toString())
                                if (currentDraft.isNotBlank()) {
                                    if (!streamingBubbleCreated) {
                                        streamingBubbleCreated = true
                                        _isThinking.value = false
                                        appendMessage(
                                            UnifiedMessage(
                                                id = streamingMsgId,
                                                type = UnifiedMessageType.RAFEL_TEXT,
                                                text = currentDraft,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                    } else {
                                        updateMessageTextById(streamingMsgId, currentDraft)
                                    }
                                }
                            }
                        }
                    )
                    if (sseResult.isSuccess || streamingBubbleCreated) {
                        sseResult
                    } else {
                        // Graceful non-streaming fallback if SSE stream fails before first token
                        textClient.generateResponse(
                            apiKey = effectiveKey,
                            recentHistory = history,
                            newPrompt = clean,
                            relevantMemorySummary = relevantMemPrompt
                        )
                    }
                } else {
                    textClient.generateResponse(
                        apiKey = effectiveKey,
                        recentHistory = history,
                        newPrompt = clean,
                        relevantMemorySummary = relevantMemPrompt
                    )
                }

                if (responseResult.isSuccess) {
                    val rawReply = responseResult.getOrNull().orEmpty()
                    if (streamingBubbleCreated) {
                        val recentAssistant = history.filter { !it.isUser }.takeLast(4).map { it.text }
                        val finalClean = PersonaSettings.postProcessResponse(
                            rawResponse = VoiceBehaviorSettings.sanitizeForNaturalDelivery(rawReply),
                            userPrompt = clean,
                            recentAssistantMessages = recentAssistant,
                            recentHistory = history
                        ).ifBlank { rawReply.trim() }
                        updateMessageTextById(streamingMsgId, finalClean)
                        persistTurn("assistant", finalClean, "RAFEL_TEXT", System.currentTimeMillis())
                    } else {
                        addRafelTextMessage(rawReply)
                    }
                } else if (streamingBubbleCreated && streamBuffer.isNotBlank()) {
                    val partialClean = VoiceBehaviorSettings.sanitizeForNaturalDelivery(streamBuffer.toString())
                    updateMessageTextById(streamingMsgId, partialClean)
                    persistTurn("assistant", partialClean, "RAFEL_TEXT", System.currentTimeMillis())
                } else {
                    val fallbackReason = when {
                        !manager.cloudInferenceEnabled.value -> "Cloud inference explicitly disabled"
                        !manager.healthMonitor.isOnline.value -> "Device offline"
                        effectiveKey.isBlank() -> "Gemini API key not configured"
                        else -> responseResult.exceptionOrNull()?.message ?: "Cloud REST inference failed"
                    }
                    manager.recordLocalFallbackResponse(reason = fallbackReason, explicitApiKey = effectiveKey)
                    val recentAssistantTexts = history.filter { !it.isUser }.takeLast(6).map { it.text }
                    val relevantMap = memoryManager.getRelevantMemories(clean, recentTopic)
                    val contextualFallback = NaturalConversationEngine.synthesizeContextualReply(
                        userMessage = clean,
                        recentHistory = history,
                        recentAssistantTexts = recentAssistantTexts,
                        relevantMemories = relevantMap
                    )
                    addRafelTextMessage(contextualFallback)
                }
            } catch (e: Exception) {
                RafelLogger.logError("Error processing chat text message", e)
                aiModelManager.recordLocalFallbackResponse(
                    reason = e.message ?: "Exception during chat processing",
                    explicitApiKey = apiKey
                )
                val history = _messages.value.dropLast(1)
                val fallback = NaturalConversationEngine.synthesizeContextualReply(
                    userMessage = clean,
                    recentHistory = history,
                    recentAssistantTexts = history.filter { !it.isUser }.takeLast(6).map { it.text },
                    relevantMemories = memoryManager.getRelevantMemories(clean)
                )
                addRafelTextMessage(fallback)
            } finally {
                _isThinking.value = false
            }
        }
    }

    /**
     * Generates a contextual, human-like response for a user turn across the unified
     * conversation pipeline (records user message, extracts memories, queries cloud REST
     * if apiKey is active or falls back to the local natural conversation engine, sanitizes,
     * and persists the assistant reply).
     */
    suspend fun generateNaturalReply(
        userText: String,
        apiKey: String = ""
    ): String {
        val clean = userText.trim()
        if (clean.isBlank()) return ""

        addUserTextMessage(clean)
        val manager = aiModelManager
        val effectiveKey = apiKey.ifBlank { manager.resolveEffectiveApiKey() }
        val history = _messages.value.dropLast(1)
        val transition = NaturalConversationEngine.detectTopicTransition(clean, history)
        val recentTopic = if (transition == TopicTransition.EXPLICIT_TOPIC_SHIFT || transition == TopicTransition.NEW_TOPIC) {
            ""
        } else {
            history.takeLast(2).joinToString(" ") { it.text }
        }
        val relevantMemPrompt = memoryManager.formatRelevantMemoryPrompt(clean, recentTopic)
        val recentAssistantTexts = history.filter { !it.isUser }.takeLast(6).map { it.text }
        val relevantMap = memoryManager.getRelevantMemories(clean, recentTopic)

        val rawReply = if (effectiveKey.isNotBlank() && manager.healthMonitor.isOnline.value && manager.cloudInferenceEnabled.value) {
            val res = resolveTextClient().generateResponse(
                apiKey = effectiveKey,
                recentHistory = history,
                newPrompt = clean,
                relevantMemorySummary = relevantMemPrompt
            )
            val cloudText = res.getOrNull()
            if (!cloudText.isNullOrBlank()) {
                cloudText
            } else {
                val reason = res.exceptionOrNull()?.message ?: "Cloud REST inference failed"
                manager.recordLocalFallbackResponse(reason = reason, explicitApiKey = effectiveKey)
                NaturalConversationEngine.synthesizeContextualReply(
                    userMessage = clean,
                    recentHistory = history,
                    recentAssistantTexts = recentAssistantTexts,
                    relevantMemories = relevantMap
                )
            }
        } else {
            val reason = when {
                !manager.cloudInferenceEnabled.value -> "Cloud inference explicitly disabled"
                !manager.healthMonitor.isOnline.value -> "Device offline"
                else -> "Gemini API key not configured"
            }
            manager.recordLocalFallbackResponse(reason = reason, explicitApiKey = effectiveKey)
            NaturalConversationEngine.synthesizeContextualReply(
                userMessage = clean,
                recentHistory = history,
                recentAssistantTexts = recentAssistantTexts,
                relevantMemories = relevantMap
            )
        }

        val addedMsg = addRafelTextMessage(rawReply)
        return addedMsg.text
    }

    /**
     * Generates a proactive voice turn when Rafel initiates or continues a conversation
     * and the Live WebSocket is unavailable or operating in REST/offline fallback mode.
     * Uses real Gemini REST inference when online with a valid key, and records an honest
     * LOCAL_FALLBACK diagnostic state when offline or cloud inference is unavailable.
     */
    suspend fun generateProactiveTurnReply(
        proactivePrompt: String,
        suggestedOpeningLine: String,
        apiKey: String = ""
    ): String {
        val manager = aiModelManager
        val effectiveKey = apiKey.ifBlank { manager.resolveEffectiveApiKey() }
        val history = _messages.value
        val lastUserText = history.lastOrNull { it.isUser }?.text.orEmpty()
        val recentTopic = history.takeLast(3).joinToString(" ") { it.text }
        val relevantMemPrompt = memoryManager.formatRelevantMemoryPrompt(lastUserText, recentTopic)

        val rawReply = if (effectiveKey.isNotBlank() && manager.healthMonitor.isOnline.value && manager.cloudInferenceEnabled.value) {
            val res = resolveTextClient().generateResponse(
                apiKey = effectiveKey,
                recentHistory = history,
                newPrompt = proactivePrompt,
                relevantMemorySummary = relevantMemPrompt
            )
            val cloudText = res.getOrNull()
            if (!cloudText.isNullOrBlank()) {
                cloudText
            } else {
                val reason = res.exceptionOrNull()?.message ?: "Cloud REST inference failed for proactive turn"
                manager.recordLocalFallbackResponse(reason = reason, explicitApiKey = effectiveKey)
                suggestedOpeningLine
            }
        } else {
            val reason = when {
                !manager.cloudInferenceEnabled.value -> "Cloud inference explicitly disabled"
                !manager.healthMonitor.isOnline.value -> "Device offline"
                else -> "Gemini API key not configured"
            }
            manager.recordLocalFallbackResponse(reason = reason, explicitApiKey = effectiveKey)
            suggestedOpeningLine
        }

        startNewAssistantVoiceTurn()
        val addedMsg = addRafelVoiceMessage(rawReply)
        return addedMsg.text
    }

    private suspend fun handleChatToolAction(
        transcript: String,
        intentResult: SemanticIntentResult,
        toolEngine: ToolExecutionEngine
    ): Boolean {
        val (toolName, toolArgs) = mapIntentToTool(intentResult) ?: return false

        val turnContext = UserTurnContext(
            userTurnId = "text_${System.currentTimeMillis()}",
            transcript = transcript,
            toolCallId = "call_text_${System.currentTimeMillis()}"
        )

        val safetyDecision = ToolSafetyGate.evaluate(
            toolName = toolName,
            args = toolArgs,
            turnContext = turnContext
        )

        return if (safetyDecision.allowed) {
            val execResult = toolEngine.execute(toolName, toolArgs, turnContext)
            val friendlyResponse = formatToolConfirmation(intentResult.intent, intentResult.target, execResult)
            addRafelTextMessage(friendlyResponse)
            true
        } else {
            val reason = if (safetyDecision.reason.isNotBlank()) {
                safetyDecision.reason
            } else {
                "Iski security permission required hai."
            }
            addRafelTextMessage(reason)
            true
        }
    }

    private fun isActionIntent(intent: AssistantIntent): Boolean {
        return when (intent) {
            AssistantIntent.OPEN_APP,
            AssistantIntent.CALL_CONTACT,
            AssistantIntent.SEND_WHATSAPP,
            AssistantIntent.SEND_SMS,
            AssistantIntent.COMPOSE_MESSAGE,
            AssistantIntent.READ_MESSAGES,
            AssistantIntent.EXPLAIN_MESSAGE,
            AssistantIntent.CLEAR_MESSAGE_CACHE,
            AssistantIntent.SEND_EMAIL,
            AssistantIntent.SEARCH_YOUTUBE,
            AssistantIntent.DEVICE_CONTROL,
            AssistantIntent.DESCRIBE_SCREEN,
            AssistantIntent.STOP_SCREEN_SHARE,
            AssistantIntent.CONFIRM_ACTION,
            AssistantIntent.CANCEL_ACTION -> true
            else -> false
        }
    }

    private fun mapIntentToTool(intentResult: SemanticIntentResult): Pair<String, JsonObject>? {
        return when (intentResult.intent) {
            AssistantIntent.OPEN_APP -> {
                val targetApp = intentResult.target ?: "Settings"
                Pair("openApp", buildJsonObject {
                    put("appName", targetApp)
                    put("packageName", targetApp)
                })
            }
            AssistantIntent.CALL_CONTACT -> {
                val contact = intentResult.target ?: return null
                Pair("searchAndCallContact", buildJsonObject {
                    put("contactName", contact)
                    put("useDialer", false)
                })
            }
            AssistantIntent.SEND_WHATSAPP -> {
                val contact = intentResult.target ?: return null
                val msg = intentResult.secondaryData ?: ""
                Pair("sendWhatsAppMessage", buildJsonObject {
                    put("contactName", contact)
                    put("message", msg)
                })
            }
            AssistantIntent.SEND_SMS -> {
                val contact = intentResult.target ?: return null
                val msg = intentResult.secondaryData ?: ""
                Pair("sendSms", buildJsonObject {
                    put("contactName", contact)
                    put("message", msg)
                    put("confirmed", false)
                })
            }
            AssistantIntent.COMPOSE_MESSAGE -> {
                val contact = intentResult.target ?: return null
                val parts = (intentResult.secondaryData ?: "WhatsApp:::").split(":::", limit = 2)
                val platform = parts.getOrNull(0)?.ifBlank { "WhatsApp" } ?: "WhatsApp"
                val msg = parts.getOrNull(1) ?: ""
                Pair("composeMessage", buildJsonObject {
                    put("platform", platform)
                    put("recipient", contact)
                    put("message", msg)
                })
            }
            AssistantIntent.READ_MESSAGES -> {
                val platform = intentResult.target
                Pair("readRecentMessages", buildJsonObject {
                    if (!platform.isNullOrBlank()) {
                        put("platform", platform)
                    }
                    put("limit", 3)
                })
            }
            AssistantIntent.EXPLAIN_MESSAGE -> {
                Pair("explainMessage", buildJsonObject {})
            }
            AssistantIntent.CLEAR_MESSAGE_CACHE -> {
                Pair("clearMessageCache", buildJsonObject {})
            }
            AssistantIntent.SEND_EMAIL -> {
                val recipient = intentResult.target ?: return null
                val body = intentResult.secondaryData ?: ""
                Pair("sendGmail", buildJsonObject {
                    put("recipientEmail", recipient)
                    put("subject", "Message from Rafel")
                    put("body", body)
                })
            }
            AssistantIntent.SEARCH_YOUTUBE -> {
                val query = intentResult.target ?: return null
                Pair("searchYouTube", buildJsonObject { put("query", query) })
            }
            AssistantIntent.DEVICE_CONTROL -> {
                when (intentResult.target) {
                    "torch_on" -> Pair("toggleTorch", buildJsonObject { put("state", "on") })
                    "torch_off" -> Pair("toggleTorch", buildJsonObject { put("state", "off") })
                    "volume" -> Pair("adjustVolume", buildJsonObject { put("direction", "up") })
                    "location" -> Pair("getCurrentLocation", buildJsonObject {})
                    else -> null
                }
            }
            AssistantIntent.DESCRIBE_SCREEN -> {
                Pair("describeScreen", buildJsonObject {})
            }
            AssistantIntent.STOP_SCREEN_SHARE -> {
                Pair("stopScreenShare", buildJsonObject {})
            }
            AssistantIntent.CONFIRM_ACTION -> {
                Pair("confirmPendingAction", buildJsonObject {})
            }
            AssistantIntent.CANCEL_ACTION -> {
                Pair("cancelPendingAction", buildJsonObject {})
            }
            else -> null
        }
    }

    private fun formatToolConfirmation(intent: AssistantIntent, target: String?, execResult: String): String {
        return when (intent) {
            AssistantIntent.OPEN_APP -> "Okay, ${target ?: "app"} open kar rahi hoon."
            AssistantIntent.CALL_CONTACT -> "${target ?: "Contact"} ko call mila rahi hoon."
            AssistantIntent.SEND_WHATSAPP -> "WhatsApp mein message taiyar hai. Check karke Send dabao."
            AssistantIntent.SEND_SMS -> "SMS draft ready kar diya hai."
            AssistantIntent.COMPOSE_MESSAGE -> execResult.removePrefix("SUCCESS: ").removePrefix("LIMITATION: ")
            AssistantIntent.READ_MESSAGES,
            AssistantIntent.EXPLAIN_MESSAGE,
            AssistantIntent.CLEAR_MESSAGE_CACHE,
            AssistantIntent.DEVICE_CONTROL,
            AssistantIntent.DESCRIBE_SCREEN,
            AssistantIntent.STOP_SCREEN_SHARE,
            AssistantIntent.CONFIRM_ACTION,
            AssistantIntent.CANCEL_ACTION -> execResult
            AssistantIntent.SEND_EMAIL -> "Email draft open kar diya."
            AssistantIntent.SEARCH_YOUTUBE -> "YouTube par search kar rahi hoon."
            else -> "Ho gaya!"
        }
    }

    /**
     * Formats recent conversation turns, relevant memories, and turn style guidance
     * to inject into Gemini Live setup instructions and prompt continuation.
     */
    fun formatPromptContext(limit: Int = 10): String {
        val recent = _messages.value.takeLast(limit)
        val latestUserMsg = recent.lastOrNull { it.isUser }?.text.orEmpty()
        val priorHistory = if (recent.lastOrNull()?.isUser == true) recent.dropLast(1) else recent
        val transition = NaturalConversationEngine.detectTopicTransition(latestUserMsg, priorHistory)
        val recentTopicText = if (transition == TopicTransition.EXPLICIT_TOPIC_SHIFT || transition == TopicTransition.NEW_TOPIC) {
            ""
        } else {
            priorHistory.takeLast(2).joinToString(" ") { it.text }
        }
        val relevantMemories = memoryManager.formatRelevantMemoryPrompt(latestUserMsg, recentTopicText)

        if (recent.isEmpty() && relevantMemories.isBlank()) return ""

        val sb = StringBuilder()
        if (recent.isNotEmpty()) {
            sb.append("PAST CONVERSATION HISTORY (CHRONOLOGICAL ORDER — OLDEST TO NEWEST):\n")
            recent.forEach { msg ->
                val speaker = if (msg.isUser) "User" else "Rafel"
                sb.append("- ").append(speaker).append(": \"").append(msg.text).append("\"\n")
            }
        }
        if (relevantMemories.isNotBlank()) {
            sb.append("\nRELEVANT SAVED USER MEMORIES (use ONLY when naturally relevant to current topic):\n")
            sb.append("- ").append(relevantMemories).append("\n")
        }
        if (latestUserMsg.isNotBlank()) {
            sb.append("\n").append(
                PersonaSettings.buildDynamicTurnGuidance(
                    userMessage = latestUserMsg,
                    recentMessages = priorHistory,
                    relevantMemorySummary = relevantMemories
                )
            ).append("\n")
        }
        sb.append("\nIMPORTANT CONTINUITY & RELEVANCE RULES:\n")
        sb.append("1. Before replying, understand the exact meaning and intent of the latest user message.\n")
        sb.append("2. Respond directly to what the user just said. Never insert unrelated food items, memories, or generic advice.\n")
        sb.append("3. Understand short replies ('hmm', 'achha', 'haan', 'nahi', 'pata nahi', 'kuch nahi') in the context of the immediate previous turn.\n")
        sb.append("4. When the user shifts topics, pivot immediately without dragging in old topics.\n")
        sb.append("5. If the user's meaning or reference is unclear, ask a natural clarification instead of guessing.")
        return sb.toString()
    }

    /**
     * Lightweight chronological conversation + user-memory summary for Gemini Live voice setup.
     * Keeps chat-to-voice continuity intact without injecting stale single-turn guidance or heavy rule walls
     * that can make native audio models sound stiff or scripted.
     */
    fun formatVoiceHistoryContext(limit: Int = 10): String {
        val recent = _messages.value.takeLast(limit)
        val latestUserMsg = recent.lastOrNull { it.isUser }?.text.orEmpty()
        val recentTopicText = recent.takeLast(3).joinToString(" ") { it.text }
        val relevantMemories = memoryManager.formatRelevantMemoryPrompt(latestUserMsg, recentTopicText)

        if (recent.isEmpty() && relevantMemories.isBlank()) return ""

        val sb = StringBuilder()
        if (recent.isNotEmpty()) {
            sb.append("RECENT CHAT & VOICE HISTORY (remember naturally if referenced):\n")
            recent.forEach { msg ->
                val speaker = if (msg.isUser) "User" else "Rafel"
                sb.append("$speaker: ${msg.text}\n")
            }
        }
        if (relevantMemories.isNotBlank()) {
            sb.append("KNOWN ABOUT USER (bring up only when it fits naturally): ")
                .append(relevantMemories.take(300))
        }
        return sb.toString().trim()
    }

    fun getDebugHistoryRedacted(limit: Int = 10): List<String> {
        val recent = _messages.value.takeLast(limit)
        return recent.map { msg ->
            val speaker = if (msg.isUser) "USER" else "RAFEL"
            val redactedText = msg.text
                .replace(Regex("\\b[6-9]\\d{9}\\b"), "[PHONE_REDACTED]")
                .replace(Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), "[EMAIL_REDACTED]")
            "$speaker: \"$redactedText\""
        }
    }

    fun clearAllHistory() {
        _messages.value = emptyList()
        memoryManager.clearAllMemories()
        scope.launch {
            try {
                memoryDao.clearAllTurns()
            } catch (e: Exception) {
                RafelLogger.logError("Failed to clear memory database", e)
            }
        }
    }

    private fun appendMessage(msg: UnifiedMessage) {
        val current = _messages.value
        val updated = if (current.size >= 80) {
            current.drop(current.size - 79) + msg
        } else {
            current + msg
        }
        _messages.value = updated
    }

    private fun updateMessageTextById(id: String, newText: String) {
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = updated[idx].copy(text = newText)
            _messages.value = updated
        }
    }

    private fun persistTurn(role: String, text: String, messageType: String, timestamp: Long) {
        scope.launch {
            try {
                memoryDao.insertTurn(
                    ConversationTurnEntity(
                        role = role,
                        text = text,
                        messageType = messageType,
                        timestamp = timestamp
                    )
                )
            } catch (e: Exception) {
                RafelLogger.logError("Failed to persist conversation turn", e)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: RafelConversationManager? = null

        fun getInstance(context: Context): RafelConversationManager {
            return INSTANCE ?: synchronized(this) {
                val inst = RafelConversationManager(context.applicationContext)
                INSTANCE = inst
                inst
            }
        }
    }
}
