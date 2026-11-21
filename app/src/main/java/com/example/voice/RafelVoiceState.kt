package com.example.voice

/**
 * Unified Voice States for Rafel.
 * Serves as the single source of truth for the entire voice interaction pipeline.
 */
enum class RafelVoiceState(val displayText: String) {
    IDLE("Rafel is ready"),
    LISTENING("Rafel is listening..."),
    USER_SPEAKING("Rafel is listening to you..."),
    WAITING_FOR_AI("Rafel is thinking..."),
    AI_SPEAKING("Rafel is speaking..."),
    INTERRUPTED("Rafel was interrupted."),
    MIC_OFF("Microphone is off"),
    ERROR("Connection error.")
}
