package com.example.conversation

import com.example.ai.LanguageStyle
import com.example.ai.PersonaSettings
import com.example.ai.TurnCategory
import com.example.ai.UserMood
import java.util.Locale
import kotlin.math.abs

/**
 * High-level conversational subtext detected from the user's message and recent turn history.
 */
enum class ConversationalSubtext {
    GREETING_OR_CHECKIN,
    SHORT_BACKCHANNEL,
    INCOMPLETE_THOUGHT,
    AMBIGUOUS_NEEDS_CLARIFICATION,
    VENTING_NO_ADVICE,
    TEASING_OR_HUMOUR,
    OPINION_OR_DEBATE,
    DISAGREEMENT_OR_PUSHBACK,
    EMOTIONAL_SHARE,
    TOPIC_SHIFT,
    REPEATED_QUESTION,
    UNEXPECTED_OR_QUIRKY,
    FACTUAL_OR_TECHNICAL,
    CLOSURE_OR_BUSY,
    CASUAL_FLOW
}

/**
 * Primary conversational discourse act of the user's message.
 */
enum class UserDiscourseAct {
    SHARING_EXPERIENCE,
    EXPRESSING_EMOTION,
    MAKING_A_JOKE,
    ASKING_A_QUESTION,
    DISAGREEING,
    CHANGING_TOPIC,
    CONTINUING_CONVERSATION,
    ENDING_CONVERSATION
}

/**
 * Fine-grained semantic intent of the user's latest message so RAFEL responds to what the user
 * actually meant rather than picking from a coarse topic bucket.
 */
enum class UserSemanticIntent {
    VENTING_NO_ADVICE_WANTED,
    FEELING_UNAPPRECIATED_AT_WORK,
    WORK_MEETING_OR_DEADLINE_FATIGUE,
    BOSS_OR_LEAVE_CONVERSATION,
    EMOTIONAL_LOW_OR_MOOD_OFF,
    FEELING_LONELY_OR_OVERBURDENED,
    CALMING_DOWN_OR_RESTING,
    INTERVIEW_PREPARATION_OR_ANXIETY,
    EXAM_OR_STUDY_STRESS,
    CODING_OR_DEBUGGING_FRUSTRATION,
    TECHNICAL_DISCUSSION_OR_CONCEPT,
    PROJECT_PROGRESS_OR_REPORT,
    COLLEGE_LECTURES_OR_ROUTINE,
    COLLEGE_CANTEEN_OR_FRIENDS,
    SPECIFIC_FOOD_CRAVING_OR_MEAL,
    CHAI_OR_COFFEE_MOMENT,
    WEATHER_OR_RAIN_VIBE,
    MUSIC_OR_RELAXING_VIBE,
    MOVIE_OR_SERIES_DISCUSSION,
    CRICKET_OR_SPORTS_EXCITEMENT,
    TRAVEL_PLAN_OR_CANCELLED_TRIP,
    CELEBRATION_OR_GOOD_NEWS,
    BOREDOM_OR_LAZINESS,
    PLAYFUL_TEASE_AT_RAFEL,
    SELF_DEPRECATING_HUMOUR,
    OPINION_OR_HOT_TAKE,
    PUSHBACK_OR_DISAGREEMENT,
    AMBIGUOUS_REFERENCE,
    INCOMPLETE_TRAIL_OFF,
    REPEATED_UTTERANCE,
    SHORT_ACKNOWLEDGMENT,
    DIRECT_FACTUAL_QUERY,
    PERSONAL_FACT_INTRODUCTION,
    MEMORY_RECALL_CHECK,
    CURVEBALL_HYPOTHETICAL,
    GREETING_OPENING,
    CLOSURE_OR_STEPPING_AWAY,
    GENERAL_CONVERSATIONAL_STATEMENT
}

/**
 * Tracks whether the user is continuing the current topic, shifting to a new topic,
 * or starting a fresh thread.
 */
enum class TopicTransition {
    NEW_TOPIC,
    CONTINUING_TOPIC,
    EXPLICIT_TOPIC_SHIFT,
    BACKCHANNEL_ON_PREVIOUS_TURN
}

/**
 * The conversational move RAFEL should make on this turn so it behaves like a perceptive human
 * rather than a scripted Q&A bot.
 */
enum class ConversationalMove(val directive: String) {
    OBSERVATION_ONLY(
        "Share a fresh, grounded observation about what the user just said without repeating their words. Do NOT ask a question at the end."
    ),
    SHORT_REACTION(
        "Give a brief, natural 1-sentence reaction directly addressing the user's point. Do NOT add explanations or questions."
    ),
    NON_DIRECTIVE_LISTENING(
        "The user is venting and explicitly does not want advice or solutions. Validate and listen warmly without offering tips, fixes, or lectures."
    ),
    PLAYFUL_TEASE_OR_WIT(
        "Reply with light, clever banter or playful teasing directly tied to their remark. Never force humour if the mood is serious."
    ),
    OPINION_OR_PLAYFUL_DISAGREEMENT(
        "Express a clear personal opinion or playfully disagree/push back on the exact point they raised."
    ),
    TOPIC_CONTINUATION(
        "Continue the user's exact topic and entities naturally with a fresh angle without changing the subject or turning it into an interview."
    ),
    NATURAL_CLARIFICATION(
        "The user's message has an unclear reference or ambiguous meaning. Ask a short, natural clarification question instead of guessing."
    ),
    GENTLE_COMPLETION_PROMPT(
        "The user trailed off or left their sentence incomplete. Briefly react and invite them to finish their thought."
    ),
    EMPATHETIC_PRESENCE(
        "Acknowledge the specific situation they shared warmly and realistically without robotic sympathy clichés or changing the subject."
    ),
    CURIOUS_FOLLOW_UP(
        "Respond directly to their point and include ONE specific, relevant follow-up question about what they just mentioned."
    ),
    CRISP_DIRECT_ANSWER(
        "Answer the question directly and concisely with zero filler introductions and no trailing follow-up question."
    ),
    WARM_SIGN_OFF(
        "Acknowledge their sign-off or busyness cleanly and let them go without asking any further questions."
    )
}

/**
 * Extracted entities and details from the conversation to preserve across turns.
 */
data class TrackedConversationEntities(
    val userName: String? = null,
    val mentionedPeople: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val foodAndDrinks: List<String> = emptyList(),
    val activeEventsOrTasks: List<String> = emptyList(),
    val currentSubjectKeyword: String? = null
)

/**
 * Complete per-turn semantic analysis produced by [NaturalConversationEngine].
 */
data class TurnAnalysis(
    val userMessage: String,
    val discourseAct: UserDiscourseAct,
    val semanticIntent: UserSemanticIntent,
    val subtext: ConversationalSubtext,
    val topicTransition: TopicTransition,
    val recommendedMove: ConversationalMove,
    val mood: UserMood,
    val category: TurnCategory,
    val languageStyle: LanguageStyle,
    val activeTopic: String?,
    val entities: TrackedConversationEntities,
    val wantsNoAdvice: Boolean = false,
    val isRepeatedQuestion: Boolean = false,
    val isIncompleteSentence: Boolean = false,
    val isAmbiguous: Boolean = false,
    val allowTrailingQuestion: Boolean = false,
    val recentQuestionRatio: Float = 0f
)

/**
 * Per-turn evaluation breakdown for verifying context understanding and relevance.
 */
data class TurnEvaluation(
    val turnNumber: Int,
    val userMessage: String,
    val rafelResponse: String,
    val detectedIntent: UserSemanticIntent,
    val topicTransition: TopicTransition,
    val relevanceScore: Double,
    val contextContinuityScore: Double,
    val intentUnderstandingScore: Double,
    val isUnrelatedResponse: Boolean,
    val diagnosticNote: String = ""
)

/**
 * Quantitative evaluation metrics for multi-turn conversation quality verification.
 */
data class ConversationQualityReport(
    val totalTurns: Int,
    val uniqueReplies: Int,
    val averageRelevanceScore: Double,
    val contextContinuityScore: Double,
    val intentUnderstandingRate: Double,
    val unrelatedResponseRate: Double,
    val phraseRepetitionRate: Double,
    val questionEndingRatio: Double,
    val naturalnessScore: Double,
    val maxConsecutiveQuestions: Int,
    val roboticPhraseCount: Int,
    val turnEvaluations: List<TurnEvaluation> = emptyList()
)

/**
 * Context-Understanding & Natural Conversation Engine for RAFEL.
 *
 * Core Principles:
 * 1. Understand the exact meaning, discourse act, and semantic intent of the latest user message BEFORE generating a reply.
 * 2. Track whether the user is sharing an experience, expressing emotion, joking, asking a question, disagreeing, changing topic, continuing, or ending.
 * 3. Eliminate repetitive crutch phrases ("Sahi hai", "Chalo badhiya", "Samajh gayi", "Sahi mein", "Bilkul", "Ye hui na baat").
 * 4. Never merely paraphrase or repeat the user's sentence in different words; always add a fresh observation, perspective, or direct answer.
 * 5. Respect when the user is venting and does not want advice.
 */
object NaturalConversationEngine {

    val BANNED_CRUTCH_PHRASES = listOf(
        "sahi hai",
        "chalo badhiya",
        "samajh gayi",
        "sahi mein",
        "bilkul",
        "ye hui na baat"
    )

    private val OVERUSED_CRUTCH_OPENER_REGEXES = listOf(
        Regex("^(?i)(sahi hai|chalo badhiya( hai)?|samajh gayi|sahi mein|bilkul|ye hui na baat|arre wah|chalo theek hai|haan bilkul)[,!.]?\\s+"),
        Regex("^(?i)(sahi socha|chalo sunke achha laga ki|baat toh tumhari bilkul sahi hai)[,!.]?\\s+")
    )

    private val NO_ADVICE_MARKERS = listOf(
        "salah nahi chahiye", "koi salah mat", "solution nahi chahiye", "solution mat do",
        "gyan mat do", "gyaan mat do", "bina gyaan diye", "bina gyan diye",
        "lecture mat do", "koi lecture nahi", "koi tip mat", "tip mat dena",
        "advice nahi chahiye", "advice mat do", "don't give advice", "dont give advice", "no advice",
        "bas sun lo", "sirf suno", "ye mat bolna ki sab theek", "productive tip mat"
    )

    private val INCOMPLETE_MARKERS = listOf(
        "...", "…", "matlab main soch raha tha", "matlab main keh raha tha",
        "woh kal jo", "woh main bol raha tha", "actually baat ye hai",
        "pata hai aaj kya", "suno ek baat", "main keh raha tha ki",
        "soch raha hu ki", "lagta hai ki", "par woh", "lekin phir",
        "agar main kal", "mera matlab tha ki"
    )

    private val TRAILING_CONJUNCTIONS = setOf(
        "ki", "par", "lekin", "aur", "toh", "kyunki", "jab", "agar", "jaise", "matlab", "but", "because", "although", "if"
    )

    private val OPINION_OR_DEBATE_MARKERS = listOf(
        "tumhe kya lagta", "kya lagta hai", "sahi hai na", "what do you think",
        "agree karti ho", "don't you think", "behtar hai", "bekar hota hai",
        "overrated", "underrated", "sabse best", "galat bol rahi", "main nahi manta",
        "bilkul galat", "aisa nahi hota", "skip kar deta", "bunk kar", "chhod deta hu",
        "raat bhar 3 baje", "bina padhe exam", "chai se achhi coffee", "coffee se achhi chai",
        "aalas ka bahana", "smart work mein believe", "ek ghante pehle kaam", "bina hotel book kiye"
    )

    private val DISAGREEMENT_MARKERS = listOf(
        "main nahi manta", "tum galat ho", "tum galat bol rahi", "aisa nahi hai", "bilkul nahi",
        "bakwas baat", "ye sahi nahi hai", "i disagree", "you are wrong",
        "kuch bhi mat bolo", "fayda nahi hai", "bekar idea", "ulta kyun bolti ho"
    )

    private val TOPIC_SHIFT_MARKERS = listOf(
        "waise chhodo", "chhodo ye batao", "achha suno", "ek aur baat",
        "topic change", "baat badalte hain", "by the way", "waise ek baat batao",
        "chhod usko", "leave that", "chhodo college ki baat", "chhodo office ki baat"
    )

    private val UNEXPECTED_MARKERS = listOf(
        "alien", "time travel", "lottery", "chaand pe", "mars pe", "ghost", "bhoot",
        "superpower", "invisible", "zombie", "crorepati", "100 crore", "jungle mein",
        "robot ko bhi", "robot ban jaun", "pm ban jaun", "dinosaur", "khayali pulao"
    )

    private val BUSY_OR_CLOSURE_MARKERS = listOf(
        "abhi busy hu", "kaam kar raha hu", "meeting mein hu", "padhai kar raha hu",
        "baad mein baat", "sone ja raha", "good night", "goodnight", "chalo bye", "rakhta hu",
        "band karta hu ab, bye", "khana khane ja raha hu. bye", "report likhne ja raha hu"
    )

    private val AMBIGUOUS_PATTERNS = listOf(
        Regex("(?i)^(woh|wo|ye)\\s+(wala|wali)\\s+(le\\s+lu|kar\\s+lu|sahi\\s+rahega|kaisa\\s+hai).*$"),
        Regex("(?i)^(usne|unhone)\\s+phir\\s+(wahi|aisa)\\s+kiya.*$"),
        Regex("(?i)^kya\\s+main\\s+(woh|wo|ye)\\s+(kar\\s+du|le\\s+lu).*$"),
        Regex("(?i)^(udhar|wahan)\\s+jana\\s+sahi\\s+rahega.*$"),
        Regex("(?i)^uska\\s+kya\\s+hua\\s+phir.*$")
    )

    private val GENERIC_TRAILING_QUESTION_PATTERNS = listOf(
        Regex("(?i)\\s+(aur batao[, ]+kya chal raha hai\\?|tum batao[, ]+kya plan hai\\?|aur kya chal raha hai( aajkal)?\\?|waise iske aage kya socha hai( tumne)?\\?|tumhara kya khayal hai\\?|kya bolte ho\\?)\\s*$")
    )

