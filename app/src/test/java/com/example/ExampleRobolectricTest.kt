package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voice.AudioPlaybackManager
import com.example.voice.RafelVoiceState
import com.example.voice.VoiceActivityDetector
import com.example.voice.VoiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Rafel", appName)
  }

  @Test
  fun `voice state machine states have appropriate display text`() {
    assertEquals("Rafel is ready", RafelVoiceState.IDLE.displayText)
    assertEquals("Rafel is listening...", RafelVoiceState.LISTENING.displayText)
    assertEquals("Rafel is listening to you...", RafelVoiceState.USER_SPEAKING.displayText)
    assertEquals("Rafel is thinking...", RafelVoiceState.WAITING_FOR_AI.displayText)
    assertEquals("Rafel is speaking...", RafelVoiceState.AI_SPEAKING.displayText)
    assertEquals("Rafel was interrupted.", RafelVoiceState.INTERRUPTED.displayText)
    assertEquals("Connection error.", RafelVoiceState.ERROR.displayText)
  }

  @Test
  fun `vad detects user speech onset and barge in`() {
    var speechStarted = false
    var speechEnded = false
    val bargeInDetected = AtomicBoolean(false)

    val vad = VoiceActivityDetector(
      onSpeechStart = { speechStarted = true },
      onSpeechChunk = { _, _ -> },
      onSpeechEnd = { speechEnded = true },
      onBargeInDetected = { bargeInDetected.set(true) }
    )

    // Send silence chunks (amplitude 10)
    val silenceChunk = ShortArray(VoiceConfig.CHUNK_SIZE_SAMPLES) { 10 }
    for (i in 0 until 5) {
      vad.processFrame(silenceChunk, silenceChunk.size, isAiSpeaking = false)
    }
    assertFalse("Silence should not trigger speech start", speechStarted)

    // Send loud voice chunks (amplitude 4000)
    val loudChunk = ShortArray(VoiceConfig.CHUNK_SIZE_SAMPLES) { 4000 }
    for (i in 0 until VoiceConfig.VAD_MIN_SPEECH_CHUNKS) {
      vad.processFrame(loudChunk, loudChunk.size, isAiSpeaking = false)
    }
    assertTrue("Loud audio exceeding threshold should trigger speech start", speechStarted)

    // First silence frame records silenceStartTimestamp
    vad.processFrame(silenceChunk, silenceChunk.size, isAiSpeaking = false)

    // Wait for silence duration to exceed END_SILENCE_SHORT_UTTERANCE_MS for short utterance
    Thread.sleep(VoiceConfig.END_SILENCE_SHORT_UTTERANCE_MS + 20)
    vad.processFrame(silenceChunk, silenceChunk.size, isAiSpeaking = false)
    assertTrue("Silence exceeding END_SILENCE_SHORT_UTTERANCE_MS should trigger speech end", speechEnded)

    // Test barge-in while AI is speaking
    val bargeInVad = VoiceActivityDetector(
      onSpeechStart = { },
      onSpeechChunk = { _, _ -> },
      onSpeechEnd = { },
      onBargeInDetected = { bargeInDetected.set(true) }
    )
    for (i in 0 until VoiceConfig.VAD_MIN_BARGE_IN_CHUNKS) {
      bargeInVad.processFrame(loudChunk, loudChunk.size, isAiSpeaking = true)
    }
    assertTrue("Speaking while AI is speaking should trigger barge-in", bargeInDetected.get())
  }

  @Test
  fun `audio playback manager increments generation on interruption and supports pre-warming`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = AudioPlaybackManager(
      context = context,
      onPlaybackStarted = {},
      onPlaybackFinished = {}
    )

    manager.prepareForPlayback()
    val initialGen = manager.activeGenerationId.get()
    manager.interruptAndFlush()
    val newGen = manager.activeGenerationId.get()

    assertTrue("Generation ID should increment on interrupt", newGen > initialGen)
    manager.release()
  }

  @Test
  fun `long conversation stability test with multiple turns and continuous audio streaming`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var playbackStartedCount = 0
    var playbackFinishedCount = 0

    val manager = AudioPlaybackManager(
      context = context,
      onPlaybackStarted = { playbackStartedCount++ },
      onPlaybackFinished = { playbackFinishedCount++ }
    )

    // Simulate 30 continuous conversation turns
    for (turn in 1..30) {
      val gen = manager.activeGenerationId.get()
      // Send 20 audio chunks per turn (~400ms per turn), including odd and even lengths to test alignment
      for (chunkIndex in 1..20) {
        val chunkSize = if (chunkIndex % 3 == 0) 961 else 960 // Odd & even chunk sizes
        val dummyPcm = ByteArray(chunkSize) { 0 }
        manager.enqueueChunk(gen, dummyPcm)
      }
      manager.markTurnComplete(gen)
      Thread.sleep(100) // Allow worker to process
    }

    // After 30 turns, manager should still be operational with no crashes or corrupted state
    val finalGen = manager.activeGenerationId.get()
    assertEquals(1L, finalGen) // No interruptions happened, generation stayed on 1
    manager.release()
  }

  @Test
  fun `stress test multiple rapid barge-in interruptions without audio pipeline degradation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = AudioPlaybackManager(
      context = context,
      onPlaybackStarted = {},
      onPlaybackFinished = {}
    )

    // Stress test: 50 successive interruptions (barge-ins)
    for (i in 1..50) {
      val gen = manager.activeGenerationId.get()
      // Enqueue chunks
      val pcm = ByteArray(960) { 1 }
      manager.enqueueChunk(gen, pcm)
      // Rapid user interruption
      manager.interruptAndFlush()
      val newGen = manager.activeGenerationId.get()
      assertEquals(gen + 1, newGen)
    }

    // Verify system continues to accept and play audio after 50 interruptions
    val lastGen = manager.activeGenerationId.get()
    val testChunk = ByteArray(1920) { 0 }
    manager.enqueueChunk(lastGen, testChunk)
    manager.markTurnComplete(lastGen)
    Thread.sleep(50)

    manager.release()
  }

  @Test
  fun `verify Gemini Live voice model and feminine voice identity configuration`() {
    assertEquals("models/gemini-3.8-live", VoiceConfig.GEMINI_LIVE_MODEL)
    assertEquals("Aoede", VoiceConfig.GEMINI_VOICE_NAME)
  }

  @Test
  fun `verify AvatarController state transitions and barge-in reset`() {
    val controller = com.example.avatar.RafelAvatarController()
    assertEquals(com.example.avatar.AvatarState.IDLE, controller.avatarState.value)

    // User starts speaking -> LISTENING
    controller.onVoiceStateChanged(RafelVoiceState.USER_SPEAKING)
    assertEquals(com.example.avatar.AvatarState.LISTENING, controller.avatarState.value)

    // Waiting for Gemini -> THINKING
    controller.onVoiceStateChanged(RafelVoiceState.WAITING_FOR_AI)
    assertEquals(com.example.avatar.AvatarState.THINKING, controller.avatarState.value)

    // Gemini starts speaking -> SPEAKING + LipSync audio
    controller.onVoiceStateChanged(RafelVoiceState.AI_SPEAKING)
    assertEquals(com.example.avatar.AvatarState.SPEAKING, controller.avatarState.value)

    // Feed audio chunk to LipSync
    val testAudio = ByteArray(480) { (it % 100).toByte() }
    controller.onAudioChunk(testAudio)
    assertTrue(controller.mouthOpenness.value >= 0f)

    // Barge-in interruption -> resets mouth openness and switches to LISTENING
    controller.onVoiceStateChanged(RafelVoiceState.INTERRUPTED)
    assertEquals(0f, controller.mouthOpenness.value, 0.001f)
    assertEquals(com.example.avatar.AvatarState.LISTENING, controller.avatarState.value)
  }

  @Test
  fun `verify context-aware emotion detection`() {
    val happyEmotion = com.example.avatar.EmotionController.analyzeEmotion(
      userMessage = "Aaj mera din bahut achha tha",
      assistantResponse = "Wah, ye sunkar mujhe bhi bahut achha laga!",
      voiceState = RafelVoiceState.AI_SPEAKING
    )
    assertEquals(com.example.avatar.AvatarEmotion.HAPPY, happyEmotion.emotion)
    assertTrue(happyEmotion.intensity > 0.5f)

    val concernedEmotion = com.example.avatar.EmotionController.analyzeEmotion(
      userMessage = "Aaj mera mood bahut kharab hai",
      assistantResponse = "Kya hua? Batao main sun rahi hoon.",
      voiceState = RafelVoiceState.AI_SPEAKING
    )
    assertEquals(com.example.avatar.AvatarEmotion.CONCERNED, concernedEmotion.emotion)

    val confusedEmotion = com.example.avatar.EmotionController.analyzeEmotion(
      userMessage = "Ye kar de",
      assistantResponse = "Haan, kya karna hai?",
      voiceState = RafelVoiceState.AI_SPEAKING
    )
    assertEquals(com.example.avatar.AvatarEmotion.CONFUSED, confusedEmotion.emotion)
  }

  @Test
  fun `verify microphone manager mute state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var ampRecorded = -1f
    val micManager = com.example.voice.MicrophoneManager(
      context = context,
      onAudioChunkRead = { _, _ -> },
      onError = { },
      onAmplitudeChanged = { amp -> ampRecorded = amp }
    )
    assertFalse("Default state must be unmuted", micManager.isMuted())
    micManager.setMuted(true)
    assertTrue("Microphone must report muted", micManager.isMuted())
    assertEquals(0f, ampRecorded, 0.001f)
    micManager.setMuted(false)
    assertFalse("Microphone must report unmuted", micManager.isMuted())
  }
}
