package com.example.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.accessibility.RafelAccessibilityService

data class PermissionItem(
    val id: String,
    val name: String,
    val description: String,
    val manifestPermission: String?,
    val isGranted: Boolean,
    val isSystemSetting: Boolean = false,
    val settingAction: String? = null
)

object PermissionHelper {

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isOverlayGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (RafelAccessibilityService.instance != null) return true
        val serviceName = "${context.packageName}/${RafelAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(serviceName) || enabledServices.contains(context.packageName)
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(context.packageName)
    }

    fun getAll10Permissions(context: Context): List<PermissionItem> {
        val list = mutableListOf<PermissionItem>()

        // 1. Microphone
        list.add(
            PermissionItem(
                id = "mic",
                name = "Microphone",
                description = "So you can talk to Rafel.",
                manifestPermission = Manifest.permission.RECORD_AUDIO,
                isGranted = isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
            )
        )

        // 2. Notifications
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true
        list.add(
            PermissionItem(
                id = "notifications",
                name = "Notifications",
                description = "Rafel can provide its own notifications.",
                manifestPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
                isGranted = notifGranted
            )
        )

        // 3. Camera
        list.add(
            PermissionItem(
                id = "camera",
                name = "Camera",
                description = "So Rafel can use the camera when you ask.",
                manifestPermission = Manifest.permission.CAMERA,
                isGranted = isPermissionGranted(context, Manifest.permission.CAMERA)
            )
        )

        // 4. Contacts
        list.add(
            PermissionItem(
                id = "contacts",
                name = "Contacts",
                description = "Find contacts when you ask Rafel.",
                manifestPermission = Manifest.permission.READ_CONTACTS,
                isGranted = isPermissionGranted(context, Manifest.permission.READ_CONTACTS)
            )
        )

        // 5. SMS
        list.add(
            PermissionItem(
                id = "sms",
                name = "SMS",
                description = "Read or send SMS only when authorized and requested.",
                manifestPermission = Manifest.permission.SEND_SMS,
                isGranted = isPermissionGranted(context, Manifest.permission.SEND_SMS)
            )
        )

        // 6. Phone Calls
        list.add(
            PermissionItem(
                id = "calls",
                name = "Phone Calls",
                description = "Make or manage calls when authorized.",
                manifestPermission = Manifest.permission.CALL_PHONE,
                isGranted = isPermissionGranted(context, Manifest.permission.CALL_PHONE)
            )
        )

        // 7. Location
        list.add(
            PermissionItem(
                id = "location",
                name = "Location",
                description = "Use location for navigation, weather and location-based requests.",
                manifestPermission = Manifest.permission.ACCESS_FINE_LOCATION,
                isGranted = isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                        isPermissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        )

        // 8. Gallery & Files
        list.add(
            PermissionItem(
                id = "gallery",
                name = "Gallery & Files",
                description = "Access photos/files only when the user grants access.",
                manifestPermission = null,
                isGranted = true // Uses official system photo picker (no broad storage permission needed)
            )
        )

        // 9. Bluetooth
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isPermissionGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            isPermissionGranted(context, Manifest.permission.BLUETOOTH)
        }
        list.add(
            PermissionItem(
                id = "bluetooth",
                name = "Bluetooth",
                description = "Interact with supported Bluetooth devices.",
                manifestPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH,
                isGranted = btGranted
            )
        )

        // 10. Calendar
        list.add(
            PermissionItem(
                id = "calendar",
                name = "Calendar",
                description = "Read or create calendar events when authorized.",
                manifestPermission = Manifest.permission.READ_CALENDAR,
                isGranted = isPermissionGranted(context, Manifest.permission.READ_CALENDAR)
            )
        )

        return list
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun openBatterySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openDefaultAssistantSettings(context: Context) {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}
