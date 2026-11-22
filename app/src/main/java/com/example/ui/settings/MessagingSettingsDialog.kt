package com.example.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messaging.model.AppPlatform
import com.example.messaging.registry.MessagingCapabilityRegistry
import com.example.notification.RafelNotificationListenerService

@Composable
fun MessagingSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val registry = remember { MessagingCapabilityRegistry.getInstance() }
    val capabilities = remember { registry.getAllCapabilities(context) }
    var isAccessGranted by remember {
        mutableStateOf(RafelNotificationListenerService.isNotificationAccessGranted(context))
    }
    var isPaused by remember {
        mutableStateOf(RafelNotificationListenerService.isReadingPaused(context))
    }
    var cacheClearedMessage by remember { mutableStateOf<String?>(null) }

    val platformsToConfigure = listOf(
        AppPlatform.WHATSAPP,
        AppPlatform.INSTAGRAM,
        AppPlatform.TELEGRAM,
        AppPlatform.MESSENGER,
        AppPlatform.SMS,
        AppPlatform.SIGNAL
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF130F1C),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Messaging & Notifications",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Permission Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAccessGranted) Color(0xFF00363A).copy(alpha = 0.7f) else Color(0xFF2D1600)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isAccessGranted) Color(0xFF00E5FF).copy(alpha = 0.5f) else Color(0xFFFF9800).copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAccessGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isAccessGranted) Color(0xFF00E5FF) else Color(0xFFFF9800),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAccessGranted) "Notification Access: Active" else "Notification Access Required",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Allows Rafel to read incoming message previews aloud when requested. Messages are processed in-memory only and never logged or sent to servers.",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    RafelNotificationListenerService.openNotificationAccessSettings(context)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAccessGranted) Color.White.copy(alpha = 0.15f) else Color(0xFF00E5FF),
                                    contentColor = if (isAccessGranted) Color.White else Color.Black
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isAccessGranted) "Open Notification Settings" else "Grant Notification Access",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Pause / Resume message reading switch
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Pause Message Reading",
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Temporarily ignore incoming notifications",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isPaused,
                                onCheckedChange = { paused ->
                                    isPaused = paused
                                    RafelNotificationListenerService.setReadingPaused(context, paused)
                                }
                            )
                        }
                    }
                }

                // Header for App Toggles
                item {
                    Text(
                        text = "Enabled Messaging Apps",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }

                // Per-app toggles
                items(platformsToConfigure) { platform ->
                    val cap = capabilities.firstOrNull { it.platform == platform }
                    val isInstalled = cap?.isInstalled == true
                    var isEnabled by remember {
                        mutableStateOf(RafelNotificationListenerService.isPlatformNotificationAllowed(context, platform))
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = platform.displayName,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (isInstalled) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "Installed",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color.White.copy(alpha = 0.1f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "Not Installed",
                                                color = Color.White.copy(alpha = 0.5f),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                                if (!cap?.limitationsDescription.isNullOrBlank()) {
                                    Text(
                                        text = cap?.limitationsDescription ?: "",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    isEnabled = checked
                                    RafelNotificationListenerService.setPlatformNotificationAllowed(context, platform, checked)
                                }
                            )
                        }
                    }
                }

                // Clear Cache Item
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            RafelNotificationListenerService.clearNotifications()
                            cacheClearedMessage = "Temporary notification memory cleared."
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Temporary Message Memory", fontSize = 12.sp)
                    }
                    if (cacheClearedMessage != null) {
                        Text(
                            text = cacheClearedMessage!!,
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    )
}
