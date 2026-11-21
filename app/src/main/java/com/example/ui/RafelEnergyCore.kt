package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.voice.RafelVoiceState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cinematic Energy Core Color Palette
 */
private val CorePureWhite = Color(0xFFFFFFFF)
private val CoreBrightGold = Color(0xFFFFF7C2)
private val CoreAmberGold = Color(0xFFFFD54F)
private val CoreWarmGold = Color(0xFFFFB300)
private val CoreVibrantOrange = Color(0xFFFF9100)
private val CoreDeepOrange = Color(0xFFFF6D00)
private val CoreFieryOrange = Color(0xFFFF3D00)
private val CoreCrimsonAccent = Color(0xFFFF1744)
private val CorePinkAccent = Color(0xFFFF2A85)

/**
 * Pre-allocated particle representation for zero-allocation 60 FPS animation.
 */
private class EnergySpark(
    val initialAngle: Float,
    val distanceRatio: Float,
    val speed: Float,
    val baseSize: Float,
    val alphaOffset: Float,
    val colorVariant: Int // 0: White/Gold, 1: Amber, 2: Deep Orange
)

/**
 * Rafel Energy Core
 *
 * A dramatic, futuristic, cinematic AI Presence inspired by a powerful central glowing
 * energy core surrounded by rotating circular symbols, rings, radial energy filaments,
 * and floating sparks.
 *
 * States:
 * - IDLE: Slow gentle pulse, calm outer rings, drifting sparks.
 * - LISTENING: Core brightens, outer rings and radial energy expand with mic input.
 * - THINKING: Rings rotate faster, sweeping orbital energy beam traverses the core, particles accelerate.
 * - SPEAKING: Core vigorously pulses with playback audio amplitude, flares radial energy rays.
 * - MIC OFF: Input reactions halt, core remains poised and softly luminous.
 */
