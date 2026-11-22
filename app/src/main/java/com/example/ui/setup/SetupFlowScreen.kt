package com.example.ui.setup

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.floating.RafelFloatingService
import com.example.util.PermissionHelper
import com.example.util.PermissionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val SetupBgDark = Color(0xFF07060A)
private val SetupGold = Color(0xFFFFB300)
private val SetupAmber = Color(0xFFFFD54F)
private val SetupOrange = Color(0xFFFF6D00)
private val SetupCardBg = Color(0xFF14101E).copy(alpha = 0.85f)
private val SetupCardBorder = Color(0xFFFFB300).copy(alpha = 0.22f)
private val SetupGreen = Color(0xFF00E676)

@Composable
fun SetupFlowScreen(
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE) }
    var currentStep by remember { mutableIntStateOf(1) }

    androidx.activity.compose.BackHandler(enabled = currentStep > 1) {
        currentStep = (currentStep - 1).coerceAtLeast(1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SetupBgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header with Steps Indicator
            SetupHeader(
                currentStep = currentStep,
                onStepClick = { step ->
                    if (step < currentStep) currentStep = step
                }
            )

            // Step Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentStep) {
                    1 -> Step1ConnectBrain(
                        onNext = { currentStep = 2 }
                    )
                    2 -> Step2Permissions(
                        onNext = { currentStep = 3 },
                        onBack = { currentStep = 1 }
                    )
                    3 -> Step3KeepActive(
                        onFinish = {
                            prefs.edit().putBoolean("setup_completed", true).apply()
                            onFinish()
                        },
                        onBack = { currentStep = 2 }
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(
    currentStep: Int,
    onStepClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(SetupGold)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "RAFEL SETUP",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = SetupGold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3-Step Indicator Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepPill(step = 1, title = "Brain", isActive = currentStep == 1, isCompleted = currentStep > 1, onClick = { onStepClick(1) })
            StepDivider(isCompleted = currentStep > 1)
            StepPill(step = 2, title = "Phone", isActive = currentStep == 2, isCompleted = currentStep > 2, onClick = { onStepClick(2) })
            StepDivider(isCompleted = currentStep > 2)
            StepPill(step = 3, title = "Active", isActive = currentStep == 3, isCompleted = false, onClick = { onStepClick(3) })
        }
    }
}

@Composable
private fun StepPill(
    step: Int,
    title: String,
    isActive: Boolean,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    val bgColor = when {
        isActive -> SetupGold
        isCompleted -> SetupGreen.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val textColor = when {
        isActive -> Color.Black
        isCompleted -> SetupGreen
        else -> Color.White.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(enabled = isCompleted, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCompleted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = SetupGreen,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Text(
                text = "$step",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun StepDivider(isCompleted: Boolean) {
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(2.dp)
            .background(if (isCompleted) SetupGreen.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f))
    )
}

// ==========================================
// STEP 1: CONNECT RAFEL'S BRAIN
// ==========================================

@Composable
private fun Step1ConnectBrain(onNext: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val savedPrefKey = remember { com.example.ai.security.SecureApiKeyStore.getApiKey(context) }

    var apiKeyInput by remember { mutableStateOf(savedPrefKey) }
    var isMasked by remember { mutableStateOf(true) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    val hasKey = apiKeyInput.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Icon Header
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(SetupGold.copy(alpha = 0.35f), SetupOrange.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .border(1.5.dp, SetupGold.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = SetupAmber,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Connect Rafel's Brain",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Rafel uses Google's ultra-low-latency Gemini Live audio system to think, understand Hindi & English, and speak with natural human prosody.",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SetupCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SetupCardBg),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "AI Connection Status",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    val statusText = if (savedPrefKey.isNotBlank()) "Connected" else "Setup Required"
                    val statusColor = if (savedPrefKey.isNotBlank()) SetupGreen else SetupOrange

                    Surface(
                        color = statusColor.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Input Field
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it.trim()
                        testStatus = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = if (isMasked) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = {
                        IconButton(onClick = { isMasked = !isMasked }) {
                            Icon(
                                imageVector = if (isMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle key visibility",
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SetupGold,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = SetupGold,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )

                if (testStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = testStatus ?: "",
                        fontSize = 13.sp,
                        color = if (testStatus!!.startsWith("✓")) SetupGreen else SetupOrange,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test Connection Button
        OutlinedButton(
            onClick = {
                if (apiKeyInput.isBlank()) {
                    testStatus = "Please enter an API key first."
                    return@OutlinedButton
                }
                isTesting = true
                testStatus = "Testing connection to Gemini..."
                coroutineScope.launch {
                    val isValid = testGeminiKey(apiKeyInput)
                    isTesting = false
                    testStatus = if (isValid) {
                        "✓ Verified! Gemini connection active & ready."
                    } else {
                        "⚠️ Unable to verify key. Please check the API key."
                    }
                }
            },
            enabled = !isTesting && hasKey,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SetupAmber),
            border = androidx.compose.foundation.BorderStroke(1.dp, SetupAmber.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = SetupAmber,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing...")
            } else {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Connection")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Save & Continue Button
        Button(
            onClick = {
                if (apiKeyInput.isNotBlank()) {
                    com.example.ai.security.SecureApiKeyStore.saveApiKey(context, apiKeyInput)
                }
                onNext()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SetupGold,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Save & Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

private suspend fun testGeminiKey(key: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models")
            .header("x-goog-api-key", key.trim())
            .build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    } catch (e: Exception) {
        false
    }
}

// ==========================================
// STEP 2: LET RAFEL USE YOUR PHONE (10 CARDS)
// ==========================================

@Composable
private fun Step2Permissions(
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var permissionsList by remember { mutableStateOf(PermissionHelper.getAll10Permissions(context)) }

    // Refresh on return
    fun refreshPermissions() {
        permissionsList = PermissionHelper.getAll10Permissions(context)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val multiplePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissions()
    }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshPermissions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Let Rafel Use Your Phone",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Enable the tools you want Rafel to assist you with. Rafel only uses permissions when you explicitly ask, and you can revoke them anytime.",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.7f),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // "Allow available permissions" batch button
        Button(
            onClick = {
                val needed = permissionsList
                    .filter { !it.isGranted && it.manifestPermission != null }
                    .mapNotNull { it.manifestPermission }
                    .toTypedArray()

                if (needed.isNotEmpty()) {
                    multiplePermissionLauncher.launch(needed)
                } else {
                    Toast.makeText(context, "All standard permissions already granted!", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SetupAmber.copy(alpha = 0.22f),
                contentColor = SetupAmber
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, SetupAmber.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Allow available permissions", fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Scrollable List of 10 Cards
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(permissionsList, key = { it.id }) { item ->
                PermissionCard(
                    item = item,
                    onRequestPermission = {
                        if (item.manifestPermission != null) {
                            singlePermissionLauncher.launch(item.manifestPermission)
                        } else {
                            PermissionHelper.openAppSettings(context)
                        }
                    }
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Bottom Navigation Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Back")
            }

            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SetupGold, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    item: PermissionItem,
    onRequestPermission: () -> Unit
) {
    val icon = when (item.id) {
        "mic" -> Icons.Default.Mic
        "notifications" -> Icons.Default.Notifications
        "camera" -> Icons.Default.CameraAlt
        "contacts" -> Icons.Default.Contacts
        "sms" -> Icons.Default.Sms
        "calls" -> Icons.Default.Call
        "location" -> Icons.Default.LocationOn
        "gallery" -> Icons.Default.PhotoLibrary
        "bluetooth" -> Icons.Default.Bluetooth
        "calendar" -> Icons.Default.CalendarMonth
        else -> Icons.Default.Check
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SetupCardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SetupCardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SetupGold.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SetupAmber,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Status Badge
                    Surface(
                        color = if (item.isGranted) SetupGreen.copy(alpha = 0.18f) else SetupOrange.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (item.isGranted) "Allowed" else "Pending",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.isGranted) SetupGreen else SetupOrange,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Button
            if (item.isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Allowed",
                    tint = SetupGreen,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                OutlinedButton(
                    onClick = onRequestPermission,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SetupAmber),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SetupAmber.copy(alpha = 0.5f))
                ) {
                    Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ==========================================
// STEP 3: KEEP RAFEL ACTIVE (BACKGROUND & ASSISTANT)
// ==========================================

@Composable
private fun Step3KeepActive(
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isOverlayGranted by remember { mutableStateOf(PermissionHelper.isOverlayGranted(context)) }
    var isBatteryIgnored by remember { mutableStateOf(PermissionHelper.isBatteryOptimizationIgnored(context)) }
    var isAccessibilityOn by remember { mutableStateOf(PermissionHelper.isAccessibilityEnabled(context)) }
    var isNotifAccessOn by remember { mutableStateOf(PermissionHelper.isNotificationAccessGranted(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = PermissionHelper.isOverlayGranted(context)
                isBatteryIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
                isAccessibilityOn = PermissionHelper.isAccessibilityEnabled(context)
                isNotifAccessOn = PermissionHelper.isNotificationAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Keep Rafel Active",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Optional assistant enhancements for background reliability, floating overlay, and UI automation.",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.7f),
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Battery / Background Operation
            item {
                AssistantFeatureCard(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Battery & Background Operation",
                    description = "Disable battery optimization so Rafel's real-time assistant stays responsive when the screen is locked.",
                    status = if (isBatteryIgnored) "Optimized" else "Restricted",
                    isPositive = isBatteryIgnored,
                    actionLabel = if (isBatteryIgnored) "Configured" else "Settings",
                    onAction = {
                        PermissionHelper.openBatterySettings(context)
                        isBatteryIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
                    }
                )
            }

            // 2. Display Over Other Apps (Floating Rafel)
            item {
                AssistantFeatureCard(
                    icon = Icons.Default.Layers,
                    title = "Display Over Other Apps",
                    description = "Enables a small, draggable circular Rafel assistant bubble over other apps for fast one-tap voice interaction.",
                    status = if (isOverlayGranted) "Allowed" else "Optional",
                    isPositive = isOverlayGranted,
                    actionLabel = if (isOverlayGranted) "Allowed" else "Enable",
                    onAction = {
                        PermissionHelper.openOverlaySettings(context)
                        isOverlayGranted = PermissionHelper.isOverlayGranted(context)
                    }
                )
            }

            // 3. Notification Access
            item {
                AssistantFeatureCard(
                    icon = Icons.Default.NotificationsActive,
                    title = "Notification Access",
                    description = "Optional: Allows Rafel to read or summarize your incoming notifications only when you ask.",
                    status = if (isNotifAccessOn) "Active" else "Optional",
                    isPositive = isNotifAccessOn,
                    actionLabel = if (isNotifAccessOn) "Active" else "Settings",
                    onAction = {
                        PermissionHelper.openNotificationListenerSettings(context)
                        isNotifAccessOn = PermissionHelper.isNotificationAccessGranted(context)
                    }
                )
            }

            // 4. Accessibility Service
            item {
                AssistantFeatureCard(
                    icon = Icons.Default.AccessibilityNew,
                    title = "Accessibility Service",
                    description = "Allows Rafel to interact with supported on-screen UI elements when you explicitly ask. Never monitors screen in background.",
                    status = if (isAccessibilityOn) "Enabled" else "Off",
                    isPositive = isAccessibilityOn,
                    actionLabel = if (isAccessibilityOn) "Active" else "Enable",
                    onAction = {
                        PermissionHelper.openAccessibilitySettings(context)
                        isAccessibilityOn = PermissionHelper.isAccessibilityEnabled(context)
                    }
                )
            }

            // 5. Default Digital Assistant
            item {
                AssistantFeatureCard(
                    icon = Icons.Default.Assistant,
                    title = "Default Digital Assistant",
                    description = "Choose Rafel as your Android default digital assistant to open voice interface with power button or gesture.",
                    status = "Available",
                    isPositive = true,
                    actionLabel = "Choose",
                    onAction = {
                        PermissionHelper.openDefaultAssistantSettings(context)
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Bottom Finish Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White.copy(alpha = 0.8f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Back")
            }

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SetupGold, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Finish Setup", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun AssistantFeatureCard(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    isPositive: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SetupCardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SetupCardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SetupGold.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SetupAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    color = if (isPositive) SetupGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPositive) SetupGreen else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.68f),
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SetupAmber),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SetupAmber.copy(alpha = 0.4f))
                ) {
                    Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
