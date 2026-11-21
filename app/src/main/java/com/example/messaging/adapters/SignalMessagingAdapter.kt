package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class SignalMessagingAdapter : BaseMessagingAdapter(AppPlatform.SIGNAL) {

    override fun getCapability(context: Context): MessagingCapability {
        val installedPkg = resolveInstalledPackage(context, listOf(platform.primaryPackage))
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
            limitationsDescription = "Signal opens with prefilled draft; select recipient inside Signal to deliver."
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

        var launched = false

        // Try sgnl://send?text=...
        val sgnlUri = Uri.parse("sgnl://send?text=${Uri.encode(message)}")
        val uriIntent = Intent(Intent.ACTION_VIEW, sgnlUri).apply {
            setPackage(installedPkg)
        }
        launched = launchIntentSafely(context, uriIntent)

        if (!launched) {
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
            ComposeResult.DraftPrepared(
                platform = platform,
                recipientName = recipientName,
                messageText = message,
                targetPackage = installedPkg,
                explanation = "Signal opened with message draft. Please select $recipientName in Signal to deliver."
            )
        } else {
            ComposeResult.Error(platform, "Failed to launch Signal compose intent.")
        }
    }
}
