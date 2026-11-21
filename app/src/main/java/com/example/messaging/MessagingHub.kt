package com.example.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeResult
import com.example.messaging.registry.MessagingCapabilityRegistry
import com.example.notification.RafelNotificationListenerService
import com.example.phone.PhoneControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessagingHub(private val context: Context) {

    private val registry = MessagingCapabilityRegistry.getInstance()
    private val phoneManager = PhoneControlManager(context)

    suspend fun composeMessage(
        platformName: String,
        recipientName: String,
        messageText: String
    ): String = withContext(Dispatchers.IO) {
        val adapter = registry.findAdapterByName(platformName)
        val platform = adapter.platform

        // 1. Check if the app is installed
        if (!adapter.isInstalled(context)) {
            return@withContext "NOT_INSTALLED: ${platform.displayName} aapke phone par installed nahi hai. Please Google Play Store se install karein."
        }

        // 2. Resolve Contact Recipient if direct phone/contact supported
        var resolvedPhone: String? = null
        var effectiveRecipientName = recipientName.trim()

        val isRawNumber = effectiveRecipientName.replace(Regex("[^0-9+]"), "").length >= 3 &&
                effectiveRecipientName.matches(Regex("^[0-9+\\-*#\\s()]+$"))

        if (adapter.getCapability(context).supportsDirectPhoneRecipient) {
            if (isRawNumber) {
                resolvedPhone = effectiveRecipientName
            } else {
                val hasContactsPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED

                if (hasContactsPermission) {
                    val matches = phoneManager.findContacts(effectiveRecipientName)
                    if (matches.isEmpty()) {
                        return@withContext "NOT_FOUND: '${effectiveRecipientName}' naam ka koi contact nahi mila. Please naam ya spelling check karein."
                    }
                    if (matches.size > 1) {
                        val summary = matches.take(3).joinToString("; ") { "${it.first} (${it.second})" }
                        return@withContext "AMBIGUOUS: '${effectiveRecipientName}' ke ${matches.size} contacts mile: $summary. Aap kisse message bhejna chahte hain?"
                    }
                    effectiveRecipientName = matches.first().first
                    resolvedPhone = matches.first().second
                }
            }
        }

        // 3. Delegate to Adapter
        val result = adapter.composeMessage(
            context = context,
            recipientName = effectiveRecipientName,
            phoneNumber = resolvedPhone,
            message = messageText
        )

        return@withContext when (result) {
            is ComposeResult.DraftPrepared -> {
                "SUCCESS: ${result.explanation}"
            }
            is ComposeResult.AppOpenedWithLimitation -> {
                "LIMITATION: ${result.explanation}"
            }
            is ComposeResult.NotInstalled -> {
                "NOT_INSTALLED: ${result.platform.displayName} is not installed."
            }
            is ComposeResult.AmbiguousRecipient -> {
                "AMBIGUOUS: ${result.promptText}"
            }
            is ComposeResult.PermissionDenied -> {
                "PERMISSION_DENIED: ${result.message}"
            }
            is ComposeResult.Error -> {
                "ERROR: ${result.errorMessage}"
            }
        }
    }

    suspend fun readRecentMessages(
        platformName: String? = null,
        limit: Int = 3
    ): String = withContext(Dispatchers.IO) {
        val hasNotificationAccess = RafelNotificationListenerService.isNotificationAccessGranted(context)
        if (!hasNotificationAccess) {
            return@withContext "PERMISSION_REQUIRED: Notification access enabled nahi hai. Naye messages sunne ke liye Settings mein jakar Rafel ko Notification access dein."
        }

        if (RafelNotificationListenerService.isReadingPaused) {
            return@withContext "PAUSED: Message reading abhi paused hai. Settings se resume kar sakte hain."
        }

        val platform = if (!platformName.isNullOrBlank()) AppPlatform.fromUserQuery(platformName) else null
        val messages = RafelNotificationListenerService.getRecentMessages(platform, limit)

        if (messages.isEmpty()) {
            val appLabel = platform?.displayName ?: "kisi bhi app"
            return@withContext "EMPTY: Abhi $appLabel se koi naya unread message notification nahi hai."
        }

        val sb = StringBuilder()
        if (platform != null) {
            sb.append("${platform.displayName} ke naye messages:\n")
        } else {
            sb.append("Aapke naye message notifications:\n")
        }

        messages.forEachIndexed { index, item ->
            val sender = item.senderName
            val text = item.previewText
            val app = item.platform.displayName
            val groupInfo = if (item.isGroup && !item.groupTitle.isNullOrBlank()) " (${item.groupTitle})" else ""
            sb.append("${index + 1}. $sender$groupInfo [$app]: \"$text\"\n")
        }

        sb.append("(Note: Yeh notification preview hai. Poora chat dekhne ke liye app khol sakte hain).")
        return@withContext sb.toString().trim()
    }

    suspend fun explainLastMessage(customText: String? = null): String = withContext(Dispatchers.IO) {
        val targetText = if (!customText.isNullOrBlank()) {
            customText
        } else {
            val last = RafelNotificationListenerService.getLatestMessage()
            last?.previewText
        }

        if (targetText.isNullOrBlank()) {
            return@withContext "Samjhane ke liye koi recent message nahi mila. Kripya message text specify karein."
        }

        return@withContext "Message content: \"$targetText\". Iska saral matlab yeh hai ki sender aapse yeh kehna chahta hai."
    }

    fun clearMessageCache(): String {
        RafelNotificationListenerService.clearNotifications()
        return "Message notifications cache cleanly clear kar diya gaya hai."
    }
}
