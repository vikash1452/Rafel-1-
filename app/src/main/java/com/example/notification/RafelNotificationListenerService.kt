package com.example.notification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.IncomingMessageNotification
import com.example.voice.RafelLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.UUID

class RafelNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isServiceConnected.value = true
        RafelLogger.logDiagnostic("RafelNotificationListenerService connected.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isServiceConnected.value = false
        RafelLogger.logDiagnostic("RafelNotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || isReadingPaused(applicationContext)) return

        val pkgName = sbn.packageName ?: return
        val platform = AppPlatform.fromPackageName(pkgName)

        // Ignore non-messaging system notifications or disabled platforms
        if (platform == AppPlatform.GENERIC && !isGenericMessagingPackage(pkgName)) {
            return
        }

        if (!isPlatformNotificationAllowed(applicationContext, platform)) {
            return
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: "Unknown Sender"

        val text = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        // Skip blank or non-message event notifications (e.g. "Checking for new messages", calls, syncs)
        if (text.isBlank() || isNoiseNotification(text)) {
            return
        }

        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        val item = IncomingMessageNotification(
            id = UUID.randomUUID().toString(),
            platform = platform,
            packageName = pkgName,
            senderName = title.trim(),
            previewText = text.trim(),
            timestamp = sbn.postTime,
            isGroup = isGroup,
            groupTitle = conversationTitle
        )

        addNotification(item)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: Could remove by key if needed, or leave ephemeral buffer
    }

    companion object {
        private const val PREFS_NAME = "RafelMessagingPrefs"
        private const val KEY_READING_PAUSED = "notification_reading_paused"
        private const val MAX_NOTIFICATION_BUFFER = 50

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnectedFlow: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        // Ephemeral in-memory ring-buffer. NEVER written to persistent disk/DB for privacy!
        private val recentNotifications = Collections.synchronizedList(mutableListOf<IncomingMessageNotification>())

        private val _notificationCountFlow = MutableStateFlow(0)
        val notificationCountFlow: StateFlow<Int> = _notificationCountFlow.asStateFlow()

        @Volatile
        var isReadingPaused: Boolean = false

        fun isReadingPaused(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val paused = prefs.getBoolean(KEY_READING_PAUSED, isReadingPaused)
            isReadingPaused = paused
            return paused
        }

        fun setReadingPaused(context: Context, paused: Boolean) {
            isReadingPaused = paused
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_READING_PAUSED, paused).apply()
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            return enabledPackages.contains(context.packageName)
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun isPlatformNotificationAllowed(context: Context, platform: AppPlatform): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("platform_notify_${platform.name}", true)
        }

        fun setPlatformNotificationAllowed(context: Context, platform: AppPlatform, allowed: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("platform_notify_${platform.name}", allowed).apply()
        }

        fun addNotification(item: IncomingMessageNotification) {
            synchronized(recentNotifications) {
                // Deduplicate consecutive identical previews from same sender
                val last = recentNotifications.lastOrNull()
                if (last != null && last.packageName == item.packageName &&
                    last.senderName == item.senderName && last.previewText == item.previewText &&
                    (item.timestamp - last.timestamp) < 5000L
                ) {
                    return
                }

                recentNotifications.add(item)
                if (recentNotifications.size > MAX_NOTIFICATION_BUFFER) {
                    recentNotifications.removeAt(0)
                }
                _notificationCountFlow.value = recentNotifications.size
            }
        }

        fun getRecentMessages(
            platform: AppPlatform? = null,
            limit: Int = 5
        ): List<IncomingMessageNotification> {
            synchronized(recentNotifications) {
                val filtered = if (platform != null) {
                    recentNotifications.filter { it.platform == platform }
                } else {
                    recentNotifications
                }
                return filtered.takeLast(limit).reversed()
            }
        }

        fun getLatestMessage(): IncomingMessageNotification? {
            synchronized(recentNotifications) {
                return recentNotifications.lastOrNull()
            }
        }

        fun clearNotifications() {
            synchronized(recentNotifications) {
                recentNotifications.clear()
                _notificationCountFlow.value = 0
            }
        }

        private fun isGenericMessagingPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("message") || lower.contains("chat") ||
                    lower.contains("talk") || lower.contains("mail")
        }

        private fun isNoiseNotification(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("checking for new messages") ||
                    lower.contains("syncing") ||
                    lower.contains("backup in progress") ||
                    lower.contains("running in background") ||
                    lower.contains("missed call")
        }
    }
}
