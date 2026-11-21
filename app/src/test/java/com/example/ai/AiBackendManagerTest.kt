package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiBackendManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun testDefaultBackendIsGeminiLive() {
        val manager = AiBackendManager(
            context = context,
            geminiKeyProvider = { "valid-gemini-key" }
        )

        assertEquals(AiBackend.GEMINI_LIVE, manager.activeBackend.value)
        assertTrue(manager.isGeminiActive())
    }

    @Test
    fun testConnectionStatusUpdates() {
        val manager = AiBackendManager(
            context = context,
            geminiKeyProvider = { "gemini-key" }
        )

        manager.setGeminiConnected(true)
        assertEquals("GEMINI_CONNECTED", manager.connectionStatus.value)

        manager.setGeminiConnected(false)
        assertEquals("GEMINI_DISCONNECTED", manager.connectionStatus.value)
    }
}

