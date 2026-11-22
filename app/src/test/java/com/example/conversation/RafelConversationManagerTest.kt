package com.example.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tools.AssistantIntent
import com.example.tools.AssistantIntentRouter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RafelConversationManagerTest {

    private lateinit var context: Context
    private lateinit var conversationManager: RafelConversationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        conversationManager = RafelConversationManager.getInstance(context)
        conversationManager.clearAllHistory()
    }

    @Test
    fun testUnifiedMessageTypes() {
        val userTextMsg = conversationManager.addUserTextMessage("Hello Rafel via chat")
        assertEquals(UnifiedMessageType.USER_TEXT, userTextMsg.type)
        assertTrue(userTextMsg.isUser)
        assertFalse(userTextMsg.isVoice)
        assertEquals("Text", userTextMsg.displaySourceTag)

        val rafelTextMsg = conversationManager.addRafelTextMessage("Haan bolo, kya chal raha hai?")
        assertEquals(UnifiedMessageType.RAFEL_TEXT, rafelTextMsg.type)
        assertFalse(rafelTextMsg.isUser)
        assertFalse(rafelTextMsg.isVoice)
        assertEquals("Text", rafelTextMsg.displaySourceTag)

        conversationManager.addUserVoiceMessage("Rafel suno")
        val messages = conversationManager.messages.value
        val voiceUserMsg = messages.last { it.text == "Rafel suno" }
        assertEquals(UnifiedMessageType.USER_VOICE, voiceUserMsg.type)
        assertTrue(voiceUserMsg.isUser)
        assertTrue(voiceUserMsg.isVoice)
        assertEquals("Voice", voiceUserMsg.displaySourceTag)

        conversationManager.addRafelVoiceMessage("Haan sun rahi hoon.")
        val updatedMessages = conversationManager.messages.value
        val voiceRafelMsg = updatedMessages.last { it.text == "Haan sun rahi hoon." }
        assertEquals(UnifiedMessageType.RAFEL_VOICE, voiceRafelMsg.type)
        assertFalse(voiceRafelMsg.isUser)
        assertTrue(voiceRafelMsg.isVoice)
        assertEquals("Voice", voiceRafelMsg.displaySourceTag)
    }

    @Test
    fun testPromptContextFormatting() {
        conversationManager.addUserTextMessage("Kal college jana hai.")
        conversationManager.addRafelTextMessage("Achha, kal college jana hai.")
        conversationManager.addUserVoiceMessage("Kitne baje nikalna chahiye?")

        val promptContext = conversationManager.formatPromptContext(limit = 5)
        assertTrue(promptContext.contains("Kal college jana hai."))
        assertTrue(promptContext.contains("Achha, kal college jana hai."))
        assertTrue(promptContext.contains("Kitne baje nikalna chahiye?"))
    }

    @Test
    fun testChatToolIntentClassification() {
        val callIntent = AssistantIntentRouter.classifyIntent("Call Rahul")
        assertEquals(AssistantIntent.CALL_CONTACT, callIntent.intent)
        assertEquals("Rahul", callIntent.target)

        val appIntent = AssistantIntentRouter.classifyIntent("Open WhatsApp")
        assertEquals(AssistantIntent.OPEN_APP, appIntent.intent)
        assertEquals("WhatsApp", appIntent.target)

        val convIntent = AssistantIntentRouter.classifyIntent("Kal mujhe college jana hai")
        assertEquals(AssistantIntent.CONVERSATION, convIntent.intent)
    }

    @Test
    fun testRafelPersonaContinuousConversationGuidelines() {
        val prompt = com.example.ai.RafelPersona.SYSTEM_PROMPT

        // Verify continuous conversation and anti-Q&A directives
        assertTrue(prompt.contains("NATURAL CONTINUOUS CONVERSATION & DISCOURSE DEPTH"))
        assertTrue(prompt.contains("robotic Q&A machine"))
        assertTrue(prompt.contains("Aaj college gaya tha"))
        assertTrue(prompt.contains("Mera exam next week hai"))

        // Verify natural continuation techniques
        assertTrue(prompt.contains("RELEVANT FOLLOW-UP QUESTION"))
        assertTrue(prompt.contains("NATURAL REACTION"))
        assertTrue(prompt.contains("SHORT OBSERVATION"))
        assertTrue(prompt.contains("PLAYFUL RESPONSE"))
        assertTrue(prompt.contains("NATURAL FINISH"))

        // Verify anti-repetition rule
        assertTrue(prompt.contains("NEVER REPEAT A PATTERN"))
        assertTrue(prompt.contains("Aur kya?"))

        // Verify ban on robotic clichés
        assertTrue(prompt.contains("Certainly!"))
        assertTrue(prompt.contains("How may I assist you"))

        // Verify feminine grammar rules
        assertTrue(prompt.contains("Main samajh gayi"))
        assertTrue(prompt.contains("Main kar rahi hoon"))
    }
}
