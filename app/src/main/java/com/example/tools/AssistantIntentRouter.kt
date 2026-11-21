package com.example.tools

import com.example.voice.RafelLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

enum class AssistantIntent {
    CONVERSATION,
    QUESTION,
    GREETING,
    CALL_CONTACT,
    SEND_WHATSAPP,
    SEND_SMS,
    COMPOSE_MESSAGE,
    READ_MESSAGES,
    EXPLAIN_MESSAGE,
    CLEAR_MESSAGE_CACHE,
    SEND_EMAIL,
    OPEN_APP,
    SEARCH_YOUTUBE,
    DEVICE_CONTROL,
    SCREEN_SHARE,
    STOP_SCREEN_SHARE,
    DESCRIBE_SCREEN,
    CONFIRM_ACTION,
    CANCEL_ACTION,
    STOP_LISTENING,
    UNKNOWN
}

data class SemanticIntentResult(
    val intent: AssistantIntent,
    val explicitAction: Boolean,
    val target: String?,
    val secondaryData: String?,
    val confidence: Float,
    val reason: String
)

/**
 * Authoritative centralized intent routing layer for Rafel.
 *
 * Pipeline:
 * MIC -> Gemini Live -> USER TRANSCRIPT / SEMANTIC INTENT -> AssistantIntentRouter -> CONVERSATION OR TOOL REQUEST -> ToolSafetyGate -> ToolExecutionEngine -> Android action
 *
 * Enforces:
 * 1. Default rule: NORMAL CONVERSATION. Tools are exceptional.
 * 2. Greetings and casual statements NEVER trigger tools.
 * 3. Strict semantic validation for Calls, WhatsApp, SMS, Screen Share, YouTube search, and App Launch.
 */
object AssistantIntentRouter {

    private val GREETING_PHRASES = listOf(
        "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
        "good night", "how are you", "how r u", "kya haal", "kaisa hai", "kaisi ho",
        "kaise ho", "are you there", "are you listening", "sun rahi ho", "sun rahe ho",
        "what's up", "whats up", "kya chal raha", "namaste", "namaskar", "shubh prabhat",
        "kuch bolo", "bolo", "tell me a joke", "what can you do", "who are you",
        "kya ho raha hai", "suno", "rafel suno"
    )

    private val CASUAL_STATEMENTS = listOf(
        "aur batao", "main theek hoon", "mujhe accha laga aapse baat karke",
        "rahul is here", "mom is here", "main rahul se mila", "rahul ko jaanta hai",
        "rahul aa gaya", "rahul kaisa hai", "main rahul ko jaanta hoon", "aaj main busy hoon",
        "phone charge ho raha hai", "battery kam hai", "kaisa laga", "achha hai",
        "theek hai", "kuch nahi", "sab theek hai", "nothing", "just chilling"
    )

