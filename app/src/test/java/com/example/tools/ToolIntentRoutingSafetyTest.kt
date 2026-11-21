package com.example.tools

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class ToolIntentRoutingSafetyTest {

    private lateinit var toolEngine: ToolExecutionEngine

    @Before
    fun setUp() {
        toolEngine = ToolExecutionEngine(ApplicationProvider.getApplicationContext())
    }

    private fun makeTurn(
        turnId: String = "turn_101",
        transcript: String?,
        toolCallId: String = "call_abc",
        consumed: Boolean = false
    ): UserTurnContext {
        val flag = AtomicBoolean(consumed)
        return UserTurnContext(
            userTurnId = turnId,
            transcript = transcript,
            toolCallId = toolCallId,
            consumedFlag = flag
        )
    }

    @Test
    fun test1_HelloRafel_PureGreeting_NoTool() {
        val turn = makeTurn(transcript = "Hello Rafel")
        val intentResult = AssistantIntentRouter.classifyIntent(turn.transcript)
        assertEquals(AssistantIntent.GREETING, intentResult.intent)

        val fakeCallArgs = buildJsonObject { put("contactName", "Rafel") }
        val decision = ToolSafetyGate.evaluate("searchAndCallContact", fakeCallArgs, turn)
        assertFalse("Greeting 'Hello Rafel' must NEVER trigger a call tool", decision.allowed)
        assertEquals("GREETING", decision.userIntent)
    }

    @Test
    fun test2_Hi_HowAreYou_NoTool() {
        val turn1 = makeTurn(transcript = "Hi")
        val decision1 = ToolSafetyGate.evaluate("searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, turn1)
        assertFalse("Greeting 'Hi' must NEVER trigger a tool", decision1.allowed)

        val turn2 = makeTurn(transcript = "How are you?")
        val decision2 = ToolSafetyGate.evaluate("searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, turn2)
        assertFalse("Inquiry 'How are you?' must NEVER trigger a tool", decision2.allowed)
    }

    @Test
    fun test3_RahulIsHere_CasualConversation_NoTool() {
        val turn1 = makeTurn(transcript = "Rahul is here")
        val decision1 = ToolSafetyGate.evaluate("searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, turn1)
        assertFalse("'Rahul is here' must NEVER trigger call tool", decision1.allowed)
        assertEquals("CONVERSATION", decision1.userIntent)

        val turn2 = makeTurn(transcript = "Rahul ghar par hai")
        val decision2 = ToolSafetyGate.evaluate("searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, turn2)
        assertFalse("'Rahul ghar par hai' must NEVER trigger call tool", decision2.allowed)
        assertEquals("CONVERSATION", decision2.userIntent)
    }

    @Test
    fun test4_HelloRahul_GreetingPerson_NoCall() {
        val turn = makeTurn(transcript = "Hello Rahul")
        val intentResult = AssistantIntentRouter.classifyIntent(turn.transcript)
        assertEquals(AssistantIntent.GREETING, intentResult.intent)

        val callArgs = buildJsonObject { put("contactName", "Rahul") }
        val decision = ToolSafetyGate.evaluate("searchAndCallContact", callArgs, turn)
        assertFalse("Greeting 'Hello Rahul' must NEVER trigger call tool", decision.allowed)
        assertEquals("GREETING", decision.userIntent)
    }

    @Test
    fun test5_CallRahul_AllowedCallTool() {
        val turn = makeTurn(transcript = "Call Rahul")
        val intentResult = AssistantIntentRouter.classifyIntent(turn.transcript)
        assertEquals(AssistantIntent.CALL_CONTACT, intentResult.intent)

        val callArgs = buildJsonObject { put("contactName", "Rahul") }
        val decision = ToolSafetyGate.evaluate("searchAndCallContact", callArgs, turn)
        assertTrue("Explicit 'Call Rahul' must be allowed", decision.allowed)
        assertEquals("CALL_CONTACT", decision.userIntent)
        assertEquals("Rahul", decision.target)
    }

    @Test
    fun test6_RahulKoCallKaro_HindiAllowedCallTool() {
        val turn = makeTurn(transcript = "Rahul ko call karo")
        val callArgs = buildJsonObject { put("contactName", "Rahul") }
        val decision = ToolSafetyGate.evaluate("searchAndCallContact", callArgs, turn)
        assertTrue("Hindi 'Rahul ko call karo' must be allowed", decision.allowed)
        assertEquals("CALL_CONTACT", decision.userIntent)
        assertEquals("Rahul", decision.target)
    }

    @Test
    fun test7_SendRahulWhatsApp_HinglishMessage() {
        val turn = makeTurn(transcript = "Rahul ko WhatsApp par kal aana message bhejo")
        val intentResult = AssistantIntentRouter.classifyIntent(turn.transcript)
        assertEquals(AssistantIntent.SEND_WHATSAPP, intentResult.intent)

        val waArgs = buildJsonObject {
            put("contactName", "Rahul")
            put("message", "kal aana")
        }
        val decision = ToolSafetyGate.evaluate("sendWhatsAppMessage", waArgs, turn)
        assertTrue("Explicit WhatsApp request must be allowed", decision.allowed)
        assertEquals("SEND_WHATSAPP", decision.userIntent)
        assertEquals("Rahul", decision.target)
    }

    @Test
    fun test8_OpenYouTube_Vs_SearchYouTube() {
        // 1. Open YouTube -> OPEN_APP
        val turn1 = makeTurn(transcript = "Open YouTube")
        val decision1 = ToolSafetyGate.evaluate("openApp", buildJsonObject { put("appName", "YouTube") }, turn1)
        assertTrue("Open YouTube must be allowed for openApp", decision1.allowed)
        assertEquals("OPEN_APP", decision1.userIntent)

        // 2. YouTube par DJ song search karo -> SEARCH_YOUTUBE
        val turn2 = makeTurn(transcript = "YouTube par DJ song search karo")
        val decision2 = ToolSafetyGate.evaluate("searchYouTube", buildJsonObject { put("query", "DJ song") }, turn2)
        assertTrue("Search YouTube must be allowed for searchYouTube", decision2.allowed)
        assertEquals("SEARCH_YOUTUBE", decision2.userIntent)
        assertEquals("dj song", decision2.target?.lowercase())
    }

    @Test
    fun test9_NullOrBlankTranscript_MustDeny() {
        // Null transcript
        val nullTurn = makeTurn(transcript = null)
        val decisionNull = ToolSafetyGate.evaluate("searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, nullTurn)
        assertFalse("Null transcript must be denied", decisionNull.allowed)
        assertEquals("Tool request rejected because the current user command could not be verified.", decisionNull.reason)

        // Blank transcript
        val blankTurn = makeTurn(transcript = "   ")
        val decisionBlank = ToolSafetyGate.evaluate("openApp", buildJsonObject { put("appName", "YouTube") }, blankTurn)
        assertFalse("Blank transcript must be denied", decisionBlank.allowed)
        assertEquals("Tool request rejected because the current user command could not be verified.", decisionBlank.reason)
    }

    @Test
    fun test10_AlreadyConsumedTurn_MustDeny() {
        val consumedTurn = makeTurn(transcript = "Call Rahul", consumed = true)
        val decision = ToolSafetyGate.evaluate("searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, consumedTurn)
        assertFalse("Already consumed turn must be denied", decision.allowed)
        assertEquals("Tool request rejected because the current user command could not be verified.", decision.reason)
    }

    @Test
    fun test11_UnknownTool_MustDeny() {
        val turn = makeTurn(transcript = "Call Rahul")
        val decision = ToolSafetyGate.evaluate("arbitraryNonExistentTool", buildJsonObject { }, turn)
        assertFalse("Unknown tool must be denied by default", decision.allowed)
        assertTrue(decision.reason.contains("Unknown tool"))
    }

    @Test
    fun test12_WhatsAppTruthfulResult() = runBlocking {
        val turn = makeTurn(transcript = "Send Rahul a WhatsApp message saying test")
        val result = toolEngine.execute(
            name = "sendWhatsAppMessage",
            args = buildJsonObject {
                put("contactName", "9999999999")
                put("message", "test")
            },
            turnContext = turn
        )
        // Must truthfully report real status (not installed or chat opened) and never falsely claim message was delivered
        assertTrue("Result must relate to WhatsApp operation: $result", result.contains("WhatsApp") || result.contains("NOT_INSTALLED") || result.contains("SUCCESS"))
        assertFalse("Must never claim message was sent without delivery confirmation", result.contains("Message sent"))
    }

    @Test
    fun test13_NegativeTest_GreetingRejectsAllTools() = runBlocking {
        val greetingTurn = makeTurn(transcript = "Hello Rafel")
        val toolsToTest = listOf(
            "searchAndCallContact",
            "sendWhatsAppMessage",
            "sendSms",
            "sendGmail",
            "openApp",
            "searchYouTube"
        )
        for (tool in toolsToTest) {
            val decision = ToolSafetyGate.evaluate(tool, buildJsonObject { put("target", "test") }, greetingTurn)
            assertFalse("Greeting turn must reject tool: $tool", decision.allowed)
        }
    }

    @Test
    fun test14_IntentClassification_Questions() {
        val q1 = AssistantIntentRouter.classifyIntent("What is the capital of India?")
        assertEquals(AssistantIntent.QUESTION, q1.intent)
        assertFalse(q1.explicitAction)

        val q2 = AssistantIntentRouter.classifyIntent("Bharat ki rajdhani kya hai")
        assertEquals(AssistantIntent.QUESTION, q2.intent)
        assertFalse(q2.explicitAction)

        val q3 = AssistantIntentRouter.classifyIntent("Aasman neela kyun hota hai")
        assertEquals(AssistantIntent.QUESTION, q3.intent)
        assertFalse(q3.explicitAction)

        val q4 = AssistantIntentRouter.classifyIntent("How does an airplane fly?")
        assertEquals(AssistantIntent.QUESTION, q4.intent)
        assertFalse(q4.explicitAction)
    }

    @Test
    fun test15_IntentClassification_AppActions() {
        val apps = listOf(
            "WhatsApp kholo" to "WhatsApp",
            "Instagram kholo" to "Instagram",
            "YouTube kholo" to "YouTube",
            "Settings kholo" to "Settings",
            "Phone kholo" to "Phone",
            "Insta kholo" to "Instagram"
        )
        for ((utterance, expectedTarget) in apps) {
            val res = AssistantIntentRouter.classifyIntent(utterance)
            assertEquals("Intent for '$utterance' must be OPEN_APP", AssistantIntent.OPEN_APP, res.intent)
            assertTrue("Action must be explicit for '$utterance'", res.explicitAction)
            assertEquals("Target app must match for '$utterance'", expectedTarget, res.target)
        }
    }

    @Test
    fun test16_IntentClassification_StopCommands() {
        val stops = listOf("Rafel chup ho jao", "Stop", "So jao", "Chup raho", "Shant raho", "Rafel stop")
        for (cmd in stops) {
            val res = AssistantIntentRouter.classifyIntent(cmd)
            assertEquals("Intent for '$cmd' must be STOP_LISTENING", AssistantIntent.STOP_LISTENING, res.intent)
            assertTrue("Action must be explicit for '$cmd'", res.explicitAction)
        }
    }

    @Test
    fun test17_IntentClassification_FollowupConversation() {
        val convos = listOf("Aur batao", "Main theek hoon", "Mujhe accha laga aapse baat karke", "Sab theek hai")
        for (conv in convos) {
            val res = AssistantIntentRouter.classifyIntent(conv)
            assertEquals("Intent for '$conv' must be CONVERSATION", AssistantIntent.CONVERSATION, res.intent)
            assertFalse("Conversation must not trigger explicit action", res.explicitAction)
        }
    }

    @Test
    fun test18_OpenApp_SettingsAndPhone_Execution() = runBlocking {
        val turnSettings = makeTurn(transcript = "Settings kholo")
        val resSettings = toolEngine.execute("openApp", buildJsonObject { put("appName", "Settings") }, turnSettings)
        assertTrue("Settings launch result must start with SUCCESS", resSettings.startsWith("SUCCESS:"))
        assertTrue("Settings launch must mention Settings", resSettings.contains("Settings"))

        val turnPhone = makeTurn(transcript = "Phone kholo")
        val resPhone = toolEngine.execute("openApp", buildJsonObject { put("appName", "Phone") }, turnPhone)
        assertTrue("Phone launch result must start with SUCCESS", resPhone.startsWith("SUCCESS:"))
        assertTrue("Phone launch must mention Phone", resPhone.contains("Phone"))
    }
}
