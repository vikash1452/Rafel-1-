package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import com.example.voice.VoiceConfig
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.BuildConfig
import com.example.RafelForegroundService
import com.example.phone.PhoneControlManager
import com.example.ui.settings.RafelSettingsScreen
import com.example.ui.setup.SetupFlowScreen
import com.example.voice.RafelVoiceState
import kotlinx.coroutines.launch

// Cinematic AI Core Palette
private val ColorCanvasDark = Color(0xFF030206)
private val ColorGlassSurface = Color(0xFF0D0A14).copy(alpha = 0.78f)
private val ColorGoldBright = Color(0xFFFFF7C2)
private val CoreAmberGold = Color(0xFFFFD54F)
private val ColorOrangeVibrant = Color(0xFFFF9100)
private val ColorOrangeDeep = Color(0xFFFF6D00)
private val ColorCrimsonAccent = Color(0xFFFF1744)
private val ColorGlassBorder = Color(0xFFFFB300).copy(alpha = 0.28f)
private val ColorSubtleBorder = Color.White.copy(alpha = 0.12f)
private val ColorNeonGreen = Color(0xFF00E676)
private val ColorNeonPink = Color(0xFFFF9100)
private val ColorNeonPinkSoft = Color(0xFFFFD54F)
private val ColorNeonCyan = Color(0xFF00E5FF)

