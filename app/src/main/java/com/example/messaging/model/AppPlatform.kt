package com.example.messaging.model

enum class AppPlatform(
    val displayName: String,
    val primaryPackage: String,
    val alternatePackages: List<String> = emptyList(),
    val directPhoneSupported: Boolean = false,
    val textPrefillSupported: Boolean = true
) {
    WHATSAPP(
        displayName = "WhatsApp",
        primaryPackage = "com.whatsapp",
        alternatePackages = listOf("com.whatsapp.w4b"),
        directPhoneSupported = true,
        textPrefillSupported = true
    ),
    WHATSAPP_BUSINESS(
        displayName = "WhatsApp Business",
        primaryPackage = "com.whatsapp.w4b",
        alternatePackages = listOf("com.whatsapp"),
        directPhoneSupported = true,
        textPrefillSupported = true
    ),
    INSTAGRAM(
        displayName = "Instagram",
        primaryPackage = "com.instagram.android",
        alternatePackages = emptyList(),
        directPhoneSupported = false,
        textPrefillSupported = false // Instagram does not support direct phone prefilled text via public intent
    ),
    MESSENGER(
        displayName = "Facebook Messenger",
        primaryPackage = "com.facebook.orca",
        alternatePackages = listOf("com.facebook.mlite"),
        directPhoneSupported = false,
        textPrefillSupported = true
    ),
    TELEGRAM(
        displayName = "Telegram",
        primaryPackage = "org.telegram.messenger",
        alternatePackages = listOf("org.thunderdog.challegram", "org.telegram.messenger.web"),
        directPhoneSupported = true,
        textPrefillSupported = true
    ),
    SMS(
        displayName = "SMS / Google Messages",
        primaryPackage = "com.google.android.apps.messaging",
        alternatePackages = listOf("com.android.mms", "com.samsung.android.messaging"),
        directPhoneSupported = true,
        textPrefillSupported = true
    ),
    SIGNAL(
        displayName = "Signal",
        primaryPackage = "org.thoughtcrime.securesms",
        alternatePackages = emptyList(),
        directPhoneSupported = false,
        textPrefillSupported = true
    ),
    GENERIC(
        displayName = "Other Messaging App",
        primaryPackage = "",
        alternatePackages = emptyList(),
        directPhoneSupported = false,
        textPrefillSupported = true
    );

    companion object {
        fun fromPackageName(packageName: String): AppPlatform {
            val pkg = packageName.lowercase()
            return when {
                pkg == "com.whatsapp.w4b" -> WHATSAPP_BUSINESS
                pkg.contains("whatsapp") -> WHATSAPP
                pkg.contains("instagram") -> INSTAGRAM
                pkg == "com.facebook.orca" || pkg == "com.facebook.mlite" -> MESSENGER
                pkg.contains("telegram") || pkg.contains("challegram") -> TELEGRAM
                pkg.contains("messaging") || pkg.contains("mms") -> SMS
                pkg.contains("securesms") -> SIGNAL
                else -> GENERIC
            }
        }

        fun fromUserQuery(query: String): AppPlatform? {
            val clean = query.lowercase().trim()
            val tokens = clean.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
            return when {
                clean.contains("whatsapp business") || clean.contains("wa business") -> WHATSAPP_BUSINESS
                clean.contains("signal") || clean.contains("सिग्नल") -> SIGNAL
                clean.contains("whatsapp") || clean.contains("whats app") || "wa" in tokens ||
                        clean.contains("व्हाट्सएप") || clean.contains("व्हाट्सअप") -> WHATSAPP
                clean.contains("instagram") || clean.contains("insta") || "ig" in tokens ||
                        clean.contains("इंस्टाग्राम") -> INSTAGRAM
                clean.contains("messenger") || clean.contains("fb messenger") || clean.contains("facebook") ||
                        clean.contains("मैसेंजर") -> MESSENGER
                clean.contains("telegram") || "tg" in tokens || clean.contains("टेलीग्राम") -> TELEGRAM
                clean.contains("sms") || clean.contains("text message") || clean.contains("google messages") ||
                        clean.contains("संदेश") -> SMS
                clean == "message" || clean == "messages" -> SMS
                else -> null
            }
        }
    }
}
