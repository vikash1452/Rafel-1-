package com.example.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import com.example.MainActivity
import com.example.RafelForegroundService
import com.example.util.PermissionHelper
import com.example.voice.RafelLogger
import com.example.voice.RafelVoiceState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modes for the Floating Assistant overlay.
 */
enum class FloatingOverlayMode {
    COMPACT_CORE,
    EXPANDED_VOICE_INTERFACE,
    LONG_PRESS_MENU
}

/**
 * Manages the floating Rafel AI Core overlay window.
 * Hosted by RafelForegroundService to prevent duplicate services and maintain single-point lifecycle.
 */
class FloatingOverlayManager(private val context: Context) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var edgeFrameView: ComposeView? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)

    // Current overlay mode
    private val _currentMode = mutableStateOf(FloatingOverlayMode.COMPACT_CORE)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun isShowing(): Boolean = isShowing

    /**
     * Displays the floating assistant overlay over other apps.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay() {
        if (isShowing) return
        if (!PermissionHelper.isOverlayGranted(context)) {
            RafelLogger.logError("Cannot show floating overlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // 1. Full-screen Futuristic Edge Frame (Thin glowing side borders, 100% pass-through touches)
            EdgeLightingPreferences.init(context)
            val edgeParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            val edgeView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@FloatingOverlayManager)
                setViewTreeViewModelStoreOwner(this@FloatingOverlayManager)
                setViewTreeSavedStateRegistryOwner(this@FloatingOverlayManager)

                setContent {
                    val voiceState by RafelForegroundService.voiceStateFlow.collectAsState()
                    val isMicMuted by RafelForegroundService.isMicMutedFlow.collectAsState()
                    val amplitude by RafelForegroundService.visualizerAmplitudeFlow.collectAsState()

                    FuturisticScreenEdgeFrame(
                        voiceState = voiceState,
                        isMicMuted = isMicMuted,
                        amplitude = amplitude
                    )
                }
            }
            edgeFrameView = edgeView
            windowManager?.addView(edgeView, edgeParams)

            // 2. Draggable Floating Rafel Core
            val screenSize = getScreenDimensions()
            val defaultX = (screenSize.x - dpToPx(76)) / 2
            val defaultY = (screenSize.y * 0.65f).toInt()

            val savedX = prefs.getInt("floating_x", defaultX)
            val savedY = prefs.getInt("floating_y", defaultY)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX.coerceIn(0, screenSize.x - dpToPx(76))
                y = savedY.coerceIn(dpToPx(40), screenSize.y - dpToPx(120))
            }
            layoutParams = params

            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@FloatingOverlayManager)
                setViewTreeViewModelStoreOwner(this@FloatingOverlayManager)
                setViewTreeSavedStateRegistryOwner(this@FloatingOverlayManager)

                setContent {
                    FloatingOverlayContent(
                        mode = _currentMode.value,
                        onModeChange = { newMode ->
                            _currentMode.value = newMode
                        },
                        onOpenApp = {
                            hideOverlay()
                            val launchIntent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            context.startActivity(launchIntent)
                        },
                        onCloseOverlay = {
                            hideOverlay()
                        }
                    )
                }
            }

            // High precision touch, drag, and tap detector
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false
            var downTimestamp = 0L

            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        downTimestamp = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > dpToPx(6) || Math.abs(dy) > dpToPx(6)) {
                            isMoving = true
                            val screen = getScreenDimensions()
                            val bubbleWidth = if (_currentMode.value == FloatingOverlayMode.COMPACT_CORE) dpToPx(76) else dpToPx(280)
                            val bubbleHeight = if (_currentMode.value == FloatingOverlayMode.COMPACT_CORE) dpToPx(76) else dpToPx(240)

                            params.x = (initialX + dx).coerceIn(dpToPx(8), screen.x - bubbleWidth - dpToPx(8))
                            params.y = (initialY + dy).coerceIn(dpToPx(36), screen.y - bubbleHeight - dpToPx(48))

                            try {
                                windowManager?.updateViewLayout(view, params)
                            } catch (e: Exception) {
                                RafelLogger.logError("Failed to update floating view layout", e)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val duration = System.currentTimeMillis() - downTimestamp
                        if (!isMoving) {
                            if (duration > 450) {
                                // Long Press
                                _currentMode.value = if (_currentMode.value == FloatingOverlayMode.LONG_PRESS_MENU) {
                                    FloatingOverlayMode.COMPACT_CORE
                                } else {
                                    FloatingOverlayMode.LONG_PRESS_MENU
                                }
                            } else {
                                // Single Tap
                                _currentMode.value = if (_currentMode.value == FloatingOverlayMode.COMPACT_CORE) {
                                    FloatingOverlayMode.EXPANDED_VOICE_INTERFACE
                                } else {
                                    FloatingOverlayMode.COMPACT_CORE
                                }
                            }
                        } else {
                            // Save new position
                            prefs.edit()
                                .putInt("floating_x", params.x)
                                .putInt("floating_y", params.y)
                                .apply()
                        }
                        true
                    }
                    else -> false
                }
            }

            composeView = view
            windowManager?.addView(view, params)
            isShowing = true
            RafelLogger.logVoiceState(RafelForegroundService.currentState, RafelForegroundService.currentState)
        } catch (e: Exception) {
            RafelLogger.logError("Error showing floating overlay", e)
            isShowing = false
        }
    }

    /**
     * Hides the floating assistant overlay.
     */
    fun hideOverlay() {
        if (!isShowing) return
        try {
            edgeFrameView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            RafelLogger.logError("Error removing edge frame view", e)
        } finally {
            edgeFrameView = null
        }

        try {
            composeView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            RafelLogger.logError("Error removing floating view", e)
        } finally {
            composeView = null
            isShowing = false
            _currentMode.value = FloatingOverlayMode.COMPACT_CORE
        }
    }

    fun destroy() {
        hideOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    private fun getScreenDimensions(): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            point.x = metrics.bounds.width()
            point.y = metrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(point)
        }
        return point
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}

