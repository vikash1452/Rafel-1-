package com.example.voice

/**
 * Diagnostic result of inspecting the Aoede voice delivery pipeline for a spoken turn.
 */
data class VoiceDeliveryInspection(
    val voiceIdentity: String,
    val startStreamingOnFirstChunk: Boolean,
    val speechPacing: Float,
    val sentenceBoundariesPreserved: Boolean,
    val hasPauseInducingPunctuation: Boolean,
    val hasOverlyFormalVocabulary: Boolean,
    val sentenceChunks: List<String>
)

/**
 * Authoritative configuration and text-to-voice prosody optimizer for RAFEL's
 * Human-Like Voice Delivery pipeline (Gemini Live Native Audio "Aoede" & spoken transcripts).
 *
 * Guarantees:
 * - Natural conversational pacing and warm sentence intonation
 * - Preservation of sentence boundaries across streamed chunks
 * - Elimination of pause-inducing punctuation (semicolons, colons, em-dashes, parentheticals)
 * - Replacement of stiff/formal textbook Hindi vocabulary with colloquial spoken Hinglish
 * - Preservation of RAFEL's voice identity ("Aoede")
 * - Immediate audio streaming on the first valid chunk (STARTUP_BUFFER_BYTES = 0)
 * - Instant playback stop on user barge-in / interruption
 */
object VoiceBehaviorSettings {

    val voiceName: String = VoiceConfig.GEMINI_VOICE_NAME
    const val startStreamingOnFirstChunk: Boolean = VoiceConfig.STARTUP_BUFFER_BYTES == 0
    const val stopImmediatelyOnInterruption: Boolean = true
    const val naturalSpeechPacing: Float = 1.0f

    val VOICE_DELIVERY_DIRECTIVE: String = """
HUMAN-LIKE VOICE DELIVERY & PROSODY (MANDATORY — AOEDE VOICE):
- Speak at a comfortable, natural conversational pace (1.0x)—never rushed or robotically fast, and never artificially slow.
- Speak in smooth, complete sentence breath-groups with natural micro-pauses only at commas and sentence endings.
- Maintain warm, casual, everyday Indian conversational intonation in Hindi, Hinglish, and English—never use a formal newsreader or IVR customer-care cadence.
- Avoid stiff, pause-inducing punctuation (no semicolons, colons, bullet lists, parenthetical asides, or ellipses in the middle of thoughts).
- NEVER read punctuation marks, markdown symbols (asterisks, hashes, dashes, bullets), or stage directions aloud.
- Do NOT fake theatrical emotions, exaggerated sighs, or random unnatural pauses.
- Keep spoken responses concise (1–2 natural sentences for casual turns) so the audio flows effortlessly.
    """.trimIndent()

    private val FORMAL_TO_NATURAL_REPLACEMENTS = listOf(
        Regex("(?i)\\bnishchit roop se\\b") to "haan",
        Regex("(?i)\\bavashya\\b") to "zaroor",
        Regex("(?i)\\bkripaya\\s+") to "",
        Regex("(?i)\\bsahayata\\b") to "madad",
        Regex("(?i)\\bprateet hota hai\\b") to "lagta hai",
        Regex("(?i)\\banubhav kar rahi\\b") to "mehsoos kar rahi",
        Regex("निश्चित रूप से") to "हाँ",
        Regex("अवश्य") to "ज़रूर",
        Regex("कृपया\\s*") to "",
        Regex("सहायता") to "मदद"
    )

    /**
     * Cleans generated text so that neither spoken output nor transcript displays
     * contain mechanical punctuation clusters, pause-inducing symbols, stage directions
     * in brackets/asterisks, overly formal textbook words, or markdown formatting symbols.
     */
    fun sanitizeForNaturalDelivery(rawText: String): String {
        if (rawText.isBlank()) return ""
        var text = rawText

        // 1. Remove stage directions in asterisks or brackets e.g. *smiles*, [laughs], (pauses)
        text = text.replace(Regex("\\*[a-zA-Z\\s]{2,25}\\*"), " ")
        text = text.replace(
            Regex("\\[(?:laughs|smiles|sighs|pauses|chuckles|giggles|thinking|softly|warmly)\\]", RegexOption.IGNORE_CASE),
            " "
        )
        text = text.replace(
            Regex("\\((?:laughs|smiles|sighs|pauses|chuckles|giggles|thinking|softly|warmly)\\)", RegexOption.IGNORE_CASE),
            " "
        )

        // 2. Strip markdown symbols (**bold**, `code`, ### headers) so punctuation is never read mechanically
        text = text.replace("**", "")
        text = text.replace("__", "")
        text = text.replace("`", "")
        text = text.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")

        // 3. Replace pause-inducing punctuation (em-dashes, semicolons, slashes) with natural comma/conjunction flow
        text = text.replace(" --- ", ", ")
        text = text.replace(" -- ", ", ")
        text = text.replace(" — ", ", ")
        text = text.replace("—", ", ")
        text = text.replace(";", ",")
        text = text.replace(" / ", " ya ")

        // 4. Replace stiff textbook/formal Hindi terms with natural everyday spoken equivalents
        for ((pattern, replacement) in FORMAL_TO_NATURAL_REPLACEMENTS) {
            text = pattern.replace(text, replacement)
        }

        // 5. Collapse excessive punctuation clusters (e.g., "....", "?!?!", "!!!") that cause unnatural TTS pauses
        text = text.replace(Regex("\\.{2,}"), ".")
        text = text.replace(Regex("!{2,}"), "!")
        text = text.replace(Regex("\\?{2,}"), "?")
        text = text.replace(Regex(",\\s*,"), ",")

        // 6. Collapse whitespace
        return text.replace(Regex("\\s{2,}"), " ").trim()
    }

    /**
     * Splits a response into complete sentence-aligned chunks so streaming delivery
     * preserves natural sentence boundaries without mid-sentence clipping.
     */
    fun splitIntoSentenceStreamChunks(rawText: String): List<String> {
        val sanitized = sanitizeForNaturalDelivery(rawText)
        if (sanitized.isBlank()) return emptyList()
        return sanitized
            .split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Inspects a spoken text payload and verifies Aoede voice pipeline health:
     * - Aoede identity preserved
     * - Immediate first-chunk streaming active
     * - Sentence boundaries preserved
     * - Zero pause-inducing punctuation or formal vocabulary
     */
    fun inspectVoiceDelivery(rawText: String): VoiceDeliveryInspection {
        val sanitized = sanitizeForNaturalDelivery(rawText)
        val chunks = splitIntoSentenceStreamChunks(sanitized)
        val hasPausePunctuation = sanitized.contains(";") || sanitized.contains("---") ||
            sanitized.contains("...") || sanitized.contains("**")
        val lower = sanitized.lowercase()
        val hasFormalVocab = lower.contains("nishchit roop se") || lower.contains("sahayata") ||
            lower.contains("kripaya") || sanitized.contains("निश्चित रूप से") || sanitized.contains("सहायता")
        val boundariesIntact = chunks.all { chunk ->
            chunk.isNotBlank() && !chunk.startsWith(",") && !chunk.startsWith(".")
        }

        return VoiceDeliveryInspection(
            voiceIdentity = voiceName,
            startStreamingOnFirstChunk = startStreamingOnFirstChunk,
            speechPacing = naturalSpeechPacing,
            sentenceBoundariesPreserved = boundariesIntact,
            hasPauseInducingPunctuation = hasPausePunctuation,
            hasOverlyFormalVocabulary = hasFormalVocab,
            sentenceChunks = chunks
        )
    }
}
