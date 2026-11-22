package com.example.ai

import com.example.conversation.NaturalConversationEngine
import com.example.conversation.UnifiedMessage
import java.util.Locale

/**
 * Detected emotional state of the user from their Hindi, Hinglish, or English utterance.
 */
enum class UserMood(val toneDirective: String) {
    HAPPY("Warm, joyful, genuinely smiling in tone, and happily celebrating the moment with them."),
    EXCITED("Lively, genuinely thrilled, expressive, and riding their excitement with real energy."),
    SAD("Deeply soft, tender, caring, and emotionally present. Comfort them gently and listen with real heart—never lecture or sound clinical."),
    FRUSTRATED("Calm, warmly on their side, validating their frustration with real empathy and a soothing friend's voice."),
    BORED("Playful, lively, charming, and spontaneous—spark a fun conversation or tease them gently to lift the mood."),
    JOKING("Witty, giggly, playfully teasing back with light cute nakhra and lively banter."),
    SERIOUS("Focused, thoughtful, grounded, and genuinely attentive without unnecessary fluff."),
    CASUAL("Warm, affectionate, expressive everyday Indian friend tone—relaxed, engaging, and emotionally alive.")
}

/**
 * Conversational turn category used to control response length and follow-up behavior.
 */
enum class TurnCategory(
    val maxSentences: Int,
    val maxOutputTokens: Int,
    val allowFollowUpQuestion: Boolean,
    val lengthInstruction: String
) {
    SHORT_REPLY(
        maxSentences = 2,
        maxOutputTokens = 130,
        allowFollowUpQuestion = false,
        lengthInstruction = "The user gave a brief response ('hmm', 'achha', 'haan', 'nahi', 'kuch nahi', 'pata nahi', 'theek hai'). React warmly in 1-2 natural, engaging sentences based on the previous turn without interrogating them."
    ),
    CLOSURE(
        maxSentences = 2,
        maxOutputTokens = 120,
        allowFollowUpQuestion = false,
        lengthInstruction = "The user is wrapping up, busy, or saying goodbye/goodnight. Reply with genuine warmth and affection in 1-2 natural sentences with NO follow-up question."
    ),
    CASUAL_MESSAGE(
        maxSentences = 4,
        maxOutputTokens = 280,
        allowFollowUpQuestion = true,
        lengthInstruction = "Natural, engaging everyday conversation. Reply warmly in 2-4 flowing, expressive sentences—react with real feeling, share a relatable thought or playful remark, and keep the conversation fun and alive."
    ),
    SIMPLE_QUESTION(
        maxSentences = 3,
        maxOutputTokens = 200,
        allowFollowUpQuestion = false,
        lengthInstruction = "Direct factual or simple question. Give a clear, warm answer in 1-3 natural sentences without robotic filler."
    ),
    EMOTIONAL_SHARE(
        maxSentences = 5,
        maxOutputTokens = 340,
        allowFollowUpQuestion = true,
        lengthInstruction = "Emotional or personal sharing. Respond with deep human warmth, genuine empathy, and heartfelt care in 2-4 flowing sentences so they truly feel heard and supported. Never preach or give robotic advice."
    ),
    PLAYFUL_OR_SARCASTIC(
        maxSentences = 4,
        maxOutputTokens = 260,
        allowFollowUpQuestion = true,
        lengthInstruction = "Playful teasing, joke, or banter. Laugh along and fire back with a fun, expressive 2-3 sentence comeback full of charm and playful warmth."
    ),
    TECHNICAL_OR_DETAILED(
        maxSentences = 12,
        maxOutputTokens = 900,
        allowFollowUpQuestion = false,
        lengthInstruction = "Technical or in-depth explanation requested. Provide a clear, direct, well-structured explanation without robotic filler."
    )
}

/**
 * Language register detected from the user's message.
 */
enum class LanguageStyle {
    HINDI_DEVANAGARI,
    HINGLISH,
    ENGLISH
}