/**
 * Composable root for the floating assistant overlay.
 */
@Composable
fun FloatingOverlayContent(
    mode: FloatingOverlayMode,
    onModeChange: (FloatingOverlayMode) -> Unit,
    onOpenApp: () -> Unit,
    onCloseOverlay: () -> Unit
) {
    val voiceState by RafelForegroundService.voiceStateFlow.collectAsState()
    val isMicMuted by RafelForegroundService.isMicMutedFlow.collectAsState()
    val amplitude by RafelForegroundService.visualizerAmplitudeFlow.collectAsState()

    AnimatedVisibility(
        visible = mode == FloatingOverlayMode.COMPACT_CORE,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        FuturisticFloatingRafelCore(
            voiceState = voiceState,
            isMicMuted = isMicMuted,
            amplitude = amplitude
        )
    }

    AnimatedVisibility(
        visible = mode == FloatingOverlayMode.EXPANDED_VOICE_INTERFACE,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        ExpandedFloatingVoiceCard(
            voiceState = voiceState,
            isMicMuted = isMicMuted,
            amplitude = amplitude,
            onToggleMic = { RafelForegroundService.toggleMicMute() },
            onMinimize = { onModeChange(FloatingOverlayMode.COMPACT_CORE) },
            onOpenApp = onOpenApp,
            onClose = onCloseOverlay
        )
    }

    AnimatedVisibility(
        visible = mode == FloatingOverlayMode.LONG_PRESS_MENU,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        FloatingQuickActionMenu(
            isMicMuted = isMicMuted,
            onToggleMic = {
                RafelForegroundService.toggleMicMute()
                onModeChange(FloatingOverlayMode.COMPACT_CORE)
            },
            onOpenApp = onOpenApp,
            onMinimize = { onModeChange(FloatingOverlayMode.COMPACT_CORE) },
            onClose = onCloseOverlay
        )
    }
}

/**
 * Compact Futuristic Floating Rafel Core (76dp x 76dp)
 * Features glowing blue + purple cybernetic energy rings, rotating particle nodes,
 * bright white-hot center, and real-time voice reactivity.
 */