    /**
     * Parses the user transcript and classifies semantic intent.
     */
    fun classifyIntent(userTranscript: String?): SemanticIntentResult {
        if (userTranscript.isNullOrBlank()) {
            return SemanticIntentResult(
                intent = AssistantIntent.CONVERSATION,
                explicitAction = false,
                target = null,
                secondaryData = null,
                confidence = 0.90f,
                reason = "Empty or null transcript defaults to normal conversation"
            )
        }

        val raw = userTranscript.trim()
        val lower = raw.lowercase(Locale.ROOT)
        val clean = lower.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

        // 1. Personal naming / memory (e.g. "call me Rahul", "mujhe Rahul bulao")
        if (clean.startsWith("call me ") || clean.contains(" call me ") ||
            clean.startsWith("call me by ") || clean.contains("mujhe ") && clean.contains("bulao") ||
            clean.startsWith("mera naam ") || clean.contains("mera naam ")
        ) {
            val name = extractNameFromCallMe(clean)
            return SemanticIntentResult(
                intent = AssistantIntent.CONVERSATION,
                explicitAction = false,
                target = name,
                secondaryData = null,
                confidence = 0.95f,
                reason = "User stated self-identity or requested to be called by name ('$clean')"
            )
        }

        // 2. Pure Greetings & Casual Queries
        if (isPureGreeting(clean)) {
            return SemanticIntentResult(
                intent = AssistantIntent.GREETING,
                explicitAction = false,
                target = null,
                secondaryData = null,
                confidence = 0.99f,
                reason = "Recognized greeting or casual inquiry"
            )
        }

        // 3. Casual statements mentioning names/status without action verb
        if (isCasualStatement(clean)) {
            return SemanticIntentResult(
                intent = AssistantIntent.CONVERSATION,
                explicitAction = false,
                target = null,
                secondaryData = null,
                confidence = 0.95f,
                reason = "Conversational statement without action verb"
            )
        }

        // 4. Stop speaking / pause listening command
        if (isStopCommand(clean)) {
            return SemanticIntentResult(
                intent = AssistantIntent.STOP_LISTENING,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.98f,
                reason = "User commanded assistant to stop speaking or pause listening"
            )
        }

        // 4B. Action Confirmation / Cancellation
        if (clean in listOf("yes", "proceed", "haan", "haan proceed karo", "confirm", "theek hai call karo", "bhej do", "sure", "okay call")) {
            return SemanticIntentResult(
                intent = AssistantIntent.CONFIRM_ACTION,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.95f,
                reason = "Confirmed pending sensitive action"
            )
        }
        if (clean in listOf("no", "cancel", "nahi", "mat karo", "stop", "ruk jao", "cancel karo", "don't call", "dont send")) {
            return SemanticIntentResult(
                intent = AssistantIntent.CANCEL_ACTION,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.95f,
                reason = "Cancelled pending action"
            )
        }

        // 5. Screen Sharing Commands
        val screenShareResult = matchScreenShare(clean, raw)
        if (screenShareResult != null) {
            return screenShareResult
        }

        // 6. YouTube Search
        val ytSearchResult = matchYouTubeSearch(clean, raw)
        if (ytSearchResult != null) {
            return ytSearchResult
        }

        // 6B. Read Messages Aloud (e.g. "Instagram par kya message aaya hai", "mere naye messages padho", "Telegram notification padho")
        val readMsgResult = matchReadMessages(clean, raw)
        if (readMsgResult != null) {
            return readMsgResult
        }

        // 6C. Explain Message Meaning
        val explainResult = matchExplainMessage(clean, raw)
        if (explainResult != null) {
            return explainResult
        }

        // 6D. Multi-app message compose (e.g. "Instagram par Rahul ko message draft karo", "Telegram par Neha ko message likho")
        val multiAppResult = matchMultiAppCompose(clean, raw)
        if (multiAppResult != null) {
            return multiAppResult
        }

        // 7. WhatsApp Messaging (e.g. "Amit ko WhatsApp message bhejo")
        val waResult = matchWhatsAppMessaging(clean, raw)
        if (waResult != null) {
            return waResult
        }

        // 8. SMS Messaging (e.g. "Mummy ko message likho: main ghar aa raha hoon")
        val smsResult = matchSmsMessaging(clean, raw)
        if (smsResult != null) {
            return smsResult
        }

        // 9. Email Dispatch
        val emailResult = matchEmail(clean, raw)
        if (emailResult != null) {
            return emailResult
        }

        // 10. Open App (Checked before CallContact so "Phone kholo" or "Settings kholo" is never misclassified as a call)
        val openAppResult = matchOpenApp(clean, raw)
        if (openAppResult != null) {
            return openAppResult
        }

        // 11. Call Contact (e.g. "Amit ko call karo", "Papa ko phone lagao", "Call Rahul")
        val callResult = matchCallContact(clean, raw)
        if (callResult != null) {
            return callResult
        }

        // 12. Hardware / Device Controls
        val deviceResult = matchDeviceControl(clean)
        if (deviceResult != null) {
            return deviceResult
        }

        // 13. Informational Questions
        val questionResult = matchQuestion(clean, raw)
        if (questionResult != null) {
            return questionResult
        }

        // Default: Natural conversation / Chit-chat / Follow-up
        return SemanticIntentResult(
            intent = AssistantIntent.CONVERSATION,
            explicitAction = false,
            target = null,
            secondaryData = null,
            confidence = 0.85f,
            reason = "No explicit tool action pattern matched; handling as normal conversation"
        )
    }

