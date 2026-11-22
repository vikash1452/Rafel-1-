package com.example.voice

import android.media.AudioFormat

/**
 * Centralized configuration values for Rafel's voice, VAD, and audio streaming systems.
 * Engineered for natural human conversational prosody, ultra-low latency, and zero clipping.
 */
object VoiceConfig {
    // Microphone Input (Gemini Live expects 16kHz, 16-bit PCM, Mono)
    const val SAMPLE_RATE_IN = 16000
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // 40ms chunk at 16kHz = 640 samples
    const val CHUNK_SIZE_SAMPLES = 640

    // Audio Output from Gemini Live (24kHz, 16-bit PCM, Mono)
    const val SAMPLE_RATE_OUT = 24000
    const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO

    // Output startup buffer threshold in bytes:
    // With progressive streaming, we start playback immediately on the first valid chunk.
    // Kept minimal to eliminate unnecessary TTFA delay while preserving PCM 16-bit frame alignment.
    const val STARTUP_BUFFER_BYTES = 0

    // Max bounded queue size: 1024 chunks (~20-30 seconds of audio)
    // Prevents chunk drops during multi-sentence Gemini generation bursts while strictly bounding memory.
    const val MAX_AUDIO_QUEUE_SIZE = 1024

    // Continuous AudioTrack internal hardware buffer capacity (14400 bytes = 300ms)
    // Low latency onset while still absorbing network packet jitter without underrun.
    const val AUDIOTRACK_BUFFER_BYTES = 14400

    // Natural Conversational VAD Parameters
    // RMS threshold for speech detection (16-bit PCM amplitude scale ~ 0 to 32767)
    const val SPEECH_THRESHOLD = 450.0

    // Stricter RMS threshold during AI speech playback for intentional user barge-in detection
    const val BARGE_IN_THRESHOLD = 1750.0

    // Minimum consecutive speech chunks required to trigger speech onset (1 chunk = 40ms)
    // Instant user speech reaction without waiting for multiple chunk buffers.
    const val VAD_MIN_SPEECH_CHUNKS = 1

    // Minimum consecutive chunks required to trigger barge-in during AI speech (2 chunks = 80ms)
    const val VAD_MIN_BARGE_IN_CHUNKS = 2

    // Post-playback echo suppression window (220ms) to prevent loudspeaker reverb tail
    // from being misinterpreted as user speech while allowing snappy conversational turns
    const val POST_AI_SPEECH_ECHO_GUARD_MS = 220L

    // Balanced Natural End-of-Speech silence timeout (460-520ms):
    // Fast sub-second turnaround while letting the user pause naturally for breath mid-sentence
    const val END_SILENCE_SHORT_UTTERANCE_MS = 520L
    const val END_SILENCE_NORMAL_MS = 460L

    // Pre-buffer duration to capture starting consonants (~160ms = 4 chunks of 40ms)
    const val PRE_BUFFER_CHUNKS = 4

    // Post-buffer duration to retain trailing speech and word endings (~80ms = 2 chunks of 40ms)
    const val POST_BUFFER_CHUNKS = 2

    // Maximum utterance safeguard (25 seconds)
    const val VAD_MAX_UTTERANCE_MS = 25000L

    // Gemini Live Model - Native Audio
    const val GEMINI_LIVE_MODEL = "models/gemini-3.8-live"
    // Sulafat: Gemini 2.5 Native Audio's warmest, sweetest, most emotionally expressive HD female voice
    const val GEMINI_VOICE_NAME = "Sulafat"
}