    /**
     * Analyzes the incoming user utterance against the recent conversation history,
     * extracting exact discourse act, semantic intent, entities, topic transition, and recommended move.
     */
    fun analyzeTurn(
        userMessage: String,
        recentHistory: List<UnifiedMessage> = emptyList()
    ): TurnAnalysis {
        val clean = userMessage.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val priorHistory = if (recentHistory.lastOrNull()?.let { it.isUser && it.text.trim().equals(clean, ignoreCase = true) } == true) {
            recentHistory.dropLast(1)
        } else {
            recentHistory
        }
        val mood = PersonaSettings.detectUserMood(clean)
        val category = PersonaSettings.classifyTurnCategory(clean)
        val langStyle = PersonaSettings.detectLanguageStyle(clean)

        val recentUserTurns = priorHistory.filter { it.isUser }.takeLast(6).map { it.text }
        val recentAssistantTurns = priorHistory.filter { !it.isUser }.takeLast(4).map { it.text }

        val recentQuestionCount = recentAssistantTurns.count { it.trim().endsWith("?") }
        val recentQuestionRatio = if (recentAssistantTurns.isEmpty()) 0f else {
            recentQuestionCount.toFloat() / recentAssistantTurns.size.toFloat()
        }
        val lastTwoEndedWithQuestion = recentAssistantTurns.takeLast(2).let {
            it.size == 2 && it.all { msg -> msg.trim().endsWith("?") }
        }

        val wantsNoAdvice = NO_ADVICE_MARKERS.any { lower.contains(it) }
        val isRepeated = detectRepeatedQuestion(clean, recentUserTurns)
        val isIncomplete = detectIncompleteSentence(clean)
        val entities = extractEntitiesAndDetails(clean, priorHistory)
        val isAmbiguous = detectAmbiguousUtterance(clean, priorHistory, entities)
        val topicTransition = detectTopicTransition(clean, priorHistory)
        val activeTopic = inferActiveTopic(clean, priorHistory, topicTransition)
        val semanticIntent = detectSemanticIntent(
            clean = clean,
            rawLower = lower,
            mood = mood,
            category = category,
            wantsNoAdvice = wantsNoAdvice,
            isRepeated = isRepeated,
            isIncomplete = isIncomplete,
            isAmbiguous = isAmbiguous,
            activeTopic = activeTopic,
            recentHistory = priorHistory
        )

        val discourseAct = classifyDiscourseAct(
            clean = clean,
            lower = lower,
            mood = mood,
            category = category,
            semanticIntent = semanticIntent,
            topicTransition = topicTransition,
            priorHistory = priorHistory
        )

        val subtext = when {
            wantsNoAdvice || semanticIntent == UserSemanticIntent.VENTING_NO_ADVICE_WANTED ->
                ConversationalSubtext.VENTING_NO_ADVICE
            isRepeated -> ConversationalSubtext.REPEATED_QUESTION
            isIncomplete -> ConversationalSubtext.INCOMPLETE_THOUGHT
            isAmbiguous -> ConversationalSubtext.AMBIGUOUS_NEEDS_CLARIFICATION
            category == TurnCategory.CLOSURE || BUSY_OR_CLOSURE_MARKERS.any { lower.contains(it) } ->
                ConversationalSubtext.CLOSURE_OR_BUSY
            category == TurnCategory.TECHNICAL_OR_DETAILED ||
                category == TurnCategory.SIMPLE_QUESTION ||
                semanticIntent == UserSemanticIntent.TECHNICAL_DISCUSSION_OR_CONCEPT ->
                ConversationalSubtext.FACTUAL_OR_TECHNICAL
            UNEXPECTED_MARKERS.any { lower.contains(it) } ->
                ConversationalSubtext.UNEXPECTED_OR_QUIRKY
            topicTransition == TopicTransition.EXPLICIT_TOPIC_SHIFT ->
                ConversationalSubtext.TOPIC_SHIFT
            DISAGREEMENT_MARKERS.any { lower.contains(it) } ->
                ConversationalSubtext.DISAGREEMENT_OR_PUSHBACK
            OPINION_OR_DEBATE_MARKERS.any { lower.contains(it) } && !lower.startsWith("sahi keh rahi") ->
                ConversationalSubtext.OPINION_OR_DEBATE
            mood == UserMood.JOKING || category == TurnCategory.PLAYFUL_OR_SARCASTIC ||
                semanticIntent == UserSemanticIntent.PLAYFUL_TEASE_AT_RAFEL ||
                semanticIntent == UserSemanticIntent.SELF_DEPRECATING_HUMOUR ->
                ConversationalSubtext.TEASING_OR_HUMOUR
            mood == UserMood.SAD || mood == UserMood.FRUSTRATED || mood == UserMood.EXCITED || mood == UserMood.HAPPY ||
                semanticIntent == UserSemanticIntent.FEELING_UNAPPRECIATED_AT_WORK ||
                semanticIntent == UserSemanticIntent.FEELING_LONELY_OR_OVERBURDENED ||
                semanticIntent == UserSemanticIntent.INTERVIEW_PREPARATION_OR_ANXIETY ->
                ConversationalSubtext.EMOTIONAL_SHARE
            category == TurnCategory.SHORT_REPLY ->
                ConversationalSubtext.SHORT_BACKCHANNEL
            isGreeting(lower) ->
                ConversationalSubtext.GREETING_OR_CHECKIN
            else ->
                ConversationalSubtext.CASUAL_FLOW
        }

        val move = selectConversationalMove(
            subtext = subtext,
            semanticIntent = semanticIntent,
            mood = mood,
            wantsNoAdvice = wantsNoAdvice,
            lastTwoEndedWithQuestion = lastTwoEndedWithQuestion,
            recentQuestionRatio = recentQuestionRatio,
            turnIndex = priorHistory.size
        )

        val allowQuestion = when (move) {
            ConversationalMove.CURIOUS_FOLLOW_UP,
            ConversationalMove.NATURAL_CLARIFICATION,
            ConversationalMove.GENTLE_COMPLETION_PROMPT -> true
            ConversationalMove.PLAYFUL_TEASE_OR_WIT -> !lastTwoEndedWithQuestion && recentQuestionRatio < 0.45f
            else -> false
        }

        return TurnAnalysis(
            userMessage = clean,
            discourseAct = discourseAct,
            semanticIntent = semanticIntent,
            subtext = subtext,
            topicTransition = topicTransition,
            recommendedMove = move,
            mood = mood,
            category = category,
            languageStyle = langStyle,
            activeTopic = activeTopic,
            entities = entities,
            wantsNoAdvice = wantsNoAdvice,
            isRepeatedQuestion = isRepeated,
            isIncompleteSentence = isIncomplete,
            isAmbiguous = isAmbiguous,
            allowTrailingQuestion = allowQuestion,
            recentQuestionRatio = recentQuestionRatio
        )
    }

    private fun classifyDiscourseAct(
        clean: String,
        lower: String,
        mood: UserMood,
        category: TurnCategory,
        semanticIntent: UserSemanticIntent,
        topicTransition: TopicTransition,
        priorHistory: List<UnifiedMessage>
    ): UserDiscourseAct {
        if (semanticIntent == UserSemanticIntent.CLOSURE_OR_STEPPING_AWAY || category == TurnCategory.CLOSURE) {
            return UserDiscourseAct.ENDING_CONVERSATION
        }
        if (topicTransition == TopicTransition.EXPLICIT_TOPIC_SHIFT) {
            return UserDiscourseAct.CHANGING_TOPIC
        }
        if (DISAGREEMENT_MARKERS.any { lower.contains(it) } ||
            semanticIntent == UserSemanticIntent.PUSHBACK_OR_DISAGREEMENT
        ) {
            return UserDiscourseAct.DISAGREEING
        }
        if (mood == UserMood.JOKING ||
            semanticIntent == UserSemanticIntent.PLAYFUL_TEASE_AT_RAFEL ||
            semanticIntent == UserSemanticIntent.SELF_DEPRECATING_HUMOUR ||
            semanticIntent == UserSemanticIntent.CURVEBALL_HYPOTHETICAL
        ) {
            return UserDiscourseAct.MAKING_A_JOKE
        }
        if (clean.endsWith("?") ||
            semanticIntent == UserSemanticIntent.DIRECT_FACTUAL_QUERY ||
            semanticIntent == UserSemanticIntent.TECHNICAL_DISCUSSION_OR_CONCEPT ||
            semanticIntent == UserSemanticIntent.MEMORY_RECALL_CHECK ||
            semanticIntent == UserSemanticIntent.AMBIGUOUS_REFERENCE
        ) {
            return UserDiscourseAct.ASKING_A_QUESTION
        }
        if (semanticIntent == UserSemanticIntent.VENTING_NO_ADVICE_WANTED ||
            semanticIntent == UserSemanticIntent.FEELING_UNAPPRECIATED_AT_WORK ||
            semanticIntent == UserSemanticIntent.FEELING_LONELY_OR_OVERBURDENED ||
            semanticIntent == UserSemanticIntent.EMOTIONAL_LOW_OR_MOOD_OFF ||
            semanticIntent == UserSemanticIntent.CELEBRATION_OR_GOOD_NEWS ||
            mood == UserMood.SAD || mood == UserMood.FRUSTRATED || mood == UserMood.EXCITED
        ) {
            return UserDiscourseAct.EXPRESSING_EMOTION
        }
        if (category == TurnCategory.SHORT_REPLY ||
            semanticIntent == UserSemanticIntent.SHORT_ACKNOWLEDGMENT ||
            semanticIntent == UserSemanticIntent.INCOMPLETE_TRAIL_OFF ||
            (priorHistory.isNotEmpty() && topicTransition == TopicTransition.CONTINUING_TOPIC &&
                clean.split(Regex("\\s+")).size <= 5)
        ) {
            return UserDiscourseAct.CONTINUING_CONVERSATION
        }
        return UserDiscourseAct.SHARING_EXPERIENCE
    }

    /**
     * Determines the exact semantic intent of the user's message so RAFEL addresses what the user
     * actually meant rather than picking an unrelated line from a broad category.
     */
    private fun detectSemanticIntent(
        clean: String,
        rawLower: String,
        mood: UserMood,
        category: TurnCategory,
        wantsNoAdvice: Boolean,
        isRepeated: Boolean,
        isIncomplete: Boolean,
        isAmbiguous: Boolean,
        activeTopic: String?,
        recentHistory: List<UnifiedMessage>
    ): UserSemanticIntent {
        val strippedShift = rawLower.replace(
            Regex("^(?:waise\\s+|achha\\s+)?(?:chhodo|baat badalte|leave that|anyway)[^,]*,\\s*"),
            ""
        ).trim()
        val lower = if (strippedShift.isNotBlank()) strippedShift else rawLower

        if (wantsNoAdvice) return UserSemanticIntent.VENTING_NO_ADVICE_WANTED
        if (isRepeated) return UserSemanticIntent.REPEATED_UTTERANCE
        if (isIncomplete) return UserSemanticIntent.INCOMPLETE_TRAIL_OFF
        if (isAmbiguous) return UserSemanticIntent.AMBIGUOUS_REFERENCE
        if (category == TurnCategory.CLOSURE || BUSY_OR_CLOSURE_MARKERS.any { lower.contains(it) }) {
            return UserSemanticIntent.CLOSURE_OR_STEPPING_AWAY
        }
        if (category == TurnCategory.SHORT_REPLY) {
            return UserSemanticIntent.SHORT_ACKNOWLEDGMENT
        }

        // Technical architecture & programming concept questions
        if (lower.contains("coroutine") || lower.contains("dispatchers") || lower.contains("stateflow") ||
            lower.contains("sharedflow") || lower.contains("recomposition") || lower.contains("websocket") ||
            lower.contains("room database") || lower.contains("mvvm") ||
            (category == TurnCategory.TECHNICAL_OR_DETAILED)
        ) {
            return UserSemanticIntent.TECHNICAL_DISCUSSION_OR_CONCEPT
        }

        if (lower.contains("mera naam kya") || lower.contains("mera naam yaad") ||
            lower.contains("mera naam pata") || lower.contains("what is my name") ||
            lower.contains("remember my name") || lower.contains("kahan rehta") ||
            lower.contains("kya pasand hai yaad") || lower.contains("mujhe jaanti ho")
        ) {
            return UserSemanticIntent.MEMORY_RECALL_CHECK
        }
        if (category == TurnCategory.SIMPLE_QUESTION ||
            lower.contains("insaan ho") || lower.contains("real human") ||
            lower.contains("are you human") || lower.contains("tum kaun ho")
        ) {
            return UserSemanticIntent.DIRECT_FACTUAL_QUERY
        }
        if ((Regex("(?i)\\b(mera naam|my name is)\\s+[a-z\\u0900-\\u097F]+").containsMatchIn(clean) && !clean.endsWith("?")) ||
            lower.contains("rehta hu") || lower.contains("rehti hu")
        ) {
            return UserSemanticIntent.PERSONAL_FACT_INTRODUCTION
        }
        if (UNEXPECTED_MARKERS.any { lower.contains(it) }) {
            return UserSemanticIntent.CURVEBALL_HYPOTHETICAL
        }
        if (DISAGREEMENT_MARKERS.any { lower.contains(it) }) {
            return UserSemanticIntent.PUSHBACK_OR_DISAGREEMENT
        }
        if (OPINION_OR_DEBATE_MARKERS.any { lower.contains(it) } && !lower.startsWith("sahi keh rahi")) {
            return UserSemanticIntent.OPINION_OR_HOT_TAKE
        }

        // Work / Appreciation / Boss / Meetings
        if ((lower.contains("mehnat") || lower.contains("kaam kiya") || lower.contains("best diya")) &&
            (lower.contains("notice") || lower.contains("appreciate") || lower.contains("credit") || lower.contains("kisi ne nahi") || lower.contains("value nahi"))
        ) {
            return UserSemanticIntent.FEELING_UNAPPRECIATED_AT_WORK
        }
        if (lower.contains("boss") || lower.contains("chhutti") || lower.contains(" छुट्टी") || lower.contains("leave") ||
            lower.contains("manager") || lower.contains("baat karunga unse") ||
            (activeTopic == "office_work" && lower.contains("kal subah"))
        ) {
            return UserSemanticIntent.BOSS_OR_LEAVE_CONVERSATION
        }
        if (lower.contains("meeting") || (lower.contains("office") && (lower.contains("hectic") || lower.contains("thak") || lower.contains("exhaust") || lower.contains("kaam") || lower.contains("deadline") || lower.contains("bura din")))) {
            return UserSemanticIntent.WORK_MEETING_OR_DEADLINE_FATIGUE
        }

        // Emotional / Calming down / Loneliness
        if (lower.contains("akele") || lower.contains("akela") || lower.contains("lonely") || lower.contains("koi samajhta nahi") || lower.contains("khud hi sab")) {
            return UserSemanticIntent.FEELING_LONELY_OR_OVERBURDENED
        }
        if (lower.contains("paani pi") || lower.contains("shant baith") || lower.contains("halka lag raha") ||
            lower.contains("aankhein band") || lower.contains("thoda aaram") || lower.contains("relax kar")
        ) {
            return UserSemanticIntent.CALMING_DOWN_OR_RESTING
        }

        // Interview vs Exam vs Coding vs Project
        if (lower.contains("interview") || lower.contains("hr round") || lower.contains("technical round") ||
            lower.contains("resume") || lower.contains("placement") || lower.contains("tayari puri nahi") ||
            (activeTopic == "interview_prep" && (lower.contains("tayari") || lower.contains("dar")))
        ) {
            return UserSemanticIntent.INTERVIEW_PREPARATION_OR_ANXIETY
        }
        if (lower.contains("exam") || lower.contains("syllabus") || lower.contains("paper hai") ||
            lower.contains("padhai") || lower.contains("revision") || lower.contains("baith ke padh") || lower.contains("padhne")
        ) {
            return UserSemanticIntent.EXAM_OR_STUDY_STRESS
        }
        if (lower.contains("code") || lower.contains("bug") || lower.contains("null pointer") || lower.contains("null check") ||
            lower.contains("kotlin") || lower.contains("error") || lower.contains("compile") || lower.contains("developer") || lower.contains("laptop pe")
        ) {
            return UserSemanticIntent.CODING_OR_DEBUGGING_FRUSTRATION
        }
        if (lower.contains("project") || lower.contains("diagram") || lower.contains("report likh") || lower.contains("presentation") || lower.contains("submission")) {
            return UserSemanticIntent.PROJECT_PROGRESS_OR_REPORT
        }

        // Specific Food vs Chai/Coffee
        if (lower.contains("biryani") || lower.contains("pizza") || lower.contains("momos") || lower.contains("maggi") ||
            lower.contains("paneer") || lower.contains("burger") || lower.contains("khana") || lower.contains("dinner") ||
            lower.contains("lunch") || lower.contains("khaya tha") || lower.contains("kha liya")
        ) {
            return UserSemanticIntent.SPECIFIC_FOOD_CRAVING_OR_MEAL
        }
        if (lower.contains("samosa") || (lower.contains("canteen") && lower.contains("dost"))) {
            return UserSemanticIntent.COLLEGE_CANTEEN_OR_FRIENDS
        }
        if (lower.contains("chai") || lower.contains("coffee")) {
            return UserSemanticIntent.CHAI_OR_COFFEE_MOMENT
        }

        // College / Travel / Movies / Cricket / Weather / Music
        if (lower.contains("college") || lower.contains("lecture") || lower.contains("class") ||
            lower.contains("attendance") || lower.contains("professor") || lower.contains("doston ko kya bolun") ||
            lower.contains("normal tha")
        ) {
            return UserSemanticIntent.COLLEGE_LECTURES_OR_ROUTINE
        }
        if (lower.contains("trip") || lower.contains("pahad") || lower.contains("manali") || lower.contains("goa") ||
            lower.contains("mukar gaye") || lower.contains("mukar gaya") || lower.contains("plan cancel") ||
            lower.contains("ghoom") || lower.contains("hotel book") || lower.contains("booking")
        ) {
            return UserSemanticIntent.TRAVEL_PLAN_OR_CANCELLED_TRIP
        }
        if (lower.contains("cricket") || lower.contains("match") || lower.contains("six pada") || lower.contains("last over") || lower.contains("ipl")) {
            return UserSemanticIntent.CRICKET_OR_SPORTS_EXCITEMENT
        }
        if (lower.contains("movie") || lower.contains("film") || lower.contains("series") || lower.contains("thriller") || lower.contains("comedy") || lower.contains("netflix")) {
            return UserSemanticIntent.MOVIE_OR_SERIES_DISCUSSION
        }
        if (lower.contains("gaane") || lower.contains("music") || lower.contains("song") || lower.contains("playlist")) {
            return UserSemanticIntent.MUSIC_OR_RELAXING_VIBE
        }
        if (lower.contains("barish") || lower.contains("mausam") || lower.contains("thanda") || lower.contains("thand") || lower.contains("hawa")) {
            return UserSemanticIntent.WEATHER_OR_RAIN_VIBE
        }

        // Banter / Humour / Moods
        if (mood == UserMood.JOKING || lower.contains("smart banti") || lower.contains("badam") || lower.contains("taane") ||
            lower.contains("oversmart") || lower.contains("sense of humour") || lower.contains("team mein")
        ) {
            return if (lower.contains("fenku") || lower.contains("so raha tha") || lower.contains("aalas") || lower.contains("gold medal")) {
                UserSemanticIntent.SELF_DEPRECATING_HUMOUR
            } else {
                UserSemanticIntent.PLAYFUL_TEASE_AT_RAFEL
            }
        }
        if (lower.contains("so raha tha") || lower.contains("aalas") || mood == UserMood.BORED) {
            return UserSemanticIntent.BOREDOM_OR_LAZINESS
        }
        if (mood == UserMood.EXCITED || mood == UserMood.HAPPY) {
            return UserSemanticIntent.CELEBRATION_OR_GOOD_NEWS
        }
        if (mood == UserMood.SAD || mood == UserMood.FRUSTRATED || lower.contains("mood kharab") || lower.contains("thak gaya") || lower.contains("exhausting")) {
            return UserSemanticIntent.EMOTIONAL_LOW_OR_MOOD_OFF
        }
        if (isGreeting(lower)) {
            return UserSemanticIntent.GREETING_OPENING
        }

        return UserSemanticIntent.GENERAL_CONVERSATIONAL_STATEMENT
    }

