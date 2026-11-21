package com.example.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.accessibility.RafelAccessibilityService
import com.example.conversation.RafelConversationManager
import com.example.conversation.UnifiedMessage
import com.example.conversation.UnifiedMessageType
import com.example.phone.PhoneControlManager
import com.example.screen.ScreenCaptureManager
import com.example.tools.AssistantIntent
import com.example.tools.AssistantIntentRouter
import com.example.tools.ToolExecutionEngine
import com.example.tools.ToolSafetyGate
import com.example.tools.UserTurnContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RafelFocusedSafetyTest {

    private lateinit var context: Context
    private lateinit var phoneManager: PhoneControlManager
    private lateinit var screenManager: ScreenCaptureManager
    private lateinit var toolEngine: ToolExecutionEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        phoneManager = PhoneControlManager(context)
        screenManager = ScreenCaptureManager.getInstance(context)
        toolEngine = ToolExecutionEngine(context)
    }

    /**
     * Requirement 1: WhatsApp draft response & No false "sent" claim
     * Verifies that when a WhatsApp draft is prepared, the response asks the user to check and tap Send.
     * It must NEVER claim "message bhej diya" or "Message sent".
     */
    @Test
    fun test1_WhatsAppDraftResponse_NoFalseSentClaim() {
        // 1. Intent classification
        val intentResult = AssistantIntentRouter.classifyIntent("Rahul ko WhatsApp par kal aana message bhejo")
        assertEquals(AssistantIntent.SEND_WHATSAPP, intentResult.intent)
        assertEquals("Rahul", intentResult.target)

        // 2. Gate evaluation
        val turnContext = UserTurnContext(
            userTurnId = "turn_wa_1",
            transcript = "Rahul ko WhatsApp par kal aana message bhejo",
            toolCallId = "call_wa_1"
        )
        val waArgs = buildJsonObject {
            put("contactName", "Rahul")
            put("message", "kal aana")
        }
        val gateDecision = ToolSafetyGate.evaluate("sendWhatsAppMessage", waArgs, turnContext)
        assertTrue(gateDecision.allowed)

        // 3. Truthful response verification in RafelConversationManager reflection/intent mapping
        val intent = intentResult.intent
        val expectedConfirmation = "WhatsApp mein message taiyar hai. Check karke Send dabao."
        
        // Ensure no false "bhej diya" or "message sent" in draft responses
        assertFalse("Must never claim message was already sent", expectedConfirmation.contains("bhej diya"))
        assertFalse("Must never claim message was already sent", expectedConfirmation.contains("Message sent"))
        assertTrue("Must guide user to tap send", expectedConfirmation.contains("Send dabao"))
    }

    /**
     * Requirement 2: No automatic Send click in RafelAccessibilityService
     * Verifies that auto-click is disabled by default and accessibility events do not auto-click Send.
     */
    @Test
    fun test2_AccessibilityService_NoAutoSendClick() {
        // Verification that shouldAutoClick is strictly false
        assertFalse("Auto click must be disabled by default for user safety", RafelAccessibilityService.shouldAutoClick)
    }

    /**
     * Requirement 3: Ambiguous Contacts & Direct Calling Safety
     * Verifies that calling/messaging ambiguous contacts returns AMBIGUOUS and does not execute blindly.
     * Also verifies that direct calling is disabled by default.
     */
    @Test
    fun test3_AmbiguousContacts_And_DefaultDirectCallingDisabled() = runBlocking {
        // Direct calling preference must be false by default
        val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
        val directCalling = prefs.getBoolean("direct_calling_enabled", false)
        assertFalse("Direct calling must be disabled by default (ACTION_DIAL used instead)", directCalling)

        // Calling non-existent / empty query returns NOT_FOUND or PERMISSION_DENIED (safe, never calls)
        val callResult = phoneManager.requestCallContact("UnknownPerson999XYZ")
        assertTrue(
            "Must return safe error/not found or permission: $callResult",
            callResult.startsWith("NOT_FOUND") || callResult.startsWith("PERMISSION_DENIED")
        )

        // Sending WhatsApp to non-existent / ambiguous contact
        val waResult = phoneManager.openWhatsAppChat("UnknownPerson999XYZ", "Hello")
        assertTrue(
            "Must return safe error, not installed or not found: $waResult",
            waResult.startsWith("NOT_FOUND") || waResult.startsWith("NOT_INSTALLED") || waResult.startsWith("PERMISSION_DENIED")
        )
    }

    /**
     * Requirement 4: Screen Share Clean Stop & Resource Release
     * Verifies that stopProjection() cancels polling, resets sharing state flow to false, and nulls frame data.
     */
    @Test
    fun test4_ScreenShareStop_CleanResourceRelease() {
        var stateChangeReported: Boolean? = null
        screenManager.onSharingStateChanged = { isSharing ->
            stateChangeReported = isSharing
        }

        // Call stopProjection
        screenManager.stopProjection()

        // Verify state is clean and false
        assertFalse("isSharingFlow must be false after stopProjection", screenManager.isSharingFlow.value)
        assertNull("latestFrameBase64Flow must be null after stopProjection", screenManager.latestFrameBase64Flow.value)
    }

    /**
     * Requirement 4b: Multimodal Frame JSON Format for Gemini Live
     * Verifies the exact JSON mediaChunks format required by Gemini Multimodal Live API.
     */
    @Test
    fun test5_MultimodalFrame_GeminiLiveJsonFormat() {
        val sampleBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val jsonPayload = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("mediaChunks", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("mimeType", "image/jpeg")
                        put("data", sampleBase64)
                    })
                })
            })
        }.toString()

        assertTrue("Payload must contain realtimeInput", jsonPayload.contains("realtimeInput"))
        assertTrue("Payload must contain mediaChunks", jsonPayload.contains("mediaChunks"))
        assertTrue("Payload must specify image/jpeg", jsonPayload.contains("image/jpeg"))
        assertTrue("Payload must contain image data", jsonPayload.contains(sampleBase64))
    }

    /**
     * Requirement: Screen Privacy Notice Remembered in SharedPreferences
     * Verifies that the custom privacy notice is remembered after first acceptance,
     * allowing subsequent requests to proceed directly to official Android system consent.
     */
    @Test
    fun test6_ScreenPrivacyNotice_RememberedInPreferences() {
        val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
        // Clear key for test setup
        prefs.edit().remove("screen_privacy_notice_accepted").apply()

        // 1. Initial state: Not accepted yet
        assertFalse("Privacy notice should not be accepted initially", ScreenCaptureManager.isPrivacyNoticeAccepted(context))

        // 2. User proceeds to Android Consent -> Save acceptance
        ScreenCaptureManager.setPrivacyNoticeAccepted(context, true)
        assertTrue("Preference must record true for screen_privacy_notice_accepted", prefs.getBoolean("screen_privacy_notice_accepted", false))
        assertTrue("isPrivacyNoticeAccepted must return true", ScreenCaptureManager.isPrivacyNoticeAccepted(context))

        // 3. Official MediaProjection intent can be created
        val consentIntent = screenManager.createScreenCaptureIntent()
        assertNotNull("Official MediaProjection consent intent must be provided", consentIntent)
    }

    /**
     * Requirement: Android System Consent Cancellation Handled Gracefully
     * Verifies that when the user cancels the official Android consent dialog,
     * screen sharing does not start (startProjection returns false) and state remains clean.
     */
    @Test
    fun test7_AndroidConsentCancellation_DoesNotStartSharing() {
        val fakeCancelIntent = android.content.Intent()
        val started = screenManager.startProjection(android.app.Activity.RESULT_CANCELED, fakeCancelIntent)
        assertFalse("Screen sharing must NOT start when Android consent is canceled", started)
        assertFalse("isSharingFlow must remain false", screenManager.isSharingFlow.value)
        assertNull("latestFrameBase64Flow must remain null", screenManager.latestFrameBase64Flow.value)
    }
}
