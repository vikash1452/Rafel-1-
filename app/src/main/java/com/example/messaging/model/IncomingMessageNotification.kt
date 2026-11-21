package com.example.messaging.model

data class IncomingMessageNotification(
    val id: String,
    val platform: AppPlatform,
    val packageName: String,
    val senderName: String,
    val previewText: String,
    val timestamp: Long,
    val isGroup: Boolean = false,
    val groupTitle: String? = null
)
