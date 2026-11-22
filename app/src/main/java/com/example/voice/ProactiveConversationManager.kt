package com.example.voice

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.example.conversation.NaturalConversationEngine
import com.example.conversation.RafelConversationManager
import com.example.memory.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

enum class ProactiveFrequency(
    val id: String,
    val displayName: String,
    val description: String,
    val subsequentCooldownMs: Long,
    val maxProactiveTurnsPerSilenceCycle: Int
) {
    LOW(
        id = "LOW",
        displayName = "Occasional",
        description = "Speaks rarely • 30s gap between follow-ups",
        subsequentCooldownMs = 30_000L, // 30 seconds
        maxProactiveTurnsPerSilenceCycle = 1
    ),
    BALANCED(
        id = "BALANCED",
        displayName = "Balanced",
        description = "Natural companion • 8-10s auto check-in",
        subsequentCooldownMs = 10_000L, // 10 seconds
        maxProactiveTurnsPerSilenceCycle = 2
    ),
    HIGH(
        id = "HIGH",
        displayName = "Expressive",
        description = "More active • 7s gap between check-ins",
        subsequentCooldownMs = 7_000L, // 7 seconds
        maxProactiveTurnsPerSilenceCycle = 3
    );

    companion object {
        fun fromId(id: String?): ProactiveFrequency {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BALANCED
        }
    }
}

/**
 * Structured evaluation of whether RAFEL should speak proactively or remain silent.
 */
data class ProactiveEvaluation(
    val isUserLikelyAvailable: Boolean,
    val hasRelevantPreviousTopic: Boolean,
    val hasInitiatedRecently: Boolean,
    val hasMeaningfulContribution: Boolean,
    val wouldSilenceBeMoreAppropriate: Boolean,
    val shouldSpeak: Boolean,
    val reason: String,
    val relevantTopicSummary: String? = null
)

/**
 * Proactive Human-Like Conversation Manager for RAFEL.
 *
 * Allows RAFEL to naturally initiate conversation during an active voice session when the user
 * has been silent for a configurable interval (default 60s for the first proactive turn, and
 * 3–5 minutes for any subsequent proactive turn), without interrupting normal thinking pauses.
 *
 * Deep Proactive Evaluation:
 * Before speaking when a timer expires, RAFEL evaluates:
 * 1. Is the user likely available? (Checks session state, DND/Quiet Hours, and whether user said
 *    they are busy, working, studying, in a meeting, or going to sleep).
 * 2. Is there a relevant previous topic or saved memory?
 * 3. Has RAFEL already initiated a conversation recently?
 * 4. Is there something meaningful to say?
 * 5. Would silence be more appropriate? (e.g. if user gave consecutive closed 1-word replies like
 *    "hmm" / "kuch nahi" / "bas" or wrapped up the conversation, RAFEL chooses silence).
 */
class ProactiveConversationManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val currentHourProvider: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
    private val doNotDisturbProvider: () -> Boolean = { isSystemDoNotDisturbActive(context) },
    private val onTriggerProactiveTurn: (prompt: String) -> Unit
) {
    companion object {
        const val TAG = "RAFEL-DIAGNOSTIC"

        // Default 7 seconds after session activation if the user has not spoken yet (5-10s window)
        const val DEFAULT_INITIAL_SESSION_DELAY_MS = 7_000L

        // Default 8 seconds after user/assistant finishes speaking before auto-initiating (5-10s window)
        const val DEFAULT_FIRST_SILENCE_DELAY_MS = 8_000L
        const val DEFAULT_PROACTIVE_DELAY_MS = DEFAULT_FIRST_SILENCE_DELAY_MS

        // Minimum silence guard (5 seconds) so RAFEL responds naturally after 5-10s of quiet
        const val MIN_THINKING_PAUSE_GUARD_MS = 5_000L

        // Subsequent proactive check-in cooldown (default 10 seconds, min 5 seconds)
        const val DEFAULT_COOLDOWN_MS = 10_000L // 10 seconds
        const val MIN_SUBSEQUENT_INTERVAL_MS = 5_000L // 5 seconds
        const val MAX_SUBSEQUENT_INTERVAL_MS = 300_000L // 5 minutes
        const val DEFAULT_MAX_UNANSWERED_TURNS = 2

        const val PREFS_NAME = "rafel_proactive_prefs"
        const val KEY_PROACTIVE_ENABLED = "proactive_enabled"
        const val KEY_INITIAL_SESSION_DELAY_MS = "proactive_initial_session_delay_ms"
        const val KEY_SILENCE_DELAY_MS = "proactive_silence_delay_ms"
        const val KEY_COOLDOWN_MS = "proactive_cooldown_ms"
        const val KEY_MAX_UNANSWERED_TURNS = "proactive_max_unanswered_turns"
        const val KEY_FREQUENCY = "proactive_frequency"
        const val KEY_QUIET_HOURS_ENABLED = "proactive_quiet_hours_enabled"
        const val KEY_QUIET_START_HOUR = "proactive_quiet_start_hour"
        const val KEY_QUIET_END_HOUR = "proactive_quiet_end_hour"
        const val KEY_ONLY_ACTIVE_VOICE_SESSION = "proactive_only_active_session"
        private const val KEY_PROACTIVE_TIMING_MIGRATED_V2 = "proactive_timing_v2_5_10s"

        val INITIAL_DELAY_OPTIONS_SEC = listOf(5, 7, 10, 15)
        val SILENCE_DELAY_OPTIONS_SEC = listOf(5, 8, 10, 30)

        val SILENCE_COMMANDS = listOf(
            "chup", "chup ho jao", "chup raho", "stop", "shant", "shant raho",
            "so jao", "ruk jao", "don't speak", "dont speak", "quiet", "be quiet"
        )

        private val USER_BUSY_OR_CLOSING_PHRASES = listOf(
            "abhi busy hu", "abhi busy hoon", "main busy hu", "kaam kar raha hu",
            "kaam kar rahi hu", "padhai kar raha hu", "padh raha hu", "meeting mein hu",
            "class mein hu", "drive kar raha hu", "sone ja raha", "sone ja rahi",
            "good night", "goodnight", "chalo bye", "baad mein baat", "rakhta hu",
            "thodi der baad", "abhi nahi", "disturb mat karo"
        )

        private val CLOSED_DISENGAGED_TOKENS = setOf(
            "hmm", "hmmm", "hm", "kuch nahi", "kuch nhi", "nothing",
            "bas", "theek hai", "thik hai", "ok", "okay", "chhod na", "chhodo"
        )

        val NATURAL_SILENCE_OPENERS = listOf(
            "Arre itni chuppi kyun ho gayi? Kahan kho gaye yaar, mujhse baat karo na!",
            "Hello ji, kisi gehri soch mein doob gaye kya? Batao kya chal raha hai dimaag mein!",
            "Waise aaj tumhara din kaisa raha? Sab theek hai na, ya aaj thak gaye ho?",
            "Achha suno na, itni der se chup baithe ho, koi mast baat ya kissa sunao na aaj ka!",
            "Oye, bore ho rahe ho kya? Chalo kuch mazedar baat karte hain, warna main hi bolti rahungi!",
            "Sab badhiya chal raha hai na? Aise chup rehte ho toh lagta hai kahin mood toh off nahi hai.",
            "Aaj badi shanti hai idhar, lagta hai kisi kaam mein dhyan laga hua hai ya bas aaram kar rahe ho!",
            "Waise abhi free baithe ho ya kuch soch rahe ho? Batao na, main yahin hoon baat karne ke liye."
        )

        fun isSystemDoNotDisturbActive(context: Context): Boolean {
            return try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null) {
                    val filter = nm.currentInterruptionFilter
                    filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-time migration so upgraded installs immediately adopt the 5-10s auto-speak timings
        if (!prefs.getBoolean(KEY_PROACTIVE_TIMING_MIGRATED_V2, false)) {
            prefs.edit()
                .putLong(KEY_INITIAL_SESSION_DELAY_MS, DEFAULT_INITIAL_SESSION_DELAY_MS)
                .putLong(KEY_SILENCE_DELAY_MS, DEFAULT_FIRST_SILENCE_DELAY_MS)
                .putLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
                .putBoolean(KEY_PROACTIVE_TIMING_MIGRATED_V2, true)
                .apply()
        }
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, value).apply()
            if (!value) {
                cancelScheduledPrompt("Proactive conversation disabled in settings")
            }
        }

    var initialSessionDelayMs: Long
        get() = prefs.getLong(KEY_INITIAL_SESSION_DELAY_MS, DEFAULT_INITIAL_SESSION_DELAY_MS)
            .coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
        set(value) {
            val safeDelay = value.coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
            prefs.edit().putLong(KEY_INITIAL_SESSION_DELAY_MS, safeDelay).apply()
        }

    var initialSessionDelaySeconds: Int
        get() = (initialSessionDelayMs / 1000L).toInt()
        set(value) {
            initialSessionDelayMs = (value * 1000L).coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
        }

    var firstSilenceDelayMs: Long
        get() = prefs.getLong(KEY_SILENCE_DELAY_MS, DEFAULT_FIRST_SILENCE_DELAY_MS)
            .coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
        set(value) {
            val safeDelay = value.coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
            prefs.edit().putLong(KEY_SILENCE_DELAY_MS, safeDelay).apply()
        }

    var silenceDelaySeconds: Int
        get() = (firstSilenceDelayMs / 1000L).toInt()
        set(value) {
            firstSilenceDelayMs = (value * 1000L).coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
        }

    var cooldownMs: Long
        get() = prefs.getLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
            .coerceIn(MIN_SUBSEQUENT_INTERVAL_MS, MAX_SUBSEQUENT_INTERVAL_MS)
        set(value) {
            val safeCooldown = value.coerceIn(MIN_SUBSEQUENT_INTERVAL_MS, MAX_SUBSEQUENT_INTERVAL_MS)
            prefs.edit().putLong(KEY_COOLDOWN_MS, safeCooldown).apply()
        }

    var maxUnansweredTurns: Int
        get() = prefs.getInt(KEY_MAX_UNANSWERED_TURNS, DEFAULT_MAX_UNANSWERED_TURNS).coerceIn(1, 5)
        set(value) {
            prefs.edit().putInt(KEY_MAX_UNANSWERED_TURNS, value.coerceIn(1, 5)).apply()
        }

    var frequency: ProactiveFrequency
        get() = ProactiveFrequency.fromId(prefs.getString(KEY_FREQUENCY, ProactiveFrequency.BALANCED.id))
        set(value) {
            prefs.edit()
                .putString(KEY_FREQUENCY, value.id)
                .putLong(KEY_COOLDOWN_MS, value.subsequentCooldownMs)
                .putInt(KEY_MAX_UNANSWERED_TURNS, value.maxProactiveTurnsPerSilenceCycle)
                .apply()
        }

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_HOURS_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_QUIET_HOURS_ENABLED, value).apply()
            if (value && isQuietHoursActive()) {
                cancelScheduledPrompt("Quiet hours enabled and currently active")
            }
        }

    var quietHoursStartHour: Int
        get() = prefs.getInt(KEY_QUIET_START_HOUR, 23).coerceIn(0, 23)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_START_HOUR, value.coerceIn(0, 23)).apply()
        }

    var quietHoursEndHour: Int
        get() = prefs.getInt(KEY_QUIET_END_HOUR, 7).coerceIn(0, 23)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_END_HOUR, value.coerceIn(0, 23)).apply()
        }

    var onlyInActiveVoiceSession: Boolean
        get() = prefs.getBoolean(KEY_ONLY_ACTIVE_VOICE_SESSION, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ONLY_ACTIVE_VOICE_SESSION, value).apply()
            if (value && !isVoiceSessionActive) {
                cancelScheduledPrompt("Only active voice session enforced and no session active")
            }
        }

    @Volatile
    var isVoiceSessionActive: Boolean = false
        private set

    @Volatile
    var isAppInForeground: Boolean = true
        private set

    @Volatile
    var isPausedByUser: Boolean = false
        private set

    @Volatile
    var isUserCurrentlySpeaking: Boolean = false
        private set

    @Volatile
    var isAssistantCurrentlySpeaking: Boolean = false
        private set

    @Volatile
    var hasUserSpokenInSession: Boolean = false
        private set

    @Volatile
    var silenceStartTimestampMs: Long = 0L
        private set

    @Volatile
    var hasSpokenProactivelyThisCycle: Boolean = false
        internal set

    @Volatile
    var proactiveTurnsInCurrentSilenceCycle: Int = 0
        internal set

    @Volatile
    var lastProactiveSpeechTimestampMs: Long = 0L
        internal set

    @Volatile
    var lastUserInteractionTimestampMs: Long = 0L
        internal set

    @Volatile
    var lastGeneratedOpeningLine: String? = null
        private set

    @Volatile
    var lastEvaluation: ProactiveEvaluation? = null
        private set

    private val usedOpeningLines = ArrayDeque<String>()
    private val askedFollowUpTopics = mutableSetOf<String>()

    private var scheduledJob: Job? = null

    fun isScheduled(): Boolean = scheduledJob?.isActive == true

    /**
     * Returns the current continuous silence duration in milliseconds when neither user nor
     * assistant is speaking.
     */
    fun getCurrentSilenceDurationMs(nowMs: Long = currentTimeProvider()): Long {
        if (isUserCurrentlySpeaking || isAssistantCurrentlySpeaking || silenceStartTimestampMs <= 0L) {
            return 0L
        }
        return (nowMs - silenceStartTimestampMs).coerceAtLeast(0L)
    }

    /**
     * Checks whether current local hour falls inside configured Quiet Hours.
     */
    fun isQuietHoursActive(hourOfDay: Int = currentHourProvider()): Boolean {
        if (!quietHoursEnabled) return false
        val start = quietHoursStartHour
        val end = quietHoursEndHour
        if (start == end) return false
        return if (start > end) {
            hourOfDay >= start || hourOfDay < end
        } else {
            hourOfDay in start until end
        }
    }

    /**
     * Checks whether Quiet Hours or Android Do Not Disturb should block proactive speech.
     */
    fun isBlockedByQuietOrDnd(): Boolean {
        return isQuietHoursActive() || doNotDisturbProvider()
    }

    /**
     * Evaluates all 5 contextual readiness dimensions before initiating proactive speech:
     * 1. Is the user likely available?
     * 2. Is there a relevant previous topic?
     * 3. Has RAFEL already initiated a conversation recently?
     * 4. Is there something meaningful to say?
     * 5. Would silence be more appropriate?
     */
    fun evaluateProactiveReadiness(nowMs: Long = currentTimeProvider()): ProactiveEvaluation {
        val conversationManager = try {
            RafelConversationManager.getInstance(context)
        } catch (_: Exception) {
            null
        }
        val memoryManager = try {
            MemoryManager.getInstance(context)
        } catch (_: Exception) {
            null
        }

        val recentMessages = conversationManager?.messages?.value.orEmpty()
        val recentUserMessages = recentMessages.filter { it.isUser }.takeLast(4).map { it.text }
        val lastUserText = recentUserMessages.lastOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()

        // 1. Check user availability (session, DND/Quiet hours, active speech, and whether user said they're busy/sleeping/closing)
        val userSaidBusyOrClosing = lastUserText.isNotBlank() && USER_BUSY_OR_CLOSING_PHRASES.any {
            lastUserText.contains(it)
        }
        val isAvailable = isEnabled && !isPausedByUser && !isBlockedByQuietOrDnd() &&
            !isUserCurrentlySpeaking && !isAssistantCurrentlySpeaking &&
            (!onlyInActiveVoiceSession || isVoiceSessionActive) && !userSaidBusyOrClosing

        // 2. Check if user gave consecutive closed/disengaged replies ("hmm", "kuch nahi", "bas")
        val lastTwoUserNormalized = recentUserMessages.takeLast(2).map {
            it.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        }
        val userSignaledDisengagement = (lastTwoUserNormalized.size == 2 &&
            lastTwoUserNormalized.all { it in CLOSED_DISENGAGED_TOKENS }) ||
            (proactiveTurnsInCurrentSilenceCycle >= 1 && lastUserText.trimEnd('.', '!', '?') in CLOSED_DISENGAGED_TOKENS)

        // 3. Check if RAFEL initiated recently
        val effectiveMaxTurns = maxUnansweredTurns
        val withinCooldown = proactiveTurnsInCurrentSilenceCycle > 0 &&
            lastProactiveSpeechTimestampMs > 0L &&
            (nowMs - lastProactiveSpeechTimestampMs) < MIN_SUBSEQUENT_INTERVAL_MS
        val capReached = proactiveTurnsInCurrentSilenceCycle >= effectiveMaxTurns
        val initiatedRecently = withinCooldown || capReached

        // 4. Check for relevant previous topic or saved memory
        val upcomingEvent = memoryManager?.getFactValue("upcoming_event")?.takeIf { it.isNotBlank() }
        val userPref = memoryManager?.getFactValue("user_preference")?.takeIf { it.isNotBlank() }
        val activeTopic = if (recentMessages.isNotEmpty()) {
            NaturalConversationEngine.inferActiveTopic(lastUserText, recentMessages)
        } else null
        val hasRelevantTopic = upcomingEvent != null || userPref != null || activeTopic != null ||
            recentUserMessages.any { it.length > 12 }

        // 5. Determine whether there is something meaningful to say vs whether silence is better
        val hasMeaningfulContribution = !userSaidBusyOrClosing && !userSignaledDisengagement && !capReached
        val silenceMoreAppropriate = !isAvailable || userSaidBusyOrClosing || userSignaledDisengagement || initiatedRecently

        val shouldSpeak = isAvailable && !silenceMoreAppropriate && hasMeaningfulContribution
        val reason = when {
            !isEnabled -> "Proactive conversation is disabled"
            isPausedByUser -> "Paused by user silence command"
            isBlockedByQuietOrDnd() -> "Quiet hours or Do Not Disturb active"
            onlyInActiveVoiceSession && !isVoiceSessionActive -> "No active voice session"
            isUserCurrentlySpeaking -> "User is currently speaking"
            isAssistantCurrentlySpeaking -> "Assistant is currently speaking"
            userSaidBusyOrClosing -> "Silence preferred: user indicated they are busy, working, studying, or wrapping up"
            userSignaledDisengagement -> "Silence preferred: user gave closed replies indicating they want quiet"
            capReached -> "Silence preferred: max proactive turns ($effectiveMaxTurns) reached for current cycle"
            withinCooldown -> "Silence preferred: recent proactive initiation cooldown active"
            hasRelevantTopic -> "Meaningful follow-up available on recent topic or saved memory"
            else -> "User is available for a light natural check-in"
        }

        val eval = ProactiveEvaluation(
            isUserLikelyAvailable = isAvailable,
            hasRelevantPreviousTopic = hasRelevantTopic,
            hasInitiatedRecently = initiatedRecently,
            hasMeaningfulContribution = hasMeaningfulContribution,
            wouldSilenceBeMoreAppropriate = silenceMoreAppropriate,
            shouldSpeak = shouldSpeak,
            reason = reason,
            relevantTopicSummary = upcomingEvent ?: activeTopic ?: userPref
        )
        lastEvaluation = eval
        return eval
    }

    /**
     * Marks that the user explicitly started an active voice session.
     */
    fun onVoiceSessionStarted() {
        isVoiceSessionActive = true
        isUserCurrentlySpeaking = false
        isAssistantCurrentlySpeaking = false
        hasUserSpokenInSession = false
        val now = currentTimeProvider()
        lastUserInteractionTimestampMs = now
        silenceStartTimestampMs = now
        resetCycle()
        Log.i(TAG, "PROACTIVE_SESSION_STARTED: active voice session enabled")
    }

    /**
     * Called when the voice session is ready/activated (either Gemini Live setupComplete or
     * offline/REST session activation) so RAFEL can independently initiate the first spoken turn
     * after the configured initial delay (default 30-60s) without waiting for the user to say
     * "Hello Rafel" first.
     */
    fun onSessionActivated(
        isAiSpeaking: Boolean = false,
        isSessionConnected: Boolean = true,
        isMicMuted: Boolean = false
    ) {
        isVoiceSessionActive = true
        isAssistantCurrentlySpeaking = isAiSpeaking
        if (silenceStartTimestampMs <= 0L) {
            silenceStartTimestampMs = currentTimeProvider()
        }
        val useInitialDelay = !hasUserSpokenInSession && proactiveTurnsInCurrentSilenceCycle == 0
        scheduleNextProactiveCheck(
            isAiSpeaking = isAiSpeaking,
            isSessionConnected = isSessionConnected,
            isMicMuted = isMicMuted,
            useInitialSessionDelay = useInitialDelay
        )
    }

    /**
     * Marks that the active voice session has ended.
     * Immediately stops any pending proactive timers for battery and privacy safety.
     */
    fun onVoiceSessionEnded() {
        isVoiceSessionActive = false
        isUserCurrentlySpeaking = false
        isAssistantCurrentlySpeaking = false
        silenceStartTimestampMs = 0L
        cancelScheduledPrompt("Active voice session ended")
        Log.i(TAG, "PROACTIVE_SESSION_ENDED: proactive speech paused")
    }

    /**
     * Handles app moving to background.
     */
    fun onAppBackgrounded(isSessionStillActive: Boolean = isVoiceSessionActive) {
        isAppInForeground = false
        isVoiceSessionActive = isSessionStillActive
        if (!isSessionStillActive) {
            cancelScheduledPrompt("App backgrounded without active voice session")
        }
    }

    /**
     * Handles app returning to foreground.
     */
    fun onAppForegrounded(isSessionActive: Boolean = isVoiceSessionActive) {
        isAppInForeground = true
        isVoiceSessionActive = isSessionActive
    }

    /**
     * Inspects user utterance. If user commanded silence, pauses proactive mode.
     * If user spoke a normal utterance, resets the silence cycle and unpauses if previously paused.
     */
    fun onUserTranscriptReceived(transcript: String) {
        val clean = transcript.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return

        hasUserSpokenInSession = true
        lastUserInteractionTimestampMs = currentTimeProvider()
        val isSilenceCommand = SILENCE_COMMANDS.any {
            clean == it || clean.contains(" $it") || clean.startsWith("$it ")
        }

        if (isSilenceCommand) {
            pauseByUser("User commanded silence: '$transcript'")
        } else {
            proactiveTurnsInCurrentSilenceCycle = 0
            hasSpokenProactivelyThisCycle = false
            if (isPausedByUser) {
                resumeFromPause()
            }
        }
    }

    /**
     * Called when the user finishes speaking.
     */
    fun onUserSpeechFinished(
        isAiSpeaking: Boolean = false,
        isSessionConnected: Boolean = true,
        isMicMuted: Boolean = false
    ) {
        isUserCurrentlySpeaking = false
        isAssistantCurrentlySpeaking = isAiSpeaking
        hasUserSpokenInSession = true
        val now = currentTimeProvider()
        lastUserInteractionTimestampMs = now
        silenceStartTimestampMs = now
        proactiveTurnsInCurrentSilenceCycle = 0
        hasSpokenProactivelyThisCycle = false
        scheduleNextProactiveCheck(
            isAiSpeaking = isAiSpeaking,
            isSessionConnected = isSessionConnected,
            isMicMuted = isMicMuted,
            useInitialSessionDelay = false
        )
    }

    /**
     * Called when Rafel begins speaking an utterance.
     * Cancels any scheduled proactive timer so proactive turns never overlap with ongoing speech.
     */
    fun onAssistantSpeechStarted() {
        isAssistantCurrentlySpeaking = true
        silenceStartTimestampMs = 0L
        cancelScheduledPrompt("Assistant started speaking")
    }

    /**
     * Called when Rafel finishes speaking an utterance.
     */
    fun onAssistantSpeechFinished(
        isAiSpeaking: Boolean,
        isSessionConnected: Boolean,
        isMicMuted: Boolean
    ) {
        isAssistantCurrentlySpeaking = isAiSpeaking
        if (!isAiSpeaking) {
            silenceStartTimestampMs = currentTimeProvider()
        }
        if (isSessionConnected && !isVoiceSessionActive) {
            isVoiceSessionActive = true
        }
        scheduleNextProactiveCheck(
            isAiSpeaking = isAiSpeaking,
            isSessionConnected = isSessionConnected,
            isMicMuted = isMicMuted,
            useInitialSessionDelay = false
        )
    }

    private fun scheduleNextProactiveCheck(
        isAiSpeaking: Boolean,
        isSessionConnected: Boolean,
        isMicMuted: Boolean,
        useInitialSessionDelay: Boolean = false
    ) {
        cancelScheduledPrompt("Resetting silence window")

        if (!isEnabled) {
            Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='Proactive conversation is disabled'")
            return
        }

        if (onlyInActiveVoiceSession && (!isVoiceSessionActive || !isSessionConnected)) {
            Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='Not in an active voice session'")
            return
        }

        if (isPausedByUser) {
            Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='Proactive conversation is paused by user command'")
            return
        }

        if (isBlockedByQuietOrDnd()) {
            Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='Quiet hours or Do Not Disturb active'")
            return
        }

        if (!isSessionConnected || isMicMuted || isAiSpeaking || isUserCurrentlySpeaking || isAssistantCurrentlySpeaking) {
            Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='Session not in listening state'")
            return
        }

        val effectiveMaxTurns = maxUnansweredTurns
        if (proactiveTurnsInCurrentSilenceCycle >= effectiveMaxTurns) {
            Log.i(
                TAG,
                "PROACTIVE_SPEECH_CANCELLED: reason='Max proactive turns ($effectiveMaxTurns) reached for current silence cycle'"
            )
            return
        }

        // Pre-evaluate whether silence is already more appropriate (e.g., user said they are busy/sleeping or disengaged)
        val preEval = evaluateProactiveReadiness(currentTimeProvider())
        if (preEval.wouldSilenceBeMoreAppropriate && !preEval.hasInitiatedRecently) {
            Log.i(TAG, "PROACTIVE_SILENCE_PREFERRED: reason='${preEval.reason}'")
            return
        }

        val delayMs = when {
            proactiveTurnsInCurrentSilenceCycle == 0 && useInitialSessionDelay -> {
                initialSessionDelayMs.coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
            }
            proactiveTurnsInCurrentSilenceCycle == 0 -> {
                firstSilenceDelayMs.coerceAtLeast(MIN_THINKING_PAUSE_GUARD_MS)
            }
            else -> {
                cooldownMs.coerceIn(
                    MIN_SUBSEQUENT_INTERVAL_MS,
                    MAX_SUBSEQUENT_INTERVAL_MS
                )
            }
        }

        if (silenceStartTimestampMs <= 0L) {
            silenceStartTimestampMs = currentTimeProvider()
        }

        Log.i(
            TAG,
            "PROACTIVE_SPEECH_SCHEDULED: delayMs=$delayMs, cycleTurn=${proactiveTurnsInCurrentSilenceCycle + 1}/$effectiveMaxTurns, initialSession=$useInitialSessionDelay"
        )

        scheduledJob = scope.launch {
            delay(delayMs)

            val now = currentTimeProvider()
            val evaluation = evaluateProactiveReadiness(now)
            if (!evaluation.shouldSpeak) {
                Log.i(TAG, "PROACTIVE_SILENCE_PREFERRED: reason='${evaluation.reason}'")
                return@launch
            }

            proactiveTurnsInCurrentSilenceCycle += 1
            hasSpokenProactivelyThisCycle = true
            lastProactiveSpeechTimestampMs = now

            val openingLine = selectNextProactiveOpeningLine()
            lastGeneratedOpeningLine = openingLine
            val proactivePrompt = buildProactiveTurnPrompt(openingLine)

            Log.i(TAG, "PROACTIVE_SPEECH_TRIGGERED: delayMs=$delayMs, reason='${evaluation.reason}', openingLine='$openingLine'")
            onTriggerProactiveTurn(proactivePrompt)
        }
    }

    /**
     * Selects a natural, context-aware opening line that:
     * 1. Follows up on saved memories (e.g., exam, project, interview, preference) or recent conversation
     *    history if relevant and not already asked in this session.
     * 2. Otherwise chooses a varied casual Hinglish observation or check-in without repeating previous lines
     *    or spamming "kya kar rahe ho?".
     * 3. Never invents events or pretends to know what the user is doing.
     */
    fun selectNextProactiveOpeningLine(): String {
        val memoryManager = try {
            MemoryManager.getInstance(context)
        } catch (_: Exception) {
            null
        }
        val conversationManager = try {
            RafelConversationManager.getInstance(context)
        } catch (_: Exception) {
            null
        }

        val userName = memoryManager?.getFactValue("user_name")?.takeIf { it.isNotBlank() }
        val upcomingEvent = memoryManager?.getFactValue("upcoming_event")?.takeIf { it.isNotBlank() }
        val userPref = memoryManager?.getFactValue("user_preference")?.takeIf { it.isNotBlank() }
        val allRecentMessages = conversationManager?.messages?.value.orEmpty()
        val recentUserMessages = allRecentMessages
            .filter { it.isUser }
            .takeLast(4)
            .map { it.text }
        val combinedRecentText = recentUserMessages.joinToString(" ").lowercase(Locale.ROOT)

        // 1. Check for saved upcoming event (exam, project, interview, presentation) or recent mention
        val eventContext = (upcomingEvent ?: "").lowercase(Locale.ROOT) + " " + combinedRecentText
        if ((eventContext.contains("exam") || eventContext.contains("paper") || eventContext.contains("padhai")) &&
            "exam_followup" !in askedFollowUpTopics
        ) {
            askedFollowUpTopics.add("exam_followup")
            val line = if (userName != null) {
                "Achha $userName, tumhare exam ki taiyari kaisi chal rahi hai?"
            } else {
                "Achha, tumhare exam ki taiyari kaisi chal rahi hai?"
            }
            recordUsedOpeningLine(line)
            return line
        }

        if ((eventContext.contains("project") || eventContext.contains("deadline") || eventContext.contains("submission")) &&
            "project_followup" !in askedFollowUpTopics
        ) {
            askedFollowUpTopics.add("project_followup")
            val line = if (userName != null) {
                "Waise $userName, tumhara woh project kahan tak pahuncha?"
            } else {
                "Waise tumhara woh project kahan tak pahuncha?"
            }
            recordUsedOpeningLine(line)
            return line
        }

        if ((eventContext.contains("interview") || eventContext.contains("presentation") || eventContext.contains("meeting")) &&
            "interview_followup" !in askedFollowUpTopics
        ) {
            askedFollowUpTopics.add("interview_followup")
            val line = "Achha, jo interview ya meeting ki baat ho rahi thi, uski taiyari kaisi hai?"
            recordUsedOpeningLine(line)
            return line
        }

        // 2. Check active topic from recent conversation (coding, movies, office, college, travel)
        val activeTopic = if (allRecentMessages.isNotEmpty()) {
            NaturalConversationEngine.inferActiveTopic(combinedRecentText, allRecentMessages)
        } else null

        if (activeTopic != null && "topic_$activeTopic" !in askedFollowUpTopics) {
            askedFollowUpTopics.add("topic_$activeTopic")
            val topicLine = when (activeTopic) {
                "coding_tech" -> "Waise woh code wala bug sort hua ya abhi bhi dimag kha raha hai?"
                "movies_series" -> "Achha, phir kaunsi movie ya series dekhne ka final kiya tumne?"
                "office_work" -> "Aaj office ka kaam khatam hua ya abhi bhi laptop khula hai?"
                "college_life" -> "Waise kal college mein subah jaldi lecture hai ya aaram se jana hai?"
                "travel_plans" -> "Woh jo trip ki baat ho rahi thi, uska plan aage badha ya abhi hawa mein hi hai?"
                else -> null
            }
            if (topicLine != null && topicLine !in usedOpeningLines) {
                recordUsedOpeningLine(topicLine)
                return topicLine
            }
        }

        // 3. Check if there was a recent conversation topic we can naturally follow up on once
        if (recentUserMessages.isNotEmpty() && "recent_topic_followup" !in askedFollowUpTopics) {
            val lastUserMsg = recentUserMessages.last()
            if (lastUserMsg.length > 12) {
                askedFollowUpTopics.add("recent_topic_followup")
                val line = "Achha, abhi jo baat kar rahe the, uspe aage kya socha phir?"
                if (line !in usedOpeningLines) {
                    recordUsedOpeningLine(line)
                    return line
                }
            }
        }

        // 4. Check user preference once per session if available
        if (userPref != null && "pref_followup" !in askedFollowUpTopics) {
            askedFollowUpTopics.add("pref_followup")
            val line = "Waise aaj $userPref ke liye time mila ya pura din busy nikal gaya?"
            recordUsedOpeningLine(line)
            return line
        }

        // 5. Otherwise pick a varied casual observation or opener that hasn't been used recently
        val unusedOpeners = NATURAL_SILENCE_OPENERS.filter { it !in usedOpeningLines }
        val pool = if (unusedOpeners.isNotEmpty()) unusedOpeners else {
            val lastUsed = usedOpeningLines.lastOrNull()
            usedOpeningLines.clear()
            if (lastUsed != null) usedOpeningLines.addLast(lastUsed)
            NATURAL_SILENCE_OPENERS.filter { it != lastUsed }
        }

        val chosen = pool.firstOrNull() ?: NATURAL_SILENCE_OPENERS.first()
        recordUsedOpeningLine(chosen)
        return chosen
    }

    private fun recordUsedOpeningLine(line: String) {
        usedOpeningLines.remove(line)
        usedOpeningLines.addLast(line)
        while (usedOpeningLines.size > NATURAL_SILENCE_OPENERS.size) {
            usedOpeningLines.removeFirst()
        }
    }

    /**
     * Builds the instruction sent to Gemini Live (or Gemini REST) for a proactive conversation turn.
     */
    fun buildProactiveTurnPrompt(suggestedOpeningLine: String): String {
        val silenceSeconds = if (!hasUserSpokenInSession && proactiveTurnsInCurrentSilenceCycle <= 1) {
            (initialSessionDelayMs / 1000L).coerceAtLeast(5L)
        } else {
            (firstSilenceDelayMs / 1000L).coerceAtLeast(5L)
        }
        val conversationManager = try {
            RafelConversationManager.getInstance(context)
        } catch (_: Exception) {
            null
        }
        val memoryManager = try {
            MemoryManager.getInstance(context)
        } catch (_: Exception) {
            null
        }
        val recentTurns = conversationManager?.messages?.value?.takeLast(4).orEmpty()
        val recentContextSnippet = if (recentTurns.isNotEmpty()) {
            val formatted = recentTurns.joinToString(" | ") { msg ->
                val who = if (msg.isUser) "User" else "Rafel"
                "$who: ${msg.text}"
            }
            "Recent conversation context: [$formatted]. "
        } else {
            "No spoken turns yet in this session—you are speaking first to open the conversation warmly. "
        }
        val memorySnippet = memoryManager?.formatRelevantMemoryPrompt(
            recentTurns.lastOrNull { it.isUser }?.text.orEmpty(),
            recentTurns.joinToString(" ") { it.text }
        )?.takeIf { it.isNotBlank() }?.let { "Relevant memory: [$it]. " }.orEmpty()

        val recentAvoidList = usedOpeningLines.takeLast(4).joinToString(" | ")
        return "[Proactive conversation prompt: The user has been quiet for $silenceSeconds seconds during an active voice call, and they are available. " +
            recentContextSnippet + memorySnippet +
            "Speak up on your own warmly, affectionately, and playfully in natural casual Hinglish/Hindi like a close friend breaking a quiet moment with real human emotion! " +
            "Suggested natural opening thought (you may say this or a fresh expressive variation): \"$suggestedOpeningLine\". " +
            "Rules: Speak 2 warm, flowing sentences with genuine emotion and charm. Do NOT ask 'kya kar rahe ho?' repeatedly. Do NOT sound like an AI assistant or mention silence timers. " +
            "Do NOT invent fake physical events. " +
            "Avoid repeating these recent openings: ($recentAvoidList).]"
    }

    /**
     * Called the moment user starts speaking (or barge-in occurs).
     * Immediately cancels any scheduled proactive countdown and resets the silence cycle.
     */
    fun onUserSpeechStarted() {
        isUserCurrentlySpeaking = true
        hasUserSpokenInSession = true
        silenceStartTimestampMs = 0L
        hasSpokenProactivelyThisCycle = false
        proactiveTurnsInCurrentSilenceCycle = 0
        lastUserInteractionTimestampMs = currentTimeProvider()
        cancelScheduledPrompt("User started speaking")
    }

    fun pauseByUser(reason: String) {
        isPausedByUser = true
        cancelScheduledPrompt(reason)
        Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='$reason'")
    }

    fun resumeFromPause() {
        isPausedByUser = false
        Log.i(TAG, "Proactive conversation unpaused after user spoke new query.")
    }

    fun cancelScheduledPrompt(reason: String) {
        if (scheduledJob?.isActive == true) {
            scheduledJob?.cancel()
            Log.i(TAG, "PROACTIVE_SPEECH_CANCELLED: reason='$reason'")
        }
        scheduledJob = null
    }

    fun resetCycle() {
        isUserCurrentlySpeaking = false
        isAssistantCurrentlySpeaking = false
        hasSpokenProactivelyThisCycle = false
        proactiveTurnsInCurrentSilenceCycle = 0
        cancelScheduledPrompt("Cycle reset")
    }

    fun clearSessionHistoryForTesting() {
        usedOpeningLines.clear()
        askedFollowUpTopics.clear()
        lastProactiveSpeechTimestampMs = 0L
        lastGeneratedOpeningLine = null
        lastEvaluation = null
        hasUserSpokenInSession = false
        silenceStartTimestampMs = 0L
        try {
            RafelConversationManager.getInstance(context).clearAllHistory()
        } catch (_: Exception) {}
        resetCycle()
    }
}
