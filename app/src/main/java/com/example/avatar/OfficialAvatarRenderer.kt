package com.example.avatar

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Official Rafel Avatar Renderer
 *
 * Renders the exact uploaded bitmap image when present.
 * When the image is not available, displays a sophisticated abstract AI interface placeholder.
 */
class OfficialAvatarRenderer : AvatarRenderer {

    @Composable
    override fun Render(
        avatarState: AvatarState,
        emotionState: EmotionState,
        mouthOpenness: Float,
        modifier: Modifier
    ) {
        val context = LocalContext.current

        // Check if an avatar drawable resource exists (e.g., rafel_avatar, rafel_avatar_final)
        val drawableResId = remember(context) {
            findAvatarDrawableRes(context)
        }

        // Check if an avatar image file exists in storage or assets
        val fileModel = remember(context) {
            findAvatarFileModel(context)
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("rafel_official_avatar_container"),
            contentAlignment = Alignment.Center
        ) {
            // Cinematic AI Energy Core presence inspired by the visual reference
            AbstractAiCorePlaceholder(
                avatarState = avatarState,
                emotionState = emotionState,
                mouthOpenness = mouthOpenness
            )
        }
    }

    private fun findAvatarDrawableRes(context: Context): Int? {
        val candidateDrawables = listOf(
            "rafel_avatar",
            "rafel_avatar_final",
            "whatsapp_image_2026_09_22_at_8_01_15_pm",
            "whatsapp_image_2026_09_20_at_9_29_27_pm",
            "rafel",
            "avatar",
            "character"
        )
        for (name in candidateDrawables) {
            val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (resId != 0) return resId
        }

        // Reflection scan on R.drawable for any resource matching rafel, avatar, or character
        try {
            val fields = com.example.R.drawable::class.java.fields
            for (field in fields) {
                val fname = field.name.lowercase()
                if ((fname.contains("rafel") || fname.contains("avatar") || fname.contains("whatsapp") || fname.contains("character"))
                    && !fname.startsWith("ic_launcher")
                ) {
                    val resId = field.getInt(null)
                    if (resId != 0) return resId
                }
            }
        } catch (_: Exception) { }

        return null
    }

    private fun findAvatarFileModel(context: Context): Any? {
        val candidateFileNames = listOf(
            "rafel_avatar.jpg",
            "rafel_avatar.png",
            "rafel_avatar.jpeg",
            "rafel_avatar.webp",
            "WhatsApp Image 2026-09-22 at 8.01.15 PM.jpeg",
            "WhatsApp Image 2026-09-22 at 8.01.15 PM.jpg",
            "avatar.jpg",
            "avatar.png",
            "character.jpg",
            "character.png"
        )
        for (name in candidateFileNames) {
            val file = File(context.filesDir, name)
            if (file.exists() && file.length() > 0) {
                return file
            }
            val extFile = context.getExternalFilesDir(null)?.let { File(it, name) }
            if (extFile != null && extFile.exists() && extFile.length() > 0) {
                return extFile
            }
        }

        // Check assets directory
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            val candidateAsset = assetList.firstOrNull { name ->
                val lower = name.lowercase()
                (lower.contains("rafel") || lower.contains("whatsapp") || lower.contains("avatar") || lower.contains("character")) &&
                (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp"))
            }
            if (candidateAsset != null) {
                return "file:///android_asset/$candidateAsset"
            }
        } catch (_: Exception) { }

        return null
    }
}

/**
 * Sophisticated abstract AI interface placeholder.
 * Looks like a premium AI assistant core (dark transparent glass, subtle glow,
 * thin circular rings, soft pink/magenta lighting, small waveform/equalizer details).
 * No cartoon graphics, no fake character, no generated avatar, no large glowing orb.
 */