@Composable
fun RafelEnergyCore(
    voiceState: RafelVoiceState,
    visualizerAmplitude: Float,
    isMicMuted: Boolean,
    serviceStarted: Boolean,
    modifier: Modifier = Modifier
) {
    val isMicOff = isMicMuted || !serviceStarted
    val isListening = !isMicOff && (voiceState == RafelVoiceState.LISTENING || voiceState == RafelVoiceState.USER_SPEAKING)
    val isThinking = !isMicOff && voiceState == RafelVoiceState.WAITING_FOR_AI
    val isSpeaking = !isMicOff && voiceState == RafelVoiceState.AI_SPEAKING

    // Smooth amplitude spring
    val targetAmp = if (isMicOff) 0f else visualizerAmplitude.coerceIn(0f, 1f)
    val animatedAmp by animateFloatAsState(
        targetValue = targetAmp,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 600f),
        label = "core_amplitude"
    )

    // Infinite transitions for smooth continuous rotations & pulses
    val infiniteTransition = rememberInfiniteTransition(label = "energy_core_infinite")

    // Idle core breath
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_pulse"
    )

    // Inner ring rotation (clockwise)
    val innerRotDuration = if (isThinking) 5000 else if (isSpeaking) 9000 else 18000
    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(innerRotDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_rot"
    )

    // Middle glyph ring rotation (counter-clockwise)
    val middleRotDuration = if (isThinking) 6500 else if (isSpeaking) 11000 else 24000
    val middleRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(middleRotDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "middle_rot"
    )

    // Outer telemetry ring rotation (slow clockwise)
    val outerRotDuration = if (isThinking) 9000 else 36000
    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(outerRotDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_rot"
    )

    // Orbital sweeping beam (for THINKING and active states)
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isThinking) 1200 else 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    // Spark particles phase
    val particlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isThinking) 1800 else if (isSpeaking) 2600 else 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particle_phase"
    )

    // Generate fixed particle positions (pre-allocated)
    val particles = remember {
        List(36) { index ->
            val angle = (index * 10f) + (index % 5) * 3f
            val distanceRatio = 0.45f + (index % 7) * 0.08f
            val speed = 0.6f + (index % 4) * 0.35f
            val baseSize = 2.5f + (index % 3) * 1.5f
            val alphaOffset = (index % 6) / 6f
            val colorVariant = index % 3
            EnergySpark(angle, distanceRatio, speed, baseSize, alphaOffset, colorVariant)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("rafel_energy_core_container"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val minDim = size.minDimension
            if (minDim <= 10f) return@Canvas

            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = minDim * 0.42f
            if (baseRadius <= 10f) return@Canvas

            // Dynamic scale factor based on voice amplitude and state
            val amplitudeBoost = if (isSpeaking) {
                animatedAmp * 0.38f
            } else if (isListening) {
                animatedAmp * 0.22f
            } else if (isThinking) {
                0.08f
            } else {
                0f
            }

            val currentScale = (idlePulse + amplitudeBoost).coerceIn(0.85f, 1.45f)

            // 1. BACKGROUND AMBIENT RADIAL GLOW
            drawAmbientBackgroundGlow(
                center = center,
                radius = baseRadius * 1.6f * currentScale,
                isThinking = isThinking,
                isSpeaking = isSpeaking,
                isListening = isListening,
                isMicOff = isMicOff,
                amplitude = animatedAmp
            )

            // 2. RADIAL ENERGY LINES (FILAMENTS / RAYS)
            drawRadialEnergyRays(
                center = center,
                baseRadius = baseRadius,
                currentScale = currentScale,
                amplitude = animatedAmp,
                isSpeaking = isSpeaking,
                isListening = isListening,
                isThinking = isThinking,
                isMicOff = isMicOff,
                phase = particlePhase
            )

            // 3. OUTER TELEMETRY & CONSTELLATION RING
            rotate(outerRotation, center) {
                drawOuterTelemetryRing(
                    center = center,
                    radius = baseRadius * 1.05f * currentScale,
                    isThinking = isThinking
                )
            }

            // 4. MIDDLE GLYPH & SYMBOL RING (Complex circular futuristic markings)
            rotate(middleRotation, center) {
                drawFuturisticGlyphRing(
                    center = center,
                    radius = baseRadius * 0.78f,
                    isSpeaking = isSpeaking,
                    isThinking = isThinking
                )
            }

            // 5. INNER SEGMENTED ARC RING
            rotate(innerRotation, center) {
                drawInnerSegmentedArcRing(
                    center = center,
                    radius = baseRadius * 0.54f * (1f + amplitudeBoost * 0.5f),
                    isThinking = isThinking,
                    sweepAngle = sweepAngle
                )
            }

            // 6. ORBITAL SWEEPING ENERGY BEAM (During THINKING or SPEAKING)
            drawOrbitalEnergyBeam(
                center = center,
                radius = baseRadius * 0.66f,
                sweepAngle = sweepAngle,
                isThinking = isThinking,
                isSpeaking = isSpeaking
            )

            // 7. FLOATING ENERGY SPARKS & PARTICLES
            drawEnergyParticles(
                center = center,
                baseRadius = baseRadius,
                particles = particles,
                phase = particlePhase,
                amplitude = animatedAmp,
                isThinking = isThinking,
                isSpeaking = isSpeaking
            )

            // 8. CENTRAL ENERGY CORE (Bright White/Gold singularity + Multi-layer orange/amber bloom)
            drawCentralEnergyCore(
                center = center,
                baseRadius = baseRadius,
                currentScale = currentScale,
                amplitude = animatedAmp,
                isSpeaking = isSpeaking,
                isListening = isListening,
                isThinking = isThinking,
                isMicOff = isMicOff
            )
        }
    }
}

/**
 * 1. Ambient Background Glow
 */
