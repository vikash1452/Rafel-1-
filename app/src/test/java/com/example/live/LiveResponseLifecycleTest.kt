package com.example.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tools.ToolExecutionEngine
import com.example.voice.RafelVoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveResponseLifecycleTest {

    private lateinit var context: Context
    private lateinit var sessionManager: LiveSessionManager
    private val interruptedCalled = AtomicBoolean(false)
    private val turnCompleteCalled = AtomicBoolean(false)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        interruptedCalled.set(false)
        turnCompleteCalled.set(false)

        sessionManager = LiveSessionManager(
            context = context,
            toolEngine = ToolExecutionEngine(context),
            onAudioOut = { _, _ -> },
            onTurnComplete = { turnCompleteCalled.set(true) },
            onServerInterrupted = { interruptedCalled.set(true) },
            onStateChange = { }
        )
    }

    @Test
    fun testNoModelResponse_timesOutAndRecoversToListening() = runBlocking {
        sessionManager.responseStartTimeoutMs = 400L
        val initialGen = sessionManager.currentGenerationId.get()

        sessionManager.updateState(RafelVoiceState.WAITING_FOR_AI)
        sessionManager.startWatchdog("turn_timeout_1", initialGen)

        // Wait for watchdog to detect absence of response
        delay(700L)

        // Generation must be invalidated
        assertTrue("Generation must increment on timeout", sessionManager.currentGenerationId.get() > initialGen)
        // Queued audio must be flushed
        assertTrue("Interruption callback must be triggered to flush audio", interruptedCalled.get())
        // State must be recovered to LISTENING, not stuck in WAITING_FOR_AI
        assertEquals("State must recover to LISTENING", RafelVoiceState.LISTENING, sessionManager.rafelState.value)
    }

    @Test
    fun testModelStartsButNeverCompletes_timesOutAndRecovers() = runBlocking {
        sessionManager.responseStartTimeoutMs = 2000L
        sessionManager.interChunkTimeoutMs = 400L
        val initialGen = sessionManager.currentGenerationId.get()

        sessionManager.updateState(RafelVoiceState.WAITING_FOR_AI)
        sessionManager.startWatchdog("turn_timeout_2", initialGen)

        // Simulate model response started, but server hangs mid-stream without turnComplete
        sessionManager.modelTurnInProgress.set(true)
        sessionManager.updateState(RafelVoiceState.AI_SPEAKING)

        // Wait for inter-chunk timeout to fire
        delay(700L)

        // State must not remain stuck on AI_SPEAKING
        assertEquals("State must recover from stalled AI_SPEAKING to LISTENING", RafelVoiceState.LISTENING, sessionManager.rafelState.value)
        assertTrue("Generation must be invalidated to drop any late audio", sessionManager.currentGenerationId.get() > initialGen)
        assertTrue("Stalled audio must be flushed", interruptedCalled.get())
    }

    @Test
    fun testWebSocketClosesMidResponse_transitionsToErrorAndFlushes() = runBlocking {
        val initialGen = sessionManager.currentGenerationId.get()
        sessionManager.modelTurnInProgress.set(true)
        sessionManager.updateState(RafelVoiceState.AI_SPEAKING)
        sessionManager.startWatchdog("turn_timeout_3", initialGen)

        // Simulate WebSocket abrupt error/closure
        sessionManager.handleServerInterruption()
        sessionManager.updateState(RafelVoiceState.ERROR)

        assertEquals("State must transition to ERROR on socket closure", RafelVoiceState.ERROR, sessionManager.rafelState.value)
        assertTrue("Audio flush must be requested", interruptedCalled.get())
        assertTrue("Generation must be bumped to drop late chunks", sessionManager.currentGenerationId.get() > initialGen)
    }

    @Test
    fun testOldTurnTimeout_doesNotResetNewerTurn() = runBlocking {
        val oldGen = 500L
        sessionManager.currentGenerationId.set(oldGen)

        // User starts turn 1
        val turn1Id = "turn_old"
        sessionManager.startWatchdog(turn1Id, oldGen)

        // Before old timeout fires, user speaks again (starts Turn 2)
        val newGen = 501L
        sessionManager.currentGenerationId.set(newGen)
        sessionManager.updateState(RafelVoiceState.USER_SPEAKING)

        // Fire the old timeout handler for oldGen
        sessionManager.handleWatchdogTimeout(turn1Id, oldGen, "Stale timeout")

        // State must remain USER_SPEAKING (must NOT be reset to LISTENING by old turn)
        assertEquals("Active turn state must not be overridden by stale turn timeout", RafelVoiceState.USER_SPEAKING, sessionManager.rafelState.value)
        assertEquals("Active generation ID must remain intact", newGen, sessionManager.currentGenerationId.get())
    }

    @Test
    fun testNormalResponse_completesSuccessfullyWithoutWatchdogTrigger() = runBlocking {
        sessionManager.responseStartTimeoutMs = 600L
        val genId = sessionManager.currentGenerationId.get()

        sessionManager.updateState(RafelVoiceState.WAITING_FOR_AI)
        sessionManager.startWatchdog("turn_normal", genId)

        // Response arrives promptly
        delay(100L)
        sessionManager.updateState(RafelVoiceState.AI_SPEAKING)
        sessionManager.modelTurnInProgress.set(true)

        delay(100L)
        // Turn completes normally
        sessionManager.cancelWatchdog()
        sessionManager.modelTurnInProgress.set(false)
        sessionManager.updateState(RafelVoiceState.LISTENING)

        // Wait past original timeout threshold to ensure watchdog was cancelled
        delay(700L)

        assertEquals("State must remain LISTENING", RafelVoiceState.LISTENING, sessionManager.rafelState.value)
        assertEquals("Generation must remain unchanged on successful completion", genId, sessionManager.currentGenerationId.get())
        assertFalse("Interruption must not be triggered on successful turn", interruptedCalled.get())
    }
}
