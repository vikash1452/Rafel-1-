package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

class SmsMessagingAdapter : BaseMessagingAdapter(AppPlatform.SMS) {

    override fun getCapability(context: Context): MessagingCapability {
        return MessagingCapability(
            platform = platform,
            installedPackage = "default_sms_app",
            isInstalled = true, // SMS intent handler is always available on Android
            supportsDirectPhoneRecipient = true,
            supportsPrefilledText = true,
            supportsNotificationReading = true,
            supportedComposeModes = listOf(ComposeMode.ACTION_SENDTO_URI),
            limitationsDescription = "Opens the default SMS app with recipient number and draft message. Tap Send in SMS app to deliver."
        )
    }

    override fun composeMessage(
        context: Context,
        recipientName: String,
        phoneNumber: String?,
        message: String
    ): ComposeResult {
        val cleanPhone = phoneNumber?.replace(Regex("[^0-9+]"), "") ?: ""

        val uri = if (cleanPhone.isNotBlank()) {
            Uri.parse("smsto:$cleanPhone")
        } else {
            Uri.parse("smsto:")
        }

        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            putExtra(Intent.EXTRA_TEXT, message)
        }

        val success = launchIntentSafely(context, intent)

        return if (success) {
            ComposeResult.DraftPrepared(
                platform = platform,
                recipientName = recipientName,
                messageText = message,
                targetPackage = "default_sms",
                explanation = "SMS composer opened for $recipientName with message draft: \"$message\". Tap Send in your messaging app."
            )
        } else {
            ComposeResult.Error(platform, "Failed to launch SMS composer.")
        }
    }
}
