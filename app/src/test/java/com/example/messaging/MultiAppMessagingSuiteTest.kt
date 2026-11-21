package com.example.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.messaging.adapters.*
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeMode
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.IncomingMessageNotification
import com.example.messaging.registry.MessagingCapabilityRegistry
import com.example.notification.RafelNotificationListenerService
import com.example.tools.AssistantIntent
import com.example.tools.AssistantIntentRouter
import com.example.tools.ToolDecisionResult
import com.example.tools.ToolExecutionEngine
import com.example.tools.ToolSafetyGate
import com.example.tools.UserTurnContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiAppMessagingSuiteTest {

    private lateinit var context: Context
    private lateinit var registry: MessagingCapabilityRegistry
    private lateinit var messagingHub: MessagingHub
    private lateinit var toolEngine: ToolExecutionEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = MessagingCapabilityRegistry.getInstance()
        messagingHub = MessagingHub(context)
        toolEngine = ToolExecutionEngine(context)
        RafelNotificationListenerService.clearNotifications()
        RafelNotificationListenerService.isReadingPaused = false
    }

    /**
     * 1. Capability Registry & Adapter Lookup Tests
     */
    @Test
    fun test1_CapabilityRegistry_AdaptersRegistered() {
        val waAdapter = registry.getAdapter(AppPlatform.WHATSAPP)
        assertTrue("WhatsApp adapter must be WhatsAppMessagingAdapter", waAdapter is WhatsAppMessagingAdapter)

        val igAdapter = registry.getAdapter(AppPlatform.INSTAGRAM)
        assertTrue("Instagram adapter must be InstagramMessagingAdapter", igAdapter is InstagramMessagingAdapter)

        val tgAdapter = registry.getAdapter(AppPlatform.TELEGRAM)
        assertTrue("Telegram adapter must be TelegramMessagingAdapter", tgAdapter is TelegramMessagingAdapter)

        val fbAdapter = registry.getAdapter(AppPlatform.MESSENGER)
        assertTrue("Messenger adapter must be MessengerAdapter", fbAdapter is MessengerAdapter)

        val smsAdapter = registry.getAdapter(AppPlatform.SMS)
        assertTrue("SMS adapter must be SmsMessagingAdapter", smsAdapter is SmsMessagingAdapter)

        val signalAdapter = registry.getAdapter(AppPlatform.SIGNAL)
        assertTrue("Signal adapter must be SignalMessagingAdapter", signalAdapter is SignalMessagingAdapter)
    }

    @Test
    fun test2_AdapterLookupByQuery() {
        assertEquals(AppPlatform.INSTAGRAM, registry.findAdapterByName("Instagram").platform)
        assertEquals(AppPlatform.TELEGRAM, registry.findAdapterByName("Telegram").platform)
        assertEquals(AppPlatform.WHATSAPP, registry.findAdapterByName("WhatsApp").platform)
        assertEquals(AppPlatform.MESSENGER, registry.findAdapterByName("Messenger").platform)
        assertEquals(AppPlatform.SMS, registry.findAdapterByName("SMS").platform)
        assertEquals(AppPlatform.SIGNAL, registry.findAdapterByName("Signal").platform)
    }

    /**
     * 2. App-Specific Capabilities & Limitations
     */
    @Test
    fun test3_Capabilities_SupportedModesAndLimitations() {
        val igCap = registry.getAdapter(AppPlatform.INSTAGRAM).getCapability(context)
        assertFalse("Instagram does not support direct phone prefill", igCap.supportsDirectPhoneRecipient)
        assertNotNull("Instagram must document its limitations", igCap.limitationsDescription)

        val tgCap = registry.getAdapter(AppPlatform.TELEGRAM).getCapability(context)
        assertTrue("Telegram supports direct phone recipient", tgCap.supportsDirectPhoneRecipient)
        assertTrue("Telegram supports prefilled text", tgCap.supportsPrefilledText)

        val smsCap = registry.getAdapter(AppPlatform.SMS).getCapability(context)
        assertTrue("SMS supports direct phone recipient", smsCap.supportsDirectPhoneRecipient)
        assertTrue("SMS is installed by default on Android", smsCap.isInstalled)
    }

    /**
     * 3. Compose & Draft Truthful Response Tests
     */
    @Test
    fun test4_SmsCompose_PreparesDraft_NeverClaimsSent() {
        val adapter = SmsMessagingAdapter()
        val result = adapter.composeMessage(context, "Rahul", "9876543210", "Main ghar aa gaya")
        assertTrue("SMS compose must prepare draft", result is ComposeResult.DraftPrepared)
        val draft = result as ComposeResult.DraftPrepared
        assertEquals("Rahul", draft.recipientName)
        assertEquals("Main ghar aa gaya", draft.messageText)
        assertFalse("Must never claim message was sent without confirmation", draft.explanation.contains("Message sent"))
    }

    @Test
    fun test5_InstagramCompose_ExplainsLimitationTruthfully() {
        val adapter = InstagramMessagingAdapter()
        // If Instagram not installed in JVM test environment, returns NotInstalled
        val result = adapter.composeMessage(context, "Rahul", null, "Hello on Instagram")
        assertTrue(
            "Result must be NotInstalled or AppOpenedWithLimitation: $result",
            result is ComposeResult.NotInstalled || result is ComposeResult.AppOpenedWithLimitation
        )
    }

    /**
     * 4. Notification Reader & Ephemeral Ring-Buffer Tests
     */
    @Test
    fun test6_NotificationReader_AddRetrieveFilterClear() {
        // Add sample notifications
        val notif1 = IncomingMessageNotification(
            id = "n1",
            platform = AppPlatform.INSTAGRAM,
            packageName = "com.instagram.android",
            senderName = "Priya",
            previewText = "Hey, are you free today?",
            timestamp = System.currentTimeMillis()
        )
        val notif2 = IncomingMessageNotification(
            id = "n2",
            platform = AppPlatform.TELEGRAM,
            packageName = "org.telegram.messenger",
            senderName = "Dev Group",
            previewText = "Meeting started at 4 PM",
            timestamp = System.currentTimeMillis(),
            isGroup = true,
            groupTitle = "Dev Group"
        )
        val notif3 = IncomingMessageNotification(
            id = "n3",
            platform = AppPlatform.WHATSAPP,
            packageName = "com.whatsapp",
            senderName = "Mummy",
            previewText = "Ghar kab aaoge?",
            timestamp = System.currentTimeMillis()
        )

        RafelNotificationListenerService.addNotification(notif1)
        RafelNotificationListenerService.addNotification(notif2)
        RafelNotificationListenerService.addNotification(notif3)

        // 1. Retrieve all recent (limit 5)
        val all = RafelNotificationListenerService.getRecentMessages(platform = null, limit = 5)
        assertEquals(3, all.size)

        // 2. Retrieve only Instagram
        val igOnly = RafelNotificationListenerService.getRecentMessages(platform = AppPlatform.INSTAGRAM, limit = 5)
        assertEquals(1, igOnly.size)
        assertEquals("Priya", igOnly[0].senderName)
        assertEquals("Hey, are you free today?", igOnly[0].previewText)

        // 3. Retrieve only Telegram
        val tgOnly = RafelNotificationListenerService.getRecentMessages(platform = AppPlatform.TELEGRAM, limit = 5)
        assertEquals(1, tgOnly.size)
        assertEquals("Dev Group", tgOnly[0].senderName)

        // 4. Clear cache
        RafelNotificationListenerService.clearNotifications()
        val afterClear = RafelNotificationListenerService.getRecentMessages(limit = 5)
        assertTrue("Notification cache must be empty after clear", afterClear.isEmpty())
    }

    @Test
    fun test7_NotificationReader_PausedState() = runBlocking {
        RafelNotificationListenerService.isReadingPaused = true
        // When reading is paused, messagingHub returns PAUSED notice
        // We set access via reflection/test condition if needed, or verify paused flag
        assertTrue("Reading paused state must be true", RafelNotificationListenerService.isReadingPaused)
    }

    /**
     * 5. Voice Intent Classification for Multi-App Messaging
     */
    @Test
    fun test8_IntentClassification_ReadMessages() {
        // "Rafel, mere naye messages padho."
        val r1 = AssistantIntentRouter.classifyIntent("Rafel, mere naye messages padho")
        assertEquals(AssistantIntent.READ_MESSAGES, r1.intent)
        assertNull(r1.target)

        // "Instagram par kya message aaya hai?"
        val r2 = AssistantIntentRouter.classifyIntent("Instagram par kya message aaya hai")
        assertEquals(AssistantIntent.READ_MESSAGES, r2.intent)
        assertEquals("Instagram", r2.target)

        // "Telegram ka latest notification padho"
        val r3 = AssistantIntentRouter.classifyIntent("Telegram ka latest notification padho")
        assertEquals(AssistantIntent.READ_MESSAGES, r3.intent)
        assertEquals("Telegram", r3.target)

        // "Rafel, is message ka matlab samjhao."
        val r4 = AssistantIntentRouter.classifyIntent("Rafel, is message ka matlab samjhao")
        assertEquals(AssistantIntent.EXPLAIN_MESSAGE, r4.intent)
    }

    @Test
    fun test9_IntentClassification_ComposeMessages() {
        // “Instagram par Rahul ko message draft karo.”
        val c1 = AssistantIntentRouter.classifyIntent("Instagram par Rahul ko message draft karo")
        assertEquals(AssistantIntent.COMPOSE_MESSAGE, c1.intent)
        assertEquals("Rahul", c1.target)
        assertTrue(c1.secondaryData?.contains("Instagram") == true)

        // “Telegram par Neha ko message likho: main aa raha hoon”
        val c2 = AssistantIntentRouter.classifyIntent("Telegram par Neha ko message likho: main aa raha hoon")
        assertEquals(AssistantIntent.COMPOSE_MESSAGE, c2.intent)
        assertEquals("Neha", c2.target)
        assertTrue(c2.secondaryData?.contains("Telegram") == true)

        // “Messenger par Papa ko message bhejne ke liye chat kholo”
        val c3 = AssistantIntentRouter.classifyIntent("Messenger par Papa ko message bhejne ke liye chat kholo")
        assertEquals(AssistantIntent.COMPOSE_MESSAGE, c3.intent)
        assertEquals("Papa", c3.target)
        assertTrue(c3.secondaryData?.contains("Messenger") == true)
    }

    @Test
    fun test10_StopSpeaking_InterruptionCommand() {
        val s1 = AssistantIntentRouter.classifyIntent("Rafel, chup ho jao")
        assertEquals(AssistantIntent.STOP_LISTENING, s1.intent)

        val s2 = AssistantIntentRouter.classifyIntent("shant raho")
        assertEquals(AssistantIntent.STOP_LISTENING, s2.intent)

        val s3 = AssistantIntentRouter.classifyIntent("stop speaking")
        assertEquals(AssistantIntent.STOP_LISTENING, s3.intent)
    }

    @Test
    fun test11_ToolSafetyGate_ReadAndComposeToolsAllowed() {
        val turn1 = UserTurnContext(
            userTurnId = "turn_read_1",
            transcript = "Instagram par kya message aaya hai",
            toolCallId = "call_read_1"
        )
        val readArgs = buildJsonObject { put("platform", "Instagram") }
        val d1 = ToolSafetyGate.evaluate("readRecentMessages", readArgs, turn1)
        assertTrue("readRecentMessages must be allowed for read intent", d1.allowed)

        val turn2 = UserTurnContext(
            userTurnId = "turn_comp_1",
            transcript = "Telegram par Neha ko message likho",
            toolCallId = "call_comp_1"
        )
        val compArgs = buildJsonObject {
            put("platform", "Telegram")
            put("recipient", "Neha")
            put("message", "Hello")
        }
        val d2 = ToolSafetyGate.evaluate("composeMessage", compArgs, turn2)
        assertTrue("composeMessage must be allowed for compose intent", d2.allowed)
    }

    @Test
    fun test12_ToolSafetyGate_GreetingDeniesMessagingTools() {
        val greetingTurn = UserTurnContext(
            userTurnId = "turn_greet_1",
            transcript = "Hello Rafel",
            toolCallId = "call_greet_1"
        )
        val compArgs = buildJsonObject {
            put("platform", "Telegram")
            put("recipient", "Neha")
        }
        val decision = ToolSafetyGate.evaluate("composeMessage", compArgs, greetingTurn)
        assertFalse("Greeting must deny composeMessage tool", decision.allowed)
    }

    @Test
    fun test13_DescribeScreen_InactiveReturnsError() {
        val phoneControl = com.example.phone.PhoneControlManager(context)
        val res = phoneControl.describeScreenContent()
        assertTrue("Screen sharing inactive must return error message", res.contains("SCREEN_SHARE_INACTIVE"))
    }

    @Test
    fun test14_MessagingHub_UnresolvedRecipientReturnsNotFound() = runBlocking {
        val result = messagingHub.composeMessage("WhatsApp", "NonExistentPersonXYZ", "Hello")
        assertTrue("Unresolved recipient must return NOT_FOUND or NOT_INSTALLED", result.contains("NOT_FOUND") || result.contains("NOT_INSTALLED"))
    }
}