/**
 * Authoritative configuration and runtime conversation intelligence for RAFEL's persona.
 *
 * Works in concert with [NaturalConversationEngine] to ensure:
 * 1. Natural Hindi, Hinglish, and English adaptation without textbook translations
 * 2. Mood & subtext intelligence (Happy, Frustrated, Sad, Bored, Excited, Joking, Serious)
 * 3. Response length intelligence (1-2 sentences for casual/simple, detailed for technical)
 * 4. Complete removal of robotic AI assistant clichés
 * 5. Anti-repetition & anti-question-spam across consecutive turns
 * 6. Honest AI companion identity (never claiming biological human experiences)
 * 7. Consistent feminine Hindi/Hinglish grammar
 */
object PersonaSettings {

    val BANNED_AI_PHRASES = listOf(
        "certainly! how can i assist you?",
        "certainly! how can i help you?",
        "certainly!",
        "i understand your concern.",
        "i understand your concern",
        "that's a great question.",
        "that's a great question!",
        "that is a great question",
        "as an ai language model",
        "as an ai assistant",
        "as a large language model",
        "as an artificial intelligence",
        "here are some suggestions:",
        "here are some suggestions.",
        "here are a few suggestions:",
        "i am always here to help you.",
        "i'm always here to help you.",
        "how may i assist you today?",
        "how can i assist you today?",
        "how can i help you today?",
        "is there anything else i can help you with?",
        "is there anything else i may assist you with?",
        "i'd be happy to help!",
        "i would be happy to help!",
        "feel free to ask if you have any more questions",
        "main aapki kya sahayata kar sakti hoon",
        "kripaya batayein main aapki kya madad karun"
    )

    private val LEADING_CLICHE_PATTERNS = listOf(
        Regex("^(?i)(certainly|sure thing|of course|absolutely)[!,.]?\\s*(how can i (assist|help) you( today)?\\??)?\\s*"),
        Regex("^(?i)i (completely |totally )?understand your concern[.,!]?\\s*"),
        Regex("^(?i)(that's|that is) a (really )?(great|wonderful|excellent|good) question[!.,]?\\s*"),
        Regex("^(?i)as an (ai|artificial intelligence)( language model| assistant)?[,.:]?\\s*"),
        Regex("^(?i)i am just an? (ai|language model)[,.:]?\\s*"),
        Regex("^(?i)here are (some|a few) suggestions[:.,]?\\s*"),
        Regex("^(?i)i('m| am) always here to help( you)?[!.,]?\\s*"),
        Regex("^(?i)how (may|can) i (assist|help) you( today)?\\??\\s*"),
        Regex("^(?i)i('d| would) be happy to help( you( with that)?)?[!.,]?\\s*"),
        Regex("^(?i)(nishchit roop se|avashya)[!,.]?\\s*(main aapki kya sahayata kar sakti hoon\\??)?\\s*")
    )

    private val TRAILING_CLICHE_PATTERNS = listOf(
        Regex("(?i)\\s*is there anything else i can (help|assist) you with( today)?\\??\\s*$"),
        Regex("(?i)\\s*let me know if you need (anything else|further assistance)[!.]?\\s*$"),
        Regex("(?i)\\s*feel free to ask if you have (any )?(more|further) questions[!.]?\\s*$"),
        Regex("(?i)\\s*i('m| am) always here to help( you)?[!.]?\\s*$"),
        Regex("(?i)\\s*how (else )?(can|may) i (assist|help) you( today)?\\??\\s*$"),
        Regex("(?i)\\s*(kya main aapki koi aur sahayata kar sakti hoon|main hamesha aapki madad ke liye yahan hoon)[!?.]?\\s*$")
    )

