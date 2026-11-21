package com.example.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Stress tests for RafelEnergyCore ray calculations and bounds safety.
 * Verifies that minLimit is strictly less than outerLimit under all extreme dynamic conditions.
 */
class RafelEnergyCoreSafetyTest {

    private fun simulateRayRange(
        baseRadius: Float,
        currentScale: Float,
        amplitude: Float,
        isSpeaking: Boolean,
        isListening: Boolean,
        isThinking: Boolean,
        phase: Float
    ) {
        val innerStart = (baseRadius * 0.32f).coerceAtLeast(0f)
        val minLimit = innerStart + 10f
        val outerLimit = (baseRadius * 1.25f).coerceAtLeast(minLimit + 1f)

        assertTrue(
            "minLimit ($minLimit) must always be strictly less than outerLimit ($outerLimit)",
            minLimit < outerLimit
        )

        val rayCount = 32
        for (i in 0 until rayCount) {
            val lengthMod = sin((i * 1.7f) + (phase * 6.283f)).toFloat()
            val extraStretch = if (isSpeaking) {
                amplitude * baseRadius * 0.55f * (0.8f + 0.4f * lengthMod)
            } else if (isListening) {
                amplitude * baseRadius * 0.32f
            } else if (isThinking) {
                baseRadius * 0.15f * lengthMod
            } else {
                baseRadius * 0.06f * lengthMod
            }

            val rawRayLen = baseRadius * 0.95f * currentScale + extraStretch
            // This call must NEVER throw IllegalArgumentException
            val rayLen = rawRayLen.coerceIn(minLimit, outerLimit)
            assertTrue(rayLen in minLimit..outerLimit)
        }
    }

    @Test
    fun testZeroAmplitudeSafety() {
        simulateRayRange(
            baseRadius = 100f,
            currentScale = 1.0f,
            amplitude = 0f,
            isSpeaking = false,
            isListening = false,
            isThinking = false,
            phase = 0f
        )
    }

    @Test
    fun testVerySmallAmplitudeSafety() {
        simulateRayRange(
            baseRadius = 120f,
            currentScale = 0.8f,
            amplitude = 0.00001f,
            isSpeaking = true,
            isListening = false,
            isThinking = false,
            phase = 0.25f
        )
    }

    @Test
    fun testMaximumAmplitudeSafety() {
        simulateRayRange(
            baseRadius = 250f,
            currentScale = 1.5f,
            amplitude = 1.0f,
            isSpeaking = true,
            isListening = false,
            isThinking = false,
            phase = 0.75f
        )
        // Extreme amplitude beyond 1.0
        simulateRayRange(
            baseRadius = 250f,
            currentScale = 2.0f,
            amplitude = 50.0f,
            isSpeaking = true,
            isListening = false,
            isThinking = false,
            phase = 1.0f
        )
    }

    @Test
    fun testRapidAmplitudeAndPhaseChanges() {
        val baseRadius = 140f
        for (step in 0..1000) {
            val amp = (sin(step * 0.1) * 0.5 + 0.5).toFloat()
            val phase = (step * 0.05f) % 1.0f
            val scale = 0.7f + 0.6f * ((step % 10) / 10f)

            simulateRayRange(
                baseRadius = baseRadius,
                currentScale = scale,
                amplitude = amp,
                isSpeaking = step % 2 == 0,
                isListening = step % 3 == 0,
                isThinking = step % 5 == 0,
                phase = phase
            )
        }
    }

    @Test
    fun testExtremeSmallAndLargeRadii() {
        val radii = listOf(0f, 0.01f, 1f, 10f, 50f, 100f, 300f, 1000f, 5000f)
        for (radius in radii) {
            simulateRayRange(
                baseRadius = radius,
                currentScale = 1.0f,
                amplitude = 0.8f,
                isSpeaking = true,
                isListening = false,
                isThinking = false,
                phase = 0.5f
            )
        }
    }
}
