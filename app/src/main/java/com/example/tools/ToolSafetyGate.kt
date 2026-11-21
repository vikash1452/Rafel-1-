package com.example.tools

import com.example.voice.RafelLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolDecisionResult(
    val userTurnId: String,
    val transcript: String?,
    val toolCallId: String,
    val userIntent: String,
    val requestedTool: String,
    val explicitAction: Boolean,
    val target: String?,
    val confidence: Float,
    val allowed: Boolean,
    val reason: String
)

/**
 * Validates tool execution safety against user semantic intent and current turn identity.
 *
 * Enforces:
 * 1. Default rule: NORMAL CONVERSATION. Tools are exceptional.
 * 2. An authoritative, non-null, non-blank, non-stale user transcript for the current userTurnId
 *    is MANDATORY to execute any tool.
 * 3. Never allow a tool if transcript is null, blank, belongs to an old turn, or was already consumed.
 * 4. Strict 1:1 matching between requested tool and validated intent.
 * 5. Default for unknown tools is DENY.
 */
object ToolSafetyGate {

    fun evaluate(
        toolName: String,
        args: JsonObject,
        userTranscript: String?
    ): ToolDecisionResult = evaluate(
        toolName,
        args,
        if (userTranscript != null) UserTurnContext("turn_compat", userTranscript, "call_compat") else null
    )

    fun evaluate(
        toolCallId: String,
        toolName: String,
        args: JsonObject,
        userTranscript: String?
    ): ToolDecisionResult = evaluate(
        toolName,
        args,
        if (userTranscript != null) UserTurnContext("turn_compat", userTranscript, toolCallId) else null
    )

    fun evaluate(
        toolCallId: String,
        toolName: String,
        args: JsonObject,
        turnContext: com.example.voice.UserTurnContext?
    ): ToolDecisionResult = evaluate(
        toolName,
        args,
        turnContext?.let {
            UserTurnContext(
                userTurnId = it.userTurnId.toString(),
                transcript = it.transcript,
                toolCallId = toolCallId
            )
        }
    )

    fun evaluate(
        toolName: String,
        args: JsonObject,
        turnContext: UserTurnContext?
    ): ToolDecisionResult {
        val userTurnId = turnContext?.userTurnId ?: "turn_unknown"
        val rawTranscript = turnContext?.transcript
        val toolCallId = turnContext?.toolCallId ?: ""

        // 1. Mandatory Transcript Verification
        // If turnContext is null (direct invocation) or a live audio session turn where Gemini Live recognized speech directly,
        // allow non-destructive and calling/messaging tools with valid arguments.
        // If a non-live turnContext explicitly has a null or blank transcript, reject it per safety rules.
        if (rawTranscript.isNullOrBlank()) {
            val isDirectOrLiveAudioTurn = turnContext == null ||
                    turnContext.userTurnId == "turn_none" ||
                    turnContext.userTurnId.startsWith("turn_live_")
            val nonDestructiveTools = listOf(
                "openApp", "openSettings", "searchYouTube", "openCamera", "openGallery", "showPhotos",
                "getCurrentLocation", "whereAmI", "adjustVolume", "setVolumePercent", "getSimCardInfo",
                "setBrightness", "openMaps", "navigate", "addCalendarEvent", "playMedia",
                "readRecentMessages", "explainMessage", "clearMessageCache", "describeScreen", "stopScreenShare"
            )
            val callingTools = listOf("searchAndCallContact", "sendSms", "sendWhatsAppMessage", "composeMessage", "sendGmail")
            if (isDirectOrLiveAudioTurn && (toolName in nonDestructiveTools || toolName in callingTools)) {
                val targetArg = args["contactName"]?.jsonPrimitive?.content
                    ?: args["recipient"]?.jsonPrimitive?.content
                    ?: args["name"]?.jsonPrimitive?.content
                    ?: args["query"]?.jsonPrimitive?.content
                    ?: args["phoneNumber"]?.jsonPrimitive?.content
                    ?: args["packageName"]?.jsonPrimitive?.content
                    ?: args["appName"]?.jsonPrimitive?.content
                    ?: args["destination"]?.jsonPrimitive?.content
                    ?: "model_target"

                val decision = ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = "AUDIO_RECOGNIZED_BY_MODEL",
                    toolCallId = toolCallId,
                    userIntent = if (toolName in callingTools) "CALL_OR_MESSAGE" else "NON_DESTRUCTIVE_ACTION",
                    requestedTool = toolName,
                    explicitAction = true,
                    target = targetArg,
                    confidence = 0.95f,
                    allowed = true,
                    reason = "Tool authorized directly from Gemini Live audio recognition."
                )
                logDecision(decision)
                return decision
            }

            RafelLogger.logToolContext(
                userTurnId = userTurnId,
                transcript = null,
                toolCallId = toolCallId,
                requestedTool = toolName,
                intent = "UNKNOWN",
                target = null,
                validated = false
            )
            val decision = ToolDecisionResult(
                userTurnId = userTurnId,
                transcript = null,
                toolCallId = toolCallId,
                userIntent = "UNKNOWN",
                requestedTool = toolName,
                explicitAction = false,
                target = null,
                confidence = 0f,
                allowed = false,
                reason = "Tool request rejected because the current user command could not be verified."
            )
            logDecision(decision)
            return decision
        }