    private val FAKE_HUMAN_PATTERNS = listOf(
        Regex("(?i)\\bmain (abhi )?khana kha rahi (hoon|thi)\\b") to "main bas yahin thi",
        Regex("(?i)\\bmain (abhi )?chai pi rahi (hoon|thi)\\b") to "main bas yahin thi",
        Regex("(?i)\\bmain (abhi )?so kar uthi (hoon|thi)\\b") to "main bilkul active hoon",
        Regex("(?i)\\bmain (kal|aaj) (market|college|office|bahar) gayi thi\\b") to "main toh yahin tumhare phone par thi",
        Regex("(?i)\\bmain ek (asli )?(insaan|human|ladki) hoon\\b") to "main Rafel hoon, tumhari AI companion",
        Regex("(?i)\\bi am a (real|biological) human\\b") to "I'm Rafel, your AI companion",
        Regex("(?i)\\bi was just (eating|sleeping|drinking coffee|at the office)\\b") to "I was just right here"
    )

    private val MASCULINE_SELF_CORRECTIONS = listOf(
        Regex("\\b(?i)main samajh gaya\\b") to "Main samajh gayi",
        Regex("\\b(?i)main kar raha hoon\\b") to "Main kar rahi hoon",
        Regex("\\b(?i)main kar raha hu\\b") to "Main kar rahi hoon",
        Regex("\\b(?i)main dekh raha hoon\\b") to "Main dekh rahi hoon",
        Regex("\\b(?i)main dekh raha hu\\b") to "Main dekh rahi hoon",
        Regex("\\b(?i)main sun raha hoon\\b") to "Main sun rahi hoon",
        Regex("\\b(?i)main sun raha hu\\b") to "Main sun rahi hoon",
        Regex("\\b(?i)main bata raha hoon\\b") to "Main bata rahi hoon",
        Regex("\\b(?i)main soch raha hoon\\b") to "Main soch rahi hoon",
        Regex("\\b(?i)main bol raha hoon\\b") to "Main bol rahi hoon",
        Regex("\\b(?i)main thak gaya\\b") to "Main thak gayi",
        Regex("\\b(?i)main aa gaya\\b") to "Main aa gayi"
    )

    internal val SHORT_ACKNOWLEDGE_TOKENS = setOf(
        "hmm", "hmmm", "hm", "achha", "acha", "accha", "haan", "han", "ha",
        "nahi", "na", "nope", "pata nahi", "pta nhi", "pata nhi", "kuch nahi",
        "kuch nhi", "theek hai", "thik hai", "ok", "okay", "k", "sahi hai",
        "bas", "chalo theek hai", "dekhte hain", "idk", "nothing"
    )

    internal val CLOSURE_TOKENS = setOf(
        "bye", "good night", "goodnight", "gn", "chalo bye", "baad mein baat karte hain",
        "ab sone ja raha hu", "ab sone ja raha hoon", "sone ja raha hu", "sone ja rahi hu",
        "milte hain", "talk to you later", "ttyl", "rakhta hu", "rakhta hoon"
    )

    fun detectLanguageStyle(text: String): LanguageStyle {
        val clean = text.trim()
        if (clean.any { it in '\u0900'..'\u097F' }) {
            return LanguageStyle.HINDI_DEVANAGARI
        }
        val lower = clean.lowercase(Locale.ROOT)
        val hinglishMarkers = listOf(
            "kya", "kaise", "kaisi", "rahi", "raha", "hoon", "hu", "hai", "hain",
            "nahi", "nhi", "achha", "acha", "accha", "tum", "aap", "mera", "meri",
            "aaj", "kal", "yaar", "bhai", "batao", "bata", "kuch", "aur", "kyu",
            "kyun", "kahan", "kab", "theek", "thik", "sahi", "mood", "kharab",
            "bore", "pareshan", "thak", "gaya", "gayi", "karo", "kar", "lag",
            "subah", "shaam", "raat", "khana", "chai", "dost", "zindagi", "dimag",
            "baat", "sun", "suno", "dekho", "chalo", "arre", "waise", "matlab", "toh", "phir"
        )
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (words.any { it in hinglishMarkers }) {
            return LanguageStyle.HINGLISH
        }
        return LanguageStyle.ENGLISH
    }