private fun DrawScope.drawAmbientBackgroundGlow(
    center: Offset,
    radius: Float,
    isThinking: Boolean,
    isSpeaking: Boolean,
    isListening: Boolean,
    isMicOff: Boolean,
    amplitude: Float
) {
    val coreAlpha = when {
        isMicOff -> 0.18f
        isSpeaking -> (0.45f + amplitude * 0.35f).coerceIn(0.45f, 0.85f)
        isListening -> (0.35f + amplitude * 0.25f).coerceIn(0.35f, 0.65f)
        isThinking -> 0.40f
        else -> 0.26f
    }

    // Outer warm crimson/amber ambience
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CoreDeepOrange.copy(alpha = coreAlpha * 0.55f),
                CoreFieryOrange.copy(alpha = coreAlpha * 0.25f),
                CoreCrimsonAccent.copy(alpha = coreAlpha * 0.08f),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.5f
        ),
        radius = radius * 1.5f,
        center = center
    )
}

/**
 * 2. Radial Energy Lines (Filaments extending outward)
 */
private fun DrawScope.drawRadialEnergyRays(
    center: Offset,
    baseRadius: Float,
    currentScale: Float,
    amplitude: Float,
    isSpeaking: Boolean,
    isListening: Boolean,
    isThinking: Boolean,
    isMicOff: Boolean,
    phase: Float
) {
    val rayCount = 32
    val innerStart = (baseRadius * 0.32f).coerceAtLeast(0f)
    val minLimit = innerStart + 10f
    val outerLimit = (baseRadius * 1.25f).coerceAtLeast(minLimit + 1f)

    for (i in 0 until rayCount) {
        val angleDeg = (i * (360f / rayCount))
        val angleRad = (angleDeg * PI / 180f).toFloat()

        // Modulation
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
        val rayLen = rawRayLen.coerceIn(minLimit, outerLimit)
        val startRadius = innerStart + (if (i % 2 == 0) 12f else 28f)

        val startX = center.x + cos(angleRad) * startRadius
        val startY = center.y + sin(angleRad) * startRadius
        val endX = center.x + cos(angleRad) * rayLen
        val endY = center.y + sin(angleRad) * rayLen

        val isMajorRay = (i % 4 == 0)
        val isMediumRay = (i % 2 == 0)

        val rayColor = when {
            isMicOff -> CoreWarmGold.copy(alpha = 0.15f)
            isMajorRay -> CoreBrightGold.copy(alpha = if (isSpeaking || isListening) 0.85f else 0.55f)
            isMediumRay -> CoreAmberGold.copy(alpha = if (isSpeaking) 0.65f else 0.35f)
            else -> CoreVibrantOrange.copy(alpha = if (isThinking) 0.50f else 0.25f)
        }

        val strokeWidth = if (isMajorRay) 2.2f else if (isMediumRay) 1.4f else 0.9f

        drawLine(
            color = rayColor,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Small bright tick mark at the tips of major rays
        if (isMajorRay) {
            val tipX = center.x + cos(angleRad) * (rayLen + 4f)
            val tipY = center.y + sin(angleRad) * (rayLen + 4f)
            drawCircle(
                color = CoreBrightGold.copy(alpha = if (isSpeaking) 0.9f else 0.6f),
                radius = 1.8f,
                center = Offset(tipX, tipY)
            )
        }
    }
}

/**
 * 3. Outer Telemetry & Constellation Ring
 */
private fun DrawScope.drawOuterTelemetryRing(
    center: Offset,
    radius: Float,
    isThinking: Boolean
) {
    // Thin outer dashed line
    drawCircle(
        color = CoreWarmGold.copy(alpha = 0.32f),
        center = center,
        radius = radius,
        style = Stroke(
            width = 1.2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 15f, 6f, 15f), 0f)
        )
    )

    // Perimeter degree marks (72 notches)
    val notchCount = 48
    for (i in 0 until notchCount) {
        val angleRad = (i * (360f / notchCount) * PI / 180f).toFloat()
        val isMajor = (i % 4 == 0)
        val notchLen = if (isMajor) 9f else 4.5f

        val x1 = center.x + cos(angleRad) * (radius - notchLen)
        val y1 = center.y + sin(angleRad) * (radius - notchLen)
        val x2 = center.x + cos(angleRad) * radius
        val y2 = center.y + sin(angleRad) * radius

        drawLine(
            color = if (isMajor) CoreBrightGold.copy(alpha = 0.55f) else CoreDeepOrange.copy(alpha = 0.28f),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = if (isMajor) 1.5f else 0.9f
        )
    }

    // 4 Corner cardinal telemetry brackets
    val cardinalAngles = floatArrayOf(0f, 90f, 180f, 270f)
    for (cardinal in cardinalAngles) {
        val rad = (cardinal * PI / 180f).toFloat()
        val nodeX = center.x + cos(rad) * radius
        val nodeY = center.y + sin(rad) * radius

        drawCircle(
            color = CoreBrightGold,
            radius = 3f,
            center = Offset(nodeX, nodeY)
        )
        drawCircle(
            color = CoreWarmGold.copy(alpha = 0.5f),
            radius = 6f,
            center = Offset(nodeX, nodeY),
            style = Stroke(width = 1f)
        )
    }
}