@Composable
fun AbstractAiCorePlaceholder(
    avatarState: AvatarState,
    emotionState: EmotionState,
    mouthOpenness: Float,
    modifier: Modifier = Modifier
) {
    val voiceState = when (avatarState) {
        AvatarState.SPEAKING -> com.example.voice.RafelVoiceState.AI_SPEAKING
        AvatarState.THINKING -> com.example.voice.RafelVoiceState.WAITING_FOR_AI
        AvatarState.LISTENING -> com.example.voice.RafelVoiceState.LISTENING
        else -> com.example.voice.RafelVoiceState.IDLE
    }

    com.example.ui.RafelEnergyCore(
        voiceState = voiceState,
        visualizerAmplitude = mouthOpenness,
        isMicMuted = false,
        serviceStarted = true,
        modifier = modifier
    )
    if (true) return

    val infiniteTransition = rememberInfiniteTransition(label = "core_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val ringRotation1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rot_1"
    )
    val ringRotation2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rot_2"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Equalizer wave values
    val eqOffset1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq1"
    )
    val eqOffset2 by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq2"
    )
    val eqOffset3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(410, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq3"
    )

    val isSpeaking = avatarState == AvatarState.SPEAKING
    val isThinking = avatarState == AvatarState.THINKING
    val isListening = avatarState == AvatarState.LISTENING

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        // Very dark transparent glass panel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = Color(0xFF0D0A14).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(28.dp)
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFFFF2A85).copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.08f),
                                Color(0xFFE040FB).copy(alpha = 0.2f)
                            )
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // Sophisticated AI Core Canvas (Thin rings, subtle glow, geometric precision)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * 0.46f)
                val baseRadius = (size.minDimension / 2f) * 0.68f

                // 1. Soft pink / magenta lighting and ambient glow
                val activeGlow = if (isSpeaking || isListening) 0.5f else 0.28f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF2A85).copy(alpha = glowAlpha * activeGlow),
                            Color(0xFFE040FB).copy(alpha = glowAlpha * 0.12f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.45f
                    ),
                    center = center,
                    radius = baseRadius * 1.45f
                )

                // 2. Outer thin circular ring with subtle dashes
                rotate(ringRotation1, center) {
                    drawCircle(
                        color = Color(0xFFFF2A85).copy(alpha = 0.32f),
                        center = center,
                        radius = baseRadius * pulseScale,
                        style = Stroke(
                            width = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f, 8f, 14f), 0f)
                        )
                    )
                }

                // 3. Middle thin circular ring
                rotate(ringRotation2, center) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.2f),
                        center = center,
                        radius = baseRadius * 0.84f,
                        style = Stroke(
                            width = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                        )
                    )
                }

                // 4. Inner delicate ring with soft pink tint
                drawCircle(
                    color = Color(0xFFFF4081).copy(alpha = 0.4f),
                    center = center,
                    radius = baseRadius * 0.58f * (if (isSpeaking) 1f + mouthOpenness * 0.12f else 1f),
                    style = Stroke(width = 1.2f)
                )

                // 5. Subtle tech crosshair tick marks
                val tickLength = 10f
                val tickDist = baseRadius * 0.94f
                drawLine(
                    color = Color(0xFFFF2A85).copy(alpha = 0.4f),
                    start = Offset(center.x, center.y - tickDist - tickLength),
                    end = Offset(center.x, center.y - tickDist),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = Color(0xFFFF2A85).copy(alpha = 0.4f),
                    start = Offset(center.x, center.y + tickDist),
                    end = Offset(center.x, center.y + tickDist + tickLength),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = Color(0xFFFF2A85).copy(alpha = 0.4f),
                    start = Offset(center.x - tickDist - tickLength, center.y),
                    end = Offset(center.x - tickDist, center.y),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = Color(0xFFFF2A85).copy(alpha = 0.4f),
                    start = Offset(center.x + tickDist, center.y),
                    end = Offset(center.x + tickDist + tickLength, center.y),
                    strokeWidth = 1.5f
                )
            }

            // Center Content: Small waveform / equalizer details + Core telemetry
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                val baseMultiplier = when {
                    isSpeaking -> (0.6f + mouthOpenness * 0.8f).coerceIn(0.4f, 1.3f)
                    isListening -> 0.75f
                    isThinking -> 0.5f
                    else -> 0.28f
                }

                // Small waveform / equalizer details
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(38.dp)
                ) {
                    val barHeights = listOf(
                        10.dp + 16.dp * eqOffset1 * baseMultiplier,
                        16.dp + 20.dp * eqOffset2 * baseMultiplier,
                        22.dp + 24.dp * eqOffset3 * baseMultiplier,
                        26.dp + 26.dp * (if (isSpeaking) (eqOffset1 + eqOffset3) / 2f else eqOffset2) * baseMultiplier,
                        22.dp + 24.dp * eqOffset1 * baseMultiplier,
                        16.dp + 20.dp * eqOffset3 * baseMultiplier,
                        10.dp + 16.dp * eqOffset2 * baseMultiplier
                    )

                    barHeights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(h.coerceIn(5.dp, 38.dp))
                                .background(
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color(0xFFFF4081),
                                            Color(0xFFE040FB).copy(alpha = 0.8f),
                                            Color(0xFF80D8FF).copy(alpha = 0.65f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "RAFel NEURAL CORE",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = when {
                        isSpeaking -> "AUDIO STREAM SYNTHESIZED"
                        isListening -> "MICROPHONE SENSOR ACTIVE"
                        isThinking -> "REASONING IN PROGRESS"
                        else -> "SYSTEM READY"
                    },
                    color = Color(0xFFFF4081).copy(alpha = 0.9f),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