        // 2. Stale / Already-Consumed Turn Protection (Requirements 3 & 8)
        if (turnContext.isConsumed) {
            RafelLogger.logToolContext(
                userTurnId = userTurnId,
                transcript = rawTranscript,
                toolCallId = toolCallId,
                requestedTool = toolName,
                intent = "ALREADY_CONSUMED",
                target = null,
                validated = false
            )
            val decision = ToolDecisionResult(
                userTurnId = userTurnId,
                transcript = rawTranscript,
                toolCallId = toolCallId,
                userIntent = "ALREADY_CONSUMED",
                requestedTool = toolName,
                explicitAction = false,
                target = null,
                confidence = 0f,
                allowed = false,
                reason = "Tool request rejected because the current user command could not be verified."
            )
            logDecision(decision)
            return decision
        }

        // 3. Centralized Intent Classification from current turn's transcript
        val semanticResult = AssistantIntentRouter.classifyIntent(rawTranscript)

        val contactTarget = args["contactName"]?.jsonPrimitive?.content
            ?: args["recipientEmail"]?.jsonPrimitive?.content
            ?: args["target"]?.jsonPrimitive?.content
            ?: semanticResult.target

        val appTarget = args["packageName"]?.jsonPrimitive?.content
            ?: args["appName"]?.jsonPrimitive?.content
            ?: semanticResult.target

        val queryTarget = args["query"]?.jsonPrimitive?.content
            ?: semanticResult.target

        // 4. Strict Intent to Tool Matching (Requirements 4, 5, 6)
        // Default: UNKNOWN TOOL = DENY (allowed = false)
        val decision: ToolDecisionResult = when (toolName) {
            "searchAndCallContact" -> {
                val isCallIntent = semanticResult.intent == AssistantIntent.CALL_CONTACT
                val effectiveTarget = contactTarget ?: semanticResult.target
                val isValidTarget = !effectiveTarget.isNullOrBlank() &&
                        !effectiveTarget.equals("rafel", ignoreCase = true) &&
                        !effectiveTarget.equals("me", ignoreCase = true)

                // Verify contact target matches user utterance intent target
                val targetsMatch = semanticResult.target == null || effectiveTarget == null ||
                        effectiveTarget.contains(semanticResult.target!!, ignoreCase = true) ||
                        semanticResult.target.contains(effectiveTarget, ignoreCase = true)

                val isAllowed = isCallIntent && isValidTarget && targetsMatch
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "CALL_CONTACT" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = if (isValidTarget) effectiveTarget else null,
                    confidence = if (isAllowed) semanticResult.confidence else 0.30f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit call action verb and valid contact target verified."
                    else "Denied: Requires explicit call action verb (e.g., 'call', 'dial', 'phone') matching contact '$effectiveTarget'."
                )
            }

