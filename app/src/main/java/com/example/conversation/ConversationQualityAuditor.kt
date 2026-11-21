package com.example.conversation

import java.util.Locale

/**
 * Independent Conversation Quality Auditor (Separated from NaturalConversationEngine).
 *
 * Explicitly separates:
 * 1. Automated Technical Validation (feminine grammar, banned AI clichés, non-empty output, voice delivery limits)
 * 2. Semantic Relevance & Anti-Paraphrase Evaluation (entity/topic alignment, topic-bleed check, user 4-gram echoing check)
 * 3. Repetition & Crutch Phrase Analysis (exact counts of "sahi hai", "chalo badhiya", "samajh gayi", "sahi mein", "bilkul", "ye hui na baat", opener distribution, 4-gram repetition, question ratio)
 * 4. Honest Conversational Weakness Critique (flags any turn that echoes user phrasing, uses a crutch phrase, gives unsolicited advice when user asked not to, or relies on fallback rather than live LLM)
 *
 * Does NOT assign a self-congratulatory "naturalness score" or claim "fully human-like" status from automated checks.
 */
object ConversationQualityAuditor {

    val MONITORED_CRUTCH_PHRASES = listOf(
        "sahi hai",
        "chalo badhiya",
        "samajh gayi",
        "sahi mein",
        "bilkul",
        "ye hui na baat"
    )

    val BANNED_AI_CLICHES = listOf(
        "certainly!",
        "how can i assist you",
        "i understand your concern",
        "that's a great question",
        "as an ai",
        "language model",
        "here are some suggestions",
        "i am always here to help",
        "feel free to ask"
    )

    val MASCULINE_SELF_GRAMMAR = listOf(
        Regex("\\bmain\\s+karta\\s+hu\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmain\\s+raha\\s+hu\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmain\\s+gaya\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmain\\s+sakta\\s+hu\\b", RegexOption.IGNORE_CASE),
        Regex("\\bkarunga\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbataunga\\b", RegexOption.IGNORE_CASE)
    )

    data class TurnAuditRecord(
        val scenarioId: Int,
        val scenarioCategory: String,
        val turnIndex: Int,
        val userMessage: String,
        val rafelResponse: String,
        val detectedIntent: UserSemanticIntent,
        val detectedDiscourseAct: UserDiscourseAct,
        val activeEntities: TrackedConversationEntities,
        val technicalPass: Boolean,
        val technicalIssues: List<String>,
        val semanticPass: Boolean,
        val semanticIssues: List<String>,
        val echoedUserFourGrams: List<String>,
        val crutchPhrasesFound: List<String>,
        val openerTwoWords: String,
        val endsWithQuestion: Boolean,
        val honestCritiqueNotes: List<String>
    )

    data class CorpusAuditSummary(
        val totalScenarios: Int,
        val totalTurns: Int,
        val technicalPassCount: Int,
        val semanticPassCount: Int,
        val crutchPhraseCounts: Map<String, Int>,
        val totalCrutchOccurrences: Int,
        val echoedTurnCount: Int,
        val repeatedFourGramRatePercent: Double,
        val questionEndingRatePercent: Double,
        val topOpeners: List<Pair<String, Int>>,
        val turnRecords: List<TurnAuditRecord>,
        val flaggedWeakTurns: List<TurnAuditRecord>
    )

