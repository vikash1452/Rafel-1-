package com.example.ui.settings

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.RafelForegroundService
import com.example.avatar.RafelAvatarView
import com.example.floating.*
import com.example.voice.RafelVoiceState
import kotlin.math.*

private val DarkStudioBg = Color(0xFF06040A)
private val GlassCardBg = Color(0xFF130D22).copy(alpha = 0.85f)
private val AccentCyan = Color(0xFF00E5FF)
private val AccentPink = Color(0xFFFF2A85)
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentGold = Color(0xFFFFD54F)
private val GlassBorder = Color.White.copy(alpha = 0.10f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeLightingSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        EdgeLightingPreferences.init(context)
    }

    val savedConfig by EdgeLightingPreferences.configFlow.collectAsState()
    val savedPresets by EdgeLightingPreferences.presetsFlow.collectAsState()

    // Working config state for live preview
    var currentConfig by remember(savedConfig) { mutableStateOf(savedConfig) }

    // Live voice state & amplitude for real-time reactivity preview
    val voiceState by RafelForegroundService.voiceStateFlow.collectAsState()
    val visualizerAmplitude by RafelForegroundService.visualizerAmplitudeFlow.collectAsState()

    // Dialog states
    var showColorPickerForTarget by remember { mutableStateOf<String?>(null) } // "primary", "secondary", "tertiary"
    var showCreatePresetDialog by remember { mutableStateOf(false) }
    var renamePresetTarget by remember { mutableStateOf<SavedEdgePreset?>(null) }
    var deletePresetTarget by remember { mutableStateOf<SavedEdgePreset?>(null) }

    // Advanced section expand state
    var isAdvancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DarkStudioBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Edge Lighting Studio",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Visual Customization & Real-time Bloom",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            currentConfig = EdgeLightingConfig.DEFAULT
                            EdgeLightingPreferences.updateConfig(context, currentConfig)
                            Toast.makeText(context, "Restored to Rafel Default", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentGold, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESET", color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkStudioBg)
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0B0716),
                tonalElevation = 12.dp,
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            currentConfig = EdgeLightingConfig.DEFAULT
                            EdgeLightingPreferences.updateConfig(context, currentConfig)
                            Toast.makeText(context, "Reset to Rafel Default style", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Reset Default", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            EdgeLightingPreferences.updateConfig(context, currentConfig)
                            Toast.makeText(context, "Edge lighting applied & saved!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCyan
                        ),
                        shape = RoundedCornerShape(14.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("APPLY TO PHONE", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            // =========================================================================
            // 1. LIVE FULL-SCREEN PHONE PREVIEW
            // =========================================================================
            item {
                Text(
                    text = "LIVE PHONE PREVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AccentGold,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )

                LargePhonePreviewStage(
                    config = currentConfig,
                    voiceState = voiceState,
                    amplitude = visualizerAmplitude
                )
            }

            // =========================================================================
            // 2. VISUAL EDGE STYLES
            // =========================================================================
            item {
                StudioSectionHeader(
                    title = "EDGE STYLES",
                    subtitle = "Tap any animated style to apply immediately"
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(EdgeStyleCards.allStyles) { styleCard ->
                        EdgeStyleVisualCard(
                            card = styleCard,
                            isSelected = (currentConfig.animationType == styleCard.defaultAnim &&
                                    currentConfig.primaryColor == styleCard.primaryColor),
                            onSelect = {
                                currentConfig = currentConfig.copy(
                                    primaryColor = styleCard.primaryColor,
                                    secondaryColor = styleCard.secondaryColor,
                                    tertiaryColor = styleCard.tertiaryColor,
                                    gradientMode = styleCard.defaultGradientMode,
                                    animationType = styleCard.defaultAnim,
                                    glow = styleCard.glow,
                                    thickness = styleCard.thickness,
                                    animationSpeed = styleCard.speed,
                                    voiceReactive = styleCard.voiceReactive
                                )
                                // Live apply immediately
                                EdgeLightingPreferences.updateConfig(context, currentConfig)
                            }
                        )
                    }
                }
            }

            // =========================================================================
            // 3. COLOR SYSTEM & DUAL/TRIPLE MODE
            // =========================================================================
            item {
                StudioSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("COLOR SYSTEM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentGold, letterSpacing = 1.2.sp)
                            Text("Curated swatches & custom spectrum", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        }

                        // Gradient Mode Selector (SINGLE, DUAL, TRIPLE)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E1632))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                .padding(2.dp)
                        ) {
                            listOf(
                                EdgeGradientMode.SINGLE to "SINGLE",
                                EdgeGradientMode.TWO_COLOR to "DUAL",
                                EdgeGradientMode.THREE_COLOR to "TRIPLE"
                            ).forEach { (mode, label) ->
                                val isSelected = currentConfig.gradientMode == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) AccentCyan else Color.Transparent)
                                        .clickable {
                                            currentConfig = currentConfig.copy(gradientMode = mode)
                                            EdgeLightingPreferences.updateConfig(context, currentConfig)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Primary, Secondary, Tertiary Target Selector Cards if DUAL or TRIPLE
                    if (currentConfig.gradientMode != EdgeGradientMode.SINGLE) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ColorChannelCard(
                                title = "LEFT EDGE",
                                colorLong = currentConfig.primaryColor,
                                modifier = Modifier.weight(1f),
                                onClick = { showColorPickerForTarget = "primary" }
                            )

                            ColorChannelCard(
                                title = "RIGHT EDGE",
                                colorLong = currentConfig.secondaryColor,
                                modifier = Modifier.weight(1f),
                                onClick = { showColorPickerForTarget = "secondary" }
                            )

                            if (currentConfig.gradientMode == EdgeGradientMode.THREE_COLOR) {
                                ColorChannelCard(
                                    title = "FRAME ACCENT",
                                    colorLong = currentConfig.tertiaryColor,
                                    modifier = Modifier.weight(1f),
                                    onClick = { showColorPickerForTarget = "tertiary" }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Large Circular Swatches
                    Text("SWATCH PALETTE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.6f), letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        EdgeColorPresets.allPresets.forEach { preset ->
                            val isSelected = currentConfig.primaryColor == preset.primaryColor &&
                                    (currentConfig.gradientMode == EdgeGradientMode.SINGLE || currentConfig.secondaryColor == preset.secondaryColor)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        currentConfig = currentConfig.copy(
                                            primaryColor = preset.primaryColor,
                                            secondaryColor = preset.secondaryColor,
                                            tertiaryColor = preset.tertiaryColor
                                        )
                                        EdgeLightingPreferences.updateConfig(context, currentConfig)
                                    }
                                    .padding(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(Color(preset.primaryColor), Color(preset.secondaryColor))
                                            )
                                        )
                                        .border(
                                            if (isSelected) 3.dp else 1.dp,
                                            if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = preset.name,
                                    fontSize = 11.sp,
                                    color = if (isSelected) AccentGold else Color.White.copy(alpha = 0.8f),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        // CUSTOM COLOR BUTTON
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showColorPickerForTarget = "primary" }
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.sweepGradient(
                                            listOf(
                                                Color.Red, Color.Yellow, Color.Green,
                                                Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                            )
                                        )
                                    )
                                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Colorize, contentDescription = "Custom Color", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Custom", fontSize = 11.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // =========================================================================
            // 4. EFFECT STRENGTH: CIRCULAR VISUAL CONTROLS
            // =========================================================================
            item {
                StudioSectionCard {
                    Text("EFFECT STRENGTH", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentGold, letterSpacing = 1.2.sp)
                    Text("Intuitive dials for bloom, power, and edge profile", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        CircularDialControl(
                            label = "Glow",
                            value = currentConfig.glow,
                            min = 0.1f,
                            max = 1.0f,
                            displayValue = "${(currentConfig.glow * 100).toInt()}%",
                            accentColor = AccentPink,
                            onValueChange = {
                                currentConfig = currentConfig.copy(glow = it)
                                EdgeLightingPreferences.updateConfig(context, currentConfig)
                            }
                        )

                        CircularDialControl(
                            label = "Intensity",
                            value = currentConfig.brightness,
                            min = 0.1f,
                            max = 1.0f,
                            displayValue = "${(currentConfig.brightness * 100).toInt()}%",
                            accentColor = AccentCyan,
                            onValueChange = {
                                currentConfig = currentConfig.copy(brightness = it)
                                EdgeLightingPreferences.updateConfig(context, currentConfig)
                            }
                        )

                        CircularDialControl(
                            label = "Thickness",
                            value = currentConfig.thickness,
                            min = 1.0f,
                            max = 7.0f,
                            displayValue = String.format("%.1fdp", currentConfig.thickness),
                            accentColor = AccentPurple,
                            onValueChange = {
                                currentConfig = currentConfig.copy(thickness = it)
                                EdgeLightingPreferences.updateConfig(context, currentConfig)
                            }
                        )
                    }
                }
            }

            // =========================================================================
            // 5. ANIMATION SELECTION (CARDS WITH DEMO ANIMATIONS)
            // =========================================================================
            item {
                StudioSectionHeader(
                    title = "ANIMATION DYNAMICS",
                    subtitle = "Dynamic kinetic motion and acoustic reactivity"
                )

                val animCards = listOf(
                    Triple(EdgeAnimationType.STATIC, "STATIC", "Constant futuristic solid aura"),
                    Triple(EdgeAnimationType.SLOW_PULSE, "PULSE", "Gentle ambient breathing rhythm"),
                    Triple(EdgeAnimationType.FLOW, "FLOW", "Continuous upward neon stream"),
                    Triple(EdgeAnimationType.WAVE, "WAVE", "Oscillating sinusoidal energy flux"),
                    Triple(EdgeAnimationType.ENERGY, "ENERGY", "Fast surging electric crests"),
                    Triple(EdgeAnimationType.VOICE_REACTIVE, "VOICE REACTIVE", "Responds to mic & Rafel's audio")
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    animCards.forEach { (type, label, desc) ->
                        val isSelected = currentConfig.animationType == type
                        AnimatedDynamicsCard(
                            type = type,
                            label = label,
                            description = desc,
                            isSelected = isSelected,
                            primaryColor = currentConfig.primaryColor,
                            secondaryColor = currentConfig.secondaryColor,
                            onSelect = {
                                currentConfig = currentConfig.copy(
                                    animationType = type,
                                    voiceReactive = (type == EdgeAnimationType.VOICE_REACTIVE)
                                )
                                EdgeLightingPreferences.updateConfig(context, currentConfig)
                            }
                        )
                    }
                }
            }

            // =========================================================================
            // 6. EDGE POSITION: VISUAL PHONE DIAGRAM
            // =========================================================================
            item {
                StudioSectionCard {
                    Text("EDGE POSITION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentGold, letterSpacing = 1.2.sp)
                    Text("Tap regions on the phone frame to select active glowing borders", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))

                    VisualPhoneEdgePositionPicker(
                        selectedPosition = currentConfig.position,
                        primaryColor = currentConfig.primaryColor,
                        secondaryColor = currentConfig.secondaryColor,
                        onPositionSelect = { pos ->
                            currentConfig = currentConfig.copy(position = pos)
                            EdgeLightingPreferences.updateConfig(context, currentConfig)
                        }
                    )
                }
            }

            // =========================================================================
            // 7. ADVANCED CONTROLS (EXPANDABLE SECTION)
            // =========================================================================
            item {
                StudioSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isAdvancedExpanded = !isAdvancedExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ADVANCED CONTROLS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp)
                            Text("Speed, bloom softness, particle bleed, and corner bloom", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        }

                        IconButton(onClick = { isAdvancedExpanded = !isAdvancedExpanded }) {
                            Icon(
                                imageVector = if (isAdvancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Advanced",
                                tint = AccentCyan
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isAdvancedExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            HorizontalDivider(color = GlassBorder)

                            // Animation Speed
                            SliderRowWithDial(
                                title = "Pulse & Flow Speed",
                                valueLabel = String.format("%.1fx", currentConfig.animationSpeed),
                                value = currentConfig.animationSpeed,
                                range = 0.4f..2.5f,
                                onValueChange = {
                                    currentConfig = currentConfig.copy(animationSpeed = it)
                                    EdgeLightingPreferences.updateConfig(context, currentConfig)
                                }
                            )

                            // Glow Softness
                            SliderRowWithDial(
                                title = "Glow Softness / Diffusion",
                                valueLabel = "${(currentConfig.glowSoftness * 100).toInt()}%",
                                value = currentConfig.glowSoftness,
                                range = 0.1f..1.0f,
                                onValueChange = {
                                    currentConfig = currentConfig.copy(glowSoftness = it)
                                    EdgeLightingPreferences.updateConfig(context, currentConfig)
                                }
                            )

                            // Edge Inset Distance
                            SliderRowWithDial(
                                title = "Edge Inset Distance",
                                valueLabel = String.format("%.0f dp", currentConfig.edgeDistance),
                                value = currentConfig.edgeDistance,
                                range = 0.0f..20.0f,
                                onValueChange = {
                                    currentConfig = currentConfig.copy(edgeDistance = it)
                                    EdgeLightingPreferences.updateConfig(context, currentConfig)
                                }
                            )

                            // Particle Amount
                            SliderRowWithDial(
                                title = "Particle Shimmer Density",
                                valueLabel = "${(currentConfig.particleAmount * 100).toInt()}%",
                                value = currentConfig.particleAmount,
                                range = 0.0f..1.0f,
                                onValueChange = {
                                    currentConfig = currentConfig.copy(particleAmount = it)
                                    EdgeLightingPreferences.updateConfig(context, currentConfig)
                                }
                            )

                            // Fade Length
                            SliderRowWithDial(
                                title = "Vertical Fade Falloff",
                                valueLabel = "${(currentConfig.fadeLength * 100).toInt()}%",
                                value = currentConfig.fadeLength,
                                range = 0.1f..1.0f,
                                onValueChange = {
                                    currentConfig = currentConfig.copy(fadeLength = it)
                                    EdgeLightingPreferences.updateConfig(context, currentConfig)
                                }
                            )

                            // Corner Glow Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1B1428))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Corner Radial Glow", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Enhance soft ambient bloom around curved phone corners", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = currentConfig.cornerGlow,
                                    onCheckedChange = {
                                        currentConfig = currentConfig.copy(cornerGlow = it)
                                        EdgeLightingPreferences.updateConfig(context, currentConfig)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.Black,
                                        checkedTrackColor = AccentGold
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 8. SAVED PRESETS (+ CREATE PRESET)
            // =========================================================================
            item {
                StudioSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("CUSTOM PRESETS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentGold, letterSpacing = 1.2.sp)
                            Text("Save and switch your signature aesthetics", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        }

                        Button(
                            onClick = { showCreatePresetDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("CREATE PRESET", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (savedPresets.isEmpty()) {
                        Text(
                            text = "No saved presets yet. Tap '+ CREATE PRESET' to store your current style.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            savedPresets.forEach { preset ->
                                SavedPresetRowItem(
                                    preset = preset,
                                    onApply = {
                                        currentConfig = preset.config
                                        EdgeLightingPreferences.updateConfig(context, currentConfig)
                                        Toast.makeText(context, "Applied preset: ${preset.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    onRename = { renamePresetTarget = preset },
                                    onDelete = { deletePresetTarget = preset }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Color Picker Dialog
    showColorPickerForTarget?.let { target ->
        val initialColor = when (target) {
            "primary" -> currentConfig.primaryColor
            "secondary" -> currentConfig.secondaryColor
            else -> currentConfig.tertiaryColor
        }

        CustomHsbColorPickerDialog(
            initialColorLong = initialColor,
            onColorSelected = { selectedLong ->
                currentConfig = when (target) {
                    "primary" -> currentConfig.copy(primaryColor = selectedLong)
                    "secondary" -> currentConfig.copy(secondaryColor = selectedLong)
                    else -> currentConfig.copy(tertiaryColor = selectedLong)
                }
                EdgeLightingPreferences.updateConfig(context, currentConfig)
            },
            onDismiss = { showColorPickerForTarget = null }
        )
    }

    // Create Preset Dialog
    if (showCreatePresetDialog) {
        var presetName by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showCreatePresetDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF120B20),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text("Save Custom Preset", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        placeholder = { Text("e.g. Pink Cyber, Blue Wave", color = Color.White.copy(alpha = 0.4f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCreatePresetDialog = false }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val finalName = presetName.trim().ifEmpty { "My Preset ${savedPresets.size + 1}" }
                                EdgeLightingPreferences.saveCustomPreset(context, finalName, currentConfig)
                                Toast.makeText(context, "Preset '$finalName' saved!", Toast.LENGTH_SHORT).show()
                                showCreatePresetDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Rename Preset Dialog
    renamePresetTarget?.let { preset ->
        var newName by remember { mutableStateOf(preset.name) }
        Dialog(onDismissRequest = { renamePresetTarget = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF120B20),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text("Rename Preset", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { renamePresetTarget = null }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    EdgeLightingPreferences.renamePreset(context, preset.id, newName.trim())
                                }
                                renamePresetTarget = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            Text("Update", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Delete Preset Dialog
    deletePresetTarget?.let { preset ->
        Dialog(onDismissRequest = { deletePresetTarget = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF120B20),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text("Delete Preset", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Are you sure you want to remove '${preset.name}'?", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { deletePresetTarget = null }) {
                            Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                EdgeLightingPreferences.deletePreset(context, preset.id)
                                deletePresetTarget = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
                        ) {
                            Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Large realistic phone-screen preview showing transparent/black display,
 * glowing animated edges, and central floating Rafel core.
 */
@Composable
private fun LargePhonePreviewStage(
    config: EdgeLightingConfig,
    voiceState: RafelVoiceState,
    amplitude: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF090614), Color(0xFF040208))
                )
            )
            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.25f), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Ambient background glow behind the phone mockup
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(config.primaryColor).copy(alpha = 0.15f), Color.Transparent),
                            radius = size.minDimension * 0.7f
                        )
                    )
                }
        )

        // The Realistic Phone Device Frame
        Box(
            modifier = Modifier
                .width(185.dp)
                .height(310.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF000000))
                .border(2.5.dp, Color(0xFF322847), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Screen Inner Bezel
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(Color(0xFF05030A))
            ) {
                // Top Camera Notch / Dynamic Island
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .width(46.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF14101F))
                )

                // Wallpaper background hints (very subtle transparent layers)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top App Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "9:41",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
                        }
                    }

                    // Floating Assistant Core in Center
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0A0617).copy(alpha = 0.85f))
                                .border(
                                    1.5.dp,
                                    Brush.sweepGradient(
                                        listOf(
                                            Color(config.primaryColor),
                                            Color(config.secondaryColor),
                                            Color(config.primaryColor)
                                        )
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            RafelAvatarView(modifier = Modifier.size(54.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Rafel Live",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Bottom Navigation Bar Hint
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                }

                // LIVE EDGE LIGHTING RENDERER (FULL LIVE ANIMATION ON PREVIEW)
                if (config.enabled) {
                    CustomEdgeLightingCanvas(
                        config = config,
                        voiceState = voiceState,
                        amplitude = amplitude,
                        isInteractivePreview = true
                    )
                }
            }
        }
    }
}

/**
 * Animated selectable card representing one of the 8 Edge Styles.
 */
@Composable
private fun EdgeStyleVisualCard(
    card: EdgeStyleCard,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "style_card_anim_${card.id}")
    val glowPhase by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "styleGlow"
    )

    Surface(
        modifier = Modifier
            .width(135.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
        color = if (isSelected) Color(0xFF1B122C) else GlassCardBg,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) AccentCyan else GlassBorder
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Miniature Phone Box with Animated Edge Preview
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .drawBehind {
                        val strokeW = 2.5.dp.toPx()
                        val bloomW = 8.dp.toPx()

                        // Left Edge Glow
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color(card.primaryColor).copy(alpha = 0.6f * glowPhase),
                                    Color.Transparent
                                ),
                                startX = 0f,
                                endX = bloomW
                            ),
                            topLeft = Offset.Zero,
                            size = Size(bloomW, size.height)
                        )
                        drawRect(
                            color = Color(card.primaryColor).copy(alpha = glowPhase),
                            topLeft = Offset.Zero,
                            size = Size(strokeW, size.height)
                        )

                        // Right Edge Glow
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(card.secondaryColor).copy(alpha = 0.6f * glowPhase)
                                ),
                                startX = size.width - bloomW,
                                endX = size.width
                            ),
                            topLeft = Offset(size.width - bloomW, 0f),
                            size = Size(bloomW, size.height)
                        )
                        drawRect(
                            color = Color(card.secondaryColor).copy(alpha = glowPhase),
                            topLeft = Offset(size.width - strokeW, 0f),
                            size = Size(strokeW, size.height)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(card.primaryColor).copy(alpha = 0.7f))
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = card.styleNumber,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isSelected) AccentCyan else Color.White.copy(alpha = 0.5f),
                letterSpacing = 1.sp
            )

            Text(
                text = card.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * Color channel selector card (Left Edge, Right Edge, Accent).
 */
@Composable
private fun ColorChannelCard(
    title: String,
    colorLong: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF191228),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(colorLong))
                    .border(1.5.dp, Color.White, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.6f))
                Text(
                    String.format("#%06X", (colorLong and 0x00FFFFFFL)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Circular dial visual control for Glow, Intensity, and Thickness.
 */
@Composable
private fun CircularDialControl(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    displayValue: String,
    accentColor: Color,
    onValueChange: (Float) -> Unit
) {
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        val delta = -dragAmount.y * 0.008f
                        val newFraction = (fraction + delta).coerceIn(0f, 1f)
                        onValueChange(min + (newFraction * (max - min)))
                        change.consume()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 6.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // Background track arc (270 degrees sweep)
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active progress arc
                val sweep = 270f * fraction
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accentColor.copy(alpha = 0.5f), accentColor, accentColor)
                    ),
                    startAngle = 135f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Indicator knob
                val angleRad = Math.toRadians((135f + sweep).toDouble())
                val knobX = center.x + radius * cos(angleRad).toFloat()
                val knobY = center.y + radius * sin(angleRad).toFloat()

                drawCircle(
                    color = Color.White,
                    radius = strokeWidth * 0.9f,
                    center = Offset(knobX, knobY)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayValue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/**
 * Animated Card with Live kinetic demonstration for animation type selection.
 */
@Composable
private fun AnimatedDynamicsCard(
    type: EdgeAnimationType,
    label: String,
    description: String,
    isSelected: Boolean,
    primaryColor: Long,
    secondaryColor: Long,
    onSelect: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "anim_card_${type.name}")

    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                when (type) {
                    EdgeAnimationType.ENERGY -> 700
                    EdgeAnimationType.FLOW -> 1200
                    EdgeAnimationType.WAVE -> 1600
                    else -> 2000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveOffset"
    )

    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseGlow"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        color = if (isSelected) Color(0xFF1E1433) else GlassCardBg,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) AccentCyan else GlassBorder
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Miniature live dynamic canvas strip
            Box(
                modifier = Modifier
                    .size(width = 38.dp, height = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .drawBehind {
                        val strokeW = 2.dp.toPx()
                        val col1 = Color(primaryColor)
                        val col2 = Color(secondaryColor)

                        when (type) {
                            EdgeAnimationType.STATIC -> {
                                drawRect(col1, topLeft = Offset.Zero, size = Size(strokeW, size.height))
                                drawRect(col2, topLeft = Offset(size.width - strokeW, 0f), size = Size(strokeW, size.height))
                            }
                            EdgeAnimationType.SLOW_PULSE -> {
                                drawRect(col1.copy(alpha = pulseGlow), topLeft = Offset.Zero, size = Size(strokeW, size.height))
                                drawRect(col2.copy(alpha = pulseGlow), topLeft = Offset(size.width - strokeW, 0f), size = Size(strokeW, size.height))
                            }
                            EdgeAnimationType.FLOW, EdgeAnimationType.ENERGY, EdgeAnimationType.WAVE -> {
                                val startY = size.height * (waveOffset - 0.4f)
                                val endY = size.height * (waveOffset + 0.6f)
                                drawRect(
                                    brush = Brush.verticalGradient(listOf(col1, col2, col1), startY = startY, endY = endY),
                                    topLeft = Offset.Zero,
                                    size = Size(strokeW, size.height)
                                )
                                drawRect(
                                    brush = Brush.verticalGradient(listOf(col2, col1, col2), startY = startY, endY = endY),
                                    topLeft = Offset(size.width - strokeW, 0f),
                                    size = Size(strokeW, size.height)
                                )
                            }
                            EdgeAnimationType.VOICE_REACTIVE, EdgeAnimationType.LISTENING_REACTIVE -> {
                                drawCircle(col1.copy(alpha = pulseGlow), radius = size.width * 0.4f, center = Offset(size.width / 2f, size.height / 2f))
                            }
                        }
                    }
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        color = if (isSelected) AccentCyan else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    if (type == EdgeAnimationType.VOICE_REACTIVE) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentPink.copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("MIC SYNC", color = AccentPink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = AccentCyan,
                    unselectedColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
    }
}

/**
 * Interactive Visual Phone Diagram for selecting LEFT, RIGHT, BOTH, or FULL_FRAME edges.
 */
@Composable
private fun VisualPhoneEdgePositionPicker(
    selectedPosition: EdgePosition,
    primaryColor: Long,
    secondaryColor: Long,
    onPositionSelect: (EdgePosition) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Miniature Phone Blueprint
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF090614))
                .border(2.dp, Color(0xFF2E2442), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            val showLeft = selectedPosition == EdgePosition.LEFT || selectedPosition == EdgePosition.BOTH || selectedPosition == EdgePosition.FULL_FRAME
            val showRight = selectedPosition == EdgePosition.RIGHT || selectedPosition == EdgePosition.BOTH || selectedPosition == EdgePosition.FULL_FRAME
            val showTopBottom = selectedPosition == EdgePosition.FULL_FRAME

            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 4.dp.toPx()
                val col1 = Color(primaryColor)
                val col2 = Color(secondaryColor)

                if (showLeft) {
                    drawRect(col1, topLeft = Offset.Zero, size = Size(strokeW, size.height))
                }
                if (showRight) {
                    drawRect(col2, topLeft = Offset(size.width - strokeW, 0f), size = Size(strokeW, size.height))
                }
                if (showTopBottom) {
                    drawRect(col1, topLeft = Offset.Zero, size = Size(size.width, strokeW))
                    drawRect(col2, topLeft = Offset(0f, size.height - strokeW), size = Size(size.width, strokeW))
                }
            }

            // Center Display Text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = selectedPosition.label.uppercase(),
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Active Borders",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Position Selection Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EdgePosition.values().forEach { pos ->
                val isSelected = selectedPosition == pos
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPositionSelect(pos) },
                    color = if (isSelected) AccentCyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, if (isSelected) AccentCyan else GlassBorder),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = pos.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) AccentCyan else Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * Saved Preset item row with Apply, Rename, Delete options.
 */
@Composable
private fun SavedPresetRowItem(
    preset: SavedEdgePreset,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF181026),
        border = BorderStroke(1.dp, GlassBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color swatch preview
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(preset.config.primaryColor),
                                Color(preset.config.secondaryColor)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    text = "${preset.config.position.label} • ${preset.config.animationType.label}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }

            IconButton(onClick = onApply, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Apply", tint = AccentCyan)
            }

            IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.White.copy(alpha = 0.6f))
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFF5252))
            }
        }
    }
}