/**
 * 4. Middle Glyph & Symbol Ring (Futuristic circular markings)
 */
private fun DrawScope.drawFuturisticGlyphRing(
    center: Offset,
    radius: Float,
    isSpeaking: Boolean,
    isThinking: Boolean
) {
    // Primary circular track
    drawCircle(
        color = CoreWarmGold.copy(alpha = 0.42f),
        center = center,
        radius = radius,
        style = Stroke(width = 1.4f)
    )

    // Inner subtle concentric guide
    drawCircle(
        color = CoreDeepOrange.copy(alpha = 0.20f),
        center = center,
        radius = radius - 14f,
        style = Stroke(
            width = 0.9f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )
    )

    // 12 Arc/Glyph clusters around the circumference
    val glyphCount = 12
    for (g in 0 until glyphCount) {
        val angleDeg = g * 30f
        val angleRad = (angleDeg * PI / 180f).toFloat()

        val glyphCenterX = center.x + cos(angleRad) * radius
        val glyphCenterY = center.y + sin(angleRad) * radius

        when (g % 4) {
            0 -> {
                // Diamond glyph marker
                val diamondPath = Path().apply {
                    moveTo(glyphCenterX, glyphCenterY - 6f)
                    lineTo(glyphCenterX + 5f, glyphCenterY)
                    lineTo(glyphCenterX, glyphCenterY + 6f)
                    lineTo(glyphCenterX - 5f, glyphCenterY)
                    close()
                }
                drawPath(
                    path = diamondPath,
                    color = CoreBrightGold.copy(alpha = if (isSpeaking) 0.9f else 0.75f)
                )
            }
            1 -> {
                // Chevron / Angle bracket
                val perpRad = angleRad + (PI / 2f).toFloat()
                val p1 = Offset(
                    glyphCenterX - cos(perpRad) * 6f - cos(angleRad) * 4f,
                    glyphCenterY - sin(perpRad) * 6f - sin(angleRad) * 4f
                )
                val pCenter = Offset(glyphCenterX, glyphCenterY)
                val p2 = Offset(
                    glyphCenterX + cos(perpRad) * 6f - cos(angleRad) * 4f,
                    glyphCenterY + sin(perpRad) * 6f - sin(angleRad) * 4f
                )
                drawLine(
                    color = CoreAmberGold.copy(alpha = 0.85f),
                    start = p1,
                    end = pCenter,
                    strokeWidth = 1.6f
                )
                drawLine(
                    color = CoreAmberGold.copy(alpha = 0.85f),
                    start = pCenter,
                    end = p2,
                    strokeWidth = 1.6f
                )
            }
            2 -> {
                // Concentric circular node with cross
                drawCircle(
                    color = CoreBrightGold.copy(alpha = 0.9f),
                    radius = 3f,
                    center = Offset(glyphCenterX, glyphCenterY)
                )
                drawCircle(
                    color = CoreDeepOrange.copy(alpha = 0.6f),
                    radius = 7f,
                    center = Offset(glyphCenterX, glyphCenterY),
                    style = Stroke(width = 1f)
                )
            }
            3 -> {
                // Triple futuristic dashes
                val perpRad = angleRad + (PI / 2f).toFloat()
                for (d in -1..1) {
                    val dX = glyphCenterX + cos(perpRad) * (d * 5f)
                    val dY = glyphCenterY + sin(perpRad) * (d * 5f)
                    drawLine(
                        color = CoreWarmGold.copy(alpha = 0.75f),
                        start = Offset(dX - cos(angleRad) * 4f, dY - sin(angleRad) * 4f),
                        end = Offset(dX + cos(angleRad) * 4f, dY + sin(angleRad) * 4f),
                        strokeWidth = 1.3f
                    )
                }
            }
        }
    }
}