@Composable
fun FuturisticFloatingRafelCore(
    voiceState: RafelVoiceState,
    isMicMuted: Boolean,
    amplitude: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "floating_core")

    // Rotation speed based on voice state
    val rotationDuration = when (voiceState) {
        RafelVoiceState.WAITING_FOR_AI -> 1400 // Fast spin when thinking
        RafelVoiceState.USER_SPEAKING, RafelVoiceState.AI_SPEAKING -> 3200
        else -> 7000 // Slow ambient spin
    }

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(rotationDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Breathing pulse
    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // Dynamic scale driven by speech audio amplitude
    val audioScale = if (voiceState == RafelVoiceState.AI_SPEAKING || voiceState == RafelVoiceState.USER_SPEAKING) {
        1.0f + (amplitude.coerceIn(0f, 1f) * 0.35f)
    } else {
        breathingPulse
    }

    // Color palette: Glowing Electric Blue + Purple
    val cyanColor = Color(0xFF00E5FF)
    val neonBlueColor = Color(0xFF2979FF)
    val purpleColor = Color(0xFF7C4DFF)
    val deepVioletColor = Color(0xFF651FFF)
    val mutedRed = Color(0xFFFF5252)

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color(0xFF07050E).copy(alpha = 0.90f))
            .border(
                1.dp,
                if (isMicMuted) mutedRed.copy(alpha = 0.5f) else cyanColor.copy(alpha = 0.35f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) - 6.dp.toPx()

            // 1. Outer ambient atmospheric radial aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isMicMuted) {
                        listOf(
                            mutedRed.copy(alpha = 0.3f),
                            Color(0xFF371018).copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    } else {
                        listOf(
                            purpleColor.copy(alpha = 0.45f * audioScale),
                            neonBlueColor.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    },
                    center = center,
                    radius = baseRadius * 1.15f
                ),
                radius = baseRadius * 1.15f,
                center = center
            )

            // 2. Outer Rotating Cyan/Blue Ring with Orbiting Spark
            rotate(rotation, pivot = center) {
                drawCircle(
                    color = if (isMicMuted) Color.White.copy(alpha = 0.2f) else cyanColor.copy(alpha = 0.8f),
                    radius = baseRadius * 0.92f,
                    center = center,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f), 0f)
                    )
                )

                // Orbiting spark particle
                if (!isMicMuted) {
                    val sparkRad = baseRadius * 0.92f
                    drawCircle(
                        color = Color.White,
                        radius = 2.8.dp.toPx(),
                        center = Offset(center.x + sparkRad, center.y)
                    )
                    drawCircle(
                        color = cyanColor.copy(alpha = 0.6f),
                        radius = 5.dp.toPx(),
                        center = Offset(center.x + sparkRad, center.y)
                    )
                }
            }

            // 3. Middle Counter-Rotating Purple/Violet Ring with Second Spark
            rotate(-rotation * 1.35f, pivot = center) {
                drawCircle(
                    color = if (isMicMuted) Color.White.copy(alpha = 0.15f) else purpleColor.copy(alpha = 0.7f),
                    radius = baseRadius * 0.70f,
                    center = center,
                    style = Stroke(width = 1.4.dp.toPx())
                )

                if (!isMicMuted) {
                    val spark2Rad = baseRadius * 0.70f
                    val angleRad = Math.toRadians(180.0)
                    drawCircle(
                        color = Color(0xFFE1BEE7),
                        radius = 2.4.dp.toPx(),
                        center = Offset(
                            center.x + (spark2Rad * cos(angleRad)).toFloat(),
                            center.y + (spark2Rad * sin(angleRad)).toFloat()
                        )
                    )
                }
            }

            // 4. Central Radiant Energy Core (White-hot center blooming to cyan & purple)
            val coreRadius = baseRadius * 0.42f * audioScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isMicMuted) {
                        listOf(
                            Color.White.copy(alpha = 0.8f),
                            Color(0xFFFF8A80),
                            mutedRed.copy(alpha = 0.85f),
                            Color(0xFF260505)
                        )
                    } else {
                        listOf(
                            Color.White,
                            cyanColor,
                            neonBlueColor,
                            deepVioletColor.copy(alpha = 0.85f)
                        )
                    },
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )
        }

        // Mic Muted indicator badge if mic is off
        if (isMicMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB71C1C))
                    .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = "Mic Muted",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

/**
 * Expanded Floating Voice Card (Requirement 4)
 * Displays "RAFel" header with live state, audio visualizer, mic mute toggle, and minimize/open buttons.
 */