/**
 * Reusable Canvas renderer for customizable edge lighting with soft bloom and shaders.
 */
@Composable
fun CustomEdgeLightingCanvas(
    config: EdgeLightingConfig,
    voiceState: RafelVoiceState,
    amplitude: Float,
    isInteractivePreview: Boolean = false
) {
    val speedMultiplier = config.animationSpeed.coerceIn(0.3f, 2.5f)
    val infiniteTransition = rememberInfiniteTransition(label = "edge_custom_anim")

    // Slow ambient breathing pulse
    val pulseDuration = (2200 / speedMultiplier).toInt().coerceAtLeast(500)
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "customIdlePulse"
    )

    // Flow & Wave animation offset
    val flowDuration = (1700 / speedMultiplier).toInt().coerceAtLeast(400)
    val flowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(flowDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "customFlowOffset"
    )

    val clampedAmp = amplitude.coerceIn(0f, 1f)
    val calculatedIntensity = when {
        !config.enabled -> 0f
        config.animationType == EdgeAnimationType.STATIC -> config.brightness
        config.animationType == EdgeAnimationType.FLOW -> config.brightness * 0.95f
        config.animationType == EdgeAnimationType.ENERGY -> config.brightness * (0.80f + 0.20f * idlePulse)
        config.animationType == EdgeAnimationType.WAVE -> config.brightness * (0.75f + 0.25f * idlePulse)
        config.voiceReactive && voiceState == RafelVoiceState.AI_SPEAKING ->
            (0.50f + (clampedAmp * 0.50f)) * config.brightness
        config.voiceReactive && (voiceState == RafelVoiceState.USER_SPEAKING || voiceState == RafelVoiceState.LISTENING) ->
            (0.40f + (clampedAmp * 0.60f)) * config.brightness
        config.animationType == EdgeAnimationType.VOICE_REACTIVE ->
            (0.45f + (clampedAmp * 0.55f)) * config.brightness
        config.animationType == EdgeAnimationType.LISTENING_REACTIVE ->
            (0.45f + (clampedAmp * 0.55f)) * config.brightness
        else -> idlePulse * config.brightness
    }.coerceIn(0.1f, 1.0f)

    val primary = Color(config.primaryColor)
    val secondary = Color(config.secondaryColor)
    val tertiary = Color(config.tertiaryColor)

    val leftColors = when (config.gradientMode) {
        EdgeGradientMode.SINGLE -> listOf(primary, primary)
        EdgeGradientMode.TWO_COLOR -> listOf(primary, secondary)
        EdgeGradientMode.THREE_COLOR -> listOf(primary, secondary, tertiary)
    }

    val rightColors = when (config.gradientMode) {
        EdgeGradientMode.SINGLE -> listOf(secondary, secondary)
        EdgeGradientMode.TWO_COLOR -> listOf(secondary, primary)
        EdgeGradientMode.THREE_COLOR -> listOf(tertiary, secondary, primary)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val lineThickness = (config.thickness * if (isInteractivePreview) 0.5f else 1f).dp.toPx()
        val rawBloom = 16.dp + (24.dp * config.glow * config.glowSoftness)
        val bloomWidth = rawBloom * (if (isInteractivePreview) 0.45f else 1f)
        val bloomPx = bloomWidth.toPx()
        val edgeDistancePx = (config.edgeDistance * if (isInteractivePreview) 0.4f else 1f).dp.toPx()

        val showLeft = config.position == EdgePosition.LEFT || config.position == EdgePosition.BOTH || config.position == EdgePosition.FULL_FRAME
        val showRight = config.position == EdgePosition.RIGHT || config.position == EdgePosition.BOTH || config.position == EdgePosition.FULL_FRAME
        val showTopBottom = config.position == EdgePosition.FULL_FRAME

        // Left Edge
        if (showLeft) {
            val leftStartX = edgeDistancePx

            // Soft large outer bloom outside the line
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.55f * calculatedIntensity),
                        secondary.copy(alpha = 0.25f * calculatedIntensity),
                        Color.Transparent
                    ),
                    startX = leftStartX,
                    endX = leftStartX + bloomPx
                ),
                topLeft = Offset(leftStartX, 0f),
                size = Size(bloomPx, height)
            )

            // Very thin bright edge line with vertical flow/wave
            val startY = if (config.animationType == EdgeAnimationType.FLOW || config.animationType == EdgeAnimationType.ENERGY) {
                height * (flowOffset - 0.4f)
            } else 0f
            val endY = if (config.animationType == EdgeAnimationType.FLOW || config.animationType == EdgeAnimationType.ENERGY) {
                height * (flowOffset + 0.6f)
            } else height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = leftColors.map { it.copy(alpha = calculatedIntensity) },
                    startY = startY,
                    endY = endY
                ),
                topLeft = Offset(leftStartX, 0f),
                size = Size(lineThickness, height)
            )
        }

        // Right Edge
        if (showRight) {
            val rightEndX = width - edgeDistancePx
            val rightStartX = rightEndX - bloomPx

            // Soft large outer bloom outside the line
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        secondary.copy(alpha = 0.25f * calculatedIntensity),
                        primary.copy(alpha = 0.55f * calculatedIntensity)
                    ),
                    startX = rightStartX,
                    endX = rightEndX
                ),
                topLeft = Offset(rightStartX, 0f),
                size = Size(bloomPx, height)
            )

            // Very thin bright edge line
            val startY = if (config.animationType == EdgeAnimationType.FLOW || config.animationType == EdgeAnimationType.ENERGY) {
                height * (1f - flowOffset - 0.4f)
            } else 0f
            val endY = if (config.animationType == EdgeAnimationType.FLOW || config.animationType == EdgeAnimationType.ENERGY) {
                height * (1.6f - flowOffset)
            } else height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = rightColors.map { it.copy(alpha = calculatedIntensity) },
                    startY = startY,
                    endY = endY
                ),
                topLeft = Offset(rightEndX - lineThickness, 0f),
                size = Size(lineThickness, height)
            )
        }

        // Full Frame Top & Bottom
        if (showTopBottom) {
            val topBloomHeight = (14.dp * (if (isInteractivePreview) 0.5f else 1f)).toPx()
            val bottomBloomHeight = (16.dp * (if (isInteractivePreview) 0.5f else 1f)).toPx()

            // Top bloom & line
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(primary.copy(alpha = 0.40f * calculatedIntensity), Color.Transparent),
                    startY = edgeDistancePx,
                    endY = edgeDistancePx + topBloomHeight
                ),
                topLeft = Offset(0f, edgeDistancePx),
                size = Size(width, topBloomHeight)
            )
            drawRect(
                color = primary.copy(alpha = calculatedIntensity),
                topLeft = Offset(0f, edgeDistancePx),
                size = Size(width, lineThickness)
            )

            // Bottom bloom & line
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, secondary.copy(alpha = 0.40f * calculatedIntensity)),
                    startY = height - edgeDistancePx - bottomBloomHeight,
                    endY = height - edgeDistancePx
                ),
                topLeft = Offset(0f, height - edgeDistancePx - bottomBloomHeight),
                size = Size(width, bottomBloomHeight)
            )
            drawRect(
                color = secondary.copy(alpha = calculatedIntensity),
                topLeft = Offset(0f, height - edgeDistancePx - lineThickness),
                size = Size(width, lineThickness)
            )
        }

        // Corner Glow
        if (config.cornerGlow) {
            val cornerRadius = (22.dp * (if (isInteractivePreview) 0.5f else 1f)).toPx()
            // Top Left Corner
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.35f * calculatedIntensity), Color.Transparent),
                    center = Offset(edgeDistancePx, edgeDistancePx),
                    radius = cornerRadius
                ),
                center = Offset(edgeDistancePx, edgeDistancePx),
                radius = cornerRadius
            )
            // Top Right Corner
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = 0.35f * calculatedIntensity), Color.Transparent),
                    center = Offset(width - edgeDistancePx, edgeDistancePx),
                    radius = cornerRadius
                ),
                center = Offset(width - edgeDistancePx, edgeDistancePx),
                radius = cornerRadius
            )
        }
    }
}

@Composable
private fun StudioSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = GlassCardBg),
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun StudioSectionHeader(
    title: String,
    subtitle: String
) {
    Column(modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = AccentGold,
            letterSpacing = 1.2.sp
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SliderRowWithDial(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(valueLabel, color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AccentCyan,
                activeTrackColor = AccentCyan,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}
