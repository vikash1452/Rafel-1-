package com.example.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.accessibility.RafelAccessibilityService
import com.example.screen.ScreenCaptureManager
import com.example.voice.RafelLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class PendingAction(
    val id: String,
    val type: ActionType,
    val title: String,
    val description: String,
    val onConfirm: suspend () -> String
) {
    enum class ActionType { CALL, SMS, DELETE, SETTINGS }
}

class PhoneControlManager(private val context: Context) {

    companion object {
        private val _pendingAction = MutableStateFlow<PendingAction?>(null)
        val pendingActionFlow: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

        fun setPendingAction(action: PendingAction?) {
            _pendingAction.value = action
        }

        fun clearPendingAction() {
            _pendingAction.value = null
        }

        suspend fun confirmPendingAction(): String {
            val action = _pendingAction.value ?: return "No pending action to confirm."
            _pendingAction.value = null
            return action.onConfirm()
        }
    }

    var pendingAction: PendingAction?
        get() = _pendingAction.value
        private set(value) {
            _pendingAction.value = value
        }

    fun clearPendingAction() {
        Companion.clearPendingAction()
    }

    suspend fun confirmPendingAction(): String {
        return Companion.confirmPendingAction()
    }

    /**
     * Contact Search and Calling implementation:
     * - Checks READ_CONTACTS permission
     * - Searches Contacts Provider for matching names/numbers
     * - Asks user clarification if ambiguous (>1 matches)
     * - Defaults to Intent.ACTION_DIAL (prefilling dialer without auto-calling)
     * - Only initiates direct call (ACTION_CALL) if CALL_PHONE permission is granted AND direct calling is enabled in Settings
     */
    suspend fun requestCallContact(
        contactName: String,
        useDialer: Boolean = false,
        simSlot: Int? = null,
        confirmed: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        android.util.Log.i("RAFEL_CALL_TRACE", "Stage 1 & 2: requestCallContact invoked for query length=${contactName.length}")
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        android.util.Log.i("RAFEL_CALL_TRACE", "Stage 4: READ_CONTACTS permission granted = $hasContactsPermission")

        val isRawNumber = contactName.replace(Regex("[^0-9+]"), "").length >= 3 &&
                contactName.matches(Regex("^[0-9+\\-*#\\s()]+$"))

        if (!hasContactsPermission && !isRawNumber) {
            android.util.Log.w("RAFEL_CALL_TRACE", "Stage 4 failed: READ_CONTACTS permission missing and query is not a raw number.")
            return@withContext "PERMISSION_DENIED: READ_CONTACTS permission is required to search contacts. Please grant Contacts permission in Settings."
        }

        val matches = if (isRawNumber) {
            listOf(Pair(contactName.trim(), contactName.replace(Regex("[^0-9+]"), "")))
        } else {
            findContacts(contactName)
        }

        android.util.Log.i("RAFEL_CALL_TRACE", "Stage 3: Contact lookup results count=${matches.size}, isRawNumber=$isRawNumber")

        if (matches.isEmpty()) {
            android.util.Log.w("RAFEL_CALL_TRACE", "Stage 3 failed: No contact found matching query.")
            return@withContext "NOT_FOUND: No contact found matching '$contactName'. Please check the name or spelling."
        }

        if (matches.size > 1 && !isRawNumber) {
            val summary = matches.take(3).joinToString("; ") { "${it.first}" }
            android.util.Log.i("RAFEL_CALL_TRACE", "Stage 3 ambiguous: Found ${matches.size} matching contacts.")
            return@withContext "AMBIGUOUS: Found ${matches.size} matching contacts for '$contactName': $summary. Which one would you like to call?"
        }

        val target = matches.first()
        val displayName = target.first
        val phoneNumber = target.second

        val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
        val directCallingEnabled = prefs.getBoolean("direct_calling_enabled", false)

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        android.util.Log.i("RAFEL_CALL_TRACE", "Stage 4: CALL_PHONE permission granted = $hasCallPermission, directCallingEnabled=$directCallingEnabled")

        // If direct calling is enabled and requested, handle confirmation if needed
        if (directCallingEnabled && hasCallPermission && !useDialer && !confirmed) {
            pendingAction = PendingAction(
                id = "call_${System.currentTimeMillis()}",
                type = PendingAction.ActionType.CALL,
                title = "Direct Call: $displayName",
                description = "Place a direct phone call to $displayName?",
                onConfirm = { executeCall(phoneNumber, displayName, directCallRequested = true, simSlot = simSlot) }
            )
            return@withContext "Calling $displayName. Please confirm to proceed with direct call."
        }

        return@withContext executeCall(
            phoneNumber = phoneNumber,
            displayName = displayName,
            directCallRequested = directCallingEnabled && hasCallPermission && !useDialer,
            simSlot = simSlot
        )
    }