/**
 * 5. Inner Segmented Arc Ring
 */
private fun DrawScope.drawInnerSegmentedArcRing(
    center: Offset,
    radius: Float,
    isThinking: Boolean,
    sweepAngle: Float
) {
    val diameter = radius * 2f
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(diameter, diameter)

    // Segment 1 (90° arc)
    drawArc(
        color = CoreBrightGold.copy(alpha = 0.65f),
        startAngle = 15f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 2.4f, cap = StrokeCap.Round)
    )

    // Segment 2 (40° arc)
    drawArc(
        color = CoreDeepOrange.copy(alpha = 0.55f),
        startAngle = 105f,
        sweepAngle = 40f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 2.4f, cap = StrokeCap.Round)
    )

    // Segment 3 (100° arc)
    drawArc(
        color = CoreAmberGold.copy(alpha = 0.75f),
        startAngle = 165f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 2.4f, cap = StrokeCap.Round)
    )

    // Segment 4 (50° arc)
    drawArc(
        color = CoreFieryOrange.copy(alpha = 0.60f),
        startAngle = 285f,
        sweepAngle = 55f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 2.4f, cap = StrokeCap.Round)
    )
}

/**
 * 6. Orbital Sweeping Energy Beam (Dynamic energy ring)
 */
private fun DrawScope.drawOrbitalEnergyBeam(
    center: Offset,
    radius: Float,
    sweepAngle: Float,
    isThinking: Boolean,
    isSpeaking: Boolean
) {
    if (!isThinking && !isSpeaking) return

    val diameter = radius * 2f
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(diameter, diameter)

    val beamSweep = if (isThinking) 110f else 60f
    val beamColor = if (isThinking) CoreBrightGold else CoreWarmGold

    drawArc(
        brush = Brush.sweepGradient(
            listOf(
                Color.Transparent,
                beamColor.copy(alpha = 0.1f),
                beamColor.copy(alpha = 0.85f),
                CorePureWhite
            ),
            center = center
        ),
        startAngle = sweepAngle,
        sweepAngle = beamSweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = if (isThinking) 3f else 2f, cap = StrokeCap.Round)
    )
}

/**
 * 7. Floating Energy Sparks & Particles
 */
