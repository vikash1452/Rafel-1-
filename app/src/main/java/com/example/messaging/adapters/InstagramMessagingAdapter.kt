package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class InstagramMessagingAdapter : BaseMessagingAdapter(AppPlatform.INSTAGRAM) {

    override fun getCapability(context: Context): MessagingCapability {
        val installedPkg = resolveInstalledPackage(context, listOf(platform.primaryPackage))
        return MessagingCapability(
            platform = platform,
            installedPackage = installedPkg,
            isInstalled = installedPkg != null,
            supportsDirectPhoneRecipient = false,
            supportsPrefilledText = false,
            supportsNotificationReading = true,
            supportedComposeModes = listOf(
                ComposeMode.ACTION_SEND_PACKAGE,
                ComposeMode.APP_LAUNCH_FALLBACK
            ),
            limitationsDescription = "Instagram Direct Messages does not support public third-party recipient preselection. Direct Inbox / Share screen is opened so you can select the user."
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
            listOf(platform.primaryPackage)
        ) ?: return ComposeResult.NotInstalled(platform, platform.primaryPackage)

        // Try direct inbox intent or share intent
        val directUri = Uri.parse("instagram://direct-inbox")
        val directIntent = Intent(Intent.ACTION_VIEW, directUri).apply {
            setPackage(installedPkg)
        }

        var launched = launchIntentSafely(context, directIntent)

        if (!launched) {
            // Fallback to generic share intent targeted to Instagram
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(installedPkg)
                putExtra(Intent.EXTRA_TEXT, message)
            }
            launched = launchIntentSafely(context, shareIntent)
        }

        if (!launched) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)
            if (launchIntent != null) {
                launched = launchIntentSafely(context, launchIntent)
            }
        }

        return if (launched) {
            ComposeResult.AppOpenedWithLimitation(
                platform = platform,
                recipientName = recipientName,
                explanation = "Instagram Direct opened. Instagram does not allow external recipient prefill, so please select '$recipientName' and paste your message: \"$message\"."
            )
        } else {
            ComposeResult.Error(platform, "Failed to launch Instagram Direct.")
        }
    }
}