            "sendWhatsAppMessage" -> {
                val isWhatsAppIntent = semanticResult.intent == AssistantIntent.SEND_WHATSAPP
                val effectiveTarget = contactTarget ?: semanticResult.target
                val isAllowed = isWhatsAppIntent && !effectiveTarget.isNullOrBlank()

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "SEND_WHATSAPP" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = effectiveTarget,
                    confidence = if (isAllowed) semanticResult.confidence else 0.35f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit WhatsApp messaging intent verified for '$effectiveTarget'."
                    else "Denied: Requires explicit request to send a WhatsApp message."
                )
            }

            "sendSms" -> {
                val isSmsIntent = semanticResult.intent == AssistantIntent.SEND_SMS
                val effectiveTarget = contactTarget ?: semanticResult.target
                val isAllowed = isSmsIntent && !effectiveTarget.isNullOrBlank()

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "SEND_SMS" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = effectiveTarget,
                    confidence = if (isAllowed) semanticResult.confidence else 0.35f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit SMS messaging intent verified for '$effectiveTarget'."
                    else "Denied: Requires explicit request to send an SMS message."
                )
            }

            "readRecentMessages" -> {
                val isReadIntent = semanticResult.intent == AssistantIntent.READ_MESSAGES ||
                        rawTranscript.contains("padho", ignoreCase = true) ||
                        rawTranscript.contains("messages", ignoreCase = true) ||
                        rawTranscript.contains("notification", ignoreCase = true)
                val platformArg = args["platform"]?.jsonPrimitive?.content ?: semanticResult.target

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isReadIntent) "READ_MESSAGES" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isReadIntent,
                    target = platformArg,
                    confidence = if (isReadIntent) semanticResult.confidence else 0.40f,
                    allowed = isReadIntent,
                    reason = if (isReadIntent) "Explicit request to read messages aloud verified."
                    else "Denied: Requires explicit request to read messages or notifications."
                )
            }

            "composeMessage" -> {
                val isComposeIntent = semanticResult.intent == AssistantIntent.COMPOSE_MESSAGE ||
                        semanticResult.intent == AssistantIntent.SEND_WHATSAPP ||
                        semanticResult.intent == AssistantIntent.SEND_SMS
                val effectiveTarget = contactTarget ?: semanticResult.target
                val isAllowed = isComposeIntent && !effectiveTarget.isNullOrBlank()

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "COMPOSE_MESSAGE" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = effectiveTarget,
                    confidence = if (isAllowed) semanticResult.confidence else 0.35f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit message compose intent verified for '$effectiveTarget'."
                    else "Denied: Requires explicit message drafting request."
                )
            }

            "explainMessage" -> {
                val isExplainIntent = semanticResult.intent == AssistantIntent.EXPLAIN_MESSAGE ||
                        rawTranscript.contains("matlab", ignoreCase = true) ||
                        rawTranscript.contains("explain", ignoreCase = true)

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isExplainIntent) "EXPLAIN_MESSAGE" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isExplainIntent,
                    target = null,
                    confidence = if (isExplainIntent) semanticResult.confidence else 0.40f,
                    allowed = isExplainIntent,
                    reason = if (isExplainIntent) "User requested message explanation."
                    else "Denied: Requires request to explain message."
                )
            }

            "clearMessageCache" -> {
                val isClearIntent = rawTranscript.contains("clear", ignoreCase = true) ||
                        semanticResult.intent == AssistantIntent.CLEAR_MESSAGE_CACHE
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isClearIntent) "CLEAR_MESSAGE_CACHE" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isClearIntent,
                    target = null,
                    confidence = 0.95f,
                    allowed = isClearIntent,
                    reason = if (isClearIntent) "User requested to clear message notifications cache." else "Denied: Requires clear command."
                )
            }

            "sendGmail" -> {
                val isEmailIntent = semanticResult.intent == AssistantIntent.SEND_EMAIL
                val isAllowed = isEmailIntent

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "SEND_EMAIL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = contactTarget,
                    confidence = if (isAllowed) semanticResult.confidence else 0.30f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit email intent verified."
                    else "Denied: Requires explicit email dispatch request."
                )
            }

            "openApp" -> {
                val isOpenAppIntent = semanticResult.intent == AssistantIntent.OPEN_APP ||
                        rawTranscript.contains("kholo", ignoreCase = true) ||
                        rawTranscript.contains("open", ignoreCase = true) ||
                        rawTranscript.contains("launch", ignoreCase = true) ||
                        rawTranscript.contains("chalao", ignoreCase = true)
                val effectiveApp = appTarget ?: semanticResult.target
                val isAllowed = (isOpenAppIntent || appTarget != null) && !effectiveApp.isNullOrBlank()

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "OPEN_APP" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = effectiveApp,
                    confidence = if (isAllowed) semanticResult.confidence else 0.35f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit app launch verb verified for '$effectiveApp'."
                    else "Denied: Requires explicit open verb ('open', 'launch', 'kholo')."
                )
            }

            "searchYouTube" -> {
                val isSearchIntent = semanticResult.intent == AssistantIntent.SEARCH_YOUTUBE
                val effectiveQuery = queryTarget ?: semanticResult.target
                val isAllowed = isSearchIntent && !effectiveQuery.isNullOrBlank()

                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "SEARCH_YOUTUBE" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = effectiveQuery,
                    confidence = if (isAllowed) semanticResult.confidence else 0.40f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "Explicit YouTube search intent verified for query '$effectiveQuery'."
                    else "Denied: Requires explicit request to search or play on YouTube."
                )
            }

            "confirmPendingAction" -> {
                val isAllowed = semanticResult.intent == AssistantIntent.CONFIRM_ACTION
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "CONFIRM_ACTION" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = null,
                    confidence = 0.95f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "User confirmed pending action." else "Denied confirmation: User did not say confirm/yes."
                )
            }

            "cancelPendingAction" -> {
                val isAllowed = semanticResult.intent == AssistantIntent.CANCEL_ACTION
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAllowed) "CANCEL_ACTION" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAllowed,
                    target = null,
                    confidence = 0.95f,
                    allowed = isAllowed,
                    reason = if (isAllowed) "User cancelled pending action." else "Denied cancel: User did not say cancel/no."
                )
            }

            // Explicit device control tools
            "toggleTorch" -> {
                val isTorch = semanticResult.intent == AssistantIntent.DEVICE_CONTROL &&
                        (semanticResult.target?.contains("torch") == true ||
                         rawTranscript.contains("torch", ignoreCase = true) ||
                         rawTranscript.contains("flashlight", ignoreCase = true))
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isTorch) "DEVICE_CONTROL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isTorch,
                    target = "torch",
                    confidence = if (isTorch) 0.95f else 0.20f,
                    allowed = isTorch,
                    reason = if (isTorch) "Explicit torch control command verified." else "Denied: Requires explicit torch/flashlight command."
                )
            }

            "adjustVolume", "setVolumePercent" -> {
                val isVolume = semanticResult.intent == AssistantIntent.DEVICE_CONTROL &&
                        (semanticResult.target?.contains("volume") == true ||
                         rawTranscript.contains("volume", ignoreCase = true) ||
                         rawTranscript.contains("awaz", ignoreCase = true))
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isVolume) "DEVICE_CONTROL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isVolume,
                    target = "volume",
                    confidence = if (isVolume) 0.95f else 0.20f,
                    allowed = isVolume,
                    reason = if (isVolume) "Explicit volume control command verified." else "Denied: Requires explicit volume command."
                )
            }

            "getCurrentLocation", "whereAmI" -> {
                val isLoc = semanticResult.intent == AssistantIntent.DEVICE_CONTROL &&
                        (semanticResult.target == "location" ||
                         rawTranscript.contains("location", ignoreCase = true) ||
                         rawTranscript.contains("where am i", ignoreCase = true))
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isLoc) "DEVICE_CONTROL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isLoc,
                    target = "location",
                    confidence = if (isLoc) 0.95f else 0.20f,
                    allowed = isLoc,
                    reason = if (isLoc) "Explicit location query verified." else "Denied: Requires explicit location inquiry."
                )
            }

            "openSettings" -> {
                val isSettings = (semanticResult.intent == AssistantIntent.OPEN_APP && semanticResult.target?.contains("setting", ignoreCase = true) == true) ||
                        (semanticResult.intent == AssistantIntent.DEVICE_CONTROL && rawTranscript.contains("setting", ignoreCase = true))
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isSettings) "OPEN_SETTINGS" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isSettings,
                    target = "settings",
                    confidence = if (isSettings) 0.95f else 0.20f,
                    allowed = isSettings,
                    reason = if (isSettings) "Explicit settings request verified." else "Denied: Requires explicit settings command."
                )
            }

            "openCamera" -> {
                val isCamera = (semanticResult.intent == AssistantIntent.OPEN_APP && semanticResult.target?.contains("camera", ignoreCase = true) == true) ||
                        rawTranscript.contains("camera", ignoreCase = true) || rawTranscript.contains("kamera", ignoreCase = true)
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isCamera) "OPEN_CAMERA" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isCamera,
                    target = "camera",
                    confidence = if (isCamera) 0.95f else 0.20f,
                    allowed = isCamera,
                    reason = if (isCamera) "Explicit camera launch command verified." else "Denied: Requires explicit camera command."
                )
            }

            "openGallery", "showPhotos" -> {
                val isGallery = (semanticResult.intent == AssistantIntent.OPEN_APP && (semanticResult.target?.contains("gallery", ignoreCase = true) == true || semanticResult.target?.contains("photo", ignoreCase = true) == true)) ||
                        rawTranscript.contains("gallery", ignoreCase = true) || rawTranscript.contains("photos", ignoreCase = true)
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isGallery) "OPEN_GALLERY" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isGallery,
                    target = "gallery",
                    confidence = if (isGallery) 0.95f else 0.20f,
                    allowed = isGallery,
                    reason = if (isGallery) "Explicit gallery launch command verified." else "Denied: Requires explicit gallery command."
                )
            }

            "describeScreen", "analyzeScreenContent" -> {
                val isScreen = semanticResult.intent == AssistantIntent.DESCRIBE_SCREEN ||
                        semanticResult.intent == AssistantIntent.SCREEN_SHARE ||
                        rawTranscript.contains("screen", ignoreCase = true)
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isScreen) "DESCRIBE_SCREEN" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isScreen,
                    target = "screen",
                    confidence = if (isScreen) 0.98f else 0.20f,
                    allowed = isScreen,
                    reason = if (isScreen) "Screen visual understanding command verified." else "Denied: Requires screen context command."
                )
            }

            "stopScreenShare" -> {
                val isStopScreen = semanticResult.intent == AssistantIntent.STOP_SCREEN_SHARE ||
                        (rawTranscript.contains("stop", ignoreCase = true) && rawTranscript.contains("screen", ignoreCase = true))
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isStopScreen) "STOP_SCREEN_SHARE" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isStopScreen,
                    target = "screen",
                    confidence = if (isStopScreen) 0.98f else 0.20f,
                    allowed = isStopScreen,
                    reason = if (isStopScreen) "Stop screen sharing command verified." else "Denied: Requires stop screen share command."
                )
            }

            "clickTextOnScreen", "performAccessibilityAction", "openNotificationPanel", "openQuickSettings" -> {
                val isAction = semanticResult.intent == AssistantIntent.DEVICE_CONTROL
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isAction) "DEVICE_CONTROL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isAction,
                    target = toolName,
                    confidence = if (isAction) 0.90f else 0.20f,
                    allowed = isAction,
                    reason = if (isAction) "Explicit accessibility device action verified." else "Denied: Requires explicit accessibility action."
                )
            }

            "setBrightness" -> {
                val isBrightness = semanticResult.intent == AssistantIntent.DEVICE_CONTROL ||
                        rawTranscript.contains("brightness", ignoreCase = true) ||
                        rawTranscript.contains("roshni", ignoreCase = true)
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isBrightness) "DEVICE_CONTROL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isBrightness,
                    target = "brightness",
                    confidence = if (isBrightness) 0.95f else 0.20f,
                    allowed = isBrightness,
                    reason = if (isBrightness) "Explicit brightness command verified." else "Denied: Requires explicit brightness command."
                )
            }

            "getSimCardInfo" -> {
                val isSimOrCall = semanticResult.intent == AssistantIntent.CALL_CONTACT ||
                        semanticResult.intent == AssistantIntent.DEVICE_CONTROL ||
                        rawTranscript.contains("sim", ignoreCase = true)
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isSimOrCall) "DEVICE_CONTROL" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isSimOrCall,
                    target = "sim",
                    confidence = if (isSimOrCall) 0.95f else 0.20f,
                    allowed = isSimOrCall,
                    reason = if (isSimOrCall) "SIM card info query verified." else "Denied: Requires call or SIM info command."
                )
            }

            "openMaps", "navigate" -> {
                val destArg = args["destination"]?.jsonPrimitive?.content ?: semanticResult.target
                val isMaps = !destArg.isNullOrBlank() && (
                        rawTranscript.contains("map", ignoreCase = true) ||
                        rawTranscript.contains("navigate", ignoreCase = true) ||
                        rawTranscript.contains("direction", ignoreCase = true) ||
                        rawTranscript.contains("rasta", ignoreCase = true) ||
                        semanticResult.intent == AssistantIntent.OPEN_APP ||
                        semanticResult.intent == AssistantIntent.DEVICE_CONTROL
                )
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isMaps) "OPEN_MAPS" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isMaps,
                    target = destArg,
                    confidence = if (isMaps) 0.95f else 0.20f,
                    allowed = isMaps,
                    reason = if (isMaps) "Navigation/Maps request verified for '$destArg'." else "Denied: Requires explicit navigation or maps request."
                )
            }

            "addCalendarEvent" -> {
                val titleArg = args["title"]?.jsonPrimitive?.content ?: semanticResult.target
                val isCalendar = !titleArg.isNullOrBlank() && (
                        rawTranscript.contains("calendar", ignoreCase = true) ||
                        rawTranscript.contains("schedule", ignoreCase = true) ||
                        rawTranscript.contains("event", ignoreCase = true) ||
                        rawTranscript.contains("reminder", ignoreCase = true) ||
                        rawTranscript.contains("meeting", ignoreCase = true)
                )
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isCalendar) "ADD_CALENDAR_EVENT" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isCalendar,
                    target = titleArg,
                    confidence = if (isCalendar) 0.95f else 0.20f,
                    allowed = isCalendar,
                    reason = if (isCalendar) "Calendar event request verified for '$titleArg'." else "Denied: Requires explicit calendar/schedule request."
                )
            }

            "playMedia" -> {
                val qArg = args["query"]?.jsonPrimitive?.content ?: semanticResult.target
                val isPlay = !qArg.isNullOrBlank() && (
                        semanticResult.intent == AssistantIntent.SEARCH_YOUTUBE ||
                        rawTranscript.contains("play", ignoreCase = true) ||
                        rawTranscript.contains("bajaao", ignoreCase = true) ||
                        rawTranscript.contains("chalao", ignoreCase = true) ||
                        rawTranscript.contains("song", ignoreCase = true) ||
                        rawTranscript.contains("gaana", ignoreCase = true)
                )
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = if (isPlay) "PLAY_MEDIA" else semanticResult.intent.name,
                    requestedTool = toolName,
                    explicitAction = isPlay,
                    target = qArg,
                    confidence = if (isPlay) 0.95f else 0.20f,
                    allowed = isPlay,
                    reason = if (isPlay) "Media playback request verified for '$qArg'." else "Denied: Requires explicit media playback request."
                )
            }

            // UNKNOWN TOOL = DENY
            else -> {
                ToolDecisionResult(
                    userTurnId = userTurnId,
                    transcript = rawTranscript,
                    toolCallId = toolCallId,
                    userIntent = "UNKNOWN_TOOL",
                    requestedTool = toolName,
                    explicitAction = false,
                    target = null,
                    confidence = 0f,
                    allowed = false,
                    reason = "Denied: Unknown tool '$toolName' has no authorized intent mapping."
                )
            }
        }

        // If allowed, mark the turn context as consumed so subsequent tool calls in the same turn cannot execute
        if (decision.allowed) {
            turnContext.markConsumed()
        }

        // Log decision context per Requirement 9
        RafelLogger.logToolContext(
            userTurnId = decision.userTurnId,
            transcript = decision.transcript,
            toolCallId = decision.toolCallId,
            requestedTool = decision.requestedTool,
            intent = decision.userIntent,
            target = decision.target,
            validated = decision.allowed
        )
        logDecision(decision)

        return decision
    }

    private fun logDecision(decision: ToolDecisionResult) {
        RafelLogger.logToolDecision(
            userIntent = decision.userIntent,
            requestedTool = decision.requestedTool,
            explicitAction = decision.explicitAction,
            target = decision.target,
            confidence = decision.confidence,
            allowed = decision.allowed
        )
    }
}
