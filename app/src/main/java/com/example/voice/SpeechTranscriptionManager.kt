package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Real-time Speech Transcription Manager for Rafel.
 * Bridges Android's SpeechRecognizer with the authoritative UserTurnManager
 * to capture live transcripts synchronized with the exact userTurnId.
 */
class SpeechTranscriptionManager(
    private val context: Context,
    private val userTurnManager: UserTurnManager
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var activeTurnId: Long = 0L

    var onTranscriptReady: ((turnId: Long, text: String, isFinal: Boolean) -> Unit)? = null

    fun initialize() {
        mainHandler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(createListener())
                    }
                    RafelLogger.logVoice("SpeechRecognizer initialized.")
                } else {
                    RafelLogger.logVoice("SpeechRecognizer not available on device.")
                }
            } catch (e: Exception) {
                RafelLogger.logVoice("SpeechRecognizer initialization notice: ${e.message}")
            }
        }
    }

    fun startListeningForTurn(turnId: Long) {
        activeTurnId = turnId
        mainHandler.post {
            try {
                val recognizer = speechRecognizer ?: try {
                    SpeechRecognizer.createSpeechRecognizer(context).also {
                        it.setRecognitionListener(createListener())
                        speechRecognizer = it
                    }
                } catch (e: Exception) {
                    null
                }

                if (recognizer != null) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    }
                    recognizer.startListening(intent)
                    RafelLogger.logLive("[STT] listening_started turn=#$turnId")
                    RafelLogger.logVoice("SpeechRecognizer listening for turn #$turnId")
                }
            } catch (e: Exception) {
                RafelLogger.logVoice("SpeechRecognizer startListening notice: ${e.message}")
            }
        }
    }

    fun stopListening(turnId: Long) {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun recordManualTranscript(turnId: Long, transcript: String) {
        userTurnManager.updateTranscript(turnId, transcript)
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                RafelLogger.logVoice("SpeechRecognizer code=$error for turn #$activeTurnId")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull { it.isNotBlank() }
                if (!text.isNullOrBlank()) {
                    RafelLogger.logLive("[TRANSCRIPT] FINAL_TRANSCRIPT: turn=#$activeTurnId")
                    RafelLogger.logVoice("SpeechRecognizer result for turn #$activeTurnId: \"$text\"")
                    userTurnManager.updateTranscript(activeTurnId, text)
                    com.example.conversation.RafelConversationManager.getInstance(context).addUserVoiceMessage(text)
                    onTranscriptReady?.invoke(activeTurnId, text, true)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull { it.isNotBlank() }
                if (!text.isNullOrBlank()) {
                    RafelLogger.logVoice("SpeechRecognizer partial result for turn #$activeTurnId: \"$text\"")
                    userTurnManager.updateTranscript(activeTurnId, text)
                    onTranscriptReady?.invoke(activeTurnId, text, false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
