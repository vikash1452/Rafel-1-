package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class GenericMessagingAdapter(private val customPackageName: String = "") : BaseMessagingAdapter(AppPlatform.GENERIC) {

    override fun getCapability(context: Context): MessagingCapability {
        val isInst = if (customPackageName.isNotBlank()) {
            resolveInstalledPackage(context, listOf(customPackageName)) != null
        } else false

        return MessagingCapability(
            platform = platform,
            installedPackage = customPackageName.ifBlank { null },
            isInstalled = isInst,
            supportsDirectPhoneRecipient = false,
            supportsPrefilledText = true,
            supportsNotificationReading = true,
            supportedComposeModes = listOf(
                ComposeMode.ACTION_SEND_PACKAGE,
                ComposeMode.APP_LAUNCH_FALLBACK
            ),
            limitationsDescription = "Generic messenger adapter uses Android standard share sheet or package intent."
        )
    }

    override fun composeMessage(
        context: Context,
        recipientName: String,
        phoneNumber: String?,
        message: String
    ): ComposeResult {
        if (customPackageName.isNotBlank()) {
            val installed = resolveInstalledPackage(context, listOf(customPackageName))
            if (installed == null) {
                return ComposeResult.NotInstalled(platform, customPackageName)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(customPackageName)
                putExtra(Intent.EXTRA_TEXT, message)
            }
            val launched = launchIntentSafely(context, shareIntent)
            return if (launched) {
                ComposeResult.DraftPrepared(
                    platform = platform,
                    recipientName = recipientName,
                    messageText = message,
                    targetPackage = customPackageName,
                    explanation = "App opened with message draft. Please select $recipientName in the app to deliver."
                )
            } else {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(customPackageName)
                if (launchIntent != null && launchIntentSafely(context, launchIntent)) {
                    ComposeResult.AppOpenedWithLimitation(
                        platform = platform,
                        recipientName = recipientName,
                        explanation = "App opened. Please select $recipientName and type your message."
                    )
                } else {
                    ComposeResult.Error(platform, "Failed to launch $customPackageName.")
                }
            }
        }

        // Generic Android Share Sheet
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val chooser = Intent.createChooser(shareIntent, "Share message to $recipientName")
        val launched = launchIntentSafely(context, chooser)

        return if (launched) {
            ComposeResult.DraftPrepared(
                platform = platform,
                recipientName = recipientName,
                messageText = message,
                targetPackage = "android.chooser",
                explanation = "Share sheet opened with draft message: \"$message\". Select target app and contact to send."
            )
        } else {
            ComposeResult.Error(platform, "Failed to launch share sheet.")
        }
    }
}