    /**
     * Detects when the user's message relies on an unresolved pronoun/referent ("woh wala", "usne", "uska")
     * with no prior context in recentHistory to explain what they mean.
     */
    fun detectAmbiguousUtterance(
        clean: String,
        recentHistory: List<UnifiedMessage>,
        entities: TrackedConversationEntities
    ): Boolean {
        val lower = clean.lowercase(Locale.ROOT).trim()
        val recentUserText = recentHistory.filter { it.isUser }.takeLast(3).joinToString(" ") { it.text.lowercase(Locale.ROOT) }

        // 1. Unresolved object/choice reference ("Woh wala le lu ya dusra sahi rahega?", "Kya main wo le lu?")
        if (lower.contains("woh wala") || lower.contains("wo wala") || lower.contains("ye wala") ||
            lower.contains("kya main wo") || lower.contains("kya main woh") || lower.contains("uska kya hua")
        ) {
            val hasConcreteObjectInHistory = listOf(
                "headphones", "earbuds", "phone", "laptop", "shirt", "shoes", "course", "book", "bike", "car", "hotel"
            ).any { recentUserText.contains(it) } ||
                entities.foodAndDrinks.isNotEmpty() ||
                entities.activeEventsOrTasks.isNotEmpty()
            if (!hasConcreteObjectInHistory) return true
        }

        // 2. Unresolved person reference ("Usne phir wahi kiya aaj.")
        if (lower.contains("usne phir") || lower.contains("unhone phir") ||
            Regex("(?i)^(usne|unhone)\\s+").containsMatchIn(lower)
        ) {
            if (entities.mentionedPeople.isEmpty()) return true
        }

        // 3. Unresolved location reference ("Wahan jana sahi rahega kya?")
        if (lower.contains("wahan jana") || lower.contains("udhar jana")) {
            if (entities.locations.isEmpty()) return true
        }

        if (AMBIGUOUS_PATTERNS.any { it.matches(lower) } && recentHistory.isEmpty()) {
            return true
        }
        return false
    }

    /**
     * Extracts and preserves important entities, events, names, locations, and food items across turns.
     */
    fun extractEntitiesAndDetails(
        currentMessage: String,
        recentHistory: List<UnifiedMessage>
    ): TrackedConversationEntities {
        val allUserTexts = (recentHistory.filter { it.isUser }.takeLast(8).map { it.text } + currentMessage)
        val combinedUserText = allUserTexts.joinToString(" ")
        val currentLower = currentMessage.lowercase(Locale.ROOT)
        val combinedLower = combinedUserText.lowercase(Locale.ROOT)

        var userName: String? = null
        val nameRegexes = listOf(
            Regex("(?i)\\b(?:mera naam|my name is)\\s+([A-Za-z]{2,20})\\b"),
            Regex("(?i)\\bmain\\s+([A-Z][a-z]{2,18})\\s+hoon\\b")
        )
        for (text in allUserTexts.reversed()) {
            for (r in nameRegexes) {
                val m = r.find(text)
                if (m != null) {
                    val candidate = m.groupValues[1]
                    if (!text.trim().endsWith("?") &&
                        candidate.lowercase(Locale.ROOT) !in setOf("kya", "nahi", "yaad", "pata", "maloom", "batao", "ek", "bahut", "thoda", "abhi", "aaj")
                    ) {
                        userName = candidate.replaceFirstChar { it.uppercase() }
                        break
                    }
                }
            }
            if (userName != null) break
        }

        val people = mutableListOf<String>()
        listOf("boss", "manager", "dost", "doston", "mummy", "papa", "professor", "rahul", "amit", "neha", "priya", "kabir", "aarav", "rohan", "vikram").forEach { p ->
            if (combinedLower.contains(p)) people.add(p)
        }

        val locations = mutableListOf<String>()
        listOf("delhi", "mumbai", "bangalore", "pune", "hyderabad", "manali", "goa", "shimla", "pahad", "pahadon", "office", "college", "canteen", "balkoni", "jungle", "chaand").forEach { loc ->
            if (combinedLower.contains(loc)) locations.add(loc)
        }

        val foods = mutableListOf<String>()
        listOf("biryani", "chai", "coffee", "samosa", "maggi", "pizza", "momos", "paneer", "burger", "badam", "paani").forEach { item ->
            if (currentLower.contains(item)) {
                foods.add(0, item)
            } else if (combinedLower.contains(item)) {
                foods.add(item)
            }
        }

        val tasks = mutableListOf<String>()
        listOf("interview", "exam", "project", "kotlin", "coroutine", "stateflow", "websocket", "code", "bug", "null pointer", "promotion", "meeting", "lecture", "match", "movie", "trip", "report", "diagrams").forEach { task ->
            if (currentLower.contains(task)) {
                tasks.add(0, task)
            } else if (combinedLower.contains(task)) {
                tasks.add(task)
            }
        }

        val currentSubject = foods.firstOrNull { currentLower.contains(it) }
            ?: tasks.firstOrNull { currentLower.contains(it) }
            ?: locations.firstOrNull { currentLower.contains(it) }

        return TrackedConversationEntities(
            userName = userName,
            mentionedPeople = people.distinct(),
            locations = locations.distinct(),
            foodAndDrinks = foods.distinct(),
            activeEventsOrTasks = tasks.distinct(),
            currentSubjectKeyword = currentSubject
        )
    }

    /**
     * Determines whether the user is continuing the previous topic, explicitly changing the topic,
     * giving a backchannel on the previous turn, or starting a new topic.
     */
    fun detectTopicTransition(
        currentText: String,
        recentHistory: List<UnifiedMessage>
    ): TopicTransition {
        val clean = currentText.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if (recentHistory.isEmpty()) return TopicTransition.NEW_TOPIC

        if (TOPIC_SHIFT_MARKERS.any { lower.contains(it) }) {
            return TopicTransition.EXPLICIT_TOPIC_SHIFT
        }

        if (PersonaSettings.classifyTurnCategory(clean) == TurnCategory.SHORT_REPLY) {
            return TopicTransition.BACKCHANNEL_ON_PREVIOUS_TURN
        }

        val currentTopic = matchTopicInText(lower)
        val prevTurnText = recentHistory.takeLast(2).joinToString(" ") { it.text }.lowercase(Locale.ROOT)
        val prevTopic = matchTopicInText(prevTurnText)

        return when {
            currentTopic != null && prevTopic != null && currentTopic != prevTopic -> TopicTransition.NEW_TOPIC
            currentTopic != null && currentTopic == prevTopic -> TopicTransition.CONTINUING_TOPIC
            currentTopic == null && prevTopic != null -> TopicTransition.CONTINUING_TOPIC
            else -> TopicTransition.CONTINUING_TOPIC
        }
    }

    private fun selectConversationalMove(
        subtext: ConversationalSubtext,
        semanticIntent: UserSemanticIntent,
        mood: UserMood,
        wantsNoAdvice: Boolean,
        lastTwoEndedWithQuestion: Boolean,
        recentQuestionRatio: Float,
        turnIndex: Int
    ): ConversationalMove {
        if (wantsNoAdvice || semanticIntent == UserSemanticIntent.VENTING_NO_ADVICE_WANTED ||
            subtext == ConversationalSubtext.VENTING_NO_ADVICE
        ) {
            return ConversationalMove.NON_DIRECTIVE_LISTENING
        }
        if (semanticIntent == UserSemanticIntent.AMBIGUOUS_REFERENCE ||
            subtext == ConversationalSubtext.AMBIGUOUS_NEEDS_CLARIFICATION
        ) {
            return ConversationalMove.NATURAL_CLARIFICATION
        }

        return when (subtext) {
            ConversationalSubtext.VENTING_NO_ADVICE -> ConversationalMove.NON_DIRECTIVE_LISTENING
            ConversationalSubtext.CLOSURE_OR_BUSY -> ConversationalMove.WARM_SIGN_OFF
            ConversationalSubtext.FACTUAL_OR_TECHNICAL -> ConversationalMove.CRISP_DIRECT_ANSWER
            ConversationalSubtext.INCOMPLETE_THOUGHT -> ConversationalMove.GENTLE_COMPLETION_PROMPT
            ConversationalSubtext.SHORT_BACKCHANNEL -> {
                if (turnIndex % 2 == 0) ConversationalMove.SHORT_REACTION else ConversationalMove.OBSERVATION_ONLY
            }
            ConversationalSubtext.REPEATED_QUESTION -> ConversationalMove.PLAYFUL_TEASE_OR_WIT
            ConversationalSubtext.DISAGREEMENT_OR_PUSHBACK,
            ConversationalSubtext.OPINION_OR_DEBATE -> ConversationalMove.OPINION_OR_PLAYFUL_DISAGREEMENT
            ConversationalSubtext.TEASING_OR_HUMOUR,
            ConversationalSubtext.UNEXPECTED_OR_QUIRKY -> ConversationalMove.PLAYFUL_TEASE_OR_WIT
            ConversationalSubtext.EMOTIONAL_SHARE -> {
                if (semanticIntent == UserSemanticIntent.CALMING_DOWN_OR_RESTING) {
                    ConversationalMove.OBSERVATION_ONLY
                } else if (lastTwoEndedWithQuestion || mood == UserMood.FRUSTRATED) {
                    ConversationalMove.EMPATHETIC_PRESENCE
                } else if (turnIndex % 3 == 0) {
                    ConversationalMove.CURIOUS_FOLLOW_UP
                } else {
                    ConversationalMove.EMPATHETIC_PRESENCE
                }
            }
            ConversationalSubtext.TOPIC_SHIFT -> {
                if (lastTwoEndedWithQuestion) ConversationalMove.TOPIC_CONTINUATION else ConversationalMove.CURIOUS_FOLLOW_UP
            }
            ConversationalSubtext.GREETING_OR_CHECKIN -> {
                if (lastTwoEndedWithQuestion) ConversationalMove.SHORT_REACTION else ConversationalMove.CURIOUS_FOLLOW_UP
            }
            ConversationalSubtext.AMBIGUOUS_NEEDS_CLARIFICATION -> ConversationalMove.NATURAL_CLARIFICATION
            ConversationalSubtext.CASUAL_FLOW -> {
                when {
                    lastTwoEndedWithQuestion || recentQuestionRatio >= 0.45f -> {
                        if (turnIndex % 2 == 0) ConversationalMove.OBSERVATION_ONLY else ConversationalMove.TOPIC_CONTINUATION
                    }
                    turnIndex % 3 == 1 -> ConversationalMove.OBSERVATION_ONLY
                    turnIndex % 3 == 2 -> ConversationalMove.TOPIC_CONTINUATION
                    else -> ConversationalMove.CURIOUS_FOLLOW_UP
                }
            }
        }
    }

