package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class WhatsAppMessagingAdapter(isBusiness: Boolean = false) : BaseMessagingAdapter(
    if (isBusiness) AppPlatform.WHATSAPP_BUSINESS else AppPlatform.WHATSAPP
) {

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
                ComposeMode.DIRECT_PHONE_LINK,
                ComposeMode.ACTION_SEND_PACKAGE
            ),
            limitationsDescription = "Direct phone links open the chat with prefilled text. Delivery is executed only after user taps Send in WhatsApp."
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

        if (cleanPhone.isBlank()) {
            return ComposeResult.Error(
                platform = platform,
                errorMessage = "WhatsApp ke liye '$recipientName' ka valid phone number nahi mila. Kripya phone number ya contact name specify karein."
            )
        }

        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(installedPkg)
        }
        val success = launchIntentSafely(context, intent)

        return if (success) {
            ComposeResult.DraftPrepared(
                platform = platform,
                recipientName = recipientName,
                messageText = message,
                targetPackage = installedPkg,
                explanation = "WhatsApp chat opened for $recipientName with prepared message draft. Tap Send in WhatsApp to deliver."
            )
        } else {
            ComposeResult.Error(
                platform = platform,
                errorMessage = "Failed to launch WhatsApp compose intent."
            )
        }
    }
}
