package com.example.tools

import android.content.Context
import com.example.BuildConfig
import com.example.phone.PhoneControlManager
import com.example.screen.ScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolExecutionEngine(private val context: Context) {

    val phoneControl = PhoneControlManager(context)
    val appLauncher = AppLauncherTool(context)
    val messagingHub = com.example.messaging.MessagingHub(context)

    suspend fun execute(
        name: String,
        args: JsonObject,
        turnContext: UserTurnContext?
    ): String = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) {
            android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 1 & 2: Recognized tool function call: toolName=$name, turnTranscript=${turnContext?.transcript ?: "(no turn transcript)"}")
        }
        try {
            val gateDecision = ToolSafetyGate.evaluate(name, args, turnContext)
            if (!gateDecision.allowed) {
                android.util.Log.w("RAFEL_MESSAGE_TRACE", "ToolSafetyGate denied tool $name: ${gateDecision.reason}")
                return@withContext gateDecision.reason
            }

            val result = when (name) {
                "openApp" -> {
                    val appName = args["packageName"]?.jsonPrimitive?.content
                        ?: args["appName"]?.jsonPrimitive?.content
                        ?: return@withContext "Error: Missing app name"
                    if (BuildConfig.DEBUG) {
                        android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 3 parsed: appName=$appName")
                    }
                    appLauncher.launchApp(appName).toAiResponse()
                }
                "searchAndCallContact" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content
                        ?: args["recipient"]?.jsonPrimitive?.content
                        ?: args["name"]?.jsonPrimitive?.content
                        ?: args["query"]?.jsonPrimitive?.content
                        ?: args["phoneNumber"]?.jsonPrimitive?.content
                        ?: args["number"]?.jsonPrimitive?.content
                        ?: return@withContext "Error: Missing contactName"
                    val useDialer = args["useDialer"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val simSlot = args["simSlot"]?.jsonPrimitive?.content?.toIntOrNull()
                    val confirmed = args["confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    android.util.Log.i("RAFEL_CALL_TRACE", "Stage 1 & 2: Tool searchAndCallContact invoked with contactName length=${contactName.length}, useDialer=$useDialer")
                    phoneControl.requestCallContact(contactName, useDialer, simSlot, confirmed)
                }
                "sendSms" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    val confirmed = args["confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 3 parsed sms: contactName length=${contactName.length}, messageLen=${message.length}")
                    phoneControl.requestSendSms(contactName, message, confirmed)
                }
                "sendWhatsAppMessage" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 3 parsed WhatsApp: contactName length=${contactName.length}, messageLen=${message.length}")
                    phoneControl.openWhatsAppChat(contactName, message)
                }
                "composeMessage" -> {
                    val platform = args["platform"]?.jsonPrimitive?.content ?: "WhatsApp"
                    val recipient = args["recipient"]?.jsonPrimitive?.content
                        ?: args["contactName"]?.jsonPrimitive?.content
                        ?: args["recipientName"]?.jsonPrimitive?.content
                        ?: return@withContext "Error: Missing recipient"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 3 parsed composeMessage: platform=$platform, recipient length=${recipient.length}, messageLen=${message.length}")
                    messagingHub.composeMessage(platform, recipient, message)
                }
                "readRecentMessages" -> {
                    val platform = args["platform"]?.jsonPrimitive?.content
                    val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3
                    messagingHub.readRecentMessages(platform, limit)
                }
                "explainMessage" -> {
                    val messageText = args["messageText"]?.jsonPrimitive?.content ?: args["message"]?.jsonPrimitive?.content
                    messagingHub.explainLastMessage(messageText)
                }
                "clearMessageCache" -> {
                    messagingHub.clearMessageCache()
                }
                "describeScreen", "analyzeScreenContent" -> {
                    phoneControl.describeScreenContent()
                }
                "stopScreenShare" -> {
                    ScreenCaptureManager.getInstance(context).stopProjection()
                    "SUCCESS: Screen sharing stopped."
                }
                "confirmPendingAction" -> {
                    phoneControl.confirmPendingAction()
                }
                "cancelPendingAction" -> {
                    phoneControl.clearPendingAction()
                    "Action cancelled."
                }
                "openCamera" -> {
                    phoneControl.openCamera()
                }
                "openGallery", "showPhotos" -> {
                    phoneControl.openGallery()
                }
                "getCurrentLocation", "whereAmI" -> {
                    phoneControl.getCurrentLocation()
                }
                "openMaps", "navigate" -> {
                    val destination = args["destination"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing destination"
                    phoneControl.openMaps(destination)
                }
                "addCalendarEvent" -> {
                    val title = args["title"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing title"
                    val description = args["description"]?.jsonPrimitive?.content ?: ""
                    phoneControl.addCalendarEvent(title, description)
                }
                "openSettings" -> {
                    val type = args["type"]?.jsonPrimitive?.content ?: "main"
                    phoneControl.openSystemSettings(type)
                }
                "adjustVolume" -> {
                    val direction = args["direction"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing direction (up/down/mute/unmute/max)"
                    phoneControl.adjustVolume(direction)
                }
                "setVolumePercent" -> {
                    val percent = args["percent"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: args["level"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: return@withContext "Error: Missing volume percent (0-100)"
                    phoneControl.setVolumePercent(percent)
                }
                "getSimCardInfo" -> {
                    phoneControl.getSimCardInfo()
                }
                "setBrightness" -> {
                    val level = args["level"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: args["percent"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: return@withContext "Error: Missing brightness level (0-100)"
                    phoneControl.setBrightness(level)
                }
                "sendGmail" -> {
                    val recipientEmail = args["recipientEmail"]?.jsonPrimitive?.content
                        ?: args["recipient"]?.jsonPrimitive?.content
                        ?: args["contactName"]?.jsonPrimitive?.content
                        ?: return@withContext "Error: Missing recipientEmail"
                    val subject = args["subject"]?.jsonPrimitive?.content ?: "Message from Rafel"
                    val body = args["body"]?.jsonPrimitive?.content
                        ?: args["message"]?.jsonPrimitive?.content
                        ?: ""
                    phoneControl.sendGmail(recipientEmail, subject, body)
                }
                "playMedia" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing query"
                    phoneControl.playMedia(query)
                }
                "performAccessibilityAction" -> {
                    val actionType = args["actionType"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing actionType"
                    val targetText = args["targetText"]?.jsonPrimitive?.content
                    phoneControl.performAccessibilityAction(actionType, targetText)
                }
                "clickTextOnScreen" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing text"
                    phoneControl.performAccessibilityAction("click", text)
                }
                "openNotificationPanel" -> {
                    phoneControl.performAccessibilityAction("notifications")
                }
                "openQuickSettings" -> {
                    phoneControl.performAccessibilityAction("quick_settings")
                }
                "searchYouTube" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing query"
                    phoneControl.searchYouTube(query)
                }
                "toggleTorch" -> {
                    val state = args["state"]?.jsonPrimitive?.content ?: "on"
                    try {
                        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val cameraId = cameraManager.cameraIdList[0]
                        cameraManager.setTorchMode(cameraId, state.lowercase() == "on")
                        "Torch turned $state."
                    } catch (e: Exception) {
                        "Torch unavailable: ${e.message}"
                    }
                }
                else -> "Error: Tool $name not found."
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 8: Tool $name execution result returned to Gemini: $result")
            }
            result
        } catch (e: Exception) {
            val err = "Error executing $name: ${e.message}"
            android.util.Log.e("RAFEL_MESSAGE_TRACE", "Stage 8 error: $err", e)
            err
        }
    }

    suspend fun execute(
        name: String,
        args: JsonObject,
        userTranscript: String?
    ): String = execute(
        name,
        args,
        if (userTranscript != null) UserTurnContext("turn_compat", userTranscript, "call_compat") else null
    )
}