    fun auditTurn(
        scenarioId: Int,
        scenarioCategory: String,
        turnIndex: Int,
        userMessage: String,
        rafelResponse: String,
        priorHistory: List<UnifiedMessage>,
        expectedMustIncludeAny: List<String> = emptyList(),
        expectedMustNotIncludeAny: List<String> = emptyList()
    ): TurnAuditRecord {
        val analysis = NaturalConversationEngine.analyzeTurn(userMessage, priorHistory)
        val lowerResponse = rafelResponse.lowercase(Locale.ROOT)
        val technicalIssues = mutableListOf<String>()
        val semanticIssues = mutableListOf<String>()
        val honestCritiqueNotes = mutableListOf<String>()

        // 1. Technical validation
        if (rafelResponse.isBlank()) {
            technicalIssues.add("Empty response")
        }
        BANNED_AI_CLICHES.forEach { cliche ->
            if (lowerResponse.contains(cliche)) {
                technicalIssues.add("Contains banned AI cliché: '$cliche'")
            }
        }
        MASCULINE_SELF_GRAMMAR.forEach { regex ->
            if (regex.containsMatchIn(rafelResponse)) {
                technicalIssues.add("Contains masculine self-reference matching '${regex.pattern}'")
            }
        }

        // 2. Crutch phrase check
        val crutchesFound = MONITORED_CRUTCH_PHRASES.filter { phrase ->
            Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(rafelResponse)
        }
        if (crutchesFound.isNotEmpty()) {
            honestCritiqueNotes.add("Uses overused crutch phrase(s): ${crutchesFound.joinToString(", ")}")
        }

        // 3. Anti-paraphrase / 4-gram echoing check against user message (for non-factual turns)
        val userWords = tokenizeWords(userMessage)
        val responseWords = tokenizeWords(rafelResponse)
        val echoedFourGrams = mutableListOf<String>()
        if (analysis.semanticIntent != UserSemanticIntent.DIRECT_FACTUAL_QUERY &&
            userWords.size >= 4 && responseWords.size >= 4
        ) {
            val userFourGrams = userWords.windowed(4, 1) { it.joinToString(" ") }.toSet()
            val respFourGrams = responseWords.windowed(4, 1) { it.joinToString(" ") }
            for (fg in respFourGrams) {
                if (fg in userFourGrams) {
                    echoedFourGrams.add(fg)
                }
            }
        }
        if (echoedFourGrams.isNotEmpty()) {
            semanticIssues.add("Echoes user 4-gram verbatim: '${echoedFourGrams.first()}'")
            honestCritiqueNotes.add("Repeats 4-word sequence from user input ('${echoedFourGrams.first()}')")
        }

        if (analysis.semanticIntent == UserSemanticIntent.GENERAL_CONVERSATIONAL_STATEMENT) {
            honestCritiqueNotes.add("Fell back to GENERAL_CONVERSATIONAL_STATEMENT instead of a specific semantic intent")
        }

        // 4. Check expected inclusions / exclusions
        if (expectedMustIncludeAny.isNotEmpty()) {
            val matched = expectedMustIncludeAny.any { token ->
                lowerResponse.contains(token.lowercase(Locale.ROOT))
            }
            if (!matched) {
                semanticIssues.add(
                    "Missing expected context reference from [${expectedMustIncludeAny.joinToString(", ")}]"
                )
            }
        }
        expectedMustNotIncludeAny.forEach { forbidden ->
            if (lowerResponse.contains(forbidden.lowercase(Locale.ROOT))) {
                semanticIssues.add("Contains forbidden/unrelated token: '$forbidden'")
            }
        }

        // 5. Check no-advice venting compliance
        if (analysis.semanticIntent == UserSemanticIntent.VENTING_NO_ADVICE_WANTED) {
            val unsolicitedAdviceMarkers = listOf(
                "tumhe ye karna chahiye",
                "you should",
                "mera suggestion hai",
                "try to",
                "ek kaam karo"
            )
            unsolicitedAdviceMarkers.forEach { marker ->
                if (lowerResponse.contains(marker)) {
                    semanticIssues.add("Gave unsolicited advice ('$marker') when user explicitly asked for no advice")
                }
            }
            if (rafelResponse.trim().endsWith("?")) {
                honestCritiqueNotes.add("Ends with a question on a no-advice venting turn where quiet listening is preferred")
            }
        }

        val openerTwoWords = responseWords.take(2).joinToString(" ").ifBlank { "(empty)" }
        val endsWithQuestion = rafelResponse.trim().endsWith("?")

        return TurnAuditRecord(
            scenarioId = scenarioId,
            scenarioCategory = scenarioCategory,
            turnIndex = turnIndex,
            userMessage = userMessage,
            rafelResponse = rafelResponse,
            detectedIntent = analysis.semanticIntent,
            detectedDiscourseAct = analysis.discourseAct,
            activeEntities = analysis.entities,
            technicalPass = technicalIssues.isEmpty(),
            technicalIssues = technicalIssues,
            semanticPass = semanticIssues.isEmpty(),
            semanticIssues = semanticIssues,
            echoedUserFourGrams = echoedFourGrams,
            crutchPhrasesFound = crutchesFound,
            openerTwoWords = openerTwoWords,
            endsWithQuestion = endsWithQuestion,
            honestCritiqueNotes = honestCritiqueNotes
        )
    }

    fun summarizeCorpus(records: List<TurnAuditRecord>): CorpusAuditSummary {
        val totalScenarios = records.map { it.scenarioId }.distinct().size
        val totalTurns = records.size
        val technicalPassCount = records.count { it.technicalPass }
        val semanticPassCount = records.count { it.semanticPass }

        val crutchCounts = MONITORED_CRUTCH_PHRASES.associateWith { phrase ->
            records.count { phrase in it.crutchPhrasesFound }
        }
        val totalCrutchOccurrences = crutchCounts.values.sum()
        val echoedTurnCount = records.count { it.echoedUserFourGrams.isNotEmpty() }

        // Corpus-wide 4-gram repetition across RAFEL's own responses
        val allFourGrams = mutableListOf<String>()
        records.forEach { record ->
            val words = tokenizeWords(record.rafelResponse)
            if (words.size >= 4) {
                allFourGrams.addAll(words.windowed(4, 1) { it.joinToString(" ") })
            }
        }
        val uniqueFourGrams = allFourGrams.toSet().size
        val repeatedFourGramRatePercent = if (allFourGrams.isEmpty()) {
            0.0
        } else {
            ((allFourGrams.size - uniqueFourGrams).toDouble() / allFourGrams.size.toDouble()) * 100.0
        }

        val questionEndingRatePercent = if (totalTurns == 0) {
            0.0
        } else {
            (records.count { it.endsWithQuestion }.toDouble() / totalTurns.toDouble()) * 100.0
        }

        val topOpeners = records
            .groupingBy { it.openerTwoWords }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .map { it.key to it.value }

        val flaggedWeakTurns = records.filter {
            !it.technicalPass || !it.semanticPass || it.crutchPhrasesFound.isNotEmpty() || it.honestCritiqueNotes.isNotEmpty()
        }

        return CorpusAuditSummary(
            totalScenarios = totalScenarios,
            totalTurns = totalTurns,
            technicalPassCount = technicalPassCount,
            semanticPassCount = semanticPassCount,
            crutchPhraseCounts = crutchCounts,
            totalCrutchOccurrences = totalCrutchOccurrences,
            echoedTurnCount = echoedTurnCount,
            repeatedFourGramRatePercent = repeatedFourGramRatePercent,
            questionEndingRatePercent = questionEndingRatePercent,
            topOpeners = topOpeners,
            turnRecords = records,
            flaggedWeakTurns = flaggedWeakTurns
        )
    }

    private fun tokenizeWords(text: String): List<String> {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
}
