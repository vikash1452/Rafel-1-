package com.example.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.RafelForegroundService
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.floating.RafelFloatingService
import com.example.util.PermissionHelper
import com.example.util.PermissionItem
import com.example.voice.VoiceConfig

private val SettingsBg = Color(0xFF07060A)
private val SettingsGold = Color(0xFFFFB300)
private val SettingsAmber = Color(0xFFFFD54F)
private val SettingsCardBg = Color(0xFF130F1C).copy(alpha = 0.85f)
private val SettingsBorder = Color(0xFFFFB300).copy(alpha = 0.2f)
private val StatusGreen = Color(0xFF00E676)
private val StatusOrange = Color(0xFFFF9100)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RafelSettingsScreen(
    onNavigateBack: () -> Unit,
    onRelaunchSetup: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableStateOf("all") } // "all", "privacy", "edge_lighting"

    BackHandler(enabled = selectedTab != "all") {
        selectedTab = "all"
    }

    // If edge lighting settings is selected, show dedicated screen
    if (selectedTab == "edge_lighting") {
        EdgeLightingSettingsScreen(
            onNavigateBack = { selectedTab = "all" }
        )
        return
    }

    // Live states
    val isServiceRunning by RafelForegroundService.isServiceRunningFlow.collectAsState()
    val isMicMuted by RafelForegroundService.isMicMutedFlow.collectAsState()
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ContextCompat.startForegroundService(context, Intent(context, RafelForegroundService::class.java))
        } else {
            Toast.makeText(context, "Microphone permission is required!", Toast.LENGTH_SHORT).show()
        }
    }
    val proactivePrefs = remember { context.getSharedPreferences("rafel_proactive_prefs", Context.MODE_PRIVATE) }
    var isProactiveEnabled by remember { mutableStateOf(proactivePrefs.getBoolean("proactive_enabled", true)) }
    var isFloatingActive by remember { mutableStateOf(RafelFloatingService.isRunning(context) && PermissionHelper.isOverlayGranted(context)) }
    var isAccessibilityOn by remember { mutableStateOf(PermissionHelper.isAccessibilityEnabled(context)) }
    var isNotifAccessOn by remember { mutableStateOf(PermissionHelper.isNotificationAccessGranted(context)) }
    var isBatteryOptimized by remember { mutableStateOf(PermissionHelper.isBatteryOptimizationIgnored(context)) }
    var permissionsList by remember { mutableStateOf(PermissionHelper.getAll10Permissions(context)) }

    fun refreshAll() {
        isFloatingActive = RafelFloatingService.isRunning(context) && PermissionHelper.isOverlayGranted(context)
        isAccessibilityOn = PermissionHelper.isAccessibilityEnabled(context)
        isNotifAccessOn = PermissionHelper.isNotificationAccessGranted(context)
        isBatteryOptimized = PermissionHelper.isBatteryOptimizationIgnored(context)
        permissionsList = PermissionHelper.getAll10Permissions(context)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = SettingsBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedTab == "privacy") "Privacy & Security" else "Rafel Settings",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTab == "privacy") {
                            selectedTab = "all"
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = SettingsAmber)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsBg)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedTab == "privacy") {
                PrivacyDashboard(
                    permissionsList = permissionsList,
                    onOpenAppSettings = { PermissionHelper.openAppSettings(context) }
                )
            } else {
                MainSettingsList(
                    permissionsList = permissionsList,
                    isMicMuted = !isServiceRunning || isMicMuted,
                    isFloatingActive = isFloatingActive,
                    isAccessibilityOn = isAccessibilityOn,
                    isNotifAccessOn = isNotifAccessOn,
                    isBatteryOptimized = isBatteryOptimized,
                    onToggleFloating = { enable ->
                        if (enable) {
                            if (PermissionHelper.isOverlayGranted(context)) {
                                RafelFloatingService.start(context)
                                isFloatingActive = true
                            } else {
                                PermissionHelper.openOverlaySettings(context)
                            }
                        } else {
                            RafelFloatingService.stop(context)
                            isFloatingActive = false
                        }
                    },
                    onToggleMic = {
                        val service = RafelForegroundService.activeService
                        if (service != null) {
                            service.toggleMicMuted()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            ContextCompat.startForegroundService(context, Intent(context, RafelForegroundService::class.java))
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onOpenAccessibility = { PermissionHelper.openAccessibilitySettings(context) },
                    onOpenNotifications = { PermissionHelper.openNotificationListenerSettings(context) },
                    onOpenBattery = { PermissionHelper.openBatterySettings(context) },
                    onOpenDefaultAssistant = { PermissionHelper.openDefaultAssistantSettings(context) },
                    onOpenPrivacy = { selectedTab = "privacy" },
                    onOpenEdgeLighting = { selectedTab = "edge_lighting" },
                    onRelaunchSetup = onRelaunchSetup
                )
            }
        }
    }
}