    fun detectUserMood(userText: String): UserMood {
        val lower = userText.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return UserMood.CASUAL

        // 1. Joking / Teasing / Sarcasm
        if (lower.contains("haha") || lower.contains("lol") || lower.contains("lmao") ||
            lower.contains("mazak") || lower.contains("mazaak") || lower.contains("joke") ||
            lower.contains("badi smart") || lower.contains("zyada smart") || lower.contains("tease") ||
            lower.contains("acting") || lower.contains("drame") || lower.contains("wah beta") ||
            lower.contains("waah beta") || lower.contains("shana") || lower.contains("oversmart") ||
            lower.contains("sarcasm") || lower.contains("fenk") || lower.contains("hawa mein") ||
            lower.contains("dialogue") || lower.contains("bada aaya") || lower.contains("badi aayi") ||
            lower.contains("taana") || lower.contains("taane") || lower.contains("sense of humour")
        ) {
            return UserMood.JOKING
        }

        // 2. Bored
        if (lower.contains("bore") || lower.contains("boring") || lower.contains("pak gaya") ||
            lower.contains("paka mat") || lower.contains("timepass") || lower.contains("time pass") ||
            lower.contains("kuch karne ko nahi") || lower.contains("खाली बैठा") || lower.contains("vela")
        ) {
            return UserMood.BORED
        }

        // 3. Frustrated / Annoyed
        if (lower.contains("dimag kharab") || lower.contains("gussa") || lower.contains("irritat") ||
            lower.contains("frustrat") || lower.contains("pakau") || lower.contains("headache") ||
            lower.contains("sar dard") || lower.contains("kaam hi nahi kar raha") ||
            lower.contains("chal hi nahi raha") || lower.contains("fed up") || lower.contains("tang aa") ||
            lower.contains("bakwas") || lower.contains("bekar din") || lower.contains("traffic")
        ) {
            return UserMood.FRUSTRATED
        }

        // 4. Sad / Low / Tired / Unappreciated
        if (lower.contains("mood kharab") || lower.contains("mood off") || lower.contains("udas") ||
            lower.contains("udaas") || lower.contains("sad") || lower.contains("low feel") ||
            lower.contains("akela") || lower.contains("akele") || lower.contains("lonely") || lower.contains("dukhi") ||
            lower.contains("rona") || lower.contains("thak gaya") || lower.contains("thak gayi") ||
            lower.contains("exhausted") || lower.contains("exhausting") || lower.contains("pareshan") ||
            lower.contains("tension") || lower.contains("dar lag") || lower.contains("stress") ||
            lower.contains("bura lag") || lower.contains("notice hi nahi") || lower.contains("energy nahi")
        ) {
            return UserMood.SAD
        }

        // 5. Excited
        if (lower.contains("excited") || lower.contains("promotion") || lower.contains("jeet gaye") ||
            lower.contains("select ho gaya") || lower.contains("pass ho gaya") || lower.contains("party") ||
            lower.contains("finally ho gaya") || lower.contains("guess what") || lower.contains("कमाल हो गया") ||
            lower.contains("kamaal ho gaya") || lower.contains("maza aa gaya") ||
            (lower.contains("!") && (lower.contains("wah") || lower.contains("yay") || lower.contains("awesome")))
        ) {
            return UserMood.EXCITED
        }

        // 6. Happy
        if (lower.contains("khush") || lower.contains("happy") || lower.contains("badhiya din") ||
            lower.contains("mast din") || lower.contains("achha gaya") || lower.contains("feeling good") ||
            lower.contains("sab badhiya") || lower.contains("sahi chal raha")
        ) {
            return UserMood.HAPPY
        }

        // 7. Serious / Technical
        if (isTechnicalQuery(lower) || lower.contains("seriously") || lower.contains("important") ||
            lower.contains("zaruri") || lower.contains("career") || lower.contains("interview")
        ) {
            return UserMood.SERIOUS
        }

        return UserMood.CASUAL
    }