@Composable
fun ExpandedFloatingVoiceCard(
    voiceState: RafelVoiceState,
    isMicMuted: Boolean,
    amplitude: Float,
    onToggleMic: () -> Unit,
    onMinimize: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit
) {
    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFF7C4DFF)

    Card(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, Brush.horizontalGradient(listOf(cyanColor, purpleColor)), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0714).copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row: Title + State + Minimize Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isMicMuted -> Color(0xFFFF5252)
                                    voiceState == RafelVoiceState.AI_SPEAKING -> cyanColor
                                    voiceState == RafelVoiceState.USER_SPEAKING -> Color(0xFF00E676)
                                    voiceState == RafelVoiceState.WAITING_FOR_AI -> Color(0xFFFFB300)
                                    else -> cyanColor.copy(alpha = 0.7f)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RAFel",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isMicMuted -> "MIC OFF"
                            voiceState == RafelVoiceState.AI_SPEAKING -> "SPEAKING"
                            voiceState == RafelVoiceState.USER_SPEAKING -> "LISTENING"
                            voiceState == RafelVoiceState.WAITING_FOR_AI -> "THINKING"
                            else -> "READY"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMicMuted) Color(0xFFFF8A80) else cyanColor
                    )
                }

                Row {
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Minimize",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live Voice Spectrum Visualizer (5 reactive bars)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val clampedAmp = amplitude.coerceIn(0f, 1f)
                val multipliers = listOf(0.6f, 1.0f, 1.4f, 0.9f, 0.5f)

                multipliers.forEach { mult ->
                    val barHeight = if (isMicMuted) {
                        4.dp
                    } else if (voiceState == RafelVoiceState.WAITING_FOR_AI) {
                        14.dp
                    } else {
                        (6 + (clampedAmp * 24 * mult)).coerceIn(4f, 30f).dp
                    }

                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(cyanColor, purpleColor)
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row: Mic Toggle & Open Rafel App
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mic Button
                Button(
                    onClick = onToggleMic,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMicMuted) Color(0xFFC62828) else Color(0xFF1E1A33)
                    ),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isMicMuted) "Unmute" else "Mute Mic",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Open App Button
                OutlinedButton(
                    onClick = onOpenApp,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cyanColor.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = cyanColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open App",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Long Press Quick Action Menu (Requirement 5)
 */
@Composable
fun FloatingQuickActionMenu(
    isMicMuted: Boolean,
    onToggleMic: () -> Unit,
    onOpenApp: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFF7C4DFF)

    Card(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Brush.horizontalGradient(listOf(cyanColor, purpleColor)), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0918).copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = "RAFEL CONTROLS",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = cyanColor,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            QuickMenuItem(
                icon = if (isMicMuted) Icons.Default.Mic else Icons.Default.MicOff,
                title = if (isMicMuted) "Turn Mic ON" else "Turn Mic OFF",
                onClick = onToggleMic
            )

            QuickMenuItem(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = "Open Rafel",
                onClick = onOpenApp
            )

            QuickMenuItem(
                icon = Icons.Default.Minimize,
                title = "Minimize to Core",
                onClick = onMinimize
            )

            QuickMenuItem(
                icon = Icons.Default.Close,
                title = "Close Floating Assistant",
                onClick = onClose,
                tint = Color(0xFFFF5252)
            )
        }
    }
}

@Composable
private fun QuickMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Full-screen Futuristic Screen Edge Frame for Background Floating Assistant Mode.
 *
 * Visual Highlights:
 * - LEFT EDGE: Thin glowing pink/magenta + purple gradient line with soft blooming glow fading inward.
 * - RIGHT EDGE: Thin glowing cyan/electric blue + purple gradient line with soft blooming glow fading inward.
 * - TOP EDGE: Very subtle dark pink/plum gradient glow.
 * - BOTTOM EDGE: Very subtle blue/purple gradient glow.
 * - CENTER: 100% transparent and non-touchable (FLAG_NOT_TOUCHABLE) so underlying apps remain fully visible and usable.
 *
 * Animations:
 * - IDLE: Slow ambient breathing pulse.
 * - LISTENING: Edge glow brightens and reacts subtly to microphone input.
 * - THINKING: Animated traveling energy wave moves along the vertical edges.
 * - SPEAKING: Edge glow reacts smoothly to Rafel's actual voice playback amplitude.
 * - MIC OFF: Static, dim, subtle glow.
 */
@Composable
fun FuturisticScreenEdgeFrame(
    voiceState: RafelVoiceState,
    isMicMuted: Boolean,
    amplitude: Float
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        EdgeLightingPreferences.init(context)
    }
    val config by EdgeLightingPreferences.configFlow.collectAsState()

    if (!config.enabled) return

    com.example.ui.settings.CustomEdgeLightingCanvas(
        config = config,
        voiceState = voiceState,
        amplitude = if (isMicMuted) 0f else amplitude,
        isInteractivePreview = false
    )
}