@Composable
private fun MainSettingsList(
    permissionsList: List<PermissionItem>,
    isMicMuted: Boolean,
    isFloatingActive: Boolean,
    isAccessibilityOn: Boolean,
    isNotifAccessOn: Boolean,
    isBatteryOptimized: Boolean,
    onToggleFloating: (Boolean) -> Unit,
    onToggleMic: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenDefaultAssistant: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenEdgeLighting: () -> Unit,
    onRelaunchSetup: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE) }
    val proactivePrefs = remember { context.getSharedPreferences("rafel_proactive_prefs", Context.MODE_PRIVATE) }
    var isProactiveEnabled by remember { mutableStateOf(proactivePrefs.getBoolean("proactive_enabled", true)) }
    val grantedCount = permissionsList.count { it.isGranted }

    var geminiKey by remember {
        mutableStateOf(com.example.ai.security.SecureApiKeyStore.getApiKey(context))
    }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showMessagingDialog by remember { mutableStateOf(false) }

    if (showMessagingDialog) {
        MessagingSettingsDialog(
            onDismiss = { showMessagingDialog = false }
        )
    }

    if (showApiKeyDialog) {
        var tempGemini by remember { mutableStateOf(geminiKey) }

        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            containerColor = Color(0xFF130F1C),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("Gemini API Key", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Your Gemini API Key is securely stored and used exclusively for Gemini Live native audio streaming.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp
                    )

                    // Gemini API Key
                    Column {
                        Text("Gemini API Key", color = SettingsAmber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = tempGemini,
                            onValueChange = { tempGemini = it },
                            placeholder = { Text("AIza...", color = Color.White.copy(alpha = 0.3f)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SettingsGold,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedKey = tempGemini.trim()
                        val saved = com.example.ai.security.SecureApiKeyStore.saveApiKey(context, trimmedKey)
                        if (!saved && trimmedKey.isNotBlank()) {
                            Toast.makeText(
                                context,
                                "Secure storage error: kripya Gemini API key dobara daalo.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            geminiKey = trimmedKey
                            val aiManager = com.example.ai.router.AIModelManager.getInstance(context)
                            aiManager.healthMonitor.clearCooldowns()
                            aiManager.isApiKeyConfigured()
                            RafelForegroundService.activeBackendManager?.updateActiveBackend()
                            if (trimmedKey.isNotBlank()) {
                                RafelForegroundService.activeService?.reconnectSession()
                            }
                            showApiKeyDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsGold)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // Section: Appearance
        item {
            SettingsCategoryHeader("APPEARANCE")
            SettingsCard {
                SettingsRowWithIcon(
                    icon = Icons.Default.BlurOn,
                    title = "Edge Lighting",
                    subtitle = "Side glowing borders, colors, animations & bloom",
                    badgeText = "CUSTOMIZE",
                    isBadgePositive = true,
                    onClick = onOpenEdgeLighting
                )
            }
        }

        // Section: AI Backend & Universal Model Router
        item {
            SettingsCategoryHeader("GEMINI LIVE AI ENGINE & AUTO-ROUTER")
            UniversalAiRouterSettingsCard(
                geminiKey = geminiKey,
                onOpenApiKeyDialog = { showApiKeyDialog = true }
            )
        }

        // Section: Microphone Control
        item {
            SettingsCategoryHeader("MICROPHONE")
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SettingsGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isMicMuted) StatusOrange else SettingsAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Microphone Status",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isMicMuted) "MIC OFF — Voice capture paused" else "MIC ON — Ready for speech & barge-in",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = !isMicMuted,
                        onCheckedChange = { onToggleMic() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = SettingsGold,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }

        // Section: Phone Control & Permissions
        item {
            SettingsCategoryHeader("PHONE CALLING & PERMISSIONS")
            SettingsCard {
                SettingsRowWithIcon(
                    icon = Icons.Default.Security,
                    title = "Android Permissions",
                    subtitle = "$grantedCount of ${permissionsList.size} permissions granted",
                    badgeText = if (grantedCount == permissionsList.size) "ALL GRANTED" else "$grantedCount/${permissionsList.size}",
                    isBadgePositive = grantedCount >= 7,
                    onClick = onOpenPrivacy
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                var isDirectCallingEnabled by remember { mutableStateOf(prefs.getBoolean("direct_calling_enabled", false)) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SettingsGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = SettingsAmber, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct Phone Calling",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isDirectCallingEnabled) "Direct Call (CALL_PHONE) active" else "Dialer Safe Mode: Opens dialer prefilled",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isDirectCallingEnabled,
                        onCheckedChange = { newVal ->
                            isDirectCallingEnabled = newVal
                            prefs.edit().putBoolean("direct_calling_enabled", newVal).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = SettingsGold,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                        )
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                var silenceDelayMs by remember {
                    mutableStateOf(
                        proactivePrefs.getLong(
                            com.example.voice.ProactiveConversationManager.KEY_SILENCE_DELAY_MS,
                            com.example.voice.ProactiveConversationManager.DEFAULT_FIRST_SILENCE_DELAY_MS
                        )
                    )
                }
                var selectedFrequency by remember {
                    mutableStateOf(
                        com.example.voice.ProactiveFrequency.fromId(
                            proactivePrefs.getString(
                                com.example.voice.ProactiveConversationManager.KEY_FREQUENCY,
                                com.example.voice.ProactiveFrequency.BALANCED.id
                            )
                        )
                    )
                }
                var quietHoursEnabled by remember {
                    mutableStateOf(
                        proactivePrefs.getBoolean(
                            com.example.voice.ProactiveConversationManager.KEY_QUIET_HOURS_ENABLED,
                            false
                        )
                    )
                }
                var onlyInActiveSession by remember {
                    mutableStateOf(
                        proactivePrefs.getBoolean(
                            com.example.voice.ProactiveConversationManager.KEY_ONLY_ACTIVE_VOICE_SESSION,
                            true
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SettingsGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = SettingsAmber, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Proactive Conversation",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isProactiveEnabled) {
                                "Speaks after ${silenceDelayMs / 1000L}s silence • ${selectedFrequency.displayName} (${selectedFrequency.description})"
                            } else {
                                "Disabled: Responds only when you speak first"
                            },
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isProactiveEnabled,
                        onCheckedChange = { newVal ->
                            isProactiveEnabled = newVal
                            proactivePrefs.edit().putBoolean("proactive_enabled", newVal).apply()
                            RafelForegroundService.activeService?.proactiveManager?.isEnabled = newVal
                        },
                        modifier = Modifier.testTag("proactive_conversation_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = SettingsGold,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                        )
                    )
                }

                AnimatedVisibility(visible = isProactiveEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. Silence Delay Selector (30s, 60s default, 90s, 120s)
                        Text(
                            text = "Silence Delay Before First Check-in",
                            color = SettingsAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.voice.ProactiveConversationManager.SILENCE_DELAY_OPTIONS_SEC.forEach { sec ->
                                val isSelected = (silenceDelayMs / 1000L).toInt() == sec
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val newMs = sec * 1000L
                                            silenceDelayMs = newMs
                                            proactivePrefs.edit()
                                                .putLong(com.example.voice.ProactiveConversationManager.KEY_SILENCE_DELAY_MS, newMs)
                                                .apply()
                                            RafelForegroundService.activeService?.proactiveManager?.firstSilenceDelayMs = newMs
                                        },
                                    color = if (isSelected) SettingsGold.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) SettingsGold else Color.White.copy(alpha = 0.12f)
                                    )
                                ) {
                                    Text(
                                        text = if (sec == 8) "8s (Def)" else "${sec}s",
                                        color = if (isSelected) SettingsAmber else Color.White.copy(alpha = 0.75f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        // 2. Conversation Frequency Selector
                        Text(
                            text = "Conversation Frequency (${selectedFrequency.description})",
                            color = SettingsAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.voice.ProactiveFrequency.entries.forEach { freq ->
                                val isSelected = selectedFrequency == freq
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedFrequency = freq
                                            proactivePrefs.edit()
                                                .putString(com.example.voice.ProactiveConversationManager.KEY_FREQUENCY, freq.id)
                                                .apply()
                                            RafelForegroundService.activeService?.proactiveManager?.frequency = freq
                                        },
                                    color = if (isSelected) SettingsGold.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) SettingsGold else Color.White.copy(alpha = 0.12f)
                                    )
                                ) {
                                    Text(
                                        text = freq.displayName,
                                        color = if (isSelected) SettingsAmber else Color.White.copy(alpha = 0.75f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        // 3. Quiet Hours Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Quiet Hours & Do Not Disturb",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Mute proactive check-ins during 11:00 PM – 7:00 AM & system DND",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = quietHoursEnabled,
                                onCheckedChange = { enabled ->
                                    quietHoursEnabled = enabled
                                    proactivePrefs.edit()
                                        .putBoolean(com.example.voice.ProactiveConversationManager.KEY_QUIET_HOURS_ENABLED, enabled)
                                        .apply()
                                    RafelForegroundService.activeService?.proactiveManager?.quietHoursEnabled = enabled
                                },
                                modifier = Modifier.testTag("proactive_quiet_hours_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = SettingsGold,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                )
                            )
                        }

                        // 4. Only Speak in Active Voice Session Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Only in Active Voice Session",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Only initiate conversation after you explicitly start RAFEL",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = onlyInActiveSession,
                                onCheckedChange = { onlyActive ->
                                    onlyInActiveSession = onlyActive
                                    proactivePrefs.edit()
                                        .putBoolean(com.example.voice.ProactiveConversationManager.KEY_ONLY_ACTIVE_VOICE_SESSION, onlyActive)
                                        .apply()
                                    RafelForegroundService.activeService?.proactiveManager?.onlyInActiveVoiceSession = onlyActive
                                },
                                modifier = Modifier.testTag("proactive_active_session_only_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = SettingsGold,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                )
                            )
                        }
                    }
                }
            }
        }

        // Section: Accessibility & Assistant
        item {
            SettingsCategoryHeader("ASSISTANT CAPABILITIES")
            SettingsCard {
                // Accessibility
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAccessibility() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SettingsGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AccessibilityNew, contentDescription = null, tint = SettingsAmber, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Accessibility Automation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(text = "Tap on-screen text and navigate apps on command", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                    }
                    Surface(
                        color = if (isAccessibilityOn) StatusGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isAccessibilityOn) "ON" else "OFF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAccessibilityOn) StatusGreen else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                // Floating Assistant
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SettingsGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = SettingsAmber, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Floating Rafel Assistant", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(text = "Show draggable bubble over other apps", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                    }
                    Switch(
                        checked = isFloatingActive,
                        onCheckedChange = { onToggleFloating(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = SettingsGold,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                        )
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                // Default Digital Assistant
                SettingsRowWithIcon(
                    icon = Icons.Default.Assistant,
                    title = "Default Digital Assistant",
                    subtitle = "Launch Rafel with assistant gesture or power button",
                    badgeText = "CONFIGURE",
                    isBadgePositive = true,
                    onClick = onOpenDefaultAssistant
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                // Battery / Background
                SettingsRowWithIcon(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Background Battery Optimization",
                    subtitle = if (isBatteryOptimized) "Disabled (Rafel runs reliably in background)" else "Restricted by system",
                    badgeText = if (isBatteryOptimized) "OPTIMIZED" else "RESTRICTED",
                    isBadgePositive = isBatteryOptimized,
                    onClick = onOpenBattery
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                // Notification Access
                SettingsRowWithIcon(
                    icon = Icons.Default.NotificationsActive,
                    title = "Notification Listener",
                    subtitle = "Allows Rafel to read requested notifications",
                    badgeText = if (isNotifAccessOn) "ON" else "OFF",
                    isBadgePositive = isNotifAccessOn,
                    onClick = onOpenNotifications
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

                // Messaging & Per-App Notification Config
                SettingsRowWithIcon(
                    icon = Icons.Default.Email,
                    title = "Messaging & Notification Apps",
                    subtitle = "Configure WhatsApp, Instagram, Telegram, Messenger, SMS & Signal",
                    badgeText = "CONFIGURE",
                    isBadgePositive = true,
                    onClick = { showMessagingDialog = true }
                )
            }
        }

        // Section: Privacy & About
        item {
            SettingsCategoryHeader("PRIVACY & APP")
            SettingsCard {
                SettingsRowWithIcon(
                    icon = Icons.Default.Shield,
                    title = "Privacy & Data Transparency",
                    subtitle = "View full explanation of how your data and permissions are protected",
                    badgeText = "VIEW",
                    isBadgePositive = true,
                    onClick = onOpenPrivacy
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsRowWithIcon(
                    icon = Icons.Default.RestartAlt,
                    title = "Relaunch Setup Wizard",
                    subtitle = "Run through the 3-step setup flow again",
                    badgeText = "SETUP",
                    isBadgePositive = false,
                    onClick = onRelaunchSetup
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsRowWithIcon(
                    icon = Icons.Default.Info,
                    title = "About Rafel",
                    subtitle = "Version 1.0.1 • Real-time AI Voice Assistant",
                    badgeText = "v1.0.1",
                    isBadgePositive = true
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ==========================================
// PRIVACY DASHBOARD (PART 12)
// ==========================================

@Composable
private fun PrivacyDashboard(
    permissionsList: List<PermissionItem>,
    onOpenAppSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SettingsBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SettingsCardBg),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Rafel Privacy Promise", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Zero hidden surveillance, background spying, or secret recordings.\n" +
                                "• Microphone audio is only streamed while active conversation is in progress.\n" +
                                "• Sensitive actions (calls, SMS, file changes) require user confirmation.\n" +
                                "• Accessibility and camera are only used on explicit user voice commands.\n" +
                                "• You can revoke any permission anytime directly in Android Settings.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsAmber),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SettingsAmber.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Android App Settings to Revoke Permissions")
                    }
                }
            }
        }

        item {
            Text(
                text = "ACTIVE PERMISSION USAGE",
                color = SettingsGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        items(permissionsList, key = { it.id }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = SettingsCardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = item.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (item.isGranted) StatusGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (item.isGranted) "ALLOWED" else "DENIED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.isGranted) StatusGreen else Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(text = item.description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f))
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// Helpers
@Composable
private fun SettingsCategoryHeader(text: String) {
    Text(
        text = text,
        color = SettingsGold,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SettingsBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SettingsCardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SettingsRowWithIcon(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String,
    isBadgePositive: Boolean,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SettingsGold.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = SettingsAmber, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(text = subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
        }
        Surface(
            color = if (isBadgePositive) StatusGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.12f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = badgeText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isBadgePositive) StatusGreen else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// ==========================================
// UNIVERSAL AI MODEL ROUTER & DIAGNOSTICS CARD
// ==========================================

@Composable
private fun UniversalAiRouterSettingsCard(
    geminiKey: String,
    onOpenApiKeyDialog: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val modelManager = remember { com.example.ai.router.AIModelManager.getInstance(context) }
    val snapshot by modelManager.telemetrySnapshot.collectAsState()
    val isAutoMode by modelManager.isAutoModeEnabled.collectAsState()
    val requiresKeyReentry by com.example.ai.security.SecureApiKeyStore.requiresKeyReentry.collectAsState()
    val reentryMessage by com.example.ai.security.SecureApiKeyStore.reentryMessage.collectAsState()
    var isProbing by remember { mutableStateOf(false) }
    var probeResults by remember { mutableStateOf<List<com.example.ai.router.ModelEndpointProbeResult>>(emptyList()) }

    SettingsCard {
        if (requiresKeyReentry) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF3E1518))
                    .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { onOpenApiKeyDialog() }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = reentryMessage ?: "Secure storage reset ho gaya — kripya Gemini API key dobara daalo.",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 1. Active Model & Connection Status Header
        SettingsRowWithIcon(
            icon = Icons.Default.Psychology,
            title = "Active Model: ${snapshot.activeLiveModelId.removePrefix("models/")}",
            subtitle = "Text/REST: ${snapshot.activeTextModelId} • Status: ${snapshot.connectionStatus}",
            badgeText = if (!snapshot.isOnline) "OFFLINE" else if (geminiKey.isNotBlank()) "ONLINE" else "KEY NEEDED",
            isBadgePositive = snapshot.isOnline && geminiKey.isNotBlank(),
            onClick = onOpenApiKeyDialog
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

        // 2. Automatic Mode ON/OFF Toggle (ON by default)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SettingsGold.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = SettingsAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Automatic Model Router",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (isAutoMode) {
                        "AUTO ON — Selects fastest healthy model & auto-switches on failure"
                    } else {
                        "AUTO OFF — Uses pinned model unless failover is required"
                    },
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = isAutoMode,
                onCheckedChange = { enabled ->
                    modelManager.setAutoModeEnabled(enabled)
                },
                modifier = Modifier.testTag("auto_model_router_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = SettingsGold,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                )
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))

        // 3. Telemetry Grid: Connectivity, Recent Latency, TTFA/TTFR, Fallbacks
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ROUTER TELEMETRY & HEALTH",
                    color = SettingsGold,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = if (snapshot.isOnline) "NETWORK: ONLINE" else "NETWORK: OFFLINE",
                    color = if (snapshot.isOnline) StatusGreen else StatusOrange,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            val lastSuccessStr = if (snapshot.lastSuccessfulModelResponseTimestamp > 0L) {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(snapshot.lastSuccessfulModelResponseTimestamp))
            } else {
                "NONE"
            }

            Text(
                text = "• Active Provider: ${snapshot.activeProvider}\n" +
                       "• Active Model: ${snapshot.activeModel}\n" +
                       "• Mode: ${snapshot.onlineOrOfflineMode} | API Key Configured: ${if (geminiKey.isNotBlank()) "YES" else snapshot.apiKeyConfigured}\n" +
                       "• REST Request Status: ${snapshot.restRequestStatus}\n" +
                       "• WebSocket State: ${snapshot.webSocketState} | Setup Complete: ${snapshot.setupComplete}\n" +
                       "• First Audio Chunk Received: ${snapshot.firstAudioChunkReceived}\n" +
                       "• Actual Response Source: ${snapshot.actualResponseSource}\n" +
                       "• Last Successful Model Response: $lastSuccessStr\n" +
                       "• Last Fallback Reason: ${snapshot.lastFallbackReason}\n" +
                       "• Live Model: ${snapshot.activeLiveModelId} | Text/STT Model: ${snapshot.activeTextModelId}\n" +
                       "• Recent Latency Score: ${snapshot.recentLatencyMs}ms" +
                       (if (snapshot.recentTtfaMs > 0L) " | TTFA: ${snapshot.recentTtfaMs}ms" else "") +
                       (if (snapshot.recentTtfrMs > 0L) " | TTFR: ${snapshot.recentTtfrMs}ms" else "") +
                       (if (snapshot.recentConnectMs > 0L) " | Connect: ${snapshot.recentConnectMs}ms" else "") + "\n" +
                       "• Fallback Events: ${snapshot.fallbackEvents.size} recorded\n" +
                       "• Offline LLM Status: ${snapshot.offlineVerification?.statusMessage ?: "Weights not installed"}",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            // Recent Fallback Event Log if any
            if (snapshot.fallbackEvents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "RECENT FALLBACK TRANSITIONS:",
                    color = SettingsAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                snapshot.fallbackEvents.takeLast(3).reversed().forEach { ev ->
                    Text(
                        text = "↳ ${ev.fromModelId.removePrefix("models/")} → ${ev.toModelId.removePrefix("models/")} (${ev.reason})",
                        color = Color(0xFFFFCC80),
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }

            // Per-Model Registry Summary
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "REGISTERED PROJECT MODELS:",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            modelManager.registry.getAllModels().forEach { model ->
                val health = snapshot.modelHealthMap[model.modelId] ?: com.example.ai.router.ModelHealthState.HEALTHY
                val stats = snapshot.modelStatsMap[model.modelId]
                val scoreMs = stats?.compositeScoreMs ?: 0L
                val scoreLabel = if (scoreMs > 0L) "${scoreMs}ms" else "UNMEASURED"
                val errPct = ((stats?.errorRate ?: 0.0) * 100.0).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "• ${model.modelId.removePrefix("models/")}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${health.badgeLabel} • $scoreLabel (err ${errPct}%)",
                        color = when (health) {
                            com.example.ai.router.ModelHealthState.HEALTHY -> StatusGreen
                            com.example.ai.router.ModelHealthState.DEGRADED -> StatusOrange
                            else -> Color.White.copy(alpha = 0.5f)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (probeResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "LIVE ENDPOINT PROBE RESULTS",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                probeResults.forEach { res ->
                    Text(
                        text = "${if (res.isReachable) "✓" else "○"} ${res.modelId.removePrefix("models/")}: ${res.detailMessage} (${res.latencyMs}ms)",
                        color = if (res.isReachable) StatusGreen else Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenApiKeyDialog,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsAmber),
                border = androidx.compose.foundation.BorderStroke(1.dp, SettingsAmber.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("API Key", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        isProbing = true
                        try {
                            probeResults = modelManager.probeAllModels(geminiKey)
                        } finally {
                            isProbing = false
                        }
                    }
                },
                enabled = !isProbing,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1.3f)
                    .testTag("probe_models_button"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isProbing) "Probing Models..." else "Verify All Models",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}


