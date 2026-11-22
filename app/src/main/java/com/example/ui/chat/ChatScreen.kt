package com.example.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.RafelForegroundService
import com.example.conversation.RafelConversationManager
import com.example.conversation.UnifiedMessage
import com.example.conversation.UnifiedMessageType
import com.example.voice.RafelVoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Unified Futuristic AI Theme Colors
private val ColorCanvasDark = Color(0xFF040208)
private val ColorGlassCard = Color(0xFF0E0A17).copy(alpha = 0.88f)
private val ColorGlassBorder = Color(0xFFFFB300).copy(alpha = 0.22f)
private val ColorNeonCyan = Color(0xFF00E5FF)
private val ColorNeonGold = Color(0xFFFFD54F)
private val ColorNeonOrange = Color(0xFFFF9100)
private val ColorNeonPink = Color(0xFFFF4081)
private val ColorUserBubble = Color(0xFF28112E)
private val ColorUserBorder = Color(0xFFFF4081).copy(alpha = 0.40f)
private val ColorRafelBubble = Color(0xFF100C1C)
private val ColorRafelBorder = Color(0xFF00E5FF).copy(alpha = 0.25f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedChatScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val conversationManager = remember { RafelConversationManager.getInstance(context) }
    val messages by conversationManager.messages.collectAsState()
    val isThinking by conversationManager.isThinking.collectAsState()

    val voiceStateFromService by RafelForegroundService.currentStateFlow.collectAsState()
    val isMicMuted by RafelForegroundService.isMicMutedFlow.collectAsState()
    val serviceActive by RafelForegroundService.isServiceRunningFlow.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val intent = Intent(context, RafelForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } else {
            Toast.makeText(context, "Microphone permission is required to talk with Rafel.", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleOrStartVoiceService() {
        val service = RafelForegroundService.activeService
        if (service != null) {
            service.toggleMicMuted()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(context, RafelForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-scroll to bottom on new message or thinking state change
    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val statusText = when {
        isThinking -> "Thinking..."
        voiceStateFromService == RafelVoiceState.AI_SPEAKING -> "Speaking..."
        voiceStateFromService == RafelVoiceState.USER_SPEAKING -> "Listening..."
        voiceStateFromService == RafelVoiceState.LISTENING -> "Online • Listening"
        isMicMuted -> "Online • Mic Muted"
        serviceActive -> "Online"
        else -> "Ready"
    }

    val statusColor = when {
        isThinking -> ColorNeonGold
        voiceStateFromService == RafelVoiceState.AI_SPEAKING -> Color(0xFFFFF9C4)
        voiceStateFromService == RafelVoiceState.USER_SPEAKING || voiceStateFromService == RafelVoiceState.LISTENING -> ColorNeonCyan
        isMicMuted -> Color(0xFFFF8A80)
        else -> Color(0xFF00E676)
    }

    fun sendMessage() {
        val trimmed = inputText.trim()
        if (trimmed.isNotBlank() && !isThinking) {
            inputText = ""
            focusManager.clearFocus()
            val freshApiKey = com.example.ai.security.SecureApiKeyStore.getApiKey(context)
            val toolEngine = RafelForegroundService.activeToolEngine
                ?: com.example.tools.ToolExecutionEngine(context)
            conversationManager.processUserTextMessage(trimmed, freshApiKey, toolEngine)
        }
    }

    Scaffold(
        containerColor = ColorCanvasDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(ColorNeonOrange, Color(0xFF4A148C))
                                    )
                                )
                                .border(1.2.dp, ColorNeonGold.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "R",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Rafel",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(statusColor, CircleShape)
                                )
                            }
                            Text(
                                text = statusText,
                                color = statusColor.copy(alpha = 0.9f),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .testTag("chat_back_button")
                            .padding(start = 6.dp)
                            .size(38.dp)
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                actions = {
                    // Quick Mic toggle button in header
                    IconButton(
                        onClick = { toggleOrStartVoiceService() },
                        modifier = Modifier
                            .testTag("chat_header_mic_button")
                            .size(38.dp)
                            .background(
                                if (serviceActive && !isMicMuted) ColorNeonOrange.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                                CircleShape
                            )
                            .border(
                                1.dp,
                                if (serviceActive && !isMicMuted) ColorNeonOrange else Color.White.copy(alpha = 0.15f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (serviceActive && !isMicMuted) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Toggle Mic",
                            tint = if (serviceActive && !isMicMuted) ColorNeonGold else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Clear conversation button
                    IconButton(
                        onClick = { showClearConfirmDialog = true },
                        modifier = Modifier
                            .testTag("chat_clear_button")
                            .padding(end = 8.dp)
                            .size(38.dp)
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Chat",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ColorCanvasDark
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Main Messages Area
            if (messages.isEmpty() && !isThinking) {
                EmptyConversationPlaceholder(
                    onSuggestionClicked = { suggestion ->
                        inputText = suggestion
                        sendMessage()
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.id }
                    ) { msg ->
                        MessageBubbleItem(message = msg)
                    }

                    if (isThinking) {
                        item(key = "thinking_indicator") {
                            ThinkingIndicatorItem()
                        }
                    }
                }
            }

            // Bottom Input Bar
            ChatInputBar(
                inputText = inputText,
                onInputTextChanged = { inputText = it },
                onSendClicked = { sendMessage() },
                onMicClicked = { toggleOrStartVoiceService() },
                isThinking = isThinking,
                isMicActive = serviceActive && !isMicMuted
            )
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = Color(0xFF13101C),
            shape = RoundedCornerShape(22.dp),
            title = {
                Text(
                    text = "Clear Conversation?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This will erase past conversation turns from memory. Rafel will start a fresh conversation session.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        conversationManager.clearAllHistory()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Clear", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                }
            }
        )
    }
}

@Composable
private fun MessageBubbleItem(message: UnifiedMessage) {
    val isUser = message.isUser
    val isVoice = message.isVoice

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (isUser) "user_message_bubble" else "rafel_message_bubble"),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .background(
                    color = if (isUser) ColorUserBubble else ColorRafelBubble,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp
                    )
                )
                .border(
                    width = 1.dp,
                    color = if (isUser) ColorUserBorder else ColorRafelBorder,
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                // Header line with Name and Source (Voice / Text)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isUser) "YOU" else "RAFEL",
                        color = if (isUser) ColorNeonPink else ColorNeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    // Source Badge: Voice vs Text
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                color = if (isVoice) ColorNeonOrange.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (isVoice) Icons.Default.Mic else Icons.Default.EditNote,
                            contentDescription = null,
                            tint = if (isVoice) ColorNeonGold else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isVoice) "Voice" else "Text",
                            color = if (isVoice) ColorNeonGold else Color.White.copy(alpha = 0.65f),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Message text
                Text(
                    text = message.text,
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Timestamp
                Text(
                    text = formattedTime,
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicatorItem() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "d1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "d2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "d3"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("thinking_indicator"),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(ColorRafelBubble, RoundedCornerShape(16.dp))
                .border(1.dp, ColorRafelBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Rafel is thinking",
                color = ColorNeonGold,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(ColorNeonCyan.copy(alpha = dot1Alpha), CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(ColorNeonCyan.copy(alpha = dot2Alpha), CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(ColorNeonCyan.copy(alpha = dot3Alpha), CircleShape)
            )
        }
    }
}

@Composable
private fun EmptyConversationPlaceholder(
    onSuggestionClicked: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(ColorNeonOrange.copy(alpha = 0.35f), Color(0xFF28112E))
                    )
                )
                .border(1.5.dp, ColorNeonGold.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = ColorNeonGold,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Chat with Rafel",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Your natural female AI companion. Type a message or speak anytime—both share the exact same conversation memory!",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(0.9f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Quick suggestions:",
            color = ColorNeonGold.copy(alpha = 0.85f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        val suggestions = listOf(
            "Rafel kya kar rahi ho?",
            "Kal college jana hai.",
            "Tu itni chup kyun hai?",
            "Ek funny joke sunao",
            "Open WhatsApp"
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(suggestions) { item ->
                Surface(
                    onClick = { onSuggestionClicked(item) },
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF151022),
                    border = BorderStroke(1.dp, ColorNeonGold.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = item,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onMicClicked: () -> Unit,
    isThinking: Boolean,
    isMicActive: Boolean
) {
    Surface(
        color = Color(0xFF090611),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Microphone Button
            IconButton(
                onClick = onMicClicked,
                modifier = Modifier
                    .testTag("chat_mic_button")
                    .size(44.dp)
                    .background(
                        if (isMicActive) {
                            Brush.radialGradient(listOf(ColorNeonOrange, Color(0xFFD84315)))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF1E172B), Color(0xFF140F1D)))
                        },
                        CircleShape
                    )
                    .border(
                        1.2.dp,
                        if (isMicActive) ColorNeonGold else Color.White.copy(alpha = 0.15f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Voice Input",
                    tint = if (isMicActive) Color.White else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Text Input Box
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                placeholder = {
                    Text(
                        text = "Message Rafel...",
                        color = Color.White.copy(alpha = 0.40f),
                        fontSize = 14.sp
                    )
                },
                maxLines = 4,
                singleLine = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSendClicked() }),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF130E20),
                    unfocusedContainerColor = Color(0xFF100B1A),
                    focusedBorderColor = ColorNeonCyan.copy(alpha = 0.7f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = ColorNeonCyan
                ),
                modifier = Modifier
                    .testTag("chat_text_input")
                    .weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send Button
            val sendEnabled = inputText.isNotBlank() && !isThinking
            IconButton(
                onClick = onSendClicked,
                enabled = sendEnabled,
                modifier = Modifier
                    .testTag("chat_send_button")
                    .size(44.dp)
                    .background(
                        if (sendEnabled) {
                            Brush.horizontalGradient(
                                listOf(ColorNeonOrange, ColorNeonPink)
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color(0xFF1E172B), Color(0xFF140F1D))
                            )
                        },
                        CircleShape
                    )
                    .border(
                        1.2.dp,
                        if (sendEnabled) ColorNeonGold.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.10f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (sendEnabled) Color.White else Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