    private fun isStopCommand(clean: String): Boolean {
        val stopPhrases = listOf(
            "chup", "chup ho jao", "chup raho", "chup ho ja", "shant", "shant raho",
            "so jao", "stop", "ruk jao", "quiet", "be quiet", "stop speaking",
            "bolna band karo", "rafel chup ho jao", "rafel so jao", "rafel stop"
        )
        val stripped = clean.replace("rafel", "").trim()
        return stopPhrases.any { clean == it || stripped == it || clean.startsWith("$it ") || clean.endsWith(" $it") }
    }

    private fun matchScreenShare(clean: String, raw: String): SemanticIntentResult? {
        val stopCommands = listOf(
            "stop screen share", "stop screen sharing", "stop sharing", "screen share band karo",
            "screen share stop karo", "stop sharing screen", "screen sharing band karo"
        )
        if (stopCommands.any { clean == it || clean.contains(it) }) {
            return SemanticIntentResult(
                intent = AssistantIntent.STOP_SCREEN_SHARE,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.98f,
                reason = "User requested to stop screen sharing"
            )
        }

        val startCommands = listOf(
            "share screen with rafel", "share screen", "start screen share", "start screen sharing",
            "screen share karo", "screen share start karo", "screen share shuru karo", "screen share chalu karo"
        )
        if (startCommands.any { clean == it || clean.contains(it) }) {
            return SemanticIntentResult(
                intent = AssistantIntent.SCREEN_SHARE,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.98f,
                reason = "User requested to start screen sharing"
            )
        }

        val describeCommands = listOf(
            "screen par kya hai", "screen par kya dikh raha hai", "meri screen dekho",
            "screen dekho", "what is on my screen", "what s on my screen", "whats on my screen",
            "describe my screen", "read my screen", "look at my screen", "screen check karo"
        )
        if (describeCommands.any { clean == it || clean.contains(it) }) {
            return SemanticIntentResult(
                intent = AssistantIntent.DESCRIBE_SCREEN,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.98f,
                reason = "User requested visual understanding of current screen"
            )
        }

        return null
    }

    private fun matchQuestion(clean: String, raw: String): SemanticIntentResult? {
        val isQuestionMark = raw.trim().endsWith("?")
        val questionWordsEn = listOf(
            "what", "why", "how", "where", "when", "who", "which", "whose", "whom",
            "can you tell", "tell me about", "explain", "is it", "are you", "do you know", "will it"
        )
        val questionWordsHi = listOf(
            "kya", "kyun", "kyon", "kaise", "kahan", "kab", "kaun", "kiska", "kisko",
            "kitna", "kitne", "kitni", "batao", "bataiye"
        )
        val startsWithQ = questionWordsEn.any { clean.startsWith("$it ") || clean == it } ||
                questionWordsHi.any { clean.startsWith("$it ") || clean == it }
        val containsQ = isQuestionMark ||
                questionWordsEn.any { clean.contains(" $it ") } ||
                questionWordsHi.any { clean.contains(" $it ") || clean.endsWith(" $it") }

        if (startsWithQ || containsQ) {
            return SemanticIntentResult(
                intent = AssistantIntent.QUESTION,
                explicitAction = false,
                target = raw.trim(),
                secondaryData = null,
                confidence = 0.94f,
                reason = "User asked an informational question needing a spoken answer"
            )
        }
        return null
    }

    private fun isPureGreeting(clean: String): Boolean {
        if (clean.isBlank()) return true

        if (GREETING_PHRASES.any { clean == it || clean == "$it rafel" || clean == "rafel $it" }) {
            return true
        }

        if ((clean.startsWith("hello ") || clean.startsWith("hi ") || clean.startsWith("hey ") || clean.startsWith("namaste ")) &&
            !clean.contains("call") && !clean.contains("phone") && !clean.contains("dial") &&
            !clean.contains("whatsapp") && !clean.contains("open") && !clean.contains("kholo") &&
            !clean.contains("search") && !clean.contains("bhejo") && !clean.contains("likho")
        ) {
            return true
        }

        return false
    }

    private fun isCasualStatement(clean: String): Boolean {
        if (CASUAL_STATEMENTS.any { clean == it || clean.contains(it) }) {
            return true
        }

        val nonActionPatterns = listOf(
            Regex("^[a-z]+\\s+(is here|is coming|came|went|is busy|called me earlier|messaged me)$"),
            Regex("^[a-z]+\\s+(aa gaya|chala gaya|kaisa hai|kaisi hai|theek hai|se mila)$"),
            Regex("^main\\s+[a-z]+\\s+(se mila|ko jaanta hoon|ko bataya)$")
        )
        return nonActionPatterns.any { it.matches(clean) }
    }

