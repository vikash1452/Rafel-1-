package com.example.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.example.tools.AssistantIntent
import com.example.tools.AssistantIntentRouter
import com.example.tools.ToolSafetyGate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@RunWith(RobolectricTestRunner::class)
class OnlineVoicePipelineTest {

    private fun turn(id: Long, text: String): UserTurnContext {
        return UserTurnContext(userTurnId = id, transcript = text, isFrozen = true)
    }

    @Test
    fun testAudioCaptureAndModelConfiguration() {
        assertEquals(16000, VoiceConfig.SAMPLE_RATE_IN)
        assertEquals(24000, VoiceConfig.SAMPLE_RATE_OUT)
        assertEquals("models/gemini-3.8-live", VoiceConfig.GEMINI_LIVE_MODEL)
        assertEquals("Aoede", VoiceConfig.GEMINI_VOICE_NAME)
        assertEquals(640, VoiceConfig.CHUNK_SIZE_SAMPLES)
        assertTrue("Silence normal timeout should be in fast conversational range", VoiceConfig.END_SILENCE_NORMAL_MS in 280L..600L)
    }

    @Test
    fun testIntentClassification_ConversationalGreeting() {
        val result = AssistantIntentRouter.classifyIntent("Hello Rafel, kaise ho?")
        assertEquals(AssistantIntent.GREETING, result.intent)
        assertFalse("Greetings must not have explicit action", result.explicitAction)

        val gateDecision = ToolSafetyGate.evaluate("call_1", "searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, turn(1L, "Hello Rafel, kaise ho?"))
        assertFalse("Tool must be blocked on greetings", gateDecision.allowed)
    }

    @Test
    fun testIntentClassification_ExplicitToolCall() {
        val result = AssistantIntentRouter.classifyIntent("Call Rahul")
        assertEquals(AssistantIntent.CALL_CONTACT, result.intent)
        assertTrue("Explicit call must have explicitAction", result.explicitAction)
        assertEquals("Rahul", result.target)

        val gateDecision = ToolSafetyGate.evaluate("call_2", "searchAndCallContact", buildJsonObject { put("contactName", "Rahul") }, turn(2L, "Call Rahul"))
        assertTrue("Explicit call tool must be allowed", gateDecision.allowed)
    }

    @Test
    fun testIntentClassification_ExplicitWhatsApp() {
        val result = AssistantIntentRouter.classifyIntent("Rahul ko WhatsApp message bhejo kal aana")
        assertEquals(AssistantIntent.SEND_WHATSAPP, result.intent)
        assertTrue("Explicit WhatsApp must have explicitAction", result.explicitAction)
        assertEquals("Rahul", result.target)

        val gateDecision = ToolSafetyGate.evaluate("call_3", "sendWhatsAppMessage", buildJsonObject { put("contactName", "Rahul"); put("message", "kal aana") }, turn(3L, "Rahul ko WhatsApp message bhejo kal aana"))
        assertTrue("Explicit WhatsApp tool must be allowed", gateDecision.allowed)
    }

    @Test
    fun testIntentClassification_YouTubeSearchVsOpen() {
        val openResult = AssistantIntentRouter.classifyIntent("YouTube kholo")
        assertEquals(AssistantIntent.OPEN_APP, openResult.intent)
        assertTrue(openResult.explicitAction)

        val searchResult = AssistantIntentRouter.classifyIntent("YouTube par DJ song search karo")
        assertEquals(AssistantIntent.SEARCH_YOUTUBE, searchResult.intent)
        assertTrue(searchResult.explicitAction)
    }
}