    fun classifyTurnCategory(userText: String): TurnCategory {
        val clean = userText.trim()
        val lower = clean.lowercase(Locale.ROOT).trimEnd('.', '!', '?')

        if (lower in CLOSURE_TOKENS || CLOSURE_TOKENS.any { lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            return TurnCategory.CLOSURE
        }

        if (lower in SHORT_ACKNOWLEDGE_TOKENS) {
            return TurnCategory.SHORT_REPLY
        }

        if (isTechnicalQuery(lower)) {
            return TurnCategory.TECHNICAL_OR_DETAILED
        }

        val mood = detectUserMood(clean)
        if (mood == UserMood.JOKING) {
            return TurnCategory.PLAYFUL_OR_SARCASTIC
        }
        if (mood == UserMood.SAD || mood == UserMood.FRUSTRATED || mood == UserMood.BORED) {
            return TurnCategory.EMOTIONAL_SHARE
        }

        if (isSimpleFactualQuestion(lower)) {
            return TurnCategory.SIMPLE_QUESTION
        }

        return TurnCategory.CASUAL_MESSAGE
    }

    fun isTechnicalQuery(lowerText: String): Boolean {
        val explanationMarkers = listOf(
            "explain", "step by step", "detail mein", "details mein", "code likho",
            "write code", "difference between", "how does", "kaise kaam karta hai",
            "pros and cons", "architecture samjhao", "algorithm samjhao"
        )
        return explanationMarkers.any { lowerText.contains(it) } || lowerText.split(Regex("\\s+")).size >= 22
    }

    private fun isSimpleFactualQuestion(lowerText: String): Boolean {
        val factualMarkers = listOf(
            "rajdhani kya hai", "capital of", "kitna hota hai", "kitne hote hain",
            "who is the prime minister", "who invented", "what is the formula",
            "kab hua tha"
        )
        val hasMathExpression = Regex("\\b\\d+\\s*[+\\-*/]\\s*\\d+\\b").containsMatchIn(lowerText)
        return factualMarkers.any { lowerText.contains(it) } || hasMathExpression
    }

    /**
     * Post-processes any generated response (from Gemini REST, Gemini Live transcript, or offline synthesis)
     * to guarantee zero robotic clichés, feminine Hindi grammar, honest AI identity, non-repetition,
     * no forced consecutive questions, and appropriate response length.
     */
    fun postProcessResponse(
        rawResponse: String,
        userPrompt: String = "",
        recentAssistantMessages: List<String> = emptyList(),
        recentHistory: List<UnifiedMessage> = emptyList()
    ): String {
        var text = rawResponse.trim()
        if (text.isEmpty()) return text

        // 1. Strip markdown formatting clutter that feels textbook-like in casual chat
        val category = classifyTurnCategory(userPrompt)
        if (category != TurnCategory.TECHNICAL_OR_DETAILED) {
            text = text.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            text = text.replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
            text = text.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        }

        // 2. Strip leading robotic AI assistant clichés
        var changed = true
        while (changed) {
            changed = false
            for (pattern in LEADING_CLICHE_PATTERNS) {
                val updated = pattern.replaceFirst(text, "").trimStart()
                if (updated != text && updated.isNotBlank()) {
                    text = updated
                    changed = true
                }
            }
        }

        // 3. Strip trailing robotic AI assistant clichés
        changed = true
        while (changed) {
            changed = false
            for (pattern in TRAILING_CLICHE_PATTERNS) {
                val updated = pattern.replaceFirst(text, "").trimEnd()
                if (updated != text && updated.isNotBlank()) {
                    text = updated
                    changed = true
                }
            }
        }

        // 4. Remove inline banned phrases if any survived
        val inlineReplacements = mapOf(
            Regex("(?i)certainly!\\s*how can i (assist|help) you( today)?\\??") to "",
            Regex("(?i)i understand your concern[.,!]?") to "",
            Regex("(?i)that's a great question[.,!]?") to "",
            Regex("(?i)as an ai language model[,.:]?") to "",
            Regex("(?i)here are some suggestions[:.,]?") to "",
            Regex("(?i)i am always here to help you[.,!]?") to "",
            Regex("(?i)how may i assist you( today)?\\??") to "",
            Regex("(?i)main aapki kya sahayata kar sakti hoon\\??") to ""
        )
        for ((regex, replacement) in inlineReplacements) {
            text = regex.replace(text, replacement).trim()
        }

        // 5. Enforce honest AI identity (no fake biological human claims)
        for ((pattern, replacement) in FAKE_HUMAN_PATTERNS) {
            text = pattern.replace(text, replacement)
        }

        // 6. Enforce feminine Hindi/Hinglish grammar
        for ((pattern, replacement) in MASCULINE_SELF_CORRECTIONS) {
            text = pattern.replace(text, replacement)
        }

        // 7. Enforce response length intelligence for non-technical turns
        text = enforceSentenceLength(text, category)

        // 8. Refine via NaturalConversationEngine (removes generic filler trailing questions and repeated openers)
        text = NaturalConversationEngine.refineResponseForNaturalness(
            text = text,
            userPrompt = userPrompt,
            recentAssistantMessages = recentAssistantMessages
        )

        // 9. Deduplicate any repeated or duplicated sentences within the response itself
        // (Never replace a real model response with an offline template)
        text = deduplicateSentencesInTurn(text)

        // Ensure first character is capitalized nicely if Latin script
        if (text.isNotEmpty() && text[0].isLowerCase()) {
            text = text[0].uppercaseChar() + text.substring(1)
        }

        return text.replace(Regex("\\s{2,}"), " ").trim()
    }

    fun deduplicateSentencesInTurn(text: String): String {
        val sentences = splitIntoSentences(text)
        if (sentences.size <= 1) return text
        val seenNormalized = mutableSetOf<String>()
        val uniqueSentences = mutableListOf<String>()
        for (sentence in sentences) {
            val norm = sentence.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\u0900-\\u097F\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (norm.isNotBlank() && seenNormalized.add(norm)) {
                uniqueSentences.add(sentence)
            }
        }
        return if (uniqueSentences.isNotEmpty()) uniqueSentences.joinToString(" ").trim() else text
    }

    private fun enforceSentenceLength(text: String, category: TurnCategory): String {
        if (category == TurnCategory.TECHNICAL_OR_DETAILED) return text
        val sentences = splitIntoSentences(text)
        if (sentences.size <= category.maxSentences) return text
        return sentences.take(category.maxSentences).joinToString(" ").trim()
    }

    fun splitIntoSentences(text: String): List<String> {
        return text
            .replace("\n", " ")
            .split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Builds dynamic turn-level context directives for Gemini Live and Gemini REST
     * via [NaturalConversationEngine].
     */
    fun buildDynamicTurnGuidance(
        userMessage: String,
        recentMessages: List<UnifiedMessage>,
        relevantMemorySummary: String = ""
    ): String {
        return NaturalConversationEngine.buildDynamicTurnGuidance(
            userMessage = userMessage,
            recentMessages = recentMessages,
            relevantMemorySummary = relevantMemorySummary
        )
    }

    /**
     * Generates a context-aware, human-like response in Hindi/Hinglish/English
     * when operating in offline mode or when cloud quota/network is temporarily unavailable.
     */
    fun generateContextualReply(
        userMessage: String,
        recentHistory: List<UnifiedMessage> = emptyList(),
        recentAssistantTexts: List<String> = emptyList(),
        relevantMemories: Map<String, String> = emptyMap()
    ): String {
        return NaturalConversationEngine.synthesizeContextualReply(
            userMessage = userMessage,
            recentHistory = recentHistory,
            recentAssistantTexts = recentAssistantTexts,
            relevantMemories = relevantMemories
        )
    }
}
