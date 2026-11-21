package com.example.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

sealed class AppLaunchResult {
    data class Success(val appName: String, val packageName: String, val message: String) : AppLaunchResult()
    data class AppNotInstalled(val appName: String, val suggestedPackage: String?, val message: String) : AppLaunchResult()
    data class NoLaunchIntent(val appName: String, val packageName: String, val message: String) : AppLaunchResult()
    data class SecurityRestriction(val appName: String, val message: String) : AppLaunchResult()
    data class Failure(val appName: String, val message: String) : AppLaunchResult()

    fun toAiResponse(): String = when (this) {
        is Success -> "SUCCESS: $message"
        is AppNotInstalled -> "NOT_INSTALLED: $message"
        is NoLaunchIntent -> "NO_LAUNCH_INTENT: $message"
        is SecurityRestriction -> "RESTRICTED: $message"
        is Failure -> "FAILED: $message"
    }
}

class AppLauncherTool(private val context: Context) {

    companion object {
        const val TAG = "RAFEL-DIAGNOSTIC"

        val KNOWN_PACKAGES: Map<String, List<String>> = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "wa" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "whats app" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "व्हाट्सएप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "व्हाट्सअप" to listOf("com.whatsapp", "com.whatsapp.w4b"),

            "instagram" to listOf("com.instagram.android"),
            "insta" to listOf("com.instagram.android"),
            "ig" to listOf("com.instagram.android"),
            "इंस्टाग्राम" to listOf("com.instagram.android"),
            "इन्स्टा" to listOf("com.instagram.android"),

            "youtube" to listOf("com.google.android.youtube"),
            "yt" to listOf("com.google.android.youtube"),
            "यू ट्यूब" to listOf("com.google.android.youtube"),
            "यूट्यूब" to listOf("com.google.android.youtube"),

            "settings" to listOf("com.android.settings"),
            "setting" to listOf("com.android.settings"),
            "phone settings" to listOf("com.android.settings"),
            "सेटिंग्स" to listOf("com.android.settings"),
            "सेटिंग" to listOf("com.android.settings"),

            "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "call" to listOf("com.google.android.dialer", "com.android.dialer"),
            "caller" to listOf("com.google.android.dialer", "com.android.dialer"),
            "phone app" to listOf("com.google.android.dialer", "com.android.dialer"),
            "फोन" to listOf("com.google.android.dialer", "com.android.dialer"),
            "डायलर" to listOf("com.google.android.dialer", "com.android.dialer"),

            "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.sec.android.app.camera"),
            "kamera" to listOf("com.google.android.GoogleCamera", "com.android.camera"),
            "कैमरा" to listOf("com.google.android.GoogleCamera", "com.android.camera"),

