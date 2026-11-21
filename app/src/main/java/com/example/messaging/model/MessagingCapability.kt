package com.example.messaging.model

enum class ComposeMode {
    DIRECT_PHONE_LINK,     // Opens chat with contact directly via phone number and prefilled text
    CUSTOM_SCHEME_URI,     // Uses official custom scheme (e.g. tg://msg?to=...)
    ACTION_SEND_PACKAGE,   // Uses Intent.ACTION_SEND targeted to package
    ACTION_SENDTO_URI,     // Uses Intent.ACTION_SENDTO (e.g. smsto:...)
    APP_LAUNCH_FALLBACK    // Launches app directly when prefill/recipient selection is not supported
}

data class MessagingCapability(
    val platform: AppPlatform,
    val installedPackage: String?,
    val isInstalled: Boolean,
    val supportsDirectPhoneRecipient: Boolean,
    val supportsPrefilledText: Boolean,
    val supportsNotificationReading: Boolean,
    val supportedComposeModes: List<ComposeMode>,
    val limitationsDescription: String? = null
)
