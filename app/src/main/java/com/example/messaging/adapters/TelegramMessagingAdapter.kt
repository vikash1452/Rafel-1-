package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class TelegramMessagingAdapter : BaseMessagingAdapter(AppPlatform.TELEGRAM) {

    override fun getCapability(context: Context): MessagingCapability {
        val installedPkg = resolveInstalledPackage(
            context,
            listOf(platform.primaryPackage) + platform.alternatePackages
        )
        return MessagingCapability(
            platform = platform,
            installedPackage = installedPkg,
            isInstalled = installedPkg != null,
            supportsDirectPhoneRecipient = true,
            supportsPrefilledText = true,
            supportsNotificationReading = true,
            supportedComposeModes = listOf(
                ComposeMode.CUSTOM_SCHEME_URI,
                ComposeMode.ACTION_SEND_PACKAGE,
                ComposeMode.APP_LAUNCH_FALLBACK
            ),
            limitationsDescription = "Telegram supports custom URI scheme and share intent with message text. Tap Send inside Telegram to deliver."
        )
    }

    override fun composeMessage(
        context: Context,
        recipientName: String,
        phoneNumber: String?,
        message: String
    ): ComposeResult {
        val installedPkg = resolveInstalledPackage(
            context,
            listOf(platform.primaryPackage) + platform.alternatePackages
        ) ?: return ComposeResult.NotInstalled(platform, platform.primaryPackage)

        val cleanPhone = phoneNumber?.replace(Regex("[^0-9+]"), "") ?: ""

        var launched = false

        // Option 1: Deep link with phone if available: tg://msg?to=+...&text=...
        if (cleanPhone.isNotBlank()) {
            val tgUri = Uri.parse("tg://msg?to=$cleanPhone&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, tgUri).apply {
                setPackage(installedPkg)
            }
            launched = launchIntentSafely(context, intent)
        }

        // Option 2: tg://msg_url?text=...
        if (!launched) {
            val tgUrlUri = Uri.parse("tg://msg_url?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, tgUrlUri).apply {
                setPackage(installedPkg)
            }
            launched = launchIntentSafely(context, intent)
        }

        // Option 3: ACTION_SEND targeted to Telegram
        if (!launched) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(installedPkg)
                putExtra(Intent.EXTRA_TEXT, message)
            }
            launched = launchIntentSafely(context, shareIntent)
        }

        return if (launched) {
            ComposeResult.DraftPrepared(
                platform = platform,
                recipientName = recipientName,
                messageText = message,
                targetPackage = installedPkg,
                explanation = "Telegram chat opened for $recipientName with message draft: \"$message\". Tap Send in Telegram to deliver."
            )
        } else {
            ComposeResult.Error(platform, "Failed to launch Telegram compose intent.")
        }
    }
}
