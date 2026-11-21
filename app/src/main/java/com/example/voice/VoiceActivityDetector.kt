package com.example.voice

import kotlin.math.sqrt

/**
 * Energy and RMS-based Voice Activity Detector (VAD) with adaptive noise floor,
 * pre-roll circular buffering, post-buffer silence retention, and strict barge-in isolation.
 *
 * Core Architectural Guarantees:
 * 1. Independent States: User microphone silence NEVER stops Rafel's audio playback.
 * 2. Strict Barge-In Protection: While Rafel is speaking (isAiSpeaking == true), microphone
 *    silence and normal room noise are completely ignored. Only genuine, intentional user speech
 *    exceeding the barge-in threshold for sustained chunks can trigger an interruption.
 * 3. Ultra-Low Latency: Dynamic end-of-speech silence detection (260ms - 340ms) minimizes
 *    turnaround delay into Gemini Live without cutting off natural pauses.
 */
class VoiceActivityDetector(
    private val onSpeechStart: (preRollChunks: List<ShortArray>) -> Unit,
    private val onSpeechChunk: (chunk: ShortArray, length: Int) -> Unit,
    private val onSpeechEnd: () -> Unit,
    private val onBargeInDetected: () -> Unit
) {
    private var isUserSpeaking = false
    private var consecutiveSpeechChunks = 0
    private var bargeInConsecutiveChunks = 0
    private var silenceStartTimestamp = 0L
    private var speechStartTimestamp = 0L
    private var estimatedNoiseFloor = 180.0
    private var postBufferChunksSent = 0
    private var aiSpeechStartTime = 0L
    private var wasAiSpeaking = false
    private var lastAiSpeakingEndTimestamp = 0L

    // Pre-roll buffer to retain audio immediately preceding speech onset
    private val preRollBuffer = ArrayDeque<ShortArray>(VoiceConfig.PRE_BUFFER_CHUNKS)

    /**
     * Explicitly notifies VAD that Rafel's audio playback just finished so residual loudspeaker
     * echo in the next few frames is not misclassified as user speech onset.
     */
    @Synchronized
    fun notifyAiSpeechEnded(timestampMs: Long = System.currentTimeMillis()) {
        wasAiSpeaking = false
        lastAiSpeakingEndTimestamp = timestampMs
        consecutiveSpeechChunks = 0
        bargeInConsecutiveChunks = 0
    }

    /**
     * Process an incoming audio PCM chunk (16-bit, 16000Hz).
     *
     * @param buffer PCM sample array
     * @param length Number of valid samples in buffer
     * @param isAiSpeaking Whether Rafel is currently producing audio output
     */
    @Synchronized
    fun processFrame(buffer: ShortArray, length: Int, isAiSpeaking: Boolean, isWaitingForAi: Boolean = false) {
        if (length <= 0) return

        val nowMs = System.currentTimeMillis()
        if (isAiSpeaking) {
            wasAiSpeaking = true
        } else if (wasAiSpeaking) {
            wasAiSpeaking = false
            lastAiSpeakingEndTimestamp = nowMs
            consecutiveSpeechChunks = 0
        }

        val rms = calculateRms(buffer, length)

        // Slowly adapt noise floor during quiet periods when AI is NOT speaking or waiting
        if (!isAiSpeaking && !isWaitingForAi && rms < estimatedNoiseFloor * 1.5) {
            estimatedNoiseFloor = (estimatedNoiseFloor * 0.95) + (rms * 0.05)
        }

        // =========================================================================
        // CASE 1A: WAITING FOR AI RESPONSE
        // Strictly waiting for Gemini to finish generating or send first audio chunk.
        // User audio, mic silence, or ambient noise MUST NOT trigger barge-in or onSpeechEnd!
        // =========================================================================
        if (isWaitingForAi) {
            if (isUserSpeaking) {
                isUserSpeaking = false
                silenceStartTimestamp = 0L
                consecutiveSpeechChunks = 0
                postBufferChunksSent = 0
            }
            bargeInConsecutiveChunks = 0
            aiSpeechStartTime = 0L
            return
        }

        // =========================================================================
        // CASE 1B: RAFEL IS ACTIVELY SPEAKING
        // Strictly dedicated to intentional user barge-in detection.
        // Ambient noise, speaker acoustic coupling, and microphone silence MUST NOT
        // trigger onSpeechEnd, turn completion, or accidental interruption!
        // =========================================================================
        if (isAiSpeaking) {
            // If user state was left as speaking from prior trailing turn, reset it cleanly
            if (isUserSpeaking) {
                isUserSpeaking = false
                silenceStartTimestamp = 0L
                consecutiveSpeechChunks = 0
                postBufferChunksSent = 0
            }

            // Stricter RMS threshold to ignore device loudspeaker echo and ambient noise
            val bargeInThreshold = maxOf(VoiceConfig.BARGE_IN_THRESHOLD, estimatedNoiseFloor * 2.8)
            val isBargeInSpeech = rms >= bargeInThreshold

            // Maintain pre-roll buffer during AI speech in case barge-in occurs
            if (preRollBuffer.size >= VoiceConfig.PRE_BUFFER_CHUNKS) {
                preRollBuffer.removeFirst()
            }
            preRollBuffer.addLast(buffer.copyOf(length))

            if (isBargeInSpeech) {
                bargeInConsecutiveChunks++
                if (bargeInConsecutiveChunks >= VoiceConfig.VAD_MIN_BARGE_IN_CHUNKS) {
                    RafelLogger.logDiagnostic("[BARGE-IN] user interrupted Rafel (rms=$rms, threshold=$bargeInThreshold)")
                    RafelLogger.logInterruption("Genuine user barge-in detected! RMS=$rms (Threshold=$bargeInThreshold)")
                    bargeInConsecutiveChunks = 0
                    consecutiveSpeechChunks = 0
                    isUserSpeaking = true
                    speechStartTimestamp = nowMs
                    silenceStartTimestamp = 0L
                    postBufferChunksSent = 0
                    RafelLogger.trackUserSpeechStart()
                    RafelLogger.logRafelVoice("SPEECH_STARTED", "bargeIn=true rms=$rms threshold=$bargeInThreshold")

                    // 1. Notify service to instantly halt Rafel's playback and audio queue
                    onBargeInDetected()

                    // 2. Forward pre-roll audio so initial syllable/word of user is not cut
                    val preRoll = preRollBuffer.toList()
                    preRollBuffer.clear()
                    onSpeechStart(preRoll)

                    // 3. Forward current chunk
                    onSpeechChunk(buffer, length)
                }
            } else {
                bargeInConsecutiveChunks = 0
            }
            // CRITICAL: When isAiSpeaking is true and no barge-in, we do NOTHING.
            // Do NOT stream mic audio to Gemini, do NOT trigger onSpeechEnd!
            return
        }

        // AI is neither speaking nor waiting: reset AI speech startup timestamp
        aiSpeechStartTime = 0L

        // =========================================================================
        // CASE 2: RAFEL IS NOT SPEAKING (IDLE / LISTENING)
        // Standard user speech onset and completion detection.
        // =========================================================================
        bargeInConsecutiveChunks = 0
        val inPostPlaybackEchoWindow = !isUserSpeaking &&
            lastAiSpeakingEndTimestamp > 0L &&
            (nowMs - lastAiSpeakingEndTimestamp) < VoiceConfig.POST_AI_SPEECH_ECHO_GUARD_MS

        val effectiveThreshold = if (inPostPlaybackEchoWindow) {
            maxOf(VoiceConfig.BARGE_IN_THRESHOLD, estimatedNoiseFloor * 2.8)
        } else {
            maxOf(VoiceConfig.SPEECH_THRESHOLD, estimatedNoiseFloor * 1.8)
        }
        val minRequiredChunks = if (inPostPlaybackEchoWindow) {
            VoiceConfig.VAD_MIN_BARGE_IN_CHUNKS
        } else {
            VoiceConfig.VAD_MIN_SPEECH_CHUNKS
        }
        val isSpeechNow = rms >= effectiveThreshold

        if (!isUserSpeaking) {
            // Maintain pre-roll buffer while waiting for user speech
            if (preRollBuffer.size >= VoiceConfig.PRE_BUFFER_CHUNKS) {
                preRollBuffer.removeFirst()
            }
            preRollBuffer.addLast(buffer.copyOf(length))

            if (isSpeechNow) {
                consecutiveSpeechChunks++
                if (consecutiveSpeechChunks >= minRequiredChunks) {
                    // Valid speech onset confirmed
                    isUserSpeaking = true
                    speechStartTimestamp = nowMs
                    silenceStartTimestamp = 0L
                    postBufferChunksSent = 0
                    RafelLiveDiagnostics.speechStarted.value = true
                    RafelLiveDiagnostics.speechEnded.value = false
                    RafelLiveDiagnostics.log("SPEECH_STARTED")
                    RafelLogger.trackUserSpeechStart()
                    RafelLogger.logRafelVoice("SPEECH_STARTED", "rms=$rms threshold=$effectiveThreshold")
                    RafelLogger.trace1UserSpeechStarted()
                    RafelLogger.logVoice("user speech detected (rms=$rms, threshold=$effectiveThreshold)")
                    RafelLogger.logDiagnostic("[VAD] speech started (rms=$rms, threshold=$effectiveThreshold)")
                    RafelLogger.logVad("Speech started. RMS=$rms (NoiseFloor=$estimatedNoiseFloor)")

                    val preRoll = preRollBuffer.toList()
                    preRollBuffer.clear()
                    onSpeechStart(preRoll)
                    onSpeechChunk(buffer, length)
                }
            } else {
                consecutiveSpeechChunks = 0
            }
        } else {
            // User is actively speaking
            val now = System.currentTimeMillis()
            val totalSpeechDuration = now - speechStartTimestamp

            if (isSpeechNow) {
                silenceStartTimestamp = 0L
                postBufferChunksSent = 0
                onSpeechChunk(buffer, length)
            } else {
                if (silenceStartTimestamp == 0L) {
                    silenceStartTimestamp = now
                }

                // Forward a few post-buffer chunks to retain trailing consonants
                if (postBufferChunksSent < VoiceConfig.POST_BUFFER_CHUNKS) {
                    postBufferChunksSent++
                    onSpeechChunk(buffer, length)
                }

                // Ultra-Fast Conversational Silence Timeout (280ms - 350ms)
                val dynamicSilenceTimeout = if (totalSpeechDuration < 800L) {
                    VoiceConfig.END_SILENCE_SHORT_UTTERANCE_MS
                } else {
                    VoiceConfig.END_SILENCE_NORMAL_MS
                }

                if (now - silenceStartTimestamp >= dynamicSilenceTimeout) {
                    // Silence timeout reached -> user has finished speaking
                    RafelLogger.trackUserSpeechEndConfirmed()
                    RafelLogger.logDiagnostic("[VAD] speech ended (silenceMs=$dynamicSilenceTimeout, utteranceMs=$totalSpeechDuration)")
                    RafelLogger.logVad("Speech ended (silence=${dynamicSilenceTimeout}ms, utterance=${totalSpeechDuration}ms).")
                    finalizeSpeech()
                    return
                }
            }

            // Max utterance safeguard (e.g. 20s)
            if (totalSpeechDuration >= VoiceConfig.VAD_MAX_UTTERANCE_MS) {
                RafelLogger.trackUserSpeechEndConfirmed()
                RafelLogger.logDiagnostic("[VAD] speech ended (max utterance safeguard: $totalSpeechDuration ms)")
                RafelLogger.logVad("Max utterance duration reached (${totalSpeechDuration}ms). Finalizing speech.")
                finalizeSpeech()
            }
        }
    }

    private fun finalizeSpeech() {
        isUserSpeaking = false
        consecutiveSpeechChunks = 0
        bargeInConsecutiveChunks = 0
        silenceStartTimestamp = 0L
        speechStartTimestamp = 0L
        postBufferChunksSent = 0
        preRollBuffer.clear()
        RafelLiveDiagnostics.speechEnded.value = true
        RafelLiveDiagnostics.speechStarted.value = false
        RafelLiveDiagnostics.log("SPEECH_ENDED")
        RafelLogger.logRafelVoice("SPEECH_ENDED", "silence confirmed")
        RafelLogger.logVoice("user speech ended")
        RafelLogger.trace2UserSpeechEnded()
        RafelLogger.logDiagnostic("[VAD] speech ended -> silence confirmed")
        onSpeechEnd()
    }

    /**
     * Resets the VAD state machine.
     */
    @Synchronized
    fun reset() {
        isUserSpeaking = false
        consecutiveSpeechChunks = 0
        bargeInConsecutiveChunks = 0
        silenceStartTimestamp = 0L
        speechStartTimestamp = 0L
        postBufferChunksSent = 0
        aiSpeechStartTime = 0L
        wasAiSpeaking = false
        lastAiSpeakingEndTimestamp = 0L
        preRollBuffer.clear()
    }

    fun isUserSpeaking(): Boolean = isUserSpeaking

    private fun calculateRms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length)
    }
}
