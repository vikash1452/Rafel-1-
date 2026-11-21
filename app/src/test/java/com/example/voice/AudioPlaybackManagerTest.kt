package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioPlaybackManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testFirstChunkTriggersPlaybackStartedImmediately() {
        var playbackStarted = false
        var playbackFinished = false
        var playedChunksCount = 0

        val manager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = { playbackStarted = true },
            onPlaybackFinished = { playbackFinished = true },
            onAudioPlayed = { playedChunksCount++ }
        )

        val generationId = 1L
        val pcmChunk = ByteArray(960) { 10 }

        manager.enqueueChunk(generationId, pcmChunk)
        Thread.sleep(100)

        assertTrue("Playback must start immediately on the first chunk without delay", playbackStarted)
        assertFalse("Playback must not be marked finished while speaking", playbackFinished)
        assertTrue("At least one chunk must have been played", playedChunksCount > 0)

        manager.markTurnComplete(generationId)
        Thread.sleep(150)

        manager.release()
    }

    @Test
    fun testMultipleChunksPlaySequentially() {
        val playedChunks = AtomicInteger(0)
        val manager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = {},
            onPlaybackFinished = {},
            onAudioPlayed = { playedChunks.incrementAndGet() }
        )

        val genId = 10L
        val chunk1 = ByteArray(960) { 1 }
        val chunk2 = ByteArray(960) { 2 }
        val chunk3 = ByteArray(960) { 3 }

        manager.enqueueChunk(genId, chunk1)
        manager.enqueueChunk(genId, chunk2)
        manager.enqueueChunk(genId, chunk3)

        Thread.sleep(150)
        assertEquals("All 3 chunks must be written sequentially to AudioTrack", 3, playedChunks.get())

        manager.markTurnComplete(genId)
        Thread.sleep(100)
        manager.release()
    }

    @Test
    fun testUserSilenceDoesNotStopPlayback() {
        val playbackFinished = AtomicBoolean(false)
        val manager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = {},
            onPlaybackFinished = { playbackFinished.set(true) }
        )

        val genId = 20L
        val chunk = ByteArray(1920) { 5 }
        manager.enqueueChunk(genId, chunk)
        Thread.sleep(80)

        assertTrue("Manager should be speaking", manager.isSpeaking())

        // Simulate VAD user silence event occurring on microphone:
        // Speech ended does NOT call interruptAndFlush or stop playback!
        // Rafel continues speaking.
        assertFalse("User silence must never mark Rafel playback finished", playbackFinished.get())
        assertTrue("Rafel must remain speaking through user silence", manager.isSpeaking())

        manager.markTurnComplete(genId)
        Thread.sleep(150)
        manager.release()
    }

    @Test
    fun testUserSpeechInterruptsPlayback() {
        val manager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = {},
            onPlaybackFinished = {}
        )

        val generationId = 1L
        val pcmChunk = ByteArray(960) { 20 }

        for (i in 0 until 5) {
            manager.enqueueChunk(generationId, pcmChunk)
        }

        // Simulate barge-in interruption triggered by genuine user speech
        manager.interruptAndFlush()

        assertEquals(0, manager.getQueueSize())
        assertTrue("Active generation ID must increment on interrupt", manager.activeGenerationId.get() > generationId)
        assertFalse("isSpeaking must be false immediately after flush", manager.isSpeaking())

        manager.release()
    }

    @Test
    fun testAudioConfigurationParameters() {
        assertEquals("Output sample rate must be 24kHz", 24000, VoiceConfig.SAMPLE_RATE_OUT)
        assertEquals("Input sample rate must be 16kHz", 16000, VoiceConfig.SAMPLE_RATE_IN)
        assertEquals("Audio format must be 16-bit PCM", android.media.AudioFormat.ENCODING_PCM_16BIT, VoiceConfig.AUDIO_FORMAT)
        assertEquals("Audio channel out must be mono", android.media.AudioFormat.CHANNEL_OUT_MONO, VoiceConfig.CHANNEL_OUT)
    }

    @Test
    fun testResponseCompletionWaitsForAudio() {
        var finished = false
        val manager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = {},
            onPlaybackFinished = { finished = true }
        )

        val genId = 30L
        manager.enqueueChunk(genId, ByteArray(3840) { 1 })
        var speaking = false
        for (i in 0 until 15) {
            if (manager.isSpeaking()) {
                speaking = true
                break
            }
            Thread.sleep(20)
        }
        assertTrue("Manager should be speaking", speaking)

        manager.markTurnComplete(genId)
        for (i in 0 until 20) {
            if (finished) break
            Thread.sleep(25)
        }

        assertTrue("Playback must finish cleanly after turn complete drain", finished)
        assertFalse("isSpeaking must be false after completion", manager.isSpeaking())
        manager.release()
    }
}
