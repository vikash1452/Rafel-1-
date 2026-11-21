package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Premium HSB (Hue, Saturation, Brightness) color picker dialog with visual sliders and live preview.
 */
@Composable
fun CustomHsbColorPickerDialog(
    initialColorLong: Long,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialColor = Color(initialColorLong)
    val initialHsb = remember(initialColorLong) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (initialColor.alpha * 255).toInt(),
                (initialColor.red * 255).toInt(),
                (initialColor.green * 255).toInt(),
                (initialColor.blue * 255).toInt()
            ),
            hsv
        )
        hsv
    }

    var hue by remember { mutableFloatStateOf(initialHsb[0]) } // 0f .. 360f
    var saturation by remember { mutableFloatStateOf(initialHsb[1].coerceIn(0.01f, 1f)) } // 0f .. 1f
    var brightness by remember { mutableFloatStateOf(initialHsb[2].coerceIn(0.01f, 1f)) } // 0f .. 1f

    val currentColorLong by remember(hue, saturation, brightness) {
        derivedStateOf {
            val hsv = floatArrayOf(hue, saturation, brightness)
            val argb = android.graphics.Color.HSVToColor(0xFF, hsv)
            argb.toLong() and 0xFFFFFFFFL
        }
    }

    val currentColor = Color(currentColorLong)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF100B1D),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CUSTOM COLOR",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Color Preview with Hex Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF171228))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(currentColor)
                            .border(2.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = String.format("#%06X", (currentColorLong and 0x00FFFFFFL)),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Live Selected Chroma",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 1. Hue Spectrum Slider
                Text(
                    text = "HUE (${hue.toInt()}°)",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                HueSliderBar(
                    hue = hue,
                    onHueChange = { hue = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Saturation Slider
                Text(
                    text = "SATURATION (${(saturation * 100).toInt()}%)",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                SaturationSliderBar(
                    hue = hue,
                    brightness = brightness,
                    saturation = saturation,
                    onSaturationChange = { saturation = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Brightness Slider
                Text(
                    text = "BRIGHTNESS (${(brightness * 100).toInt()}%)",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                BrightnessSliderBar(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onBrightnessChange = { brightness = it }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Quick Palette Swatches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val quickColors = listOf(
                        0xFFFF2A85, 0xFF7C4DFF, 0xFF00E5FF, 0xFF2979FF,
                        0xFF00E676, 0xFFFF9100, 0xFFFF1744, 0xFFFFFFFF
                    )
                    quickColors.forEach { cLong ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(cLong))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                .clickable {
                                    val qHsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(cLong.toInt(), qHsv)
                                    hue = qHsv[0]
                                    saturation = qHsv[1]
                                    brightness = qHsv[2]
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                    }

                    Button(
                        onClick = {
                            onColorSelected(currentColorLong)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply Color", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HueSliderBar(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    val hueColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(hueColors))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onHueChange(fraction * 360f)
                }
            }
            .clickable {
                // simple tap fallback handled by drag gestures
            }
    ) {
        val thumbX = (hue / 360f) * maxWidth.value
        Box(
            modifier = Modifier
                .offset(x = (thumbX - 10).coerceAtLeast(0f).dp)
                .size(20.dp, 24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
        )
    }
}

@Composable
private fun SaturationSliderBar(
    hue: Float,
    brightness: Float,
    saturation: Float,
    onSaturationChange: (Float) -> Unit
) {
    val pureHueRgb = remember(hue, brightness) {
        val hsv = floatArrayOf(hue, 1f, brightness)
        Color(android.graphics.Color.HSVToColor(0xFF, hsv))
    }
    val desatRgb = remember(hue, brightness) {
        val hsv = floatArrayOf(hue, 0f, brightness)
        Color(android.graphics.Color.HSVToColor(0xFF, hsv))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(desatRgb, pureHueRgb)))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onSaturationChange(fraction)
                }
            }
    ) {
        val thumbX = saturation * maxWidth.value
        Box(
            modifier = Modifier
                .offset(x = (thumbX - 10).coerceAtLeast(0f).dp)
                .size(20.dp, 24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
        )
    }
}

@Composable
private fun BrightnessSliderBar(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit
) {
    val brightHueRgb = remember(hue, saturation) {
        val hsv = floatArrayOf(hue, saturation, 1f)
        Color(android.graphics.Color.HSVToColor(0xFF, hsv))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(Color.Black, brightHueRgb)))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onBrightnessChange(fraction)
                }
            }
    ) {
        val thumbX = brightness * maxWidth.value
        Box(
            modifier = Modifier
                .offset(x = (thumbX - 10).coerceAtLeast(0f).dp)
                .size(20.dp, 24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
        )
    }
}
