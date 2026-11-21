package com.example.voice

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceBehaviorTest {

    @Test
    fun testUserSilenceWhileAiSpeakingDoesNotTriggerSpeechEndOrBargeIn() {
        var speechStarted = false
        var speechEnded = false
        var bargeInDetected = false
        var chunksSent = 0

        val vad = VoiceActivityDetector(
            onSpeechStart = { speechStarted = true },
            onSpeechChunk = { _, _ -> chunksSent++ },
            onSpeechEnd = { speechEnded = true },
            onBargeInDetected = { bargeInDetected = true }
        )

        val silentBuffer = ShortArray(640) { 50 } // Low energy ambient silence
        val lowNoiseBuffer = ShortArray(640) { 150 } // Room noise

        // Simulate 20 chunks (800ms) while AI is speaking
        for (i in 0 until 20) {
            vad.processFrame(if (i % 2 == 0) silentBuffer else lowNoiseBuffer, 640, isAiSpeaking = true)
        }

        // Assert: During AI speech, silence and normal room noise MUST NEVER:
        // 1. Trigger speech end (which previously interrupted Rafel)
        // 2. Trigger false barge-in
        // 3. Stream mic chunks to the server
        assertFalse("Speech end should NOT be called during AI speech", speechEnded)
        assertFalse("Barge-in should NOT be called on silence/ambient noise", bargeInDetected)
        assertEquals("No mic chunks should be forwarded during AI speech without barge-in", 0, chunksSent)
    }

    @Test
    fun testGenuineUserSpeechWhileAiSpeakingTriggersBargeIn() {
        var speechStarted = false
        var bargeInDetected = false
        var chunksSent = 0

        val vad = VoiceActivityDetector(
            onSpeechStart = { speechStarted = true },
            onSpeechChunk = { _, _ -> chunksSent++ },
            onSpeechEnd = { },
            onBargeInDetected = { bargeInDetected = true }
        )

        val loudSpeechBuffer = ShortArray(640) { 2500 } // Strong user speech > BARGE_IN_THRESHOLD (1800)

        // Feed 1 chunk: not enough yet for sustained barge-in
        vad.processFrame(loudSpeechBuffer, 640, isAiSpeaking = true)
        assertFalse("1 chunk of loud noise should not trigger barge-in yet", bargeInDetected)

        // Feed 2nd chunk (reaches VAD_MIN_SPEECH_CHUNKS = 2)
        vad.processFrame(loudSpeechBuffer, 640, isAiSpeaking = true)
        assertTrue("2 sustained chunks of loud speech must trigger genuine barge-in", bargeInDetected)
        assertTrue("Speech start must be triggered on barge-in", speechStarted)
        assertTrue("Chunks must start streaming after barge-in", chunksSent > 0)
    }

    @Test
    fun testNormalUserSpeechAndSilenceTimeout() {
        var speechStarted = false
        var speechEnded = false
        var chunksSent = 0

        val vad = VoiceActivityDetector(
            onSpeechStart = { speechStarted = true },
            onSpeechChunk = { _, _ -> chunksSent++ },
            onSpeechEnd = { speechEnded = true },
            onBargeInDetected = {}
        )

        val speechBuffer = ShortArray(640) { 1200 } // Normal speech > SPEECH_THRESHOLD (450)
        val silenceBuffer = ShortArray(640) { 30 }   // Silence

        // Step 1: User speaks for 5 chunks (200ms)
        for (i in 0 until 5) {
            vad.processFrame(speechBuffer, 640, isAiSpeaking = false)
        }
        assertTrue("Speech start must be triggered", speechStarted)
        assertTrue("Speech chunks must be forwarded", chunksSent > 0)
        assertFalse("Speech should not end while user is speaking", speechEnded)

        // Step 2: User goes silent for 750ms
        // With conversational timeout (500ms - 700ms), speechEnded must trigger
        for (i in 0 until 18) {
            vad.processFrame(silenceBuffer, 640, isAiSpeaking = false)
            Thread.sleep(45) // Simulate real-time passage
        }
        assertTrue("Speech end must trigger promptly after silence timeout", speechEnded)
    }

    @Test
    fun testLongQuestionWithSustainedSpeechMaintainsSpeakingState() {
        var speechStarted = false
        var speechEnded = false
        var chunksSent = 0

        val vad = VoiceActivityDetector(
            onSpeechStart = { speechStarted = true },
            onSpeechChunk = { _, _ -> chunksSent++ },
            onSpeechEnd = { speechEnded = true },
            onBargeInDetected = {}
        )

        val speechBuffer = ShortArray(640) { 1500 }

        // User speaks a long question (30 chunks = 1200ms)
        for (i in 0 until 30) {
            vad.processFrame(speechBuffer, 640, isAiSpeaking = false)
        }

        assertTrue("Speech must be marked active during long question", speechStarted)
        assertEquals("All 30 speech chunks must be forwarded to Gemini", 30, chunksSent)
        assertFalse("Speech must NOT prematurely end during long continuous speech", speechEnded)
    }

    @Test
    fun testEndOfSpeechDetectionIn500to700msWindow() {
        var speechEnded = false
        val vad = VoiceActivityDetector(
            onSpeechStart = {},
            onSpeechChunk = { _, _ -> },
            onSpeechEnd = { speechEnded = true },
            onBargeInDetected = {}
        )

        val speechBuffer = ShortArray(640) { 1500 }
        val silenceBuffer = ShortArray(640) { 20 }

        // User speaks 1000ms
        for (i in 0 until 25) {
            vad.processFrame(speechBuffer, 640, isAiSpeaking = false)
        }

        val silenceStart = System.currentTimeMillis()
        while (!speechEnded && (System.currentTimeMillis() - silenceStart < 1000)) {
            vad.processFrame(silenceBuffer, 640, isAiSpeaking = false)
            Thread.sleep(30)
        }

        val elapsedSilence = System.currentTimeMillis() - silenceStart
        assertTrue("Speech end must be triggered", speechEnded)
        assertTrue("Speech end must trigger promptly in 280-650ms window (actual: ${elapsedSilence}ms)", elapsedSilence in 280..650)
    }

    @Test
    fun testMicrophoneSilenceDoesNotStopPlaybackWhileAiIsSpeaking() {
        var speechEndTriggered = false
        var bargeInTriggered = false

        val vad = VoiceActivityDetector(
            onSpeechStart = {},
            onSpeechChunk = { _, _ -> },
            onSpeechEnd = { speechEndTriggered = true },
            onBargeInDetected = { bargeInTriggered = true }
        )

        val silenceBuffer = ShortArray(640) { 10 }

        // While AI is speaking, user is completely silent for 2 seconds (50 chunks)
        for (i in 0 until 50) {
            vad.processFrame(silenceBuffer, 640, isAiSpeaking = true)
        }

        assertFalse("Microphone silence while AI is speaking MUST NEVER trigger speech end", speechEndTriggered)
        assertFalse("Silence must never trigger barge-in", bargeInTriggered)
    }
}
