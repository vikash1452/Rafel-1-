package com.example.voice

import android.util.Log

/**
 * Concise logger for Rafel.
 * Strips out high-overhead per-chunk tracing from production runtime
 * while preserving concise error handling and connection status.
 */
object RafelLogger {
    private const val TAG_PREFIX = "Rafel_"

    private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
        } catch (_: Throwable) {
            System.err.println("ERROR [$tag] $msg")
            tr?.printStackTrace()
        }
    }

    private fun safeLogW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            // silent in test
        }
    }

    private fun safeLogI(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (_: Throwable) {
            // silent in test
        }
    }

    fun logVoiceState(from: RafelVoiceState, to: RafelVoiceState) {
        safeLogI("${TAG_PREFIX}STATE", "${from.name} -> ${to.name}")
    }

    /**
     * Temporary minimal one-time audio event logger (Section 8)
     */
    fun logAudioOneTime(message: String) {
        safeLogI("AUDIO", message)
    }

    fun logRafelVoice(event: String, details: String = "") {}
    fun logRafelLive(event: String, details: String = "") {}
    fun logRafelAudio(event: String, details: String = "") {}

    fun logRafelError(category: String, details: String, tr: Throwable? = null) {
        safeLogE("RAFEL-ERROR", "$category: $details", tr)
    }

    fun logRafelAudioRouting(outputDevice: String, mediaVolume: Int, maxVolume: Int, audioMode: String, isMusicActive: Boolean) {}

    fun logVoice(message: String) {}
    fun logLive(message: String) {}
    fun logAudio(message: String) {}
    fun logMic(message: String) {}
    fun logVad(message: String) {}

    fun logGeminiConnection(message: String) {
        safeLogI("${TAG_PREFIX}CONNECTION", sanitize(message))
    }

    fun logGeminiResponse(message: String) {}
    fun logAudioInput(message: String) {}
    fun logAudioOutput(message: String) {}

    fun logInterruption(message: String) {
        safeLogW("${TAG_PREFIX}INTERRUPT", message)
    }

    fun logDiagnostic(message: String) {}

    // Latency & pipeline tracer signatures (preserved as no-ops for API/test compatibility)
    fun trackMicStart() {}
    fun trackUserSpeechStart() {}
    fun trackUserSpeechEndConfirmed() {}
    fun trackUserSpeechEnd() {}
    fun trackVadEnd() {}
    fun trackAudioStreamEndSent() {}
    fun trackTurnCompleteSent() {}
    fun trackGeminiRequestSent() {}
    fun trackGeminiResponseStarted() {}
    fun trackFirstGeminiAudioReceived(generationId: Long, chunkSize: Int) {}
    fun trackFirstGeminiAudio(generationId: Long, chunkSize: Int) {}
    fun trackFirstAudioReceived(generationId: Long, chunkSize: Int) {}
    fun trackFirstAudioEnqueued(generationId: Long) {}
    fun trackAudioChunkReceived(chunkSize: Int, decodeTimeMs: Long) {}
    fun trackAudioChunkPlayed(written: Int, writeTimeMs: Long, queueSize: Int, underruns: Int) {}
    fun trackAudioPlaybackStart(generationId: Long) {}
    fun trackPlaybackStarted(generationId: Long) {}

    fun logError(message: String, throwable: Throwable? = null) {
        safeLogE("${TAG_PREFIX}ERROR", message, throwable)
    }

    // Explicit Trace Tags (no-ops in production)
    fun trace1UserSpeechStarted() {}
    fun trace2UserSpeechEnded() {}
    fun trace3UserTurnSent() {}
    fun trace4GeminiSessionState(state: String) {}
    fun trace5GeminiResponseReceived(details: String = "") {}
    fun trace6GeminiAudioChunkReceived(size: Int, genId: Long) {}
    fun trace7FirstAudioChunk(size: Int, genId: Long) {}
    fun trace8AudioPlaybackStart(genId: Long) {}
    fun trace9UiStateSpeaking() {}
    fun trace10ResponseCompleted(genId: Long) {}
    fun trace11UiStateIdle() {}

    fun logLiveSessionCreated(sessionId: Long) {}
    fun logLiveSessionConnected(sessionId: Long) {
        safeLogI("LIVE", "Live session connected: $sessionId")
    }
    fun logLiveSessionSetupComplete(sessionId: Long) {
        safeLogI("LIVE", "Live session setup complete: $sessionId")
    }
    fun logLiveSessionAudio(sessionId: Long, bytes: Int) {}
    fun logLiveSessionClosed(sessionId: Long, code: Int, reason: String) {
        safeLogI("LIVE", "Live session closed: code=$code, reason=$reason")
    }
    fun logLiveConnect() {}
    fun logLiveConnectSuccess(sessionId: Long) {}
    fun logLiveSetupSent(model: String) {}
    fun logLiveSetupAcknowledged() {}
    fun logLiveMessage(msgType: String, summary: String = "") {}
    fun logUserAudioSent(bytes: Int) {}
    fun logLiveAudioChunk(bytes: Int, genId: Long) {}
    fun logAudioPlaybackStarted(genId: Long) {}
    fun logUiSpeaking() {}

    fun logLiveError(errorMessage: String) {
        safeLogE("LIVE-ERROR", errorMessage)
    }

    fun logLiveErrorDetailed(
        exceptionClass: String,
        exceptionMessage: String,
        responseCode: Int?,
        rawBody: String?,
        model: String,
        sanitizedEndpoint: String
    ) {
        safeLogE("LIVE-ERROR", "$exceptionClass: $exceptionMessage (code=$responseCode, model=$model)")
    }

    fun logLiveClose(code: Int, reason: String) {}
    fun logLiveCloseDetailed(
        code: Int,
        reason: String,
        isIntentional: Boolean,
        reconnectTriggered: Boolean,
        currentState: String
    ) {}

    fun logInputPcm(bytes: Int, sampleRate: Int = 16000, channels: Int = 1, encoding: String = "PCM_16BIT") {}
    fun logInputDevice(type: String, name: String, id: Int) {}
    fun logUserTranscript(heard: String) {}
    fun logUserTurn(startTime: Long, endTime: Long, duration: Long, audioBytes: Long, turnComplete: Boolean) {}

    fun logToolContext(
        userTurnId: String,
        transcript: String?,
        toolCallId: String,
        requestedTool: String,
        intent: String,
        target: String?,
        validated: Boolean
    ) {}

    fun logToolDecision(
        userIntent: String,
        requestedTool: String,
        explicitAction: Boolean,
        target: String? = null,
        confidence: Float = 0.95f,
        allowed: Boolean
    ) {}

    fun logLiveReconnect(attempt: Int = 1) {}

    private fun sanitize(message: String): String {
        return message.replace(Regex("(?i)(key=)[A-Za-z0-9_-]{10,}"), "$1[REDACTED]")
            .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9_.-]{10,}"), "$1[REDACTED]")
    }
}