            "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
            "photos" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
            "गैलरी" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),

            "maps" to listOf("com.google.android.apps.maps"),
            "map" to listOf("com.google.android.apps.maps"),
            "मैप" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),

            "chrome" to listOf("com.android.chrome"),
            "browser" to listOf("com.android.chrome"),

            "gmail" to listOf("com.google.android.gm"),
            "email" to listOf("com.google.android.gm")
        )

        fun normalizeName(raw: String): String {
            return raw.trim().lowercase(Locale.ROOT)
                .replace(Regex("(?i)\\bapp\\b"), "")
                .replace(Regex("(?i)\\bkholo\\b"), "")
                .replace(Regex("(?i)\\bopen\\b"), "")
                .replace(Regex("(?i)\\blaunch\\b"), "")
                .replace(Regex("(?i)\\bstart\\b"), "")
                .replace(Regex("(?i)\\bchalao\\b"), "")
                .replace("खोलो", "")
                .replace("खोल दो", "")
                .replace("खोल", "")
                .replace("चलाओ", "")
                .trim()
        }

        fun getCanonicalName(cleanName: String, fallback: String): String {
            return when (cleanName) {
                "whatsapp", "wa", "whats app", "व्हाट्सएप", "व्हाट्सअप" -> "WhatsApp"
                "instagram", "insta", "ig", "इंस्टाग्राम", "इन्स्टा" -> "Instagram"
                "youtube", "yt", "यू ट्यूब", "यूट्यूब" -> "YouTube"
                "settings", "setting", "phone settings", "device settings", "सेटिंग्स", "सेटिंग" -> "Settings"
                "phone", "dialer", "call", "caller", "phone app", "calling app", "फोन", "डायलर" -> "Phone"
                "camera", "kamera", "कैमरा" -> "Camera"
                "gallery", "photos", "गैलरी" -> "Gallery"
                "chrome", "browser" -> "Chrome"
                "maps", "map", "मैप", "google maps" -> "Maps"
                "gmail", "email" -> "Gmail"
                else -> fallback.replaceFirstChar { it.uppercase() }
            }
        }
    }

    /**
     * Resolves app name to target package name without launching.
     */
    fun resolvePackage(requestedName: String): String? {
        val clean = normalizeName(requestedName)
        if (clean.contains(".") && !clean.contains(" ")) {
            return clean
        }
        val candidate = KNOWN_PACKAGES[clean]?.firstOrNull()
        if (candidate != null) return candidate

        // Check installed applications
        return try {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installed) {
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT)
                if (label == clean || label.contains(clean) || clean.contains(label)) {
                    return app.packageName
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves and launches an application safely from any coroutine context.
     */
    suspend fun launchApp(requestedName: String): AppLaunchResult {
        val cleanName = normalizeName(requestedName)
        val canonicalName = getCanonicalName(cleanName, requestedName)
        Log.i(TAG, "APP_LAUNCH_REQUEST: appName='$requestedName' (normalized='$cleanName')")

        // 1. System Settings intent
        if (cleanName in listOf("settings", "setting", "phone settings", "device settings", "सेटिंग्स", "सेटिंग")) {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i(TAG, "APP_RESOLVED: appName='$requestedName' -> package='com.android.settings'")
            return launchSystemIntent(settingsIntent, "Settings", "com.android.settings")
        }

        // 2. Phone / Dialer intent
        if (cleanName in listOf("phone", "dialer", "call", "caller", "phone app", "calling app", "फोन", "डायलर")) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i(TAG, "APP_RESOLVED: appName='$requestedName' -> package='com.android.dialer'")
            return launchSystemIntent(dialIntent, "Phone", "com.android.dialer")
        }

        // 3. Camera intent
        if (cleanName in listOf("camera", "kamera", "कैमरा")) {
            val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (camIntent.resolveActivity(context.packageManager) != null) {
                Log.i(TAG, "APP_RESOLVED: appName='$requestedName' -> package='camera'")
                return launchSystemIntent(camIntent, "Camera", "camera")
            }
        }

        val pm = context.packageManager

        // 4. Known aliases lookup
        val candidates = KNOWN_PACKAGES[cleanName] ?: emptyList()
        for (pkg in candidates) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                Log.i(TAG, "APP_RESOLVED: appName='$requestedName' -> package='$pkg'")
                return tryLaunchIntent(launchIntent, canonicalName, pkg)
            }
        }

        // 5. Direct package name given
        if (cleanName.contains(".") && !cleanName.contains(" ")) {
            val launchIntent = pm.getLaunchIntentForPackage(cleanName)
            if (launchIntent != null) {
                Log.i(TAG, "APP_RESOLVED: appName='$requestedName' -> package='$cleanName'")
                return tryLaunchIntent(launchIntent, cleanName, cleanName)
            }
        }

        // 6. Installed apps scanning
        val installedApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        var matchedPkg: String? = null
        var matchedLabel: String? = null

        for (appInfo in installedApps) {
            val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
            if (label == cleanName || label.contains(cleanName) || cleanName.contains(label)) {
                matchedPkg = appInfo.packageName
                matchedLabel = pm.getApplicationLabel(appInfo).toString()
                break
            }
        }

        if (matchedPkg != null) {
            Log.i(TAG, "APP_RESOLVED: appName='$requestedName' -> package='$matchedPkg' (label='$matchedLabel')")
            val launchIntent = pm.getLaunchIntentForPackage(matchedPkg)
            if (launchIntent != null) {
                return tryLaunchIntent(launchIntent, matchedLabel ?: canonicalName, matchedPkg)
            } else {
                Log.e(TAG, "APP_LAUNCH_FAILED: appName='$requestedName', reason='No launch intent for package $matchedPkg'")
                return AppLaunchResult.NoLaunchIntent(
                    appName = matchedLabel ?: canonicalName,
                    packageName = matchedPkg,
                    message = "App '${matchedLabel ?: canonicalName}' is installed, but it does not have a default launchable activity."
                )
            }
        }

        // 7. App not installed
        val suggestedPkg = candidates.firstOrNull()
        Log.e(TAG, "APP_LAUNCH_FAILED: appName='$requestedName', reason='App not installed'")
        return AppLaunchResult.AppNotInstalled(
            appName = canonicalName,
            suggestedPackage = suggestedPkg,
            message = "App '$canonicalName' is not installed on this device. You can download it from Google Play Store."
        )
    }

    private fun tryLaunchIntent(intent: Intent, appName: String, packageName: String): AppLaunchResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "APP_LAUNCH_SUCCESS: appName='$appName', package='$packageName'")
            AppLaunchResult.Success(
                appName = appName,
                packageName = packageName,
                message = "Opened $appName successfully."
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "APP_LAUNCH_FAILED: appName='$appName', reason='Security restriction: ${e.message}'")
            AppLaunchResult.SecurityRestriction(
                appName = appName,
                message = "Permission or security restriction prevented opening $appName: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "APP_LAUNCH_FAILED: appName='$appName', reason='${e.message}'")
            AppLaunchResult.Failure(
                appName = appName,
                message = "Failed to launch $appName: ${e.message}"
            )
        }
    }

    private fun launchSystemIntent(intent: Intent, appName: String, fallbackPackage: String): AppLaunchResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "APP_LAUNCH_SUCCESS: appName='$appName', package='$fallbackPackage'")
            AppLaunchResult.Success(
                appName = appName,
                packageName = fallbackPackage,
                message = "Opened $appName successfully."
            )
        } catch (e: Exception) {
            Log.e(TAG, "APP_LAUNCH_FAILED: appName='$appName', reason='${e.message}'")
            AppLaunchResult.Failure(
                appName = appName,
                message = "Failed to open $appName: ${e.message}"
            )
        }
    }
}
