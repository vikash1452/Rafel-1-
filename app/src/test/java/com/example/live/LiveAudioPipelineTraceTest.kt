package com.example.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voice.AudioPlaybackManager
import com.example.voice.RafelVoiceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveAudioPipelineTraceTest {

    private lateinit var context: Context
    private lateinit var playbackManager: AudioPlaybackManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCompleteVoiceTurnTraceSequence() = runBlocking {
        val playbackStartedCalled = AtomicBoolean(false)
        val playbackFinishedCalled = AtomicBoolean(false)
        val chunksWritten = AtomicInteger(0)

        playbackManager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = { playbackStartedCalled.set(true) },
            onPlaybackFinished = { playbackFinishedCalled.set(true) },
            onAudioPlayed = { chunksWritten.incrementAndGet() }
        )

        // Simulate complete real pipeline sequence:
        // 1. User turn sent
        val genId = 1001L
        playbackManager.prepareForPlayback()

        // 2. Gemini sends response audio chunks (24kHz 16-bit PCM Mono)
        val chunk1 = ByteArray(1920) { 1 } // 40ms audio
        val chunk2 = ByteArray(1920) { 2 } // 40ms audio
        val chunk3 = ByteArray(1920) { 3 } // 40ms audio

        playbackManager.enqueueChunk(genId, chunk1)
        playbackManager.enqueueChunk(genId, chunk2)
        playbackManager.enqueueChunk(genId, chunk3)

        // Wait brief moment for playback loop to process
        var started = false
        for (i in 0 until 15) {
            if (playbackStartedCalled.get()) {
                started = true
                break
            }
            kotlinx.coroutines.delay(20)
        }

        // 3. Server turn completed
        playbackManager.markTurnComplete(genId)
        kotlinx.coroutines.delay(100)

        // Assert that playback started immediately on chunk 1
        assertTrue("Playback MUST start immediately on first audio chunk", started)
        assertTrue("Audio chunks must be processed by AudioTrack", chunksWritten.get() > 0)

        playbackManager.release()
    }

    @Test
    fun testGeminiLivePlaybackAndBargeInFlush() = runBlocking {
        val playbackStartedCalled = AtomicBoolean(false)
        val playbackFinishedCalled = AtomicBoolean(false)

        playbackManager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = { playbackStartedCalled.set(true) },
            onPlaybackFinished = { playbackFinishedCalled.set(true) }
        )

        val genId = 2002L
        playbackManager.prepareForPlayback()
        val pcmChunk = ByteArray(4800) { 4 } // 100ms 24kHz 16-bit mono PCM
        playbackManager.enqueueChunk(genId, pcmChunk)

        for (i in 0 until 15) {
            if (playbackStartedCalled.get()) break
            kotlinx.coroutines.delay(20)
        }

        assertTrue("Gemini Live AudioTrack playback must start", playbackStartedCalled.get())
        assertTrue("AudioTrack hardware report must reflect initialized state",
            playbackManager.getAudioHardwareReport().contains("STATE_INITIALIZED"))

        // Verify instant barge-in interruption
        playbackManager.interruptAndFlush()
        assertFalse("Playback must stop immediately on barge-in", playbackManager.isSpeaking())
        assertTrue("Playback finished callback must be invoked on barge-in", playbackFinishedCalled.get())

        playbackManager.release()
    }

    @Test
    fun testSessionIdIncrementAndIsolation() {
        val prefs = context.getSharedPreferences("rafel_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_api_key", "test_mock_api_key_12345").commit()

        val toolEngine = com.example.tools.ToolExecutionEngine(context)
        val sessionManager = LiveSessionManager(
            context = context,
            toolEngine = toolEngine,
            onAudioOut = { _, _ -> },
            onTurnComplete = {},
            onStateChange = {},
            onServerInterrupted = {}
        )

        val id1 = sessionManager.currentSessionId.get()
        sessionManager.startSession()
        val id2 = sessionManager.currentSessionId.get()
        assertTrue("Starting session must increment session ID", id2 > id1)

        sessionManager.stopSession()
        assertFalse("Stopped session must not be connected", sessionManager.isConnected())
    }
}