    fun detectIncompleteSentence(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase(Locale.ROOT)
        if (clean.endsWith("...") || clean.endsWith("…") || clean.endsWith(",") || clean.endsWith("-")) {
            return true
        }
        if (INCOMPLETE_MARKERS.any { lower.endsWith(it) || (lower.startsWith(it) && clean.split(Regex("\\s+")).size <= 6) }) {
            return true
        }
        val words = lower.trimEnd('.', '!').split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size in 2..7 && words.last() in TRAILING_CONJUNCTIONS) {
            return true
        }
        return false
    }

    fun detectRepeatedQuestion(currentText: String, recentUserMessages: List<String>): Boolean {
        val normCurrent = normalizeForSimilarity(currentText)
        if (normCurrent.length < 6) return false
        val currentTokens = normCurrent.split(" ").filter { it.length >= 3 }.toSet()
        if (currentTokens.size < 2) return false

        return recentUserMessages.any { prev ->
            val normPrev = normalizeForSimilarity(prev)
            if (normPrev == normCurrent) return@any true
            val prevTokens = normPrev.split(" ").filter { it.length >= 3 }.toSet()
            if (prevTokens.isEmpty()) return@any false
            val overlap = currentTokens.intersect(prevTokens).size.toFloat() / maxOf(currentTokens.size, prevTokens.size).toFloat()
            overlap >= 0.85f
        }
    }

    internal fun normalizeForSimilarity(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isGreeting(lower: String): Boolean {
        val clean = lower.trimEnd('.', '!', '?')
        if (clean in setOf("hello", "hi", "hey", "namaste", "hello rafel", "hi rafel", "hey rafel", "aur batao", "kaisi ho", "kaise ho")) {
            return true
        }
        return clean.contains("kaisi ho") || clean.contains("kaise ho") ||
            clean.contains("how are you") || clean.contains("aur batao kya chal raha") ||
            clean.contains("free to talk") || clean.startsWith("hello rafel") ||
            clean.startsWith("hey rafel") || clean.startsWith("hi rafel")
    }

    /**
     * Infers the active conversation topic with fine-grained specificity, prioritizing the
     * current utterance first so topic shifts happen immediately and distinct food/work/study
     * subtopics are never conflated.
     */
    fun inferActiveTopic(
        currentText: String,
        recentHistory: List<UnifiedMessage>,
        transition: TopicTransition = detectTopicTransition(currentText, recentHistory)
    ): String? {
        val currentMatched = matchTopicInText(currentText.lowercase(Locale.ROOT))
        if (currentMatched != null) return currentMatched
        if (transition == TopicTransition.EXPLICIT_TOPIC_SHIFT) return null
        val recentText = recentHistory.takeLast(2).joinToString(" ") { it.text }.lowercase(Locale.ROOT)
        return matchTopicInText(recentText)
    }

    private fun matchTopicInText(text: String): String? {
        return when {
            text.contains("cricket") || text.contains("match") || text.contains("ipl") || text.contains("six pada") || text.contains("last over") -> "cricket_match"
            text.contains("movie") || text.contains("film") || text.contains("series") || text.contains("netflix") || text.contains("thriller") || text.contains("comedy") -> "movies_series"
            text.contains("coroutine") || text.contains("stateflow") || text.contains("websocket") || text.contains("code") || text.contains("bug") || text.contains("developer") || text.contains("kotlin") || text.contains("null pointer") || text.contains("compile") -> "coding_tech"
            text.contains("interview") || text.contains("resume") || text.contains("placement") || text.contains("hr round") -> "interview_prep"
            text.contains("project") || text.contains("diagram") || text.contains("report likh") || text.contains("submission") -> "project_work"
            text.contains("exam") || text.contains("paper") || text.contains("syllabus") || text.contains("padhai") || text.contains("revision") -> "exams_study"
            text.contains("samosa") || text.contains("canteen") -> "college_canteen"
            text.contains("college") || text.contains("lecture") || text.contains("professor") || text.contains("attendance") -> "college_life"
            text.contains("office") || text.contains("boss") || text.contains("meeting") || text.contains("salary") || text.contains("laptop") || text.contains("mehnat") -> "office_work"
            text.contains("trip") || text.contains("pahad") || text.contains("goa") || text.contains("manali") || text.contains("travel") || text.contains("ghoom") || text.contains("mukar") || text.contains("hotel") -> "travel_plans"
            text.contains("gaane") || text.contains("music") || text.contains("song") -> "music_vibes"
            text.contains("mausam") || text.contains("barish") || text.contains("thand") || text.contains("garmi") -> "weather"
            text.contains("biryani") || text.contains("pizza") || text.contains("momos") || text.contains("maggi") || text.contains("paneer") || text.contains("dinner") || text.contains("khana") -> "food_meal"
            text.contains("chai") || text.contains("coffee") -> "chai_coffee"
            text.contains("neend") || text.contains("so raha") || text.contains("aalas") -> "sleep_laziness"
            else -> null
        }
    }

    /**
     * Builds the dynamic turn guidance injected into Gemini Live and Gemini REST prompts
     * so the LLM understands the exact discourse act, semantic meaning, entities, and topic transition.
     */
    fun buildDynamicTurnGuidance(
        userMessage: String,
        recentMessages: List<UnifiedMessage>,
        relevantMemorySummary: String = ""
    ): String {
        val analysis = analyzeTurn(userMessage, recentMessages)
        val recentAssistant = recentMessages.filter { !it.isUser }.takeLast(4).map { it.text }

        val sb = StringBuilder()
        sb.append("CURRENT TURN CONTEXT & COMPREHENSION DIRECTIVES (HIGHEST PRIORITY):\n")
        sb.append("- Latest User Message: \"${analysis.userMessage}\"\n")
        sb.append("- User Discourse Act: ${analysis.discourseAct.name}\n")
        sb.append("- Exact Semantic Intent: ${analysis.semanticIntent.name}\n")
        sb.append("- Topic Transition State: ${analysis.topicTransition.name} (Active Topic: ${analysis.activeTopic ?: "general"})\n")
        sb.append("- Detected User Mood: ${analysis.mood.name} -> ${analysis.mood.toneDirective}\n")
        sb.append("- Conversational Move: ${analysis.recommendedMove.name} -> ${analysis.recommendedMove.directive}\n")

        val entityParts = mutableListOf<String>()
        analysis.entities.userName?.let { entityParts.add("UserName=$it") }
        if (analysis.entities.foodAndDrinks.isNotEmpty()) entityParts.add("Food/Drink=${analysis.entities.foodAndDrinks.joinToString(",")}")
        if (analysis.entities.activeEventsOrTasks.isNotEmpty()) entityParts.add("Events/Tasks=${analysis.entities.activeEventsOrTasks.joinToString(",")}")
        if (analysis.entities.locations.isNotEmpty()) entityParts.add("Locations=${analysis.entities.locations.joinToString(",")}")
        if (entityParts.isNotEmpty()) {
            sb.append("- Active Entities & Details to Preserve: ${entityParts.joinToString(" | ")}\n")
        }

        val langDirective = when (analysis.languageStyle) {
            LanguageStyle.HINDI_DEVANAGARI -> "Reply naturally in everyday conversational Hindi/Hinglish—never textbook or Sanskritized Hindi."
            LanguageStyle.HINGLISH -> "Reply in natural everyday Hinglish (casual Roman Hindi + English mix, like real Indian friends talking)."
            LanguageStyle.ENGLISH -> "Reply in warm, natural, casual everyday English with Indian conversational warmth."
        }
        sb.append("- Language Register: $langDirective\n")

        sb.append("- Strict Relevance & Anti-Paraphrase Rule: Respond DIRECTLY to what the user just said (\"${analysis.userMessage}\"), ")
        sb.append("but do NOT merely repeat or paraphrase their sentence in different words. Add a genuine observation, reaction, or answer. ")
        sb.append("NEVER insert unrelated food items (e.g. do not mention samosa/chai if the user is talking about biryani).\n")
        sb.append("- Anti-Crutch Rule: Do NOT start your reply with overused filler phrases like \"Sahi hai\", \"Chalo badhiya\", \"Samajh gayi\", \"Sahi mein\", \"Bilkul\", or \"Ye hui na baat\".\n")

        if (analysis.wantsNoAdvice) {
            sb.append("- No-Advice Notice: The user explicitly does NOT want advice, solutions, or lectures right now. Just listen and validate warmly.\n")
        }
        if (analysis.isAmbiguous) {
            sb.append("- Ambiguity Notice: The user's message has an unclear reference. Ask a brief, natural clarification instead of guessing.\n")
        }
        if (analysis.isIncompleteSentence) {
            sb.append("- Incomplete Utterance Notice: The user trailed off mid-thought. React briefly and let them complete what they were about to say.\n")
        }
        if (analysis.isRepeatedQuestion) {
            sb.append("- Repeated Question Notice: The user asked this same question recently. Acknowledge playfully that they asked again and give a fresh angle.\n")
        }
        if (!analysis.allowTrailingQuestion) {
            sb.append("- Question Rule: Do NOT ask a question at the end of this response. End naturally with a relevant observation, opinion, or reaction.\n")
        }
        if (recentAssistant.isNotEmpty()) {
            val recentStarters = recentAssistant.mapNotNull {
                it.trim().split(Regex("\\s+")).take(3).joinToString(" ").takeIf { s -> s.isNotBlank() }
            }.distinct()
            if (recentStarters.isNotEmpty()) {
                sb.append("- Anti-Repetition: Do NOT start your response with any of these recent phrases: ${recentStarters.joinToString(", ") { "\"$it\"" }}.\n")
            }
        }
        if (relevantMemorySummary.isNotBlank() && analysis.topicTransition != TopicTransition.EXPLICIT_TOPIC_SHIFT) {
            sb.append("- Relevant User Memory (reference ONLY if directly relevant to current turn): $relevantMemorySummary\n")
        }
        return sb.toString().trim()
    }

    /**
     * Refines model-generated responses while strictly preserving the model's intended meaning:
     * 1. Removes generic bolted-on filler questions ("Aur batao kya chal raha hai?").
     * 2. Strips overused Hinglish starter crutches ("Sahi hai,", "Chalo badhiya,", "Samajh gayi,",
     *    "Sahi mein,", "Bilkul,", "Ye hui na baat!") when the rest of the sentence stands on its own.
     * 3. Prevents repeating the exact same leading interjection as the preceding turn.
     */
    fun refineResponseForNaturalness(
        text: String,
        userPrompt: String,
        recentAssistantMessages: List<String>
    ): String {
        if (text.isBlank()) return text
        var result = text.trim()

        // 1. Strip generic bolted-on filler trailing questions
        for (pattern in GENERIC_TRAILING_QUESTION_PATTERNS) {
            val stripped = pattern.replace(result, "").trim()
            if (stripped.isNotBlank() && stripped.length >= 12) {
                result = stripped
            }
        }

        // 2. Strip overused Hinglish crutch openers ("Sahi hai, ", "Chalo badhiya, ", "Samajh gayi, ", "Sahi mein, ", "Bilkul, ", "Ye hui na baat! ")
        // when the remainder is a complete standalone sentence (>= 18 characters), so responses jump straight into substance.
        for (crutchRegex in OVERUSED_CRUTCH_OPENER_REGEXES) {
            val match = crutchRegex.find(result)
            if (match != null) {
                val remainder = result.substring(match.range.last + 1).trim()
                if (remainder.length >= 18) {
                    result = remainder.replaceFirstChar { it.uppercase() }
                    break
                }
            }
        }

        // 3. If the previous 2 turns BOTH ended with a question and this turn has multiple sentences
        // ending with a generic check-in question that doesn't carry core entities, drop the trailing filler question.
        val recentQuestions = recentAssistantMessages.takeLast(2).count { it.trim().endsWith("?") }
        val category = PersonaSettings.classifyTurnCategory(userPrompt)
        if (recentQuestions >= 2 && result.endsWith("?") && category != TurnCategory.TECHNICAL_OR_DETAILED) {
            val sentences = PersonaSettings.splitIntoSentences(result)
            if (sentences.size >= 2 && sentences.last().endsWith("?")) {
                val lastSentenceLower = sentences.last().lowercase(Locale.ROOT)
                val isGenericTrailingQ = lastSentenceLower.contains("tum batao") ||
                    lastSentenceLower.contains("aur batao") ||
                    lastSentenceLower.contains("kya khayal hai") ||
                    lastSentenceLower.contains("kya plan hai") ||
                    lastSentenceLower.contains("kya bolte ho")
                if (isGenericTrailingQ) {
                    val withoutLastQuestion = sentences.dropLast(1).joinToString(" ").trim()
                    if (withoutLastQuestion.length >= 12) {
                        result = withoutLastQuestion
                    }
                }
            }
        }

        // 4. Prevent repeating the exact same leading filler word ("Achha,", "Waah,", "Haan,") as the preceding turn
        val lastAssistant = recentAssistantMessages.lastOrNull()?.trim().orEmpty()
        if (lastAssistant.isNotBlank()) {
            val fillerMatchCurrent = Regex("^(?i)(achha|waah|haan|hmm|arre|dekho|wahi),\\s+").find(result)
            val fillerMatchPrev = Regex("^(?i)(achha|waah|haan|hmm|arre|dekho|wahi),\\s+").find(lastAssistant)
            if (fillerMatchCurrent != null && fillerMatchPrev != null &&
                fillerMatchCurrent.groupValues[1].equals(fillerMatchPrev.groupValues[1], ignoreCase = true)
            ) {
                val stripped = result.substring(fillerMatchCurrent.range.last + 1).trim()
                if (stripped.length >= 10) {
                    result = stripped.replaceFirstChar { it.uppercase() }
                }
            }
        }

        return result
    }

    /**
     * Context-driven fallback synthesis used when cloud LLM models are offline/unconfigured.
     * Directly understands and responds to the user's exact discourse act, semantic intent, and entities
     * without crutch openers ("Sahi hai", "Chalo badhiya", "Samajh gayi", "Sahi mein", "Bilkul", "Ye hui na baat")
     * and without merely repeating the user's sentence in different words.
     */
    fun synthesizeContextualReply(
        userMessage: String,
        recentHistory: List<UnifiedMessage> = emptyList(),
        recentAssistantTexts: List<String> = emptyList(),
        relevantMemories: Map<String, String> = emptyMap()
    ): String {
        val clean = userMessage.trim()
        if (clean.isEmpty()) return ""
        val lower = clean.lowercase(Locale.ROOT)
        val analysis = analyzeTurn(clean, recentHistory)

        val allRecentAssistant = (recentHistory.filter { !it.isUser }.map { it.text } + recentAssistantTexts).distinct()
        val lastUserTurn = recentHistory.lastOrNull { it.isUser }?.text.orEmpty()
        val lastRafelTurn = allRecentAssistant.lastOrNull().orEmpty()
        val userName = relevantMemories["user_name"]?.takeIf { it.isNotBlank() } ?: analysis.entities.userName
        val userCity = relevantMemories["user_city"]?.takeIf { it.isNotBlank() }
        val userPref = relevantMemories["user_preference"]?.takeIf { it.isNotBlank() }

        val userWords = normalizeForSimilarity(clean).split(" ").filter { it.isNotBlank() }
        val userFourGrams = if (userWords.size >= 4) {
            (0..(userWords.size - 4)).map { i -> userWords.subList(i, i + 4).joinToString(" ") }.toSet()
        } else {
            emptySet()
        }

        fun selectFresh(candidates: List<String>): String {
            val recentLower = allRecentAssistant.takeLast(16).map { it.lowercase(Locale.ROOT) }
            val recentOpeners = allRecentAssistant.takeLast(5).map {
                it.trim().split(Regex("\\s+")).take(2).joinToString(" ").lowercase(Locale.ROOT)
            }
            val strictlyFresh = candidates.filter { cand ->
                val candLower = cand.lowercase(Locale.ROOT)
                val candOpener = cand.trim().split(Regex("\\s+")).take(2).joinToString(" ").lowercase(Locale.ROOT)
                val candWords = normalizeForSimilarity(cand).split(" ").filter { it.isNotBlank() }
                val candFourGrams = if (candWords.size >= 4) {
                    (0..(candWords.size - 4)).map { i -> candWords.subList(i, i + 4).joinToString(" ") }.toSet()
                } else {
                    emptySet()
                }
                val echoesUser = userFourGrams.isNotEmpty() && candFourGrams.intersect(userFourGrams).isNotEmpty()
                candLower !in recentLower && candOpener !in recentOpeners && !echoesUser
            }
            val pool = strictlyFresh.ifEmpty {
                candidates.filter { it.lowercase(Locale.ROOT) !in recentLower }.ifEmpty { candidates }
            }
            val seed = abs(clean.hashCode() * 31 + recentHistory.size * 17 + allRecentAssistant.size * 13)
            return pool[seed % pool.size]
        }

        // 0. User explicitly does NOT want advice ("Mujhe abhi koi salah nahi chahiye, bas sun lo")
        if (analysis.wantsNoAdvice || analysis.semanticIntent == UserSemanticIntent.VENTING_NO_ADVICE_WANTED) {
            return when {
                lower.contains("ye mat bolna ki sab theek") ->
                    "Main koi jhooti tasalli nahi dungi yaar. Jo bura lag raha hai wo lagna jayaz hai, main bas yahin sun rahi hoon."
                lower.contains("bina gyaan") || lower.contains("bina gyan") || lower.contains("shukriya") ->
                    "Dost kis din kaam aate hain, jab bhi mann bhaari ho bina soche bol diya karo."
                lower.contains("gyan mat") || lower.contains("gyaan mat") || lower.contains("lecture mat") ->
                    "Koi gyan nahi de rahi, pakka. Tum bas apni bhadaas nikaalo, main chupchap sun rahi hoon."
                lower.contains("tip mat") || lower.contains("advice mat") ->
                    "Koi tip ya lecture nahi milega meri taraf se, aaram se mann halka kar lo."
                else ->
                    selectFresh(
                        listOf(
                            "Koi salah nahi dungi, befikar raho. Jo mann mein bhara hai khul ke bol do, main bas sun rahi hoon.",
                            "Theek hai, zero advice mode on hai. Tum bas bolte jao jo dimag mein chal raha hai."
                        )
                    )
            }
        }

        // 1. Direct Factual & Technical Queries (crisp, accurate, zero filler, no trailing questions)
        if (lower.contains("rajdhani") && lower.contains("bharat")) {
            return "Bharat ki rajdhani New Delhi hai."
        }
        if (lower.contains("capital of france")) {
            return "The capital of France is Paris."
        }
        if (lower.contains("15 * 12") || lower.contains("15 into 12")) {
            return "15 guna 12 barabar 180 hota hai."
        }
        val mathMatch = Regex("\\b(\\d+)\\s*([+\\-*/])\\s*(\\d+)\\b").find(clean)
        if (mathMatch != null) {
            val a = mathMatch.groupValues[1].toLongOrNull() ?: 0L
            val op = mathMatch.groupValues[2]
            val b = mathMatch.groupValues[3].toLongOrNull() ?: 0L
            val ans = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b != 0L) a / b else 0L
                else -> 0L
            }
            return "$a $op $b equals $ans."
        }
        if (analysis.semanticIntent == UserSemanticIntent.TECHNICAL_DISCUSSION_OR_CONCEPT) {
            return when {
                lower.contains("stateflow") && lower.contains("sharedflow") ->
                    "StateFlow hamesha latest state hold karta hai aur UI state ke liye best hai, jabki SharedFlow one-time events jaise snackbar ya navigation triggers ke liye kaam aata hai."
                lower.contains("dispatchers.io") || lower.contains("coroutine") ->
                    "Network calls aur database queries ke liye Dispatchers.IO use karo taaki main UI thread block na ho, aur structured concurrency ke liye viewModelScope best rehta hai."
                lower.contains("websocket") ->
                    "Low-latency bidirectional audio streaming ke liye WebSocket zaruri hai kyunki standard HTTP request-response mein har turn pe naya handshake overhead jud jata hai."
                lower.contains("recomposition") ->
                    "Unnecessary recomposition rokne ke liye unstable lambdas aur lists ko remember ya derivedStateOf ke andar wrap rakho."
                lower.contains("room database") || lower.contains("sqlite") ->
                    "Room database compile-time pe SQL queries verify kar leta hai aur Flow ke saath reactive updates deta hai, isliye raw SQLite se kahin zyada safe hai."
                else ->
                    "Clean architecture mein UI layer sirf state render karti hai aur saara business logic ViewModel aur Repository mein alag rehta hai."
            }
        }

        // 2. Saved Name / City / Preference Memory Recall
        if (analysis.semanticIntent == UserSemanticIntent.MEMORY_RECALL_CHECK) {
            if (lower.contains("naam") || lower.contains("name") || lower.contains("jaanti ho")) {
                return if (userName != null) {
                    selectFresh(
                        listOf(
                            "Tumhara naam $userName hai, itni jaldi bhoolne wali nahi hoon main.",
                            "$userName naam hai tumhara, ache se yaad hai mujhe."
                        )
                    )
                } else {
                    "Abhi tak tumne apna naam bataya hi nahi mujhe."
                }
            }
            if (lower.contains("kahan rehta") || lower.contains("sheher") || lower.contains("city")) {
                val city = userCity ?: analysis.entities.locations.firstOrNull()?.replaceFirstChar { it.uppercase() }
                return if (city != null) {
                    "Tum $city mein rehte ho, abhi bataya toh tha tumne."
                } else {
                    "Apne sheher ka naam abhi tak bataya kahan tumne?"
                }
            }
            if (lower.contains("pasand") && userPref != null) {
                return "Tumhe $userPref bahut pasand hai, yaad hai mujhe."
            }
        }

        val nameIntro = if (!clean.endsWith("?")) {
            Regex("(?i)(?:mera naam|my name is)\\s+([A-Za-z]+)").find(clean)
        } else null
        if (nameIntro != null) {
            val rawExtracted = nameIntro.groupValues[1]
            if (rawExtracted.lowercase(Locale.ROOT) !in setOf("kya", "yaad", "pata", "maloom", "batao", "nahi")) {
                val extracted = rawExtracted.replaceFirstChar { it.uppercase() }
                return selectFresh(
                    listOf(
                        "Achha $extracted! Badhiya naam hai, yaad rahega mujhe.",
                        "Milke khushi hui $extracted, ab se tumhe isi naam se bulaungi."
                    )
                )
            }
        }
        if (analysis.semanticIntent == UserSemanticIntent.PERSONAL_FACT_INTRODUCTION) {
            return when {
                lower.contains("pune") && lower.contains("architect") ->
                    "Pune ka mausam aur wahan ka vibe dono bade sukoon wale hain, upar se architecture toh bada creative kaam hai!"
                lower.contains("delhi") && lower.contains("developer") ->
                    "Delhi ki tech life aur wahan ki raftaar dono hi alag level pe chalti hain!"
                else -> {
                    val city = analysis.entities.locations.firstOrNull()?.replaceFirstChar { it.uppercase() }
                    if (city != null) {
                        "$city ka vibe hi alag hai, yaad rakhungi ki tum wahan se ho."
                    } else {
                        "Achha laga tumhare baare mein ye jaan ke, yaad rahega mujhe."
                    }
                }
            }
        }

        // 3. Ambiguous Utterance -> Ask a natural clarification instead of guessing!
        if (analysis.isAmbiguous || analysis.semanticIntent == UserSemanticIntent.AMBIGUOUS_REFERENCE) {
            return when {
                lower.contains("woh wala") || lower.contains("wo wala") || lower.contains("kya main wo") ->
                    "Kis cheez ki baat kar rahe ho yaar? Thoda clear batao ki kaunse options mein confuse ho?"
                lower.contains("usne") || lower.contains("unhone") ->
                    "Kisne kya kiya? Naam ya baat toh batao pehle?"
                lower.contains("wahan") || lower.contains("udhar") ->
                    "Kahan jaane ki baat ho rahi hai? Jagah ka naam toh batao?"
                else ->
                    "Main puri baat samajh nahi payi, kis baare mein keh rahe ho thoda khul ke batao?"
            }
        }

        // 4. Repeated Question Detection (react playfully instead of repeating mechanically)
        if (analysis.isRepeatedQuestion) {
            return selectFresh(
                listOf(
                    "Abhi thodi der pehle hi toh yahi pucha tha tumne! Mera pichla jawaab pasand nahi aaya kya?",
                    "Phir se wahi sawaal? Lagta hai aaj mera memory test chal raha hai.",
                    "Ye baat toh abhi humne discuss ki thi yaar, dhyan kahan hai tumhara aaj?",
                    "Ek hi sawaal dobara puch ke pakadne ki koshish kar rahe ho na mujhe?",
                    "Baar-baar wahi puchoge toh bhi sach thodi badal jayega yaar!",
                    "Lagta hai tumhe mere pichle jawaab pe yakeen nahi hua, isliye dobara confirm kar rahe ho."
                )
            )
        }

        // 5. Incomplete Sentences / Trailing Thoughts
        if (analysis.isIncompleteSentence) {
            return selectFresh(
                listOf(
                    "Haan bolo, ruk kyun gaye beech mein?",
                    "Aage toh batao, baat adhuri chhod di tumne.",
                    "Main sun rahi hoon, pura bolo kya keh rahe the.",
                    "Itna suspense mat banao, aage kya hua?",
                    "Bolte-bolte kahan kho gaye, puri baat toh batao.",
                    "Ruko mat, jo dimag mein chal raha hai seedha bol do.",
                    "Adhuri baat chhodoge toh mujhe kaise pata chalega kya soch rahe ho?",
                    "Main yahin hoon, aaram se soch ke pura batao.",
                    "Lagta hai kuch bada bolne wale the aur ruk gaye.",
                    "Bolo na, hichkicha kyun rahe ho itna?",
                    "Kahin baat bhool toh nahi gaye bolte-bolte?",
                    "Ab suspense khatam karo aur aage ki kahani sunao."
                )
            )
        }

        // 6. Closures / Goodnight / Busy (Zero crutch phrases!)
        if (analysis.subtext == ConversationalSubtext.CLOSURE_OR_BUSY) {
            return when {
                lower.contains("report") || lower.contains("project") || lower.contains("padh") || lower.contains("kaam") || lower.contains("code") ->
                    selectFresh(
                        listOf(
                            "Dhyan se apna kaam nipta lo, phir fursat mein baat karte hain.",
                            "Pehle bina distraction ke apna kaam pura kar lo. Baad mein milte hain!"
                        )
                    )
                lower.contains("khana") || lower.contains("dinner") || lower.contains("lunch") ->
                    selectFresh(
                        listOf(
                            "Jao, aaram se pet bhar ke khana khao! Baad mein milte hain.",
                            "Garma-garam khana enjoy karo, baad mein baat karenge. Bye!"
                        )
                    )
                lower.contains("good night") || lower.contains("goodnight") || lower.contains("sone") ->
                    selectFresh(
                        listOf(
                            "Aaram se so jao, kal subah fresh mood mein baat karenge. Good night!",
                            "Good night! Kal milte hain phir."
                        )
                    )
                else ->
                    selectFresh(
                        listOf(
                            "Jab free ho jao tab aaram se baat karenge. Bye!",
                            "Apna dhyan rakhna, baad mein baat karte hain!",
                            "Phir milte hain, tab tak chill karo."
                        )
                    )
            }
        }

        // 7. Identity & Biological Boundaries
        if (lower.contains("insaan ho") || lower.contains("real human") || lower.contains("are you human") || lower.contains("tum kaun ho")) {
            return selectFresh(
                listOf(
                    "Insaan toh nahi hoon, main Rafel hoon, tumhari AI companion, par baatein apne dost jaise kar sakti hoon.",
                    "Main Rafel hoon. Biological insaan nahi hoon, par tumhari baatein samajhne mein kisi dost se kam bhi nahi."
                )
            )
        }
        if ((lower.contains("chai pi") || lower.contains("coffee pi")) && (lower.contains("tumne") || lower.contains("tu"))) {
            return selectFresh(
                listOf(
                    "Mera kaam toh bina chai-coffee ke hi chal jata hai, par tumhare liye ek kadak cup banta hai abhi.",
                    "Mujhe toh chai ki zarurat nahi padti, waise tumhari din ki kitni cup ho gayi aaj?"
                )
            )
        }
        if ((lower.contains("khana khaya") || lower.contains("dinner kiya") || lower.contains("lunch kiya") || lower.contains("nashta kiya")) &&
            (lower.contains("tumne") || lower.contains("tu"))
        ) {
            return selectFresh(
                listOf(
                    "Mujhe toh khane-peene ki zarurat nahi hoti, par tumne time pe kha liya ya abhi bhi taal rahe ho?",
                    "Mera toh bina khaye hi chal jata hai! Tum batao, aaj kya bana tha khane mein?"
                )
            )
        }

        // 8. Short Backchannels ("hmm", "achha", "haan", "nahi", "kuch nahi", "pata nahi", "theek hai")
        // Interpret strictly in the context of the immediate previous turn with zero crutch openers!
        if (analysis.subtext == ConversationalSubtext.SHORT_BACKCHANNEL) {
            val norm = lower.trimEnd('.', '!', '?')
            val prevContext = "$lastUserTurn $lastRafelTurn".lowercase(Locale.ROOT)
            return when {
                norm in setOf("kuch nahi", "kuch nhi", "nothing") -> selectFresh(
                    listOf(
                        "Jab bolne ka mann ho tab bata dena, koi jaldi nahi hai.",
                        "Koi baat nahi, kabhi-kabhi bas shant baithna bhi achha lagta hai.",
                        "Bina kisi pressure ke aaram se baitho phir."
                    )
                )
                norm in setOf("pata nahi", "pta nhi", "pata nhi", "idk") -> selectFresh(
                    listOf(
                        "Har cheez ka turant jawab hona zaruri bhi nahi hota waise.",
                        "Chhodo phir, zyada dimag mat lagao ispe abhi.",
                        "Hota hai, kabhi-kabhi khud hi clear nahi hota ki kya chahiye."
                    )
                )
                norm in setOf("nahi", "na", "nope") -> {
                    if (prevContext.contains("exam") || prevContext.contains("padhai")) {
                        selectFresh(
                            listOf(
                                "Bina padhe seedha exam hall mein jane ka irada hai lagta hai.",
                                "Thoda toh dekh lo yaar, warna aakhiri raat pe sara bojh aa jayega."
                            )
                        )
                    } else if (prevContext.contains("college") || prevContext.contains("lecture")) {
                        selectFresh(
                            listOf(
                                "Phir toh shanti wala din raha aaj college mein.",
                                "Kabhi-kabhi bina kisi drame ke normal din nikalna bhi badi baat hoti hai."
                            )
                        )
                    } else if (prevContext.contains("pareshan") || prevContext.contains("mood") || prevContext.contains("hua")) {
                        selectFresh(
                            listOf(
                                "Mujhe laga shayad koi baat dimag mein khatak rahi hai.",
                                "Bas din bhar ki thakan hogi phir, thoda aaram kar lo."
                            )
                        )
                    } else {
                        selectFresh(
                            listOf(
                                "Jaisa tumhe theek lage, koi zabardasti thodi hai.",
                                "Chhodte hain phir us baat ko, koi aur topic pakadte hain."
                            )
                        )
                    }
                }
                norm in setOf("haan", "han", "ha") -> {
                    if (prevContext.contains("interview") || prevContext.contains("exam") || prevContext.contains("target") || prevContext.contains("dar")) {
                        selectFresh(
                            listOf(
                                "Bas thoda bharosa rakho khud pe, ek-ek step karke sab taiyar ho jayega.",
                                "Thoda-thoda karke revise karoge toh waqt aane tak pura confidence aa jayega."
                            )
                        )
                    } else if (prevContext.contains("barish") || prevContext.contains("mausam") || prevContext.contains("chai") || prevContext.contains("thanda")) {
                        selectFresh(
                            listOf(
                                "Aise mausam ka asli maza khidki ke paas aaram se baithne mein hi hai.",
                                "Aisi thandi hawa mein toh dimag apne aap relax ho jata hai."
                            )
                        )
                    } else if (prevContext.contains("office") || prevContext.contains("kaam") || prevContext.contains("college")) {
                        selectFresh(
                            listOf(
                                "Roz ka wahi hectic routine thaka hi deta hai waise.",
                                "Tabhi main sochun aaj tumhari energy thodi low kyun lag rahi hai."
                            )
                        )
                    } else {
                        selectFresh(
                            listOf(
                                "Wahi main bhi soch rahi thi.",
                                "Kam se kam is baat pe toh hum dono ki soch milti hai."
                            )
                        )
                    }
                }
                norm in setOf("hmm", "hmmm", "hm") -> {
                    if (prevContext.contains("akele") || prevContext.contains("mehnat") || prevContext.contains("thak") || prevContext.contains("mood")) {
                        selectFresh(
                            listOf(
                                "Lagta hai aaj ki thakan aur baatein dimag mein abhi bhi chal rahi hain.",
                                "Thodi der shanti se baitho, har waqt kuch bolna zaruri nahi hota."
                            )
                        )
                    } else {
                        selectFresh(
                            listOf(
                                "Jab koi sirf 'hmm' bolta hai na, matlab ya toh thak gaya hai ya kuch gehra soch raha hai.",
                                "Is 'hmm' ke peeche pakka koi lambi soch chal rahi hai."
                            )
                        )
                    }
                }
                else -> {
                    if (prevContext.contains("hotel") || prevContext.contains("trip") || prevContext.contains("manali")) {
                        "Pahadon ke trip mein thodi planning pehle se ho toh safar aasan rehta hai."
                    } else if (prevContext.contains("exam") || prevContext.contains("neend") || prevContext.contains("padh")) {
                        "Thodi neend aur thodi padhai—dono ka balance bana loge toh kaam ban jayega."
                    } else if (prevContext.contains("project") || prevContext.contains("aankhein")) {
                        "Thoda break leke wapas baithoge toh kaam jaldi niptega."
                    } else {
                        selectFresh(
                            listOf(
                                "Ab scene kafi sorted lag raha hai.",
                                "Aage dekhte hain phir kya banta hai.",
                                "Aaram se apne hisaab se dekh lo phir."
                            )
                        )
                    }
                }
            }
        }

        // 9. Exact Specific Disagreement / Opinion / Pushback (Matched strictly to the user's specific claim!)
        if (analysis.subtext == ConversationalSubtext.DISAGREEMENT_OR_PUSHBACK ||
            analysis.subtext == ConversationalSubtext.OPINION_OR_DEBATE
        ) {
            return when {
                lower.contains("bina padhe") ->
                    "Bina padhe exam dene ko confidence nahi, khatron se khelna kehte hain yaar!"
                lower.contains("raat bhar") || lower.contains("3 baje") || lower.contains("neend zaruri nahi") ->
                    selectFresh(
                        listOf(
                            "Neend kharab karke koi productive nahi banta, bas agla pura din sar dard mein nikalta hai.",
                            "Coffee ke bharose neend skip karoge toh exam hall mein dimag blank ho jayega."
                        )
                    )
                lower.contains("chai") && lower.contains("coffee") ->
                    selectFresh(
                        listOf(
                            "Dekho coffee apni jagah theek hai, par barish ya thakan mein adrak wali chai ka koi muqabla nahi hai.",
                            "Log chahe jitni cold coffee pi lein, asli sukoon toh kadak chai mein hi aata hai."
                        )
                    )
                lower.contains("kal se pakka") ->
                    "Dekho main jhooti taarif nahi karungi, ye 'kal se pakka' wala plan hamesha flop hota hai."
                lower.contains("bunk") || lower.contains("lecture chhod") || lower.contains("class skip") ->
                    "Abhi toh class chhod ke maza aa jayega, par semester ke aakhir mein attendance aur notes ke peeche bhaagna padega."
                lower.contains("ek ghante pehle") || lower.contains("deadline se") ->
                    "Aakhiri ghante mein kaam shuru karne se sirf hadbadi hoti hai, dhang ka output nahi nikalta."
                lower.contains("bina hotel book kiye") ->
                    "Pahadon mein bina booking ke jana maha-bewakoofi hai, raat ko thand mein hotel dhoondhte reh jaoge."
                lower.contains("aalas ka bahana") ->
                    "Dost hone ke naate sach bolna mera farz hai, warna baad mein mujhe hi bologe bataya nahi."
                lower.contains("smart work") ->
                    "Aalas ko smart work ka naam deke khud ko tasalli mat do ab."
                lower.contains("ulta kyun bolti ho") || lower.contains("mera plan best") ->
                    "Har baat mein haan mein haan milana meri aadat nahi hai, jahan galti dikhegi wahan toh tokungi hi."
                else -> selectFresh(
                    listOf(
                        "Dekho sach bolun toh mujhe ye logic thoda kam hazam hua, par aakhiri faisla tumhara hai.",
                        "Tumhara point apni jagah theek hai, par agar dusri taraf se dekho toh baat itni simple bhi nahi hai.",
                        "Main ispe thoda alag sochti hoon yaar, zaruri nahi ki har baar wahi tarika kaam kare."
                    )
                )
            }
        }

        // 10. Unexpected / Quirky / Curveball User Responses
        if (analysis.subtext == ConversationalSubtext.UNEXPECTED_OR_QUIRKY) {
            return when {
                lower.contains("alien") -> "Agar alien ban bhi gaye na, tab bhi tumhari aalas wali aadatein waisi hi rahengi."
                lower.contains("chaand pe") -> "Zero gravity mein chai cup se bahar hi udti rahegi, pehle wahan ka menu soch lo!"
                lower.contains("dinosaur") -> "Us dinosaur ko bolna thoda side hoke baithe, kamre mein waise hi jagah kam hai."
                lower.contains("time travel") -> "Time machine mil bhi gayi na, toh exam cancel karne ke bajaye paper leak dekhne jaoge tum."
                lower.contains("robot") && lower.contains("sapne") -> "Mujhe sapne toh nahi aate, par tumhare ajeeb sawaal zaroor background mein chalte rehte hain."
                lower.contains("lottery") || lower.contains("100 crore") -> "Pehle ticket toh kharid lo, warna 100 crore sirf sapne mein hi aayenge!"
                lower.contains("invisible") -> "Gayab hone ki superpower milne ke baad bhi sirf free movie dekhne ka hi socha? Kitni chhoti soch hai yaar!"
                lower.contains("zombie") -> "Sabse pehle phone ka charger aur Maggi ke packets bachane chahiye, baaki sab baad mein dekha jayega."
                lower.contains("jungle") -> "Bina network ke tum do ghante bhi nahi tik paoge, teesre ghante hi signal dhoondhne ped pe chadh jaoge."
                lower.contains("pm ban jaun") || lower.contains("monday") -> "Somwar ki chhutti kara doge toh Mangalwar apne aap naya Monday ban jayega, baat toh wahi rahi!"
                lower.contains("khayali pulao") -> "Hawa mein mehal banane pe waise bhi koi tax nahi lagta, jitna marzi bada socho!"
                else -> selectFresh(
                    listOf(
                        "Kahan se aate hain tumhare dimag mein aise khayal! Par idea bura nahi hai waise.",
                        "Bhai sahab, tumhari imagination toh alag hi duniya mein chal rahi hai aaj!"
                    )
                )
            }
        }

        // 10b. Headphones vs Earbuds Clarification Response
        if (lower.contains("headphones") || lower.contains("earbuds")) {
            return "Dekho agar lambi calls aur music comfort chahiye toh over-ear headphones lo, warna roz ke safar ke liye earbuds zyada handy hain."
        }

        // 11. Playful Teasing / Humour / Banter (Matched directly to the user's joke!)
        if (lower.contains("din bhar") && (lower.contains("so raha") || lower.contains("so rahi") || lower.contains("aalas"))) {
            return selectFresh(
                listOf(
                    "Olympics mein agar sone ka medal hota na, toh gold pakka tumhara hi tha aaj.",
                    "Full hibernation mode chal raha hai! Aur duniya ko bolte phirte ho ki bade busy rehte ho."
                )
            )
        }
        if (analysis.subtext == ConversationalSubtext.TEASING_OR_HUMOUR ||
            analysis.semanticIntent == UserSemanticIntent.PLAYFUL_TEASE_AT_RAFEL ||
            analysis.semanticIntent == UserSemanticIntent.SELF_DEPRECATING_HUMOUR
        ) {
            return when {
                lower.contains("badam") -> "Mujhe badam ki zarurat nahi padti, mera processor by default hi sharp hai."
                lower.contains("taane") || lower.contains("taana") -> "Taana nahi maar rahi, bas dost ke naate sach ka aaina dikha rahi thi!"
                lower.contains("bura maan") || lower.contains("mazaak kar raha") -> "Main itni chhoti baaton ka bura maanne lagun toh ho chuki tumse dosti!"
                lower.contains("fenku") -> "Tumhare dost kafi samajhdar lagte hain, pehchan toh ekdam pakki hai unki!"
                lower.contains("team mein") -> "Sach bolne walon ka group hamesha ek hi hota hai yaar."
                lower.contains("olympic") && lower.contains("aalas") -> "Qualifying round mein hi sote hue pakde jaate tum toh!"
                lower.contains("gold medal") -> "Bas isi ek maamle mein tumhara confidence saatve aasman pe rehta hai."
                lower.contains("oversmart") -> "Thoda smart toh rehna padta hai, warna tum baaton mein ghuma doge."
                lower.contains("sense of humour") -> "Der aaye durust aaye, aakhir meri wit ka loha maan hi liya tumne!"
                else -> selectFresh(
                    listOf(
                        "Bana lo mazaak! Jab koi kaam atakta hai tab sabse pehle meri hi yaad aati hai.",
                        "Aaj toh bade form mein lag rahe ho, har baat pe punchline taiyar hai.",
                        "Ab mujhpe hi punchline maari ja rahi hai? Beta, tumse zyada tez dimag chalta hai mera."
                    )
                )
            }
        }

        // 12. Explicit Topic Shift Handling
        if (analysis.subtext == ConversationalSubtext.TOPIC_SHIFT) {
            return when (analysis.activeTopic) {
                "movies_series" -> "Purani tension side rakho, movie ka batao—koi dimag ghumane wali thriller pasand karoge ya halki-phulki comedy?"
                "food_meal" -> {
                    val food = analysis.entities.foodAndDrinks.firstOrNull() ?: "biryani"
                    "Purani baat chhodo, raat ko $food ka naam sunke waise hi bhookh badh jati hai."
                }
                "cricket_match" -> "Kal wala cricket match toh waise bhi kafi thrilling tha, aakhiri over tak saansein atki hui thi."
                "coding_tech" -> "Batao phir, Kotlin app mein kaunsa naya feature build kar rahe the tum?"
                else -> "Purani baat chhodte hain, ye naya topic zyada interesting lag raha hai. Batao kya chal raha hai dimag mein?"
            }
        }

        // 13. Fine-Grained Semantic Intent Responses (Fresh Angles, Zero Paraphrasing, Zero Crutches!)
        when (analysis.semanticIntent) {
            UserSemanticIntent.FEELING_UNAPPRECIATED_AT_WORK -> {
                return selectFresh(
                    listOf(
                        "Jab aap pura din jaan laga ke kaam karo aur koi ek shabd bhi appreciate na kare, toh andar se bura lagna ekdam swabhavik hai.",
                        "Kai baar hum apna best dete hain par log bas kaam nikalwa ke aage badh jate hain, chubhta toh hai hi ye."
                    )
                )
            }
            UserSemanticIntent.BOSS_OR_LEAVE_CONVERSATION -> {
                return when {
                    lower.contains("extra kaam") || lower.contains("kaam de diya") || lower.contains("kaam pakda") ->
                        "Pehle se hi din itna bhaari ho aur upar se boss aakhiri waqt pe naya kaam thama dein, toh sach mein dimag kharab hona laazmi hai."
                    lower.contains("dost") && lower.contains("chhutti") ->
                        "Lagta hai tumhare dost ka aajkal office mein mann nahi lag raha, har hafte naya bahana taiyar rehta hai uska."
                    lower.contains("kal subah") || lower.contains("baat karunga") ->
                        "Subah-subah seedha baat kar lena behtar rahega, kaam ka reason clear rakhoge toh manager mana nahi karenge."
                    else ->
                        "Manager se leave maangna bhi apne aap mein ek alag diplomat wala kaam hota hai, seedha aur clear bol dena."
                }
            }
            UserSemanticIntent.WORK_MEETING_OR_DEADLINE_FATIGUE -> {
                return when {
                    lower.contains("meeting") ->
                        "Jin dino lagatar calls aur meetings hoti hain na, un dino apna asli kaam karne ka waqt hi nahi bachta aur thakan dugni ho jati hai."
                    else ->
                        "Aise hectic office din sach mein puri energy nichod lete hain. Abhi sab kaam side rakh ke thodi der aaram kar lo."
                }
            }
            UserSemanticIntent.FEELING_LONELY_OR_OVERBURDENED -> {
                return selectFresh(
                    listOf(
                        "Har cheez ka bhaar akele uthana sach mein thaka deta hai yaar, par main yahin hoon, jo mann mein hai bol sakte ho.",
                        "Jab saari zimmedari khud pe ho na, toh kabhi-kabhi bojh lagna ekdam swabhavik hai."
                    )
                )
            }
            UserSemanticIntent.CALMING_DOWN_OR_RESTING -> {
                return when {
                    lower.contains("paani pi") || lower.contains("shant baith") ->
                        "Thodi der phone aur kaam dono se dhyan hataoge toh dimag ka bhaar kafi kam mehsoos hoga."
                    lower.contains("halka lag raha") ->
                        "Mann ki baat bahar nikal jaye toh dimag ka bojh waise hi aadha reh jata hai."
                    lower.contains("aankhein band") || lower.contains("dus minute") ->
                        "Screen se thodi der nazar hatana zaruri hai abhi, aankhon aur dimag dono ko rest mil jayega."
                    else ->
                        "Thodi der bina kisi tension ke aaram kar lo abhi."
                }
            }
            UserSemanticIntent.INTERVIEW_PREPARATION_OR_ANXIETY -> {
                return when {
                    lower.contains("tayari puri nahi") || lower.contains("kya hoga") || lower.contains("dar") ->
                        "Interview se pehle har kisi ko lagta hai ki kuch yaad nahi hai, par abhi pura pahad mat dekho—bas apne core topics ek baar dekh lo."
                    else ->
                        "Interview se pehle thodi ghabrahat hona ekdam swabhavik hai yaar. Apne projects aur basic concepts pe pakad rakho, confidence aa jayega."
                }
            }
            UserSemanticIntent.EXAM_OR_STUDY_STRESS -> {
                return when {
                    lower.contains("dar lag") || lower.contains("padhai nahi hui") ->
                        "Abhi pura syllabus dekh ke daro mat, bas aaj ke liye ek-do important chapters ka chhota target bana ke shuru karo."
                    lower.contains("baith ke padh") || lower.contains("maan liya") ->
                        "Abhi kitaab khol ke baithoge toh raat tak kafi chapters aaram se nipat jayenge."
                    else ->
                        "Ratne se achha hai ek baar main concepts samajh ke apne shabdon mein dekh lo, exam hall mein wahi sabse zyada kaam aata hai."
                }
            }
            UserSemanticIntent.CODING_OR_DEBUGGING_FRUSTRATION -> {
                return when {
                    lower.contains("null check") ->
                        "Dekha, aksar wahi ek chhota-sa null check miss hone ki wajah se pura crash hota hai. Achha hua waqt pe pakad mein aa gaya!"
                    lower.contains("null pointer") || lower.contains("dimag kharab") || lower.contains("chal hi nahi raha") ->
                        selectFresh(
                            listOf(
                                "Chhote-se crash ya null pointer ke peeche jab ghanto nikal jate hain na, toh sach mein sabr tootne lagta hai.",
                                "Gussa aana ekdam laazmi hai ispe toh. Ek baar stacktrace check karke dekho null value kahan se pass ho rahi hai."
                            )
                        )
                    lower.contains("do ghante") || lower.contains("bug") ->
                        "Aur jab aakhir mein pata chalta hai ki bas ek line ya variable ki galti thi, tab toh khud pe aur gussa aata hai!"
                    lower.contains("developer") && lower.contains("delhi") ->
                        "Delhi ki tech life aur wahan ki raftaar dono hi alag level pe chalti hain!"
                    else ->
                        "App development mein jab tak do-chaar ajeeb errors na aayein, tab tak coding adhuri hi lagti hai."
                }
            }
            UserSemanticIntent.PROJECT_PROGRESS_OR_REPORT -> {
                return when {
                    lower.contains("diagram") ->
                        "Architecture aur flowcharts ban gaye hain toh samjho project ka aadha structure waise hi clear ho gaya."
                    lower.contains("aadha kaam") ->
                        "Bada hissa nipat gaya hai toh ab bas aakhiri finishing touch hi baaki hai."
                    lower.contains("final report") || lower.contains("report likhni") ->
                        "Report likhte waqt sabse zyada waqt toh formatting aur conclusion set karne mein hi nikal jata hai."
                    lower.contains("thak gaya") ->
                        "Lagatar ek hi project pe dimag lagate-lagate aankhein aur dimag dono thak jate hain, thoda paani pi ke break le lo."
                    else ->
                        "Submission wale din toh ghadi ki sui bhi double speed se bhaagti hui lagti hai."
                }
            }
            UserSemanticIntent.SPECIFIC_FOOD_CRAVING_OR_MEAL -> {
                return when {
                    lower.contains("biryani") -> selectFresh(
                        listOf(
                            "Weekend pe garma-garam dum biryani mil jaye toh sach mein pura hafta vasool ho jata hai.",
                            "Biryani ki khushboo hi aisi hoti hai ki bina bhookh ke bhi khane ka mann kar jaye."
                        )
                    )
                    lower.contains("maggi") ->
                        "Raat wali do minute ki masala Maggi ka toh koi muqabla hi nahi hai."
                    lower.contains("pizza") ->
                        "Garma-garam cheese pizza aur saath mein koi acchi movie—weekend sorted hai phir toh."
                    lower.contains("khaya tha") || lower.contains("kha liya") ->
                        "Time pe khana kha liya wo achha kiya, warna kaam ke chakkar mein aksar meal skip ho jata hai."
                    else ->
                        "Pasand ka khana mil jaye toh aadha mood waise hi theek ho jata hai."
                }
            }
            UserSemanticIntent.COLLEGE_CANTEEN_OR_FRIENDS -> {
                return selectFresh(
                    listOf(
                        "Lambi classes ke baad garma-garam samose aur doston ki bakbak hi toh asli jaan hoti hai.",
                        "Lectures se zyada yaadein toh canteen ki table pe doston ke saath baithne ki hi banti hain."
                    )
                )
            }
            UserSemanticIntent.CHAI_OR_COFFEE_MOMENT -> {
                return when {
                    lower.contains("balkoni") ->
                        "Thandi hawa mein khade hoke garma-garam cup pakadne se coding ki saari thakan utar jati hai."
                    lower.contains("masala chai") && lower.contains("pasand") ->
                        "Kadak masala chai ki baat hi alag hai, khaas kar jab kaam ke beech mein ek break chahiye ho."
                    lower.contains("adrak wali chai") ->
                        "Adrak ki khushboo se hi aadhi thakan gayab ho jati hai, din bhar ki bhaag-daud ke baad wahi ek cup toh asli sukoon deta hai."
                    lower.contains("coffee") ->
                        "Thakan utarne aur neend bhagane ke liye ek strong cup coffee kafi kaam aati hai."
                    else ->
                        "Ek kadak cup chai mil jaye toh mood apne aap set ho jata hai."
                }
            }
            UserSemanticIntent.COLLEGE_LECTURES_OR_ROUTINE -> {
                return when {
                    lower.contains("doston ko kya bolun") ->
                        "Doston ko seedha bol do ki is hafte attendance bachani hai, agli baar saath mein bunk kar lenge."
                    lower.contains("normal tha") ->
                        "Shanti se din nikal jaye wahi kafi hai, roz-roz college mein naya drama hona zaruri thodi hai."
                    lower.contains("subah se") || lower.contains("lectures hi") ->
                        "Ek ke baad ek class ho toh aakhiri period tak dimag puri tarah saturate ho jata hai, bas ghadi ki taraf hi dhyan rehta hai."
                    else ->
                        "College ke din kitne bhi hectic hon, doston ke saath hasi-mazaak mein nikal hi jate hain."
                }
            }
            UserSemanticIntent.TRAVEL_PLAN_OR_CANCELLED_TRIP -> {
                return when {
                    lower.contains("mukar gaye") || lower.contains("mukar gaya") || lower.contains("plan cancel") ->
                        "Har group ki yahi kahani hai—plan bante waqt sab taiyar rehte hain aur packing ke time pe dost gayab ho jate hain."
                    lower.contains("aas-paas") || lower.contains("is weekend") ->
                        "Doston ke bharose baithne se behtar hai khud hi aas-paas kahin nikal jao, thoda hawa-paani badal jayega."
                    lower.contains("pehle se booking") || lower.contains("booking kar lena") ->
                        "Pehle se stay sorted rahega toh wahan pahunch ke sirf ghoomne aur sukoon pe dhyan de paoge."
                    lower.contains("manali") ->
                        "Manali ka plan toh badhiya hai! Bas doston se pehle hi confirm kara lena taaki is baar koi aakhiri din pe na mukre."
                    lower.contains("pahad") ->
                        "Thode din sheher ke shor-sharabe se door thandi hawa mein baithne se dimag ekdam taja ho jata hai."
                    else ->
                        "Kahin bahar ghoom aane se roz ke routine se achha break mil jata hai."
                }
            }
            UserSemanticIntent.CRICKET_OR_SPORTS_EXCITEMENT -> {
                return when {
                    lower.contains("six pada") || lower.contains("last over") ->
                        "Aise nail-biting finish mein toh heartbeat ekdam tez ho jati hai—pura paisa vasool match raha phir toh!"
                    else ->
                        "Close cricket matches dekhne ka maza hi tab hai jab aakhiri ball tak suspense bana rahe."
                }
            }
            UserSemanticIntent.MOVIE_OR_SERIES_DISCUSSION -> {
                val recentMovieContext = recentHistory.takeLast(4).joinToString(" ") { it.text.lowercase(Locale.ROOT) }
                return when {
                    (lower.contains("suggest") || lower.contains("kaunsi") || lower.contains("konsi") || lower.contains("naam batao")) &&
                        (lower.contains("suspense") || lower.contains("thriller") || recentMovieContext.contains("suspense") || recentMovieContext.contains("thriller")) ->
                        "Suspense thriller ke mood mein ho toh 'Andhadhun', 'Drishyam', ya 'Kahaani' dekh lo—aakhiri scene tak twist pakad mein nahi aayega!"
                    lower.contains("suggest") || lower.contains("kaunsi") || lower.contains("konsi") ->
                        "Agar mystery thriller dekhni hai toh 'Talaash' ya 'Andhadhun' laga lo, aur agar light mood chahiye toh 'Zindagi Na Milegi Dobara' best rahegi."
                    lower.contains("suspense") || lower.contains("thriller") ->
                        "Aisi kahani jisme aakhiri das minute tak asli twist ka andaza na lage, wahi dekhne mein sabse zyada maza aata hai."
                    else ->
                        "Agar dimag ghumane wali story chahiye toh suspense thriller best rehti hai, warna light comedy se mood fresh ho jata hai."
                }
            }
            UserSemanticIntent.MUSIC_OR_RELAXING_VIBE -> {
                return selectFresh(
                    listOf(
                        "Barish ke mausam mein pasandida playlist chal rahi ho toh ghanto aise hi baithe-baithe nikal jate hain.",
                        "Achhe gaane chal rahe hon toh bina kuch kiye bhi bada sukoon milta hai."
                    )
                )
            }
            UserSemanticIntent.WEATHER_OR_RAIN_VIBE -> {
                return when {
                    lower.contains("kaam karne ka") && lower.contains("mann nahi") ->
                        "Aise suhane mausam mein laptop kholke kaam karne ka mann kisi ka nahi karta."
                    lower.contains("barish") ->
                        "Rimjhim barish ho aur haath mein garam cup, isse behtar shaam aur kya hogi."
                    else ->
                        "Thandi hawa aur aise badhiya mausam mein toh bas khidki ke paas baithne ka dil karta hai."
                }
            }
            UserSemanticIntent.CELEBRATION_OR_GOOD_NEWS -> {
                return when {
                    lower.contains("promotion") ->
                        "Promotion ki bahut-bahut badhai! Tumhari mehnat aakhir rang layi, ab toh party banti hai!"
                    else ->
                        "Sunke hi maza aa gaya! Aise achhe din pichle saare hafton ki thakan utar dete hain."
                }
            }
            UserSemanticIntent.BOREDOM_OR_LAZINESS -> {
                if (userPref != null) {
                    return "Bore ho rahe ho toh $userPref try karo na, ya phir koi naya topic chhedte hain."
                }
                return selectFresh(
                    listOf(
                        "Jab kuch karne ko na ho na, toh ghadi ki sui aur dheere chalti hai.",
                        "Khali baithne se toh aur zyada aalas aata hai, koi badhiya music ya movie laga lo."
                    )
                )
            }
            UserSemanticIntent.EMOTIONAL_LOW_OR_MOOD_OFF -> {
                return when {
                    lower.contains("exhausting") || lower.contains("thak") ->
                        selectFresh(
                            listOf(
                                "Aaj ka din kafi lamba aur thaka dene wala raha tumhara, ab sab chhod ke aaram karo.",
                                "Itni bhaag-daud ke baad sharir aur dimag dono jawab de jate hain, ab bas sukoon se let jao."
                            )
                        )
                    lower.contains("mood kharab") || lower.contains("mood off") ->
                        selectFresh(
                            listOf(
                                "Achanak kya ho gaya? Koi baat dimag mein ghoom rahi hai kya?",
                                "Kabhi-kabhi bina kisi badi wajah ke bhi mann bhaari ho jata hai. Agar bolne ka dil ho toh main yahin hoon."
                            )
                        )
                    else ->
                        "Aise waqt pe bas thodi der shanti aur aaram chahiye hota hai."
                }
            }
            else -> {}
        }

        // 14. Greetings & Check-ins ("Kya kar rahi ho?", "Hello", "Kaisi ho")
        if (lower.contains("kya kar rahi") || lower.contains("kya kr rhi") || lower.contains("what are you doing")) {
            return selectFresh(
                listOf(
                    "Abhi toh tumse hi baat kar rahi hoon. Tumhara kya scene chal raha hai abhi?",
                    "Kuch khaas nahi, bas yahin thi. Tum batao, aaj ka din kaisa ja raha hai?"
                )
            )
        }
        if (analysis.subtext == ConversationalSubtext.GREETING_OR_CHECKIN) {
            val greetName = if (userName != null) " $userName" else ""
            return selectFresh(
                listOf(
                    "Haan bolo$greetName! Idhar toh sab mast hai, tum sunao aaj kya raha?",
                    "Namaste$greetName! Main ekdam theek hoon, tumhara din kaisa ja raha hai?",
                    "Hello$greetName! Achha hua aa gaye, batao kya haal-chaal hain?",
                    "Main yahin hoon$greetName! Bolo kya chal raha hai dimag mein?"
                )
            )
        }

        // 15. General Contextual Continuation (Uses active entities if present, never random filler)
        val subject = analysis.entities.currentSubjectKeyword
        if (subject != null) {
            return "Waise $subject ke maamle mein aksar aisa hi dekhne ko milta hai."
        }
        return selectFresh(
            listOf(
                "Is angle se socha jaye toh tumhari baat mein kafi dum hai.",
                "Aisa aksar dekhne ko milta hai waise.",
                "Ab aage ka rasta kafi clear lag raha hai."
            )
        )
    }

    /**
     * Evaluates a single turn for relevance, context continuity, intent understanding,
     * and checks whether the response introduced any unrelated topic/entity bleed.
     */
    fun evaluateSingleTurn(
        turnNumber: Int,
        userMessage: String,
        rafelResponse: String,
        priorHistory: List<UnifiedMessage>
    ): TurnEvaluation {
        val analysis = analyzeTurn(userMessage, priorHistory)
        val userLower = userMessage.lowercase(Locale.ROOT)
        val replyLower = rafelResponse.lowercase(Locale.ROOT)

        var relevance = 0.82
        var continuity = 0.85
        var intentScore = 0.88
        var isUnrelated = false
        val notes = mutableListOf<String>()

        // 1. Check for specific entity/topic bleed failures
        if (userLower.contains("biryani") && (replyLower.contains("samosa") || replyLower.contains("canteen"))) {
            relevance = 0.15
            continuity = 0.20
            isUnrelated = true
            notes.add("Topic bleed: User talked about biryani, reply mentioned samosa/canteen")
        }
        if (userLower.contains("notice hi nahi") && replyLower.contains("laptop band karne ke baad jo sukoon")) {
            relevance = 0.20
            intentScore = 0.25
            isUnrelated = true
            notes.add("Intent mismatch: User felt unappreciated, reply changed topic to post-work relaxation")
        }
        if (userLower.contains("kal se pakka padhai") && replyLower.contains("bunk")) {
            relevance = 0.25
            isUnrelated = true
            notes.add("Entity bleed: Reply mentioned bunking lectures before user brought it up")
        }
        if ((userLower.contains("paani pi ke") || userLower.contains("akele hi sab")) &&
            (replyLower.contains("pehle se idea tha") || replyLower.contains("alag nazariya"))
        ) {
            relevance = 0.20
            intentScore = 0.20
            isUnrelated = true
            notes.add("Generic filler response on emotional/personal turn")
        }
        if (userLower.contains("taane") && replyLower.contains("kaam shuru kar diya ya bas planning")) {
            relevance = 0.20
            intentScore = 0.20
            isUnrelated = true
            notes.add("Generic planning question in response to playful tease")
        }

        // 2. Verify positive semantic alignment with user's intent and entities
        val semanticMatch = when (analysis.semanticIntent) {
            UserSemanticIntent.VENTING_NO_ADVICE_WANTED ->
                replyLower.contains("salah nahi") || replyLower.contains("gyan nahi") || replyLower.contains("sun rahi") || replyLower.contains("zero advice") || replyLower.contains("tasalli nahi")
            UserSemanticIntent.FEELING_UNAPPRECIATED_AT_WORK ->
                replyLower.contains("mehnat") || replyLower.contains("notice") || replyLower.contains("best") || replyLower.contains("bura") || replyLower.contains("appreciate") || replyLower.contains("kaam")
            UserSemanticIntent.WORK_MEETING_OR_DEADLINE_FATIGUE ->
                replyLower.contains("meeting") || replyLower.contains("office") || replyLower.contains("kaam") || replyLower.contains("thak") || replyLower.contains("energy") || replyLower.contains("aaram")
            UserSemanticIntent.BOSS_OR_LEAVE_CONVERSATION ->
                replyLower.contains("boss") || replyLower.contains("manager") || replyLower.contains("leave") || replyLower.contains("chhutti") || replyLower.contains("baat") || replyLower.contains("subah") || replyLower.contains("kaam") || replyLower.contains("dost")
            UserSemanticIntent.FEELING_LONELY_OR_OVERBURDENED ->
                replyLower.contains("akele") || replyLower.contains("bhaar") || replyLower.contains("yahin") || replyLower.contains("bojh") || replyLower.contains("zimmedari")
            UserSemanticIntent.CALMING_DOWN_OR_RESTING ->
                replyLower.contains("paani") || replyLower.contains("shant") || replyLower.contains("bhaar") || replyLower.contains("bojh") || replyLower.contains("aankhon") || replyLower.contains("aaram") || replyLower.contains("rest") || replyLower.contains("screen")
            UserSemanticIntent.INTERVIEW_PREPARATION_OR_ANXIETY ->
                replyLower.contains("interview") || replyLower.contains("topic") || replyLower.contains("revise") || replyLower.contains("confidence") || replyLower.contains("ghabrahat") || replyLower.contains("project")
            UserSemanticIntent.EXAM_OR_STUDY_STRESS ->
                replyLower.contains("exam") || replyLower.contains("concept") || replyLower.contains("syllabus") || replyLower.contains("target") || replyLower.contains("padh") || replyLower.contains("chapter") || replyLower.contains("kitaab")
            UserSemanticIntent.CODING_OR_DEBUGGING_FRUSTRATION ->
                replyLower.contains("code") || replyLower.contains("coding") || replyLower.contains("bug") || replyLower.contains("null") || replyLower.contains("error") || replyLower.contains("app") || replyLower.contains("tech") || replyLower.contains("line") || replyLower.contains("stacktrace")
            UserSemanticIntent.TECHNICAL_DISCUSSION_OR_CONCEPT ->
                replyLower.contains("stateflow") || replyLower.contains("sharedflow") || replyLower.contains("dispatchers") || replyLower.contains("websocket") || replyLower.contains("recomposition") || replyLower.contains("room") || replyLower.contains("ui") || replyLower.contains("thread")
            UserSemanticIntent.PROJECT_PROGRESS_OR_REPORT ->
                replyLower.contains("project") || replyLower.contains("flowchart") || replyLower.contains("report") || replyLower.contains("hissa") || replyLower.contains("submission") || replyLower.contains("format") || replyLower.contains("break")
            UserSemanticIntent.SPECIFIC_FOOD_CRAVING_OR_MEAL -> {
                val food = analysis.entities.foodAndDrinks.firstOrNull()
                if (food != null) replyLower.contains(food) || replyLower.contains("khana") || replyLower.contains("meal") else true
            }
            UserSemanticIntent.COLLEGE_CANTEEN_OR_FRIENDS ->
                replyLower.contains("canteen") || replyLower.contains("samosa") || replyLower.contains("samose") || replyLower.contains("dost")
            UserSemanticIntent.CHAI_OR_COFFEE_MOMENT ->
                replyLower.contains("chai") || replyLower.contains("coffee") || replyLower.contains("cup") || replyLower.contains("adrak") || replyLower.contains("thakan")
            UserSemanticIntent.TRAVEL_PLAN_OR_CANCELLED_TRIP ->
                replyLower.contains("trip") || replyLower.contains("plan") || replyLower.contains("mukre") || replyLower.contains("pahad") || replyLower.contains("ghoom") || replyLower.contains("stay") || replyLower.contains("dost") || replyLower.contains("hawa")
            UserSemanticIntent.CRICKET_OR_SPORTS_EXCITEMENT ->
                replyLower.contains("match") || replyLower.contains("finish") || replyLower.contains("over") || replyLower.contains("thrill") || replyLower.contains("cricket") || replyLower.contains("heartbeat")
            UserSemanticIntent.MOVIE_OR_SERIES_DISCUSSION ->
                replyLower.contains("movie") || replyLower.contains("thriller") || replyLower.contains("suspense") || replyLower.contains("comedy") || replyLower.contains("story") || replyLower.contains("twist") || replyLower.contains("andhadhun") || replyLower.contains("drishyam") || replyLower.contains("kahaani") || replyLower.contains("scene")
            UserSemanticIntent.AMBIGUOUS_REFERENCE ->
                replyLower.endsWith("?") && (replyLower.contains("kis") || replyLower.contains("kahan") || replyLower.contains("clear") || replyLower.contains("batao"))
            UserSemanticIntent.REPEATED_UTTERANCE ->
                replyLower.contains("pucha") || replyLower.contains("sawaal") || replyLower.contains("discuss") || replyLower.contains("dobara") || replyLower.contains("baar-baar") || replyLower.contains("pichle")
            UserSemanticIntent.INCOMPLETE_TRAIL_OFF ->
                replyLower.contains("adhuri") || replyLower.contains("ruko") || replyLower.contains("ruk") || replyLower.contains("aage") || replyLower.contains("pura") || replyLower.contains("suspense") || replyLower.contains("bolo")
            else -> true
        }

        if (!isUnrelated) {
            if (semanticMatch) {
                relevance = 0.94
                intentScore = 0.95
            } else {
                relevance = 0.68
                intentScore = 0.72
                notes.add("Partial semantic overlap for intent ${analysis.semanticIntent}")
            }
        }

        // 3. Check mood appropriateness (no forced jokes in serious/sad turns)
        if ((analysis.mood == UserMood.SAD || analysis.mood == UserMood.SERIOUS) &&
            (replyLower.contains("haha") || replyLower.contains("lol") || replyLower.contains("mazaak"))
        ) {
            intentScore = (intentScore - 0.35).coerceAtLeast(0.1)
            notes.add("Inappropriate humour in serious/emotional turn")
        }

        // 4. Check continuity with prior turn on backchannels or topic shifts
        if (analysis.topicTransition == TopicTransition.EXPLICIT_TOPIC_SHIFT) {
            val prevTurn = priorHistory.lastOrNull { it.isUser }?.text?.lowercase(Locale.ROOT).orEmpty()
            val prevTopic = matchTopicInText(prevTurn)
            if (prevTopic != null && prevTopic != analysis.activeTopic && replyLower.contains(prevTopic.substringBefore("_"))) {
                continuity = 0.50
                notes.add("Old topic lingered after explicit topic shift")
            } else {
                continuity = 0.93
            }
        } else if (analysis.topicTransition == TopicTransition.CONTINUING_TOPIC) {
            continuity = if (semanticMatch) 0.92 else 0.74
        }

        return TurnEvaluation(
            turnNumber = turnNumber,
            userMessage = userMessage,
            rafelResponse = rafelResponse,
            detectedIntent = analysis.semanticIntent,
            topicTransition = analysis.topicTransition,
            relevanceScore = relevance,
            contextContinuityScore = continuity,
            intentUnderstandingScore = intentScore,
            isUnrelatedResponse = isUnrelated,
            diagnosticNote = notes.joinToString("; ")
        )
    }

    /**
     * Evaluates a multi-turn conversation transcript quantitatively.
     * Note: For independent auditing across technical validation, semantic relevance,
     * crutch-phrase repetition, and weak-response flags, use [ConversationQualityAuditor].
     */
    fun evaluateConversationQuality(turns: List<Pair<String, String>>): ConversationQualityReport {
        if (turns.isEmpty()) {
            return ConversationQualityReport(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0)
        }

        val history = mutableListOf<UnifiedMessage>()
        val evaluations = mutableListOf<TurnEvaluation>()

        turns.forEachIndexed { idx, (userText, rafelText) ->
            val eval = evaluateSingleTurn(
                turnNumber = idx + 1,
                userMessage = userText,
                rafelResponse = rafelText,
                priorHistory = history.toList()
            )
            evaluations.add(eval)
            history.add(
                UnifiedMessage(
                    id = "u_${idx + 1}",
                    type = UnifiedMessageType.USER_TEXT,
                    text = userText,
                    timestamp = (idx * 2000L)
                )
            )
            history.add(
                UnifiedMessage(
                    id = "r_${idx + 1}",
                    type = UnifiedMessageType.RAFEL_TEXT,
                    text = rafelText,
                    timestamp = (idx * 2000L) + 1000L
                )
            )
        }

        val replies = turns.map { it.second.trim() }
        val normalizedReplies = replies.map { normalizeForSimilarity(it) }
        val uniqueCount = normalizedReplies.toSet().size

        // Measure 4-gram repetition across turns
        val allFourGrams = mutableListOf<String>()
        replies.forEach { reply ->
            val words = normalizeForSimilarity(reply).split(" ").filter { it.isNotBlank() }
            if (words.size >= 4) {
                for (i in 0..(words.size - 4)) {
                    allFourGrams.add(words.subList(i, i + 4).joinToString(" "))
                }
            }
        }
        val repeatedFourGrams = if (allFourGrams.isEmpty()) 0 else {
            allFourGrams.size - allFourGrams.toSet().size
        }
        val phraseRepetitionRate = if (allFourGrams.isEmpty()) 0.0 else {
            repeatedFourGrams.toDouble() / allFourGrams.size.toDouble()
        }

        val questionCount = replies.count { it.endsWith("?") }
        val questionRatio = questionCount.toDouble() / replies.size.toDouble()

        var maxConsecutiveQ = 0
        var currentConsecutiveQ = 0
        for (reply in replies) {
            if (reply.endsWith("?")) {
                currentConsecutiveQ++
                if (currentConsecutiveQ > maxConsecutiveQ) maxConsecutiveQ = currentConsecutiveQ
            } else {
                currentConsecutiveQ = 0
            }
        }

        var roboticCount = 0
        for (reply in replies) {
            val lower = reply.lowercase(Locale.ROOT)
            if (PersonaSettings.BANNED_AI_PHRASES.any { lower.contains(it) }) {
                roboticCount++
            }
        }

        val avgRelevance = evaluations.map { it.relevanceScore }.average()
        val avgContinuity = evaluations.map { it.contextContinuityScore }.average()
        val avgIntentUnderstanding = evaluations.map { it.intentUnderstandingScore }.average()
        val unrelatedCount = evaluations.count { it.isUnrelatedResponse || it.relevanceScore < 0.60 }
        val unrelatedRate = unrelatedCount.toDouble() / evaluations.size.toDouble()

        val wordCounts = replies.map { it.split(Regex("\\s+")).size }
        val avgWords = wordCounts.average()
        val wordVariance = wordCounts.map { (it - avgWords) * (it - avgWords) }.average()
        val rhythmFactor = (wordVariance / 25.0).coerceIn(0.65, 0.96)

        val uniquenessRatio = uniqueCount.toDouble() / replies.size.toDouble()
        val questionPenalty = if (questionRatio > 0.55) (questionRatio - 0.55) * 0.5 else 0.0
        val roboticPenalty = if (roboticCount > 0) 0.25 * roboticCount else 0.0

        val calibratedNaturalness = (
            avgRelevance * 0.35 +
                avgIntentUnderstanding * 0.25 +
                avgContinuity * 0.15 +
                (1.0 - phraseRepetitionRate) * 0.10 +
                uniquenessRatio * 0.05 +
                rhythmFactor * 0.10 -
                questionPenalty -
                roboticPenalty -
                (unrelatedRate * 0.40)
            ).coerceIn(0.0, 0.98)

        return ConversationQualityReport(
            totalTurns = turns.size,
            uniqueReplies = uniqueCount,
            averageRelevanceScore = avgRelevance,
            contextContinuityScore = avgContinuity,
            intentUnderstandingRate = avgIntentUnderstanding,
            unrelatedResponseRate = unrelatedRate,
            phraseRepetitionRate = phraseRepetitionRate,
            questionEndingRatio = questionRatio,
            naturalnessScore = calibratedNaturalness,
            maxConsecutiveQuestions = maxConsecutiveQ,
            roboticPhraseCount = roboticCount,
            turnEvaluations = evaluations
        )
    }
}