private fun DrawScope.drawEnergyParticles(
    center: Offset,
    baseRadius: Float,
    particles: List<EnergySpark>,
    phase: Float,
    amplitude: Float,
    isThinking: Boolean,
    isSpeaking: Boolean
) {
    for (spark in particles) {
        val currentProgress = (phase * spark.speed + spark.alphaOffset) % 1f
        val radDist = baseRadius * (spark.distanceRatio + currentProgress * 0.45f)
        val particleAngle = spark.initialAngle + (currentProgress * 45f)
        val angleRad = (particleAngle * PI / 180f).toFloat()

        val pX = center.x + cos(angleRad) * radDist
        val pY = center.y + sin(angleRad) * radDist

        // Smooth parabolic alpha (fades in, peaks, fades out)
        val alphaCurve = sin(currentProgress * PI).toFloat()
        val boost = if (isThinking) 1.2f else if (isSpeaking) 1.3f else 1.0f
        val finalAlpha = (alphaCurve * boost).coerceIn(0f, 1f)

        val sparkColor = when (spark.colorVariant) {
            0 -> CoreBrightGold.copy(alpha = finalAlpha * 0.95f)
            1 -> CoreAmberGold.copy(alpha = finalAlpha * 0.80f)
            else -> CoreDeepOrange.copy(alpha = finalAlpha * 0.70f)
        }

        val dynamicSize = (spark.baseSize * (1f + amplitude * 0.8f)).coerceIn(1.5f, 6f)

        drawCircle(
            color = sparkColor,
            radius = dynamicSize,
            center = Offset(pX, pY)
        )

        // Subtle bloom around brighter particles
        if (spark.colorVariant == 0 && finalAlpha > 0.4f) {
            drawCircle(
                color = CoreWarmGold.copy(alpha = finalAlpha * 0.25f),
                radius = dynamicSize * 2.4f,
                center = Offset(pX, pY)
            )
        }
    }
}

/**
 * 8. Central Energy Core (Singularity & Concentric Orange/Gold Glow)
 */
private fun DrawScope.drawCentralEnergyCore(
    center: Offset,
    baseRadius: Float,
    currentScale: Float,
    amplitude: Float,
    isSpeaking: Boolean,
    isListening: Boolean,
    isThinking: Boolean,
    isMicOff: Boolean
) {
    // Outer intense glow radius
    val outerGlowRadius = baseRadius * 0.46f * currentScale
    val midGlowRadius = baseRadius * 0.30f * currentScale
    val innerCoreRadius = baseRadius * 0.16f * currentScale
    val singularityRadius = baseRadius * 0.08f * currentScale

    // Layer 1: Fiery Deep Orange / Crimson Outer Bloom
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CoreDeepOrange.copy(alpha = if (isMicOff) 0.4f else 0.85f),
                CoreFieryOrange.copy(alpha = 0.55f),
                CoreCrimsonAccent.copy(alpha = 0.22f),
                Color.Transparent
            ),
            center = center,
            radius = outerGlowRadius
        ),
        radius = outerGlowRadius,
        center = center
    )

    // Layer 2: Vibrant Amber & Gold Radiance
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CoreAmberGold.copy(alpha = 0.95f),
                CoreWarmGold.copy(alpha = 0.75f),
                CoreDeepOrange.copy(alpha = 0.35f),
                Color.Transparent
            ),
            center = center,
            radius = midGlowRadius
        ),
        radius = midGlowRadius,
        center = center
    )

    // Layer 3: Warm Golden Core
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CorePureWhite,
                CoreBrightGold,
                CoreAmberGold.copy(alpha = 0.85f),
                Color.Transparent
            ),
            center = center,
            radius = innerCoreRadius
        ),
        radius = innerCoreRadius,
        center = center
    )

    // Layer 4: Intense White-Hot Singularity Point (Extremely bright center)
    val hotSpotRadius = (singularityRadius * (1f + amplitude * 0.35f)).coerceIn(6f, 32f)
    drawCircle(
        color = CorePureWhite,
        radius = hotSpotRadius,
        center = center
    )

    // Layer 5: Very sharp concentric energy containment rings right around the singularity
    drawCircle(
        color = CoreBrightGold.copy(alpha = 0.85f),
        radius = hotSpotRadius + 4f,
        center = center,
        style = Stroke(width = 1.5f)
    )

    drawCircle(
        color = CoreAmberGold.copy(alpha = 0.45f),
        radius = hotSpotRadius + 10f,
        center = center,
        style = Stroke(
            width = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        )
    )
}