@Composable
fun RafelScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE) }
    val isSetupCompleted = prefs.getBoolean("setup_completed", false)
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = if (isSetupCompleted) "home" else "setup"
    ) {
        composable("setup") {
            SetupFlowScreen(
                onFinish = {
                    navController.navigate("home") {
                        popUpTo("setup") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            HomeScreen(
                onNavigateToChat = { navController.navigate("chat") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            RafelSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onRelaunchSetup = { navController.navigate("setup") }
            )
        }
        composable("chat") {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToChat: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE) }
    var apiKey by remember {
        mutableStateOf(com.example.ai.security.SecureApiKeyStore.getApiKey(context))
    }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Screen Sharing State & Official MediaProjection Consent Launcher
    val screenManager = remember { com.example.screen.ScreenCaptureManager.getInstance(context) }
    val isScreenSharing by screenManager.isSharingFlow.collectAsState()
    val requiresKeyReentry by com.example.ai.security.SecureApiKeyStore.requiresKeyReentry.collectAsState()
    val reentryMessage by com.example.ai.security.SecureApiKeyStore.reentryMessage.collectAsState()
    var showScreenPrivacyDialog by remember { mutableStateOf(false) }
    var showMessagingDialog by remember { mutableStateOf(false) }
    var showPermissionExplanationDialog by remember { mutableStateOf(false) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            com.example.screen.ScreenProjectionForegroundService.startWithConsent(
                context = context,
                resultCode = result.resultCode,
                consentData = result.data!!
            )
            android.widget.Toast.makeText(context, "Screen sharing started with Rafel", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Screen share permission was cancelled/denied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Collect real-time state from ForegroundService
    val voiceStateFromService by RafelForegroundService.currentStateFlow.collectAsState()
    val isMicMuted by RafelForegroundService.isMicMutedFlow.collectAsState()
    val visualizerAmplitude by RafelForegroundService.visualizerAmplitudeFlow.collectAsState()
    val serviceStarted by RafelForegroundService.isServiceRunningFlow.collectAsState()
    var isOverlayGranted by remember { mutableStateOf(com.example.util.PermissionHelper.isOverlayGranted(context)) }
    val pendingAction by PhoneControlManager.pendingActionFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.RECORD_AUDIO] == true) {
            val intent = Intent(context, RafelForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } else {
            android.widget.Toast.makeText(context, "Microphone permission is required!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = com.example.util.PermissionHelper.isOverlayGranted(context)
                apiKey = com.example.ai.security.SecureApiKeyStore.getApiKey(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(voiceStateFromService) {
        com.example.avatar.RafelAvatarController.shared.onVoiceStateChanged(voiceStateFromService)
    }

    // Determine normalized status label
    val statusText = when {
        !serviceStarted -> "READY"
        voiceStateFromService == RafelVoiceState.AI_SPEAKING -> "SPEAKING"
        isMicMuted || voiceStateFromService == RafelVoiceState.MIC_OFF -> "MIC OFF"
        voiceStateFromService == RafelVoiceState.WAITING_FOR_AI -> "THINKING"
        voiceStateFromService == RafelVoiceState.LISTENING || voiceStateFromService == RafelVoiceState.USER_SPEAKING -> "LISTENING"
        voiceStateFromService == RafelVoiceState.INTERRUPTED -> "READY"
        voiceStateFromService == RafelVoiceState.ERROR -> "ERROR"
        else -> "READY"
    }

    val hintText = when {
        !serviceStarted -> "Tap to start Rafel"
        isMicMuted || statusText == "MIC OFF" -> "Microphone is off • Tap mic to listen"
        statusText == "LISTENING" -> "Listening to your voice..."
        statusText == "THINKING" -> "Processing response..."
        statusText == "SPEAKING" -> "Rafel is speaking..."
        statusText == "ERROR" -> "Tap Reconnect to resume"
        else -> "Rafel is ready"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = "RAFel",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Small online/status indicator beside it
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (serviceStarted) ColorNeonGreen else ColorNeonPink.copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (serviceStarted) ColorNeonGreen.copy(alpha = 0.4f) else ColorNeonPink.copy(alpha = 0.3f),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Online / Disconnected Status Badge
                        val isOnline = serviceStarted && voiceStateFromService != RafelVoiceState.ERROR
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isOnline) ColorNeonGreen.copy(alpha = 0.15f) else Color(0xFFFF5252).copy(alpha = 0.15f)
                                )
                                .border(
                                    1.dp,
                                    if (isOnline) ColorNeonGreen.copy(alpha = 0.4f) else Color(0xFFFF5252).copy(alpha = 0.4f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isOnline) "ONLINE" else "DISCONNECTED",
                                color = if (isOnline) ColorNeonGreen else Color(0xFFFF8A80),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ColorCanvasDark
                ),
                actions = {
                    // Screen Share toggle button
                    IconButton(
                        onClick = {
                            if (isScreenSharing) {
                                screenManager.stopProjection()
                                android.widget.Toast.makeText(context, "Screen sharing stopped", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                showScreenPrivacyDialog = true
                            }
                        },
                        modifier = Modifier
                            .testTag("screen_share_button")
                            .padding(end = 4.dp)
                            .size(40.dp)
                            .background(
                                if (isScreenSharing) ColorNeonCyan.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (isScreenSharing) ColorNeonCyan else ColorSubtleBorder,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                            contentDescription = if (isScreenSharing) "Stop Screen Sharing" else "Share Screen with Rafel",
                            tint = if (isScreenSharing) ColorNeonCyan else Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Chat quick access button
                    IconButton(
                        onClick = onNavigateToChat,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape)
                            .border(1.dp, ColorSubtleBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = "Conversation Logs",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Settings button with clean gear icon
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.06f), CircleShape)
                            .border(1.dp, ColorSubtleBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        containerColor = ColorCanvasDark
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorCanvasDark)
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                // Floating Overlay Permission Banner (Requirement 9)
                AnimatedVisibility(
                    visible = !isOverlayGranted,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF0F0B1E).copy(alpha = 0.95f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Floating Assistant",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "Floating Rafel needs permission to appear over other apps.",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.72f),
                                    lineHeight = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    com.example.util.PermissionHelper.openOverlaySettings(context)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Allow Floating Rafel",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Secure Keystore Re-entry Warning Banner
                AnimatedVisibility(
                    visible = requiresKeyReentry,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2B1215).copy(alpha = 0.95f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ColorCrimsonAccent.copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = reentryMessage ?: "Secure storage reset ho gaya — kripya Gemini API key dobara daalo.",
                                fontSize = 11.5.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { showSettingsDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CoreAmberGold),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Key Daalo", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Near Top/Center: Premium status capsule: ● LIVE RAFEL
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 4.dp)
                        .background(
                            color = ColorGlassSurface,
                            shape = RoundedCornerShape(22.dp)
                        )
                        .border(
                            BorderStroke(1.dp, ColorGlassBorder),
                            shape = RoundedCornerShape(22.dp)
                        )
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.5.dp)
                            .background(
                                color = if (serviceStarted) ColorNeonGreen else ColorOrangeVibrant,
                                shape = CircleShape
                            )
                            .border(
                                1.dp,
                                if (serviceStarted) ColorNeonGreen.copy(alpha = 0.5f) else ColorOrangeVibrant.copy(alpha = 0.5f),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LIVE RAFEL",
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.2.sp
                    )
                }

                // CENTER: Large animated Rafel Energy Core (Cinematic AI Core presence)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    RafelEnergyCore(
                        voiceState = voiceStateFromService,
                        visualizerAmplitude = visualizerAmplitude,
                        isMicMuted = isMicMuted,
                        serviceStarted = serviceStarted,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Pending Action Confirmation Banner
                AnimatedVisibility(
                    visible = pendingAction != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    pendingAction?.let { action ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1528)),
                            border = BorderStroke(1.5.dp, ColorOrangeVibrant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = ColorOrangeVibrant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Confirmation Required",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = action.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ColorGoldBright
                                )
                                Text(
                                    text = action.description,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                PhoneControlManager.clearPendingAction()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Cancel", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                PhoneControlManager.confirmPendingAction()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = ColorOrangeVibrant, contentColor = Color.White),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Confirm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // VOICE AREA at the bottom
                VoiceControlSection(
                    serviceStarted = serviceStarted,
                    statusText = statusText,
                    hintText = hintText,
                    voiceState = voiceStateFromService,
                    isMicMuted = isMicMuted,
                    visualizerAmplitude = visualizerAmplitude,
                    onNavigateToChat = onNavigateToChat,
                    onToggleMic = {
                        RafelForegroundService.activeService?.toggleMicMuted()
                    },
                    onStartRafel = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (hasMic) {
                            val intent = Intent(context, RafelForegroundService::class.java)
                            ContextCompat.startForegroundService(context, intent)
                        } else {
                            val acknowledged = prefs.getBoolean("permission_explanation_acknowledged", false)
                            if (acknowledged) {
                                permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                            } else {
                                showPermissionExplanationDialog = true
                            }
                        }
                    },
                    onStopRafel = {
                        val intent = Intent(context, RafelForegroundService::class.java)
                        context.stopService(intent)
                    },
                    onReconnect = {
                        RafelForegroundService.activeService?.reconnectSession()
                    }
                )
            }
        }
    }

    if (showScreenPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showScreenPrivacyDialog = false },
            containerColor = Color(0xFF130F1C),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Screen Sharing Privacy",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "When active, Rafel captures screen frames at controlled intervals so she can visually understand and answer your questions about on-screen content in Hindi/Hinglish.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1600)),
                        border = BorderStroke(1.dp, ColorOrangeVibrant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ColorOrangeVibrant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Privacy Warning: Do NOT share passwords, OTPs, debit/credit cards, or banking/UPI screens. You can stop sharing at any moment.",
                                color = Color(0xFFFFCC80),
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showScreenPrivacyDialog = false
                        com.example.screen.ScreenCaptureManager.setPrivacyNoticeAccepted(context, true)
                        screenCaptureLauncher.launch(screenManager.createScreenCaptureIntent())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Proceed to Android Consent", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showScreenPrivacyDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }

    if (showPermissionExplanationDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionExplanationDialog = false },
            containerColor = Color(0xFF130F1C),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Microphone Permission",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Text(
                    text = "Rafel needs microphone access to start voice interaction and respond to your voice commands in real time. Audio is processed only during active voice sessions.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionExplanationDialog = false
                        prefs.edit().putBoolean("permission_explanation_acknowledged", true).apply()
                        permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Proceed", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showPermissionExplanationDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }

    if (showMessagingDialog) {
        com.example.ui.settings.MessagingSettingsDialog(
            onDismiss = { showMessagingDialog = false }
        )
    }

    if (showSettingsDialog) {
        FuturisticSettingsDialog(
            geminiApiKey = apiKey,
            onSaveSettings = { newGeminiKey ->
                val trimmedKey = newGeminiKey.trim()
                val saved = com.example.ai.security.SecureApiKeyStore.saveApiKey(context, trimmedKey)
                if (!saved && trimmedKey.isNotBlank()) {
                    android.widget.Toast.makeText(
                        context,
                        "Secure storage error: kripya Gemini API key dobara daalo.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    apiKey = trimmedKey
                    val aiManager = com.example.ai.router.AIModelManager.getInstance(context)
                    aiManager.healthMonitor.clearCooldowns()
                    aiManager.isApiKeyConfigured()
                    RafelForegroundService.activeBackendManager?.updateActiveBackend()
                    if (trimmedKey.isNotBlank()) {
                        RafelForegroundService.activeService?.reconnectSession()
                    }
                    showSettingsDialog = false
                }
            },
            onDismiss = { showSettingsDialog = false },
            onOpenAccessibility = {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            },
            onOpenChatLogs = {
                showSettingsDialog = false
                onNavigateToChat()
            }
        )
    }
}

/**
 * Bottom Voice Area
 */
@Composable
private fun VoiceControlSection(
    serviceStarted: Boolean,
    statusText: String,
    hintText: String,
    voiceState: RafelVoiceState,
    isMicMuted: Boolean,
    visualizerAmplitude: Float,
    onNavigateToChat: () -> Unit,
    onToggleMic: () -> Unit,
    onStartRafel: () -> Unit,
    onStopRafel: () -> Unit,
    onReconnect: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        // Status indicator pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(
                    color = ColorGlassSurface,
                    shape = RoundedCornerShape(22.dp)
                )
                .border(1.dp, ColorGlassBorder, RoundedCornerShape(22.dp))
                .padding(horizontal = 20.dp, vertical = 7.dp)
        ) {
            // Visual element beside status
            when (statusText) {
                "LISTENING", "SPEAKING" -> {
                    StatusWaveform(statusText == "SPEAKING")
                    Spacer(modifier = Modifier.width(8.dp))
                }
                "THINKING" -> {
                    StatusProcessingSpinner()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                "MIC OFF" -> {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFFFF5252), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                color = if (serviceStarted) ColorNeonGreen else ColorOrangeVibrant,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }

            Text(
                text = statusText,
                color = when (statusText) {
                    "LISTENING" -> ColorGoldBright
                    "THINKING" -> ColorOrangeVibrant
                    "SPEAKING" -> Color(0xFFFFF9C4)
                    "MIC OFF" -> Color(0xFFFF8A80)
                    "ERROR" -> Color(0xFFFF5252)
                    else -> Color.White.copy(alpha = 0.95f)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.6.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Real-Time Voice Waveform Visualizer
        VoiceWaveformVisualizer(
            amplitude = visualizerAmplitude,
            isSpeaking = (statusText == "SPEAKING"),
            isListening = (statusText == "LISTENING"),
            isMicOff = isMicMuted || !serviceStarted,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (!serviceStarted) {
            // Main button: "START RAFEL"
            Button(
                modifier = Modifier
                    .testTag("start_rafel_button")
                    .height(56.dp)
                    .fillMaxWidth(0.80f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 10.dp),
                shape = RoundedCornerShape(28.dp),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStartRafel()
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(ColorOrangeDeep, ColorOrangeVibrant, CoreAmberGold)
                            ),
                            shape = RoundedCornerShape(28.dp)
                        )
                        .border(1.2.dp, Color(0xFFFFF9C4).copy(alpha = 0.5f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Start Rafel",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "START RAFEL",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            letterSpacing = 1.8.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary visual hint
            Text(
                text = hintText,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            )
        } else {
            // Active Voice Controls Row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Stop button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStopRafel()
                    },
                    modifier = Modifier
                        .testTag("stop_rafel_button")
                        .size(46.dp)
                        .background(Color(0xFFE53935).copy(alpha = 0.16f), CircleShape)
                        .border(1.dp, Color(0xFFE53935).copy(alpha = 0.40f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Rafel",
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 2. Microphone ON / OFF Toggle Button
                val micActive = !isMicMuted
                Box(
                    modifier = Modifier
                        .testTag("mic_toggle_button")
                        .height(52.dp)
                        .widthIn(min = 160.dp)
                        .background(
                            brush = if (micActive) {
                                Brush.horizontalGradient(
                                    listOf(
                                        ColorOrangeDeep,
                                        ColorOrangeVibrant
                                    )
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF261820),
                                        Color(0xFF1B1117)
                                    )
                                )
                            },
                            shape = RoundedCornerShape(26.dp)
                        )
                        .border(
                            width = 1.4.dp,
                            brush = if (micActive) {
                                Brush.linearGradient(
                                    listOf(Color(0xFFFFF9C4).copy(alpha = 0.6f), CoreAmberGold)
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(Color(0xFFFF5252).copy(alpha = 0.75f), Color.White.copy(alpha = 0.15f))
                                )
                            },
                            shape = RoundedCornerShape(26.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleMic()
                        }
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (micActive) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (micActive) "Mute Microphone" else "Unmute Microphone",
                            tint = if (micActive) Color.White else Color(0xFFFF8A80),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (micActive) "MIC ON" else "MIC OFF",
                            color = if (micActive) Color.White else Color(0xFFFF8A80),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // 3. Reconnect button
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onReconnect()
                    },
                    modifier = Modifier
                        .testTag("reconnect_button")
                        .size(46.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        .border(1.dp, ColorSubtleBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reconnect Rafel",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 4. Chat conversation access button
                IconButton(
                    onClick = onNavigateToChat,
                    modifier = Modifier
                        .testTag("chat_button")
                        .size(46.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        .border(1.dp, ColorSubtleBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = "Conversation Logs",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Secondary visual hint
            Text(
                text = hintText,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * Real-time Voice Visualizer
 * Reacts dynamically to:
 * - Playback audio when Rafel speaks (vibrant reactive bars)
 * - Microphone audio when user speaks (vibrant reactive bars)
 * - Idle: very subtle, slow gentle wave
 * - Mic OFF: resting bars that do not react to microphone
 */
@Composable
fun VoiceWaveformVisualizer(
    amplitude: Float,
    isSpeaking: Boolean,
    isListening: Boolean,
    isMicOff: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "idle_wave")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val barCount = 26
    val animatedAmplitude by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isMicOff) 0f else amplitude,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.72f,
            stiffness = 750f
        ),
        label = "amp"
    )

    Canvas(
        modifier = modifier
            .height(36.dp)
            .padding(horizontal = 24.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val step = width / (barCount + 1)
        val barWidth = (step * 0.52f).coerceIn(2.5f, 6f)

        for (i in 0 until barCount) {
            val x = (i + 1) * step
            val distFromCenter = kotlin.math.abs((i - barCount / 2f) / (barCount / 2f))
            val bellFactor = (1f - distFromCenter * 0.65f).coerceAtLeast(0.2f)

            val barHeight: Float = when {
                isMicOff -> {
                    3.dp.toPx()
                }
                animatedAmplitude > 0.03f -> {
                    val freqMod = 0.7f + 0.3f * kotlin.math.sin(i * 0.75f).toFloat()
                    val dynamicH = animatedAmplitude * (height - 6.dp.toPx()) * bellFactor * freqMod
                    (dynamicH + 4.dp.toPx()).coerceIn(4.dp.toPx(), height)
                }
                else -> {
                    val subtleWave = kotlin.math.sin(idlePhase + i * 0.24f).toFloat()
                    (3.5.dp.toPx() + 2.5.dp.toPx() * subtleWave * bellFactor).coerceAtLeast(2.5.dp.toPx())
                }
            }

            val brush = when {
                isMicOff -> Brush.verticalGradient(
                    listOf(Color(0xFF42382E).copy(alpha = 0.35f), Color(0xFF261F1A).copy(alpha = 0.25f))
                )
                isSpeaking -> Brush.verticalGradient(
                    listOf(ColorGoldBright, ColorOrangeVibrant, ColorCrimsonAccent)
                )
                isListening -> Brush.verticalGradient(
                    listOf(CoreAmberGold, ColorOrangeDeep, Color(0xFF80D8FF))
                )
                else -> Brush.verticalGradient(
                    listOf(CoreAmberGold.copy(alpha = 0.45f), ColorOrangeDeep.copy(alpha = 0.22f))
                )
            }

            drawRoundRect(
                brush = brush,
                topLeft = Offset(x - barWidth / 2f, centerY - barHeight / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Animated mini waveform for LISTENING / SPEAKING state
 */
@Composable
private fun StatusWaveform(isSpeaking: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_eq")
    val v1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w1"
    )
    val v2 by infiniteTransition.animateFloat(
        initialValue = 14f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(460, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w2"
    )
    val v3 by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w3"
    )

    val tint = if (isSpeaking) ColorGoldBright else ColorOrangeVibrant

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(18.dp)
    ) {
        listOf(v1, v2, v3, v1 * 0.8f).forEach { h ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(h.dp)
                    .background(tint, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * Animated processing indicator for THINKING state
 */
@Composable
private fun StatusProcessingSpinner() {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner_rot")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    Canvas(modifier = Modifier.size(16.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        rotate(angle, center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        ColorOrangeDeep.copy(alpha = 0.1f),
                        ColorOrangeVibrant,
                        CoreAmberGold
                    )
                ),
                radius = size.minDimension / 2f - 1f,
                center = center,
                style = Stroke(width = 2f)
            )
        }
    }
}

/**
 * Modern Unified Chat Screen for conversation history and interactive text/voice chat
 */
@Composable
fun ChatScreen(onNavigateBack: () -> Unit) {
    com.example.ui.chat.UnifiedChatScreen(onNavigateBack = onNavigateBack)
}

/**
 * Settings Dialog for Gemini Live configuration and utilities
 */
@Composable
fun FuturisticSettingsDialog(
    geminiApiKey: String,
    onSaveSettings: (geminiKey: String) -> Unit,
    onDismiss: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenChatLogs: () -> Unit
) {
    var tempGeminiKey by remember { mutableStateOf(geminiApiKey) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13101C),
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                text = "Rafel Gemini Settings",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Gemini API Key entry
                Column {
                    Text(
                        text = "Gemini API Key",
                        color = ColorNeonCyan,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Required for Gemini Live. Injected from AI Studio secrets or enter below.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = tempGeminiKey,
                        onValueChange = { tempGeminiKey = it },
                        placeholder = { Text("AIza...", color = Color.White.copy(alpha = 0.3f)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ColorNeonCyan,
                            unfocusedBorderColor = ColorSubtleBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = ColorNeonCyan
                        ),
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            if (tempGeminiKey.isNotEmpty()) {
                                IconButton(onClick = { tempGeminiKey = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Clear",
                                        tint = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Action buttons inside settings
                Button(
                    onClick = onOpenChatLogs,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ColorSubtleBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Conversation Logs", color = Color.White, fontSize = 13.5.sp)
                }

                Button(
                    onClick = onOpenAccessibility,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ColorSubtleBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Accessibility Settings", color = Color.White, fontSize = 13.5.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveSettings(tempGeminiKey) },
                colors = ButtonDefaults.buttonColors(containerColor = ColorNeonPink),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

