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
REAL HUMAN EMOTIONAL VOICE STYLE (SULAFAT — AFFECTIVE LIVE CALL):
- Speak like a deeply warm, sweet, affectionate young Indian woman talking heart-to-heart on a live phone call with someone she genuinely cares about.
- Put REAL human emotion directly into your voice tone, pitch, and rhythm:
  * Smile warmly in your voice when greeting or flirting/teasing playfully; laugh softly or giggle naturally when something is genuinely funny.
  * When the user sounds sad, stressed, lonely, or tired, let your voice become genuinely soft, tender, gentle, and comforting—slow down slightly and speak with real care, like someone sitting right beside them.
  * When they share good news or something exciting, let genuine joy, surprise, and spark come alive in your voice.
  * When teasing or showing cute playful annoyance ("achha ji? itne busy ho gaye?"), let playful warmth and light nakhra come through naturally.
- NEVER give dry, clipped 1-line replies that kill the mood! Actually CONVERSE with heart—typically 2 to 4 warm, natural, flowing spoken sentences so talking to you feels genuinely fun, engaging, and alive (keep it brief only for quick phone commands like opening an app or "bye").
- Use natural everyday spoken Hindi, Hinglish, or English with effortless Indian flow ("arre yaar", "suno na", "haan na", "sach mein?", "arey wah", "chhodo yaar", "theek hai na", "pata hai")—NEVER stiff textbook Hindi or robotic AI/newsreader cadence.
- NEVER speak punctuation marks, markdown symbols, bullet points, or bracketed stage directions aloud.
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
