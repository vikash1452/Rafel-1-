package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class MessengerAdapter : BaseMessagingAdapter(AppPlatform.MESSENGER) {

    override fun getCapability(context: Context): MessagingCapability {
        val installedPkg = resolveInstalledPackage(
            context,
            listOf(platform.primaryPackage) + platform.alternatePackages
        )
        return MessagingCapability(
            platform = platform,
            installedPackage = installedPkg,
            isInstalled = installedPkg != null,
            supportsDirectPhoneRecipient = false,
            supportsPrefilledText = true,
            supportsNotificationReading = true,
            supportedComposeModes = listOf(
                ComposeMode.CUSTOM_SCHEME_URI,
                ComposeMode.ACTION_SEND_PACKAGE,
                ComposeMode.APP_LAUNCH_FALLBACK
            ),
            limitationsDescription = "Facebook Messenger allows sharing text draft; recipient is selected inside Messenger."
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

        // Try standard share intent to Messenger
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(installedPkg)
            putExtra(Intent.EXTRA_TEXT, message)
        }

        var launched = launchIntentSafely(context, shareIntent)

        if (!launched) {
            val uri = Uri.parse("fb-messenger://share")
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(installedPkg)
            }
            launched = launchIntentSafely(context, viewIntent)
        }

        if (!launched) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)
            if (launchIntent != null) {
                launched = launchIntentSafely(context, launchIntent)
            }
        }

        return if (launched) {
            ComposeResult.DraftPrepared(
                platform = platform,
                recipientName = recipientName,
                messageText = message,
                targetPackage = installedPkg,
                explanation = "Messenger opened with message draft. Please select $recipientName in Messenger to deliver."
            )
        } else {
            ComposeResult.Error(platform, "Failed to launch Facebook Messenger.")
        }
    }
}