    private fun extractNameFromCallMe(clean: String): String? {
        val pattern1 = Regex("(?:call me|mera naam|mujhe)\\s+([a-zA-Z]+)")
        val match = pattern1.find(clean)
        return match?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }
    }

    private fun matchCallContact(clean: String, raw: String): SemanticIntentResult? {
        // Exclude self-naming, greeting, and app open verbs
        if (clean.contains("call me") || clean.contains("bulao") || clean.startsWith("hello") || clean.startsWith("hi ") ||
            clean.contains("kholo") || clean.contains("open") || clean.contains("launch") || clean.contains("chalao")
        ) {
            return null
        }

        var contactTarget: String? = null

        // Pattern 1: English "call <target>", "dial <target>", "phone <target>", "ring <target>"
        val enPattern = Regex("^(?:call|dial|phone|ring|make a call to|place a call to)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:please|now|on sim \\d+))?$")
        val enMatch = enPattern.find(clean)
        if (enMatch != null) {
            contactTarget = enMatch.groupValues[1].trim()
        }

        // Pattern 2: Hindi/Hinglish "<target> ko call karo/lagao/milao", "<target> ko phone lagao/karo"
        if (contactTarget == null) {
            val hiPattern1 = Regex("^([a-zA-Z0-9\\s]+?)\\s+ko\\s+(?:ek\\s+)?(?:call|phone|dial)\\s+(?:karo|lagao|kijiye|kar do|milao|karna)$")
            val hiMatch1 = hiPattern1.find(clean)
            if (hiMatch1 != null) {
                contactTarget = hiMatch1.groupValues[1].trim()
            }
        }

        // Pattern 3: Hindi/Hinglish "call lagao <target>", "phone karo <target>", "call karo <target>"
        if (contactTarget == null) {
            val hiPattern2 = Regex("^(?:call lagao|call karo|phone lagao|phone karo|dial karo)\\s+([a-zA-Z0-9\\s]+)$")
            val hiMatch2 = hiPattern2.find(clean)
            if (hiMatch2 != null) {
                contactTarget = hiMatch2.groupValues[1].trim()
            }
        }

        if (!contactTarget.isNullOrBlank()) {
            val cleanTarget = contactTarget.replace(Regex("(?:please|now|sim 1|sim 2)"), "").trim()
            if (isValidContactTarget(cleanTarget)) {
                return SemanticIntentResult(
                    intent = AssistantIntent.CALL_CONTACT,
                    explicitAction = true,
                    target = cleanTarget.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                    secondaryData = null,
                    confidence = 0.98f,
                    reason = "Explicit call action verb and valid contact target matched"
                )
            }
        }

        return null
    }

    private fun matchWhatsAppMessaging(clean: String, raw: String): SemanticIntentResult? {
        if (!clean.contains("whatsapp")) return null

        if (clean in listOf("open whatsapp", "whatsapp kholo", "launch whatsapp", "whatsapp open karo", "start whatsapp")) {
            return null
        }

        var target: String? = null
        var message: String? = null

        val enPattern1 = Regex("^(?:send\\s+(?:a\\s+)?whatsapp(?:\\s+message)?\\s+to|whatsapp)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:saying|with|that|message)\\s+(.+))?$")
        val enMatch1 = enPattern1.find(clean)
        if (enMatch1 != null) {
            target = enMatch1.groupValues[1].trim()
            if (enMatch1.groupValues.size > 2 && enMatch1.groupValues[2].isNotBlank()) {
                message = enMatch1.groupValues[2].trim()
            }
        }

        if (target == null) {
            val enPattern2 = Regex("^send\\s+([a-zA-Z0-9\\s]+?)\\s+(?:a\\s+)?whatsapp(?:\\s+message)?(?:\\s+(?:saying|with|that)\\s+(.+))?$")
            val enMatch2 = enPattern2.find(clean)
            if (enMatch2 != null) {
                target = enMatch2.groupValues[1].trim()
                if (enMatch2.groupValues.size > 2 && enMatch2.groupValues[2].isNotBlank()) {
                    message = enMatch2.groupValues[2].trim()
                }
            }
        }

        // Hindi: "<target> ko whatsapp [par] <msg> message bhejo/likho"
        if (target == null) {
            val hiPattern3 = Regex("^([a-zA-Z0-9\\s]+?)\\s+ko\\s+whatsapp\\s+(?:par\\s+)?(.+?)\\s+(?:message\\s+)?(?:bhejo|karo|kar do|likho|likh kar bhejo)$")
            val hiMatch3 = hiPattern3.find(clean)
            if (hiMatch3 != null) {
                target = hiMatch3.groupValues[1].trim()
                if (hiMatch3.groupValues.size > 2 && hiMatch3.groupValues[2].isNotBlank()) {
                    message = hiMatch3.groupValues[2].trim()
                }
            }
        }

        // Hindi: "<target> ko whatsapp [message] bhejo/likho [ki] <msg>"
        if (target == null) {
            val hiPattern = Regex("^([a-zA-Z0-9\\s]+?)\\s+ko\\s+whatsapp\\s+(?:par\\s+)?(?:message\\s+)?(?:bhejo|karo|kar do|likho)(?:\\s+(?:ki\\s+|saying\\s+)?(.+))?$")
            val hiMatch = hiPattern.find(clean)
            if (hiMatch != null) {
                target = hiMatch.groupValues[1].trim()
                if (hiMatch.groupValues.size > 2 && hiMatch.groupValues[2].isNotBlank()) {
                    message = hiMatch.groupValues[2].trim()
                }
            }
        }

        // Hindi: "whatsapp par <target> ko [message] bhejo/likho [ki] <msg>"
        if (target == null) {
            val hiPattern2 = Regex("^whatsapp\\s+par\\s+([a-zA-Z0-9\\s]+?)\\s+ko\\s+(?:message\\s+)?(?:bhejo|karo|likho)(?:\\s+(?:ki\\s+)?(.+))?$")
            val hiMatch2 = hiPattern2.find(clean)
            if (hiMatch2 != null) {
                target = hiMatch2.groupValues[1].trim()
                if (hiMatch2.groupValues.size > 2 && hiMatch2.groupValues[2].isNotBlank()) {
                    message = hiMatch2.groupValues[2].trim()
                }
            }
        }

        if (!target.isNullOrBlank() && isValidContactTarget(target)) {
            return SemanticIntentResult(
                intent = AssistantIntent.SEND_WHATSAPP,
                explicitAction = true,
                target = target.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                secondaryData = message ?: "",
                confidence = 0.98f,
                reason = "Explicit WhatsApp messaging intent and target contact matched"
            )
        }

        return null
    }

    private fun matchSmsMessaging(clean: String, raw: String): SemanticIntentResult? {
        if (!clean.contains("sms") && !clean.contains("message") && !clean.contains("sandesh") && !clean.contains("text")) return null
        if (clean.contains("whatsapp")) return null

        var target: String? = null
        var msg: String? = null

        val enPattern = Regex("^(?:send\\s+(?:an?\\s+)?(?:sms|text|message)\\s+to)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:saying|with|that)\\s+(.+))?$")
        val enMatch = enPattern.find(clean)
        if (enMatch != null) {
            target = enMatch.groupValues[1].trim()
            if (enMatch.groupValues.size > 2 && enMatch.groupValues[2].isNotBlank()) {
                msg = enMatch.groupValues[2].trim()
            }
        }

        // Hindi: "<target> ko message/sms likho/bhejo [ki / :] <msg>"
        if (target == null) {
            val hiPattern = Regex("^([a-zA-Z0-9\\s]+?)\\s+ko\\s+(?:sms|message|sandesh|text)\\s+(?:bhejo|karo|kar do|likho)(?:\\s+(?:ki\\s+|saying\\s+)?(.+))?$")
            val hiMatch = hiPattern.find(clean)
            if (hiMatch != null) {
                target = hiMatch.groupValues[1].trim()
                if (hiMatch.groupValues.size > 2 && hiMatch.groupValues[2].isNotBlank()) {
                    msg = hiMatch.groupValues[2].trim()
                }
            }
        }

        // Hindi: "message bhejo <target> ko [ki] <msg>"
        if (target == null) {
            val hiPattern2 = Regex("^(?:sms|message|sandesh)\\s+(?:bhejo|karo|likho)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+ko)?(?:\\s+(?:ki\\s+)?(.+))?$")
            val hiMatch2 = hiPattern2.find(clean)
            if (hiMatch2 != null) {
                target = hiMatch2.groupValues[1].trim()
                if (hiMatch2.groupValues.size > 2 && hiMatch2.groupValues[2].isNotBlank()) {
                    msg = hiMatch2.groupValues[2].trim()
                }
            }
        }

        if (!target.isNullOrBlank() && isValidContactTarget(target)) {
            return SemanticIntentResult(
                intent = AssistantIntent.SEND_SMS,
                explicitAction = true,
                target = target.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                secondaryData = msg ?: "",
                confidence = 0.98f,
                reason = "Explicit SMS messaging intent and target contact matched"
            )
        }

        return null
    }

    private fun matchReadMessages(clean: String, raw: String): SemanticIntentResult? {
        val lower = clean.lowercase(Locale.ROOT).trim()

        // Negative check: compose or send verbs
        if (lower.contains("bhejo") || lower.contains("likho") || lower.contains("draft") || lower.contains("send") || lower.contains("dial")) {
            return null
        }

        val isReadVerb = lower.contains("padho") || lower.contains("sunao") || lower.contains("read") ||
                lower.contains("check karo") || lower.contains("batao") || lower.contains("kya aaya hai") || lower.contains("kya message aaya")

        val mentionsMessagesOrNotif = lower.contains("message") || lower.contains("messages") ||
                lower.contains("notification") || lower.contains("notifications") || lower.contains("sandesh")

        if (!isReadVerb && !mentionsMessagesOrNotif) {
            return null
        }

        if ((isReadVerb && mentionsMessagesOrNotif) ||
            lower in listOf("read my messages", "read messages", "mere naye messages padho", "naye messages padho", "messages padho", "notification padho", "notifications padho") ||
            lower.contains("kya message aaya") || lower.contains("latest notification")
        ) {
            // Determine platform target if specified
            val platform = when {
                lower.contains("instagram") || lower.contains("insta") -> "Instagram"
                lower.contains("telegram") || lower.contains("tg") -> "Telegram"
                lower.contains("whatsapp") || lower.contains("wa") -> "WhatsApp"
                lower.contains("messenger") || lower.contains("facebook") -> "Messenger"
                lower.contains("sms") -> "SMS"
                lower.contains("signal") -> "Signal"
                else -> null
            }

            return SemanticIntentResult(
                intent = AssistantIntent.READ_MESSAGES,
                explicitAction = true,
                target = platform,
                secondaryData = null,
                confidence = 0.98f,
                reason = "Explicit request to read messages/notifications aloud" + if (platform != null) " for $platform" else ""
            )
        }

        return null
    }

    private fun matchMultiAppCompose(clean: String, raw: String): SemanticIntentResult? {
        val lower = clean.lowercase(Locale.ROOT).trim()

        val app = when {
            lower.contains("instagram") || lower.contains("insta") -> "Instagram"
            lower.contains("telegram") || lower.contains("tg") -> "Telegram"
            lower.contains("messenger") || lower.contains("facebook") -> "Messenger"
            lower.contains("signal") -> "Signal"
            else -> null
        } ?: return null

        // Pattern 1: "<app> par <target> ko message draft/likho/bhejo [: / ki] <msg>"
        val p1 = Regex("(?:in|on|par|pe)?\\s*${app.lowercase()}\\s+(?:par|pe|me)?\\s*([a-zA-Z0-9\\s]+?)\\s+ko\\s+(?:message\\s+)?(?:draft\\s+karo|likho|bhejo|bhejne ke liye chat kholo)(?:\\s*[: ki saying that]*\\s*(.*))?$")
        val m1 = p1.find(lower)
        if (m1 != null) {
            val target = m1.groupValues[1].trim()
            val msg = if (m1.groupValues.size > 2) m1.groupValues[2].trim() else ""
            if (isValidContactTarget(target)) {
                return SemanticIntentResult(
                    intent = AssistantIntent.COMPOSE_MESSAGE,
                    explicitAction = true,
                    target = target.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                    secondaryData = "$app:::$msg",
                    confidence = 0.98f,
                    reason = "Explicit multi-app message compose request for $app"
                )
            }
        }

        // Pattern 2: "<target> ko <app> [par] message draft/likho/bhejo [: / ki] <msg>"
        val p2 = Regex("^([a-zA-Z0-9\\s]+?)\\s+ko\\s+${app.lowercase()}\\s+(?:par|pe|me)?\\s*(?:message\\s+)?(?:draft\\s+karo|likho|bhejo)(?:\\s*[: ki saying that]*\\s*(.*))?$")
        val m2 = p2.find(lower)
        if (m2 != null) {
            val target = m2.groupValues[1].trim()
            val msg = if (m2.groupValues.size > 2) m2.groupValues[2].trim() else ""
            if (isValidContactTarget(target)) {
                return SemanticIntentResult(
                    intent = AssistantIntent.COMPOSE_MESSAGE,
                    explicitAction = true,
                    target = target.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                    secondaryData = "$app:::$msg",
                    confidence = 0.98f,
                    reason = "Explicit multi-app message compose request for $app"
                )
            }
        }

        return null
    }

    private fun matchExplainMessage(clean: String, raw: String): SemanticIntentResult? {
        val lower = clean.lowercase(Locale.ROOT).trim()
        if (lower.contains("matlab samjhao") || lower.contains("explain this message") ||
            lower.contains("is message ka matlab") || lower.contains("message explain karo") ||
            lower.contains("ye message samjhao")
        ) {
            return SemanticIntentResult(
                intent = AssistantIntent.EXPLAIN_MESSAGE,
                explicitAction = true,
                target = null,
                secondaryData = null,
                confidence = 0.97f,
                reason = "User requested message explanation"
            )
        }
        return null
    }

    private fun matchYouTubeSearch(clean: String, raw: String): SemanticIntentResult? {
        if (!clean.contains("youtube") && !clean.contains("yt")) return null

        if (clean in listOf("open youtube", "launch youtube", "youtube kholo", "start youtube", "open yt", "yt kholo")) {
            return null
        }

        var query: String? = null

        val p1 = Regex("^(?:search\\s+(?:for\\s+)?|find\\s+|look\\s+up\\s+)(.+?)\\s+(?:on|in)\\s+youtube$")
        val m1 = p1.find(clean)
        if (m1 != null) {
            query = m1.groupValues[1].trim()
        }

        if (query.isNullOrBlank()) {
            val p2 = Regex("^youtube\\s+(?:par|pe|me)\\s+(?:search\\s+karo|chalao|bajaao|dhoondho|play)?\\s*(.+)$")
            val m2 = p2.find(clean)
            if (m2 != null) {
                query = m2.groupValues[1].trim()
            }
        }

        if (query.isNullOrBlank()) {
            val p3 = Regex("^(?:play|chalao|bajaao)\\s+(.+?)\\s+(?:on|par|pe|in)?\\s+youtube$")
            val m3 = p3.find(clean)
            if (m3 != null) {
                query = m3.groupValues[1].trim()
            }
        }

        if (query.isNullOrBlank()) {
            val p4 = Regex("^(.+?)\\s+youtube\\s+(?:par|pe|me)\\s+(?:search|chalao|bajaao|dhoondho|play)(?:\\s+karo)?$")
            val m4 = p4.find(clean)
            if (m4 != null) {
                query = m4.groupValues[1].trim()
            }
        }

        if (!query.isNullOrBlank() && query != "youtube" && query != "kholo") {
            return SemanticIntentResult(
                intent = AssistantIntent.SEARCH_YOUTUBE,
                explicitAction = true,
                target = query,
                secondaryData = null,
                confidence = 0.98f,
                reason = "Explicit YouTube search request for query '$query'"
            )
        }

        return null
    }

    private fun matchEmail(clean: String, raw: String): SemanticIntentResult? {
        if (!clean.contains("email") && !clean.contains("mail") && !clean.contains("gmail")) return null

        val p1 = Regex("^(?:send\\s+(?:an?\\s+)?(?:email|mail|gmail)\\s+to|email|mail)\\s+([a-zA-Z0-9@.\\s]+?)(?:\\s+(?:saying|about|with)\\s+(.+))?$")
        val m1 = p1.find(clean)
        if (m1 != null) {
            val target = m1.groupValues[1].trim()
            if (isValidContactTarget(target)) {
                return SemanticIntentResult(
                    intent = AssistantIntent.SEND_EMAIL,
                    explicitAction = true,
                    target = target,
                    secondaryData = if (m1.groupValues.size > 2) m1.groupValues[2].trim() else "",
                    confidence = 0.95f,
                    reason = "Explicit email dispatch request to '$target'"
                )
            }
        }

        return null
    }

    private fun matchOpenApp(clean: String, raw: String): SemanticIntentResult? {
        val openVerbs = listOf("open ", "launch ", "start ", " kholo", " open karo", " chalao", " launch karo")
        val hasOpenVerb = openVerbs.any { clean.startsWith(it) || clean.endsWith(it) || clean.contains(it) }
        if (!hasOpenVerb) return null

        var appName: String? = null

        val enMatch = Regex("^(?:open|launch|start)\\s+([a-zA-Z0-9\\s]+)$").find(clean)
        if (enMatch != null) {
            appName = enMatch.groupValues[1].trim()
        }

        if (appName == null) {
            val hiMatch = Regex("^([a-zA-Z0-9\\s]+?)\\s+(?:kholo|chalao|open karo|launch karo)$").find(clean)
            if (hiMatch != null) {
                appName = hiMatch.groupValues[1].trim()
            }
        }

        if (!appName.isNullOrBlank()) {
            val cleanApp = appName.replace(Regex("(?i)\\bapp\\b"), "").trim().ifBlank { appName }
            if (cleanApp.isNotBlank() && !isGreetingWord(cleanApp)) {
                val canonicalTarget = when (cleanApp.lowercase()) {
                    "whatsapp", "wa" -> "WhatsApp"
                    "instagram", "insta", "ig" -> "Instagram"
                    "youtube", "yt" -> "YouTube"
                    "settings", "setting" -> "Settings"
                    "phone", "dialer", "call", "caller" -> "Phone"
                    "camera", "kamera" -> "Camera"
                    "gallery", "photos" -> "Gallery"
                    else -> cleanApp.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                }
                return SemanticIntentResult(
                    intent = AssistantIntent.OPEN_APP,
                    explicitAction = true,
                    target = canonicalTarget,
                    secondaryData = null,
                    confidence = 0.97f,
                    reason = "Explicit request to launch app '$cleanApp'"
                )
            }
        }

        return null
    }

    private fun matchDeviceControl(clean: String): SemanticIntentResult? {
        if (clean in listOf("turn on torch", "turn on flashlight", "torch on", "flashlight on", "torch jalao", "torch on karo", "flashlight jalao")) {
            return SemanticIntentResult(AssistantIntent.DEVICE_CONTROL, true, "torch_on", null, 0.98f, "Torch ON command")
        }
        if (clean in listOf("turn off torch", "turn off flashlight", "torch off", "flashlight off", "torch band karo", "flashlight band karo")) {
            return SemanticIntentResult(AssistantIntent.DEVICE_CONTROL, true, "torch_off", null, 0.98f, "Torch OFF command")
        }
        if (clean.contains("volume") || clean.contains("awaz")) {
            return SemanticIntentResult(AssistantIntent.DEVICE_CONTROL, true, "volume", null, 0.95f, "Volume adjustment command")
        }
        if (clean.contains("where am i") || clean.contains("meri location") || clean.contains("location kya hai") || clean.contains("kahan hoon")) {
            return SemanticIntentResult(AssistantIntent.DEVICE_CONTROL, true, "location", null, 0.95f, "Location inquiry command")
        }
        return null
    }

    private fun isValidContactTarget(target: String): Boolean {
        val t = target.lowercase(Locale.ROOT).trim()
        if (t.isBlank()) return false
        if (t in listOf("rafel", "me", "myself", "friend", "someone", "there", "kisi ko", "ko", "kripya", "please", "call", "phone", "whatsapp", "kholo", "open", "launch", "chalao", "screen")) {
            return false
        }
        if (isGreetingWord(t)) return false
        return true
    }

    private fun isGreetingWord(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT).trim()
        return w in listOf("hello", "hi", "hey", "namaste", "good", "morning", "afternoon", "evening", "night", "there")
    }
}