    private fun executeCall(
        phoneNumber: String,
        displayName: String,
        directCallRequested: Boolean,
        simSlot: Int?
    ): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        val maskedNumber = if (cleanNumber.length > 4) "***${cleanNumber.takeLast(4)}" else "***"
        if (com.example.BuildConfig.DEBUG) {
            android.util.Log.i("RAFEL_CALL_TRACE", "Stage 3 resolved: Contact '$displayName', number masked=$maskedNumber")
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
        val directCallingEnabled = prefs.getBoolean("direct_calling_enabled", false)

        val shouldDirectCall = directCallRequested && directCallingEnabled && hasCallPermission
        android.util.Log.i("RAFEL_CALL_TRACE", "Stage 5: shouldDirectCall=$shouldDirectCall")

        val uri = try {
            Uri.parse("tel:$cleanNumber")
        } catch (e: Exception) {
            android.util.Log.e("RAFEL_CALL_TRACE", "Stage 5 error: Malformed tel URI: ${e.message}")
            return "ERROR: Malformed phone number URI."
        }

        val intent = if (shouldDirectCall) {
            Intent(Intent.ACTION_CALL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (simSlot != null) {
                    val slotIndex = simSlot - 1
                    putExtra("com.android.phone.force.slot", true)
                    putExtra("com.android.phone.extra.slot", slotIndex)
                    putExtra("simSlot", slotIndex)
                }
            }
        } else {
            Intent(Intent.ACTION_DIAL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val pm = context.packageManager
        val resolved = intent.resolveActivity(pm) != null
        android.util.Log.i("RAFEL_CALL_TRACE", "Stage 5 & 6: Intent action=${intent.action}, resolvedActivity=$resolved")

        return try {
            context.startActivity(intent)
            android.util.Log.i("RAFEL_CALL_TRACE", "Stage 6 success: startActivity completed for action=${intent.action}")
            if (shouldDirectCall) {
                "CALL_INITIATED: Direct call placed to $displayName ($maskedNumber)."
            } else if (directCallRequested && (!hasCallPermission || !directCallingEnabled)) {
                "DIALER_FALLBACK: Direct calling not enabled or CALL_PHONE permission missing. DIALER_OPENED for $displayName ($maskedNumber). Tap call button to dial."
            } else {
                "DIALER_OPENED: Opened phone dialer for $displayName ($maskedNumber). Tap call button to dial."
            }
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.e("RAFEL_CALL_TRACE", "Stage 6 error: ActivityNotFoundException: ${e.message}", e)
            "ERROR: No dialer activity found on device to handle phone call."
        } catch (e: Exception) {
            android.util.Log.e("RAFEL_CALL_TRACE", "Stage 6 error: Exception placing call: ${e.message}", e)
            "ERROR: Unable to place call: ${e.message}"
        }
    }

    /**
     * SMS Tool implementation:
     * - Resolves recipient contact
     * - Uses Intent.ACTION_SENDTO with smsto: for user safety
     * - Only dispatches direct SMS if SEND_SMS permission is granted AND explicit user confirmation is given
     */
    suspend fun requestSendSms(
        contactName: String,
        message: String,
        confirmed: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val isRawNumber = contactName.replace(Regex("[^0-9+]"), "").length >= 3 &&
                contactName.matches(Regex("^[0-9+\\-*#\\s()]+$"))

        if (!hasContactsPermission && !isRawNumber) {
            return@withContext "PERMISSION_DENIED: READ_CONTACTS permission is required to find '$contactName'. Please grant Contacts permission in Settings."
        }

        val matches = if (isRawNumber) {
            listOf(Pair(contactName.trim(), contactName.replace(Regex("[^0-9+]"), "")))
        } else {
            findContacts(contactName)
        }

        if (matches.isEmpty()) {
            return@withContext "NOT_FOUND: Could not find a contact matching '$contactName' to send SMS."
        }

        if (matches.size > 1 && !isRawNumber) {
            val summary = matches.take(3).joinToString("; ") { "${it.first} (${it.second})" }
            return@withContext "AMBIGUOUS: Found ${matches.size} contacts for '$contactName': $summary. Which one should I message?"
        }

        val target = matches.first()
        val displayName = target.first
        val phoneNumber = target.second

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPermission && !confirmed) {
            pendingAction = PendingAction(
                id = "sms_${System.currentTimeMillis()}",
                type = PendingAction.ActionType.SMS,
                title = "Send SMS to $displayName",
                description = "Send message: \"$message\" to $displayName ($phoneNumber)?",
                onConfirm = { executeSendSms(phoneNumber, displayName, message) }
            )
            return@withContext "Sending SMS to $displayName: \"$message\". Please confirm to send."
        }

        return@withContext executeSendSms(phoneNumber, displayName, message)
    }

    private fun executeSendSms(phoneNumber: String, targetName: String, message: String): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPermission) {
            return try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(cleanNumber, null, message, null, null)
                "SUCCESS: SMS sent successfully to $targetName."
            } catch (e: Exception) {
                openSmsComposer(cleanNumber, message)
                "DIALER_FALLBACK: Direct SMS sending encountered an error (${e.message}). Opened message composer with draft for $targetName instead."
            }
        } else {
            openSmsComposer(cleanNumber, message)
            return "SUCCESS: Opened message composer for $targetName with draft message: \"$message\". Tap send in your messaging app."
        }
    }

    private fun openSmsComposer(number: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * WhatsApp Tool implementation:
     * - Checks if WhatsApp or WhatsApp Business is installed
     * - Resolves contact recipient
     * - Opens official WhatsApp deep link to compose screen
     * - Reports truthful result (chat opened, user taps send)
     */
    suspend fun openWhatsAppChat(contactName: String, message: String): String = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val isStandardInstalled = try {
            pm.getPackageInfo("com.whatsapp", 0)
            true
        } catch (_: Exception) { false }

        val isBusinessInstalled = try {
            pm.getPackageInfo("com.whatsapp.w4b", 0)
            true
        } catch (_: Exception) { false }

        if (!isStandardInstalled && !isBusinessInstalled) {
            return@withContext "NOT_INSTALLED: WhatsApp is not installed on this device. Please install WhatsApp from the Google Play Store."
        }

        val isRawNumber = contactName.replace(Regex("[^0-9+]"), "").length >= 3 &&
                contactName.matches(Regex("^[0-9+\\-*#\\s()]+$"))

        if (!isRawNumber) {
            val hasContactsPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasContactsPermission) {
                return@withContext "PERMISSION_DENIED: READ_CONTACTS permission is required to find '$contactName'. Please grant Contacts permission in Settings."
            }
        }

        val matches = if (isRawNumber) {
            listOf(Pair(contactName.trim(), contactName.replace(Regex("[^0-9+]"), "")))
        } else {
            findContacts(contactName)
        }

        if (matches.isEmpty()) {
            return@withContext "NOT_FOUND: Could not find a contact matching '$contactName' to open WhatsApp."
        }

        if (matches.size > 1 && !isRawNumber) {
            val summary = matches.take(3).joinToString("; ") { "${it.first} (${it.second})" }
            return@withContext "AMBIGUOUS: Multiple contacts found for '$contactName': $summary. Please specify which contact you want to message."
        }

        val target = matches.first()
        val displayName = target.first
        val phoneNumber = target.second
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")

        val targetPackage = if (isStandardInstalled) "com.whatsapp" else "com.whatsapp.w4b"

        return@withContext try {
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=" + Uri.encode(message))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "SUCCESS: WhatsApp chat opened for $displayName with message draft: \"$message\". Tap Send in WhatsApp to deliver."
        } catch (e: Exception) {
            try {
                // Fallback without package lock if specific intent failed
                val fallbackUri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=" + Uri.encode(message))
                val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                "SUCCESS: WhatsApp chat opened for $displayName with message draft: \"$message\". Tap Send in WhatsApp to deliver."
            } catch (e2: Exception) {
                "ERROR: WhatsApp could not be opened: ${e2.message}"
            }
        }
    }

    /**
     * Screen Understanding / Screen Analysis tool implementation:
     * - Checks if ScreenCaptureManager is actively sharing
     * - Captures current visible frame and returns status for Gemini analysis
     */
    fun describeScreenContent(): String {
        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 1: Screen-capture permission and projection active state check.")
        val screenManager = ScreenCaptureManager.getInstance(context)
        val isSharing = screenManager.isSharingFlow.value
        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 1 result: isSharing=$isSharing")
        if (!isSharing) {
            android.util.Log.w("RAFEL_SCREEN_TRACE", "Stage 1 failed: Screen sharing is currently OFF.")
            return "SCREEN_SHARE_INACTIVE: Screen sharing is currently OFF. Please grant consent to enable live screen understanding."
        }

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 2: Capturing fresh frame...")
        val frameBase64 = screenManager.captureCurrentFrame()
        if (frameBase64.isNullOrBlank()) {
            android.util.Log.e("RAFEL_SCREEN_TRACE", "Stage 2 failed: Captured frame is null or blank.")
            return "ERROR: Could not capture screen frame from active projection."
        }
        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 2 success: Base64 length = ${frameBase64.length} chars.")

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 3: Base64 decoding/encoding validation...")
        val decodedBytes = try {
            android.util.Base64.decode(frameBase64, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("RAFEL_SCREEN_TRACE", "Stage 3 failed: Base64 decode error: ${e.message}")
            null
        }
        if (decodedBytes == null || decodedBytes.isEmpty()) {
            android.util.Log.e("RAFEL_SCREEN_TRACE", "Stage 3 failed: Decoded JPEG bytes empty or invalid.")
            return "ERROR: Screen frame decoding failed."
        }
        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 3 success: JPEG byte count = ${decodedBytes.size} bytes.")

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 4: Checking active Gemini Live session and setup-complete state...")
        val service = com.example.RafelForegroundService.activeService
        val liveSession = service?.liveSessionManager
        val isConnected = liveSession?.isConnected() == true
        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 4 state: serviceExists=${service != null}, sessionExists=${liveSession != null}, connected=$isConnected")

        if (liveSession == null || !isConnected) {
            android.util.Log.e("RAFEL_SCREEN_TRACE", "Stage 4 failed: Gemini Live session not connected or setup incomplete.")
            return "ERROR: Gemini Live session is not connected or setup is incomplete. Please ensure Rafel is connected."
        }

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 5: Preparing exact image-input message type (realtimeInput.mediaChunks mimeType=image/jpeg)...")
        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 5 ready: Sending image payload of size ${frameBase64.length} chars over WebSocket.")

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 6: Initiating WebSocket send...")
        try {
            liveSession.sendImageFrame(frameBase64)
            android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 6 success: WebSocket send completed successfully.")
        } catch (e: Exception) {
            android.util.Log.e("RAFEL_SCREEN_TRACE", "Stage 6 failed: WebSocket send threw exception: ${e.message}", e)
            return "ERROR: Failed to transmit screen frame to Gemini: ${e.message}"
        }

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 7: Prompting model to analyze newly sent image...")
        try {
            liveSession.sendTextMessage("Analyze the screen image I just sent you and describe what is visible on my screen.")
            android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 7 success: Prompt text sent to model for image analysis.")
        } catch (e: Exception) {
            android.util.Log.w("RAFEL_SCREEN_TRACE", "Stage 7 warning: Failed to send follow-up prompt: ${e.message}")
        }

        android.util.Log.i("RAFEL_SCREEN_TRACE", "Stage 8: Returning final response to user indicating screen frame was sent and is being analyzed.")
        return "SUCCESS: Screen frame sent (${decodedBytes.size} bytes). Rafel is analyzing your screen now."
    }

    fun findContacts(namePattern: String): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val exactMatches = mutableListOf<Pair<String, String>>()
                val startsWithMatches = mutableListOf<Pair<String, String>>()
                val containsMatches = mutableListOf<Pair<String, String>>()

                val cleanPattern = namePattern.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                val searchWords = cleanPattern.split(" ").filter { it.isNotEmpty() }

                while (cursor.moveToNext()) {
                    val contactName = cursor.getString(nameIdx) ?: continue
                    val contactNum = cursor.getString(numIdx) ?: continue
                    val cleanContact = contactName.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

                    if (cleanContact.isEmpty()) continue

                    if (cleanContact == cleanPattern) {
                        exactMatches.add(Pair(contactName, contactNum))
                    } else if (cleanContact.startsWith(cleanPattern)) {
                        startsWithMatches.add(Pair(contactName, contactNum))
                    } else if (searchWords.isNotEmpty() && searchWords.all { cleanContact.contains(it) }) {
                        containsMatches.add(Pair(contactName, contactNum))
                    }
                }

                if (exactMatches.isNotEmpty()) return exactMatches.distinctBy { it.second }
                if (startsWithMatches.isNotEmpty()) return startsWithMatches.distinctBy { it.second }
                if (containsMatches.isNotEmpty()) return containsMatches.distinctBy { it.second }
            }
        } catch (e: Exception) {
            RafelLogger.logError("Error reading contacts: ${e.message}")
        }
        return emptyList()
    }

    suspend fun openApp(appName: String): String {
        return com.example.tools.AppLauncherTool(context).launchApp(appName).toAiResponse()
    }

    fun searchYouTube(query: String): String {
        return try {
            val appIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(appIntent)
                "Searched YouTube for '$query'."
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                "Opened YouTube search in browser for '$query'."
            }
        } catch (e: Exception) {
            "Unable to search YouTube: ${e.message}"
        }
    }

    fun openCamera(): String {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Camera opened."
        } catch (e: Exception) {
            "Unable to open camera: ${e.message}"
        }
    }

    fun openGallery(): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened photo gallery."
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                "Opened photo picker."
            } catch (e2: Exception) {
                "Unable to open gallery: ${e2.message}"
            }
        }
    }

    fun openSystemSettings(settingType: String): String {
        val action = when (settingType.lowercase().trim()) {
            "wifi", "wi-fi", "internet" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "screen", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume", "audio" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "notification", "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "default_apps", "assistant" -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            }
            context.startActivity(intent)
            "Opened ${settingType.replaceFirstChar { it.uppercase() }} settings."
        } catch (e: Exception) {
            "Unable to open settings: ${e.message}"
        }
    }

    suspend fun getCurrentLocation(): String = withContext(Dispatchers.IO) {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return@withContext "Location permission is not granted. Please allow Location in Rafel Settings."
        }

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null

            for (provider in providers) {
                @Suppress("MissingPermission")
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }

            if (bestLocation != null) {
                val lat = bestLocation.latitude
                val lon = bestLocation.longitude

                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address: Address = addresses[0]
                        val locality = address.locality ?: address.subAdminArea ?: ""
                        val admin = address.adminArea ?: ""
                        val country = address.countryName ?: ""
                        val fullAddress = listOf(locality, admin, country).filter { it.isNotBlank() }.joinToString(", ")
                        return@withContext "You are currently near $fullAddress (Coordinates: ${String.format(Locale.US, "%.4f, %.4f", lat, lon)})."
                    }
                } catch (e: Exception) {
                    // Geocoder failed or offline
                }

                return@withContext "Your coordinates are: Latitude ${String.format(Locale.US, "%.4f", lat)}, Longitude ${String.format(Locale.US, "%.4f", lon)}."
            } else {
                return@withContext "Waiting for GPS satellite lock. Please ensure Location is enabled in device settings."
            }
        } catch (e: Exception) {
            return@withContext "Error fetching location: ${e.message}"
        }
    }

    fun openMaps(destination: String): String {
        return try {
            val uri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                "Opened Google Maps navigating to '$destination'."
            } catch (e: Exception) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                "Opened Maps in browser for '$destination'."
            }
        } catch (e: Exception) {
            "Unable to open navigation: ${e.message}"
        }
    }

    fun addCalendarEvent(title: String, description: String = "", beginTimeMillis: Long = System.currentTimeMillis() + 3600000): String {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened calendar to create event: '$title'."
        } catch (e: Exception) {
            "Unable to add calendar event: ${e.message}"
        }
    }

    fun adjustVolume(direction: String): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        return try {
            when (direction.lowercase().trim()) {
                "up", "raise", "increase" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume increased."
                }
                "down", "lower", "decrease" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume decreased."
                }
                "mute" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume muted."
                }
                "unmute" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "Volume unmuted."
                }
                "max" -> {
                    val max = audioManager.getStreamMaxVolume(stream)
                    audioManager.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
                    "Volume set to maximum."
                }
                else -> "Unknown volume command. Use up, down, mute, or max."
            }
        } catch (e: Exception) {
            "Failed to adjust volume: ${e.message}"
        }
    }

    fun performAccessibilityAction(actionType: String, targetText: String? = null): String {
        val service = RafelAccessibilityService.instance
            ?: return "Accessibility Service is OFF. Turn ON 'Rafel Automation' in Android Accessibility Settings."

        return when (actionType.lowercase().trim()) {
            "click" -> {
                if (targetText.isNullOrBlank()) return "Missing target text to click."
                val success = RafelAccessibilityService.clickTextOnScreen(targetText)
                if (success) "Clicked '$targetText' on screen." else "Could not find '$targetText' on current screen."
            }
            "back" -> {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                if (success) "Navigated back." else "Failed to navigate back."
            }
            "home" -> {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                if (success) "Navigated to home screen." else "Failed to navigate home."
            }
            "recents" -> {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                if (success) "Opened recent apps." else "Failed to open recent apps."
            }
            "notifications" -> {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                if (success) "Opened notification drawer." else "Failed to open notifications."
            }
            "quick_settings" -> {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                if (success) "Opened quick settings." else "Failed to open quick settings."
            }
            else -> "Unknown accessibility action '$actionType'."
        }
    }

    fun sendGmail(recipientEmail: String, subject: String, body: String): String {
        return try {
            val mailtoUri = Uri.parse("mailto:${Uri.encode(recipientEmail.trim())}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
            val gmailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
                setPackage("com.google.android.gm")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail.trim()))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(gmailIntent)
                "SUCCESS: Opened Gmail compose screen for $recipientEmail with subject '$subject'. Tap Send in Gmail to deliver."
            } catch (_: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(recipientEmail.trim())}")).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail.trim()))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                "SUCCESS: Opened email composer for $recipientEmail with subject '$subject'. Tap Send in your email app."
            }
        } catch (e: Exception) {
            "Unable to open email composer: ${e.message}"
        }
    }

    fun setVolumePercent(percent: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        return try {
            val clamped = percent.coerceIn(0, 100)
            val max = audioManager.getStreamMaxVolume(stream)
            val targetVol = Math.round((clamped / 100.0) * max).toInt().coerceIn(0, max)
            audioManager.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
            "Volume set to $clamped% ($targetVol/$max)."
        } catch (e: Exception) {
            "Failed to set volume percent: ${e.message}"
        }
    }

    fun getSimCardInfo(): String {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneState) {
            return "SIM_INFO: READ_PHONE_STATE permission not granted. Using default SIM."
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                @Suppress("MissingPermission")
                val subs = subManager?.activeSubscriptionInfoList
                if (!subs.isNullOrEmpty()) {
                    val info = subs.joinToString(", ") { sub ->
                        "SIM ${sub.simSlotIndex + 1}: ${sub.carrierName ?: sub.displayName ?: "Active"}"
                    }
                    "Active SIM cards (${subs.size}): $info"
                } else {
                    "1 active default SIM detected."
                }
            } else {
                "Default SIM active."
            }
        } catch (e: Exception) {
            "Default SIM active (${e.message})."
        }
    }

    fun setBrightness(level: Int): String {
        val clamped = level.coerceIn(0, 100)
        val systemBrightness = Math.round((clamped / 100.0) * 255).toInt().coerceIn(0, 255)
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    systemBrightness
                )
                "Screen brightness set to $clamped%."
            } else {
                openSystemSettings("display")
                "Opened Display settings to adjust brightness to $clamped% (Modify System Settings permission not granted)."
            }
        } catch (e: Exception) {
            openSystemSettings("display")
            "Opened Display settings to adjust brightness."
        }
    }

    fun playMedia(query: String): String {
        return try {
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (mediaIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mediaIntent)
                "Playing '$query'."
            } else {
                searchYouTube(query)
            }
        } catch (_: Exception) {
            searchYouTube(query)
        }
    }
}
