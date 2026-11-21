package com.example.avatar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Real-time audio-driven Lip-Sync controller.
 *
 * Consumes PCM 16-bit 24 kHz audio chunks from Gemini Live playback.
 * Calculates Root Mean Square (RMS) energy, normalizes it to [0.0f .. 1.0f],
 * and applies smoothing and decay to produce natural mouth openness dynamics.
 */
class LipSyncController {

    private val _mouthOpenness = MutableStateFlow(0f)
    val mouthOpenness: StateFlow<Float> = _mouthOpenness.asStateFlow()

    private var smoothedLevel = 0f

    /**
     * Updates lip-sync using raw PCM 16-bit 24kHz audio bytes.
     */
    fun processAudioChunk(pcmData: ByteArray) {
        if (pcmData.size < 2) {
            decay()
            return
        }

        var sumSquares = 0.0
        val sampleCount = pcmData.size / 2

        var i = 0
        while (i < pcmData.size - 1) {
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        // Normalize RMS with thresholding (noise floor ~300, speech peak ~7000)
        val normalized = ((rms - 350.0) / 4500.0).coerceIn(0.0, 1.0).toFloat()

        // Attack & Decay smoothing for natural mouth articulation
        smoothedLevel = if (normalized > smoothedLevel) {
            smoothedLevel * 0.4f + normalized * 0.6f // Fast attack
        } else {
            smoothedLevel * 0.75f + normalized * 0.25f // Smooth decay
        }

        _mouthOpenness.value = smoothedLevel
    }

    fun updateAudioLevel(normalizedLevel: Float) {
        val clamped = normalizedLevel.coerceIn(0f, 1f)
        smoothedLevel = smoothedLevel * 0.5f + clamped * 0.5f
        _mouthOpenness.value = smoothedLevel
    }

    private fun decay() {
        smoothedLevel *= 0.7f
        if (smoothedLevel < 0.02f) smoothedLevel = 0f
        _mouthOpenness.value = smoothedLevel
    }

    /**
     * Resets lip sync immediately (e.g. on user barge-in / interruption).
     */
    fun reset() {
        smoothedLevel = 0f
        _mouthOpenness.value = 0f
    }
}
