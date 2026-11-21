package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.conversation.RafelConversationManager
import com.example.memory.MemoryManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProactiveConversationManagerTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val triggeredPrompt = AtomicReference<String?>(null)
    private val triggeredCount = AtomicInteger(0)
    private val simulatedClockMs = AtomicLong(1_000_000L)
    private val simulatedHourOfDay = AtomicInteger(14) // 2:00 PM (outside quiet hours)
    private var simulatedDndActive: Boolean = false
    private lateinit var proactiveManager: ProactiveConversationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ProactiveConversationManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        MemoryManager.getInstance(context).clearAllMemories()
        RafelConversationManager.getInstance(context).clearAllHistory()

        triggeredPrompt.set(null)
        triggeredCount.set(0)
        simulatedClockMs.set(1_000_000L)
        simulatedHourOfDay.set(14)
        simulatedDndActive = false

        proactiveManager = ProactiveConversationManager(
            context = context,
            scope = testScope,
            currentTimeProvider = { 1_000_000L + testScope.testScheduler.currentTime },
            currentHourProvider = { simulatedHourOfDay.get() },
            doNotDisturbProvider = { simulatedDndActive },
            onTriggerProactiveTurn = { prompt ->
                triggeredPrompt.set(prompt)
                triggeredCount.incrementAndGet()
            }
        )
        proactiveManager.isEnabled = true
        proactiveManager.firstSilenceDelayMs = ProactiveConversationManager.DEFAULT_FIRST_SILENCE_DELAY_MS // 60s
        proactiveManager.frequency = ProactiveFrequency.BALANCED
        proactiveManager.quietHoursEnabled = false
        proactiveManager.onlyInActiveVoiceSession = true
        proactiveManager.clearSessionHistoryForTesting()
        proactiveManager.onVoiceSessionStarted()
    }

    /**
     * Test 1 & 2:
     * - User stops speaking for 60 seconds.
     * - Normal thinking pauses (e.g. 4s, 15s, 30s) do NOT trigger interruption.
     * - After 60 seconds of silence, RAFEL initiates one natural conversation.
     */
    @Test
    fun test1And2_userStopsSpeakingFor60Seconds_initiatesOneNaturalConversation() = testScope.runTest {
        proactiveManager.onUserSpeechFinished(
            isAiSpeaking = false,
            isSessionConnected = true,
            isMicMuted = false
        )

        assertTrue("Proactive timer must be scheduled after user finishes speaking", proactiveManager.isScheduled())

        // Normal thinking pause (5 seconds) -> must NOT interrupt
        advanceTimeBy(5_000L)
        simulatedClockMs.addAndGet(5_000L)
        assertNull("RAFEL must not interrupt a 5-second thinking pause", triggeredPrompt.get())

        // 30 seconds -> still waiting for default 60s threshold
        advanceTimeBy(25_000L)
        simulatedClockMs.addAndGet(25_000L)
        assertNull("RAFEL must not trigger before 60s default delay", triggeredPrompt.get())

        // Reach 60 seconds total
        advanceTimeBy(30_500L)
        simulatedClockMs.addAndGet(30_500L)

        assertEquals("RAFEL must initiate exactly one proactive turn at 60s", 1, triggeredCount.get())
        val prompt = triggeredPrompt.get()
        assertNotNull("Proactive prompt must be non-null", prompt)
        val openingLine = proactiveManager.lastGeneratedOpeningLine
        assertNotNull("Opening line must be generated", openingLine)
        assertTrue("Opening line must be natural and non-empty", openingLine!!.isNotBlank())
        assertTrue("Prompt must include the natural opening line", prompt!!.contains(openingLine))
    }

    /**
     * Test 3 & 4:
     * - User starts speaking before the 60s timer expires.
     * - Proactive response is cancelled immediately and does not fire.
     */
    @Test
    fun test3And4_userStartsSpeakingBeforeTimerExpires_cancelledImmediately() = testScope.runTest {
        proactiveManager.onAssistantSpeechFinished(
            isAiSpeaking = false,
            isSessionConnected = true,
            isMicMuted = false
        )
        assertTrue("Proactive speech should be scheduled", proactiveManager.isScheduled())

        // User is silent for 40 seconds, then starts speaking
        advanceTimeBy(40_000L)
        simulatedClockMs.addAndGet(40_000L)
        proactiveManager.onUserSpeechStarted()

        assertFalse("Proactive timer must be cancelled immediately when user starts speaking", proactiveManager.isScheduled())

        // Advance well past 60s
        advanceTimeBy(30_000L)
        simulatedClockMs.addAndGet(30_000L)
        assertNull("Proactive turn must NOT fire after user started speaking", triggeredPrompt.get())
        assertEquals(0, triggeredCount.get())
    }

    /**
     * Test 5:
     * - Repeated silence does not cause repeated unwanted speech.
     * - Subsequent proactive messages require at least 3–5 minutes (180s–300s) cooldown.
     * - Unresponded proactive turns are capped per silence cycle so RAFEL never monologues endlessly.
     * - Opening lines are varied and never repeat consecutively.
     */
    @Test
    fun test5_repeatedSilence_enforces3To5MinCooldownAndCapsUnwantedSpeech() = testScope.runTest {
        proactiveManager.frequency = ProactiveFrequency.BALANCED // 4 min (240s) subsequent cooldown, max 2 turns

        // First silence period: 60s
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        advanceTimeBy(60_500L)
        simulatedClockMs.addAndGet(60_500L)
        assertEquals("First proactive message should fire after 60s", 1, triggeredCount.get())
        val firstLine = proactiveManager.lastGeneratedOpeningLine
        assertNotNull(firstLine)

        // Assistant finishes speaking the first proactive message; user remains silent
        triggeredPrompt.set(null)
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertTrue("Second proactive check should be scheduled with 3-5 min cooldown", proactiveManager.isScheduled())

        // After another 60s of silence, it must NOT speak again yet (needs 3-5 minutes!)
        advanceTimeBy(60_000L)
        simulatedClockMs.addAndGet(60_000L)
        assertNull("Must NOT trigger a second proactive message after only 60s", triggeredPrompt.get())

        // Advance the remaining 3+ minutes (total 240.5s since first proactive turn)
        advanceTimeBy(181_000L)
        simulatedClockMs.addAndGet(181_000L)
        assertEquals("Second proactive message may fire after 4 minutes (3-5m window)", 2, triggeredCount.get())
        val secondLine = proactiveManager.lastGeneratedOpeningLine
        assertNotNull(secondLine)
        assertNotEquals("Proactive opening lines must vary and never repeat", firstLine, secondLine)

        // User STILL remains silent after 2nd proactive message -> must NOT schedule a 3rd unresponded turn!
        triggeredPrompt.set(null)
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertFalse("Must NOT schedule any more proactive turns when user remains silent after cap", proactiveManager.isScheduled())

        advanceTimeBy(600_000L)
        simulatedClockMs.addAndGet(600_000L)
        assertNull("No further unwanted speech may occur during continuous silence", triggeredPrompt.get())
        assertEquals(2, triggeredCount.get())
    }

    /**
     * Test 6:
     * - Quiet hours (e.g. 11 PM – 7 AM) and Do Not Disturb prevent proactive speech.
     */
    @Test
    fun test6_quietHoursAndDoNotDisturb_preventProactiveSpeech() = testScope.runTest {
        proactiveManager.quietHoursEnabled = true
        proactiveManager.quietHoursStartHour = 23 // 11 PM
        proactiveManager.quietHoursEndHour = 7    // 7 AM

        // Simulate 11:30 PM (hour = 23)
        simulatedHourOfDay.set(23)
        assertTrue("23:00 should be detected as Quiet Hours", proactiveManager.isQuietHoursActive())

        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertFalse("Proactive speech must not schedule during Quiet Hours", proactiveManager.isScheduled())
        advanceTimeBy(65_000L)
        assertNull("No proactive prompt during Quiet Hours", triggeredPrompt.get())

        // Simulate daytime (2:00 PM), but Android Do Not Disturb is ON
        simulatedHourOfDay.set(14)
        simulatedDndActive = true
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertFalse("Proactive speech must not schedule when Do Not Disturb is active", proactiveManager.isScheduled())
        advanceTimeBy(65_000L)
        assertNull("No proactive prompt when DND is active", triggeredPrompt.get())
    }

    /**
     * Test 7:
     * - Background and foreground transitions & active voice session gating work correctly.
     */
    @Test
    fun test7_backgroundForegroundTransitionsAndActiveSessionGating() = testScope.runTest {
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertTrue(proactiveManager.isScheduled())

        proactiveManager.onVoiceSessionEnded()
        assertFalse("Stopping the active voice session must cancel proactive timers", proactiveManager.isScheduled())

        proactiveManager.onAssistantSpeechFinished(false, false, false)
        assertFalse("Must not schedule when voice session is disconnected", proactiveManager.isScheduled())

        proactiveManager.onVoiceSessionStarted()
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertTrue("Should schedule when active voice session is running", proactiveManager.isScheduled())

        proactiveManager.onAppBackgrounded(isSessionStillActive = false)
        assertFalse("Backgrounding without an active voice session must cancel timer", proactiveManager.isScheduled())

        proactiveManager.onAppForegrounded(isSessionActive = true)
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertTrue("Returning to foreground with active session resumes scheduling", proactiveManager.isScheduled())
    }

    /**
     * Context-Aware Memory Follow-Up Test:
     * - If the user previously mentioned an exam or project, RAFEL naturally follows up on it
     *   once and does not ask the same question repeatedly.
     */
    @Test
    fun testContextAwareFollowUp_usesSavedExamAndProjectMemoryWithoutRepeating() = testScope.runTest {
        val memoryManager = MemoryManager.getInstance(context)
        memoryManager.observeUserUtterance("Mera naam Rahul hai aur agle hafte mera exam hai")

        val firstLine = proactiveManager.selectNextProactiveOpeningLine()
        assertTrue(
            "Should naturally follow up on the user's exam: got '$firstLine'",
            firstLine.contains("exam", ignoreCase = true)
        )

        val secondLine = proactiveManager.selectNextProactiveOpeningLine()
        assertNotEquals("Must not ask the same exam follow-up repeatedly", firstLine, secondLine)
    }

    @Test
    fun testProactiveCancelled_onSilenceCommandAndResumedOnNewQuery() = testScope.runTest {
        proactiveManager.onAssistantSpeechFinished(false, true, false)
        assertTrue(proactiveManager.isScheduled())

        proactiveManager.onUserTranscriptReceived("Rafel chup ho jao")
        assertTrue("Proactive conversation must be paused by user silence command", proactiveManager.isPausedByUser)
        assertFalse("Scheduled job must be cancelled on silence command", proactiveManager.isScheduled())

        proactiveManager.onUserTranscriptReceived("Aaj mausam kaisa hai")
        assertFalse("Proactive mode must unpause when user asks a new question", proactiveManager.isPausedByUser)
    }

    /**
     * Deep Proactive Evaluation Test:
     * Verifies that RAFEL does NOT speak merely because a timer expired when:
     * 1. User indicated they are busy/working/studying/going to sleep ("Main abhi padhai kar raha hu").
     * 2. User gave consecutive closed 1-word replies ("hmm", "kuch nahi") indicating they prefer silence.
     * And verifies that when the user IS available after discussing a topic (e.g., coding bug),
     * RAFEL initiates a relevant topic check-in without asking "kya kar rahe ho?".
     */
    @Test
    fun testProactiveReadinessEvaluation_choosesSilenceWhenUserBusyOrDisengaged() = testScope.runTest {
        val convManager = RafelConversationManager.getInstance(context)

        // Case 1: User says they are busy studying
        convManager.addUserTextMessage("Main abhi padhai kar raha hu, thoda busy hu")
        val busyEval = proactiveManager.evaluateProactiveReadiness()
        assertFalse("User should be marked unavailable when busy studying", busyEval.isUserLikelyAvailable)
        assertTrue("Silence must be preferred when user is busy studying", busyEval.wouldSilenceBeMoreAppropriate)
        assertFalse("RAFEL must NOT speak proactively when user is busy", busyEval.shouldSpeak)

        proactiveManager.onAssistantSpeechFinished(false, true, false)
        advanceTimeBy(65_000L)
        assertEquals("Timer expiry must not trigger speech when silence is more appropriate", 0, triggeredCount.get())

        // Case 2: User gives consecutive closed 1-word replies ("hmm", "kuch nahi")
        convManager.clearAllHistory()
        convManager.addUserTextMessage("hmm")
        convManager.addRafelTextMessage("Theek hai, aaram se soch lo.")
        convManager.addUserTextMessage("kuch nahi")
        val disengagedEval = proactiveManager.evaluateProactiveReadiness()
        assertTrue("Silence must be preferred after consecutive closed replies", disengagedEval.wouldSilenceBeMoreAppropriate)
        assertFalse("RAFEL must NOT speak when user signaled disengagement", disengagedEval.shouldSpeak)

        // Case 3: User was discussing a coding bug and is available -> meaningful topic follow-up
        convManager.clearAllHistory()
        convManager.addUserTextMessage("Yaar subah se Kotlin app mein ek bug fix kar raha tha")
        convManager.addRafelTextMessage("Coding mein chhote bugs sabse zyada time lete hain.")
        val topicEval = proactiveManager.evaluateProactiveReadiness()
        assertTrue("User is available", topicEval.isUserLikelyAvailable)
        assertTrue("Has relevant coding topic", topicEval.hasRelevantPreviousTopic)
        assertTrue("Has meaningful contribution", topicEval.hasMeaningfulContribution)
        assertFalse("Silence is not required", topicEval.wouldSilenceBeMoreAppropriate)
        assertTrue("Should speak on relevant topic", topicEval.shouldSpeak)

        val opening = proactiveManager.selectNextProactiveOpeningLine()
        assertTrue("Opening should reference the code/bug topic: $opening", opening.contains("bug", ignoreCase = true) || opening.contains("code", ignoreCase = true))
        assertFalse("Must not ask generic 'kya kar rahe ho?'", opening.lowercase().contains("kya kar rahe ho"))
    }

    /**
     * Scenario 1, 2, 3, 5, 6, 8:
     * - Scenario 1: Start RAFEL voice mode and remain silent -> RAFEL speaks first after initialSessionDelayMs (30s) without requiring "Hello Rafel".
     * - Scenario 2 & 3: User speaks -> RAFEL replies -> User remains silent -> RAFEL initiates another relevant turn after firstSilenceDelayMs (60s).
     * - Scenario 5: User mutes RAFEL -> no proactive speech occurs.
     * - Scenario 6: User stops the session -> all proactive triggers stop.
     * - Scenario 8: No duplicate or overlapping audio when assistant is already speaking (onAssistantSpeechStarted cancels timer).
     */
    @Test
    fun testAllProactiveVoiceLifecycleScenarios_assistantSpeaksFirst_continuesAfterSilence_andRespectsMuteStopAndNoOverlap() = testScope.runTest {
        proactiveManager.initialSessionDelayMs = 30_000L
        proactiveManager.firstSilenceDelayMs = 60_000L
        proactiveManager.onVoiceSessionStarted()

        // Scenario 1: Session activates, user has NOT spoken at all ("hasUserSpokenInSession == false")
        proactiveManager.onSessionActivated(
            isAiSpeaking = false,
            isSessionConnected = true,
            isMicMuted = false
        )
        assertFalse("User has not spoken yet", proactiveManager.hasUserSpokenInSession)
        assertTrue("Proactive timer must be scheduled immediately on session activation", proactiveManager.isScheduled())

        advanceTimeBy(15_000L)
        simulatedClockMs.addAndGet(15_000L)
        assertEquals("Should not fire at 15s when initialSessionDelayMs is 30s", 0, triggeredCount.get())
        assertTrue("Silence duration must be tracked accurately", proactiveManager.getCurrentSilenceDurationMs() >= 15_000L)

        advanceTimeBy(15_500L)
        simulatedClockMs.addAndGet(15_500L)
        assertEquals("RAFEL must speak first at 30s without user saying Hello Rafel", 1, triggeredCount.get())
        val firstPrompt = triggeredPrompt.get()
        assertNotNull(firstPrompt)
        assertTrue(
            "Initial prompt should note that no spoken turns occurred yet in this session",
            firstPrompt!!.contains("speaking first", ignoreCase = true) || firstPrompt.contains("Proactive conversation prompt", ignoreCase = true)
        )

        // Scenario 8: While RAFEL is speaking, onAssistantSpeechStarted cancels any timer and prevents overlap
        proactiveManager.onAssistantSpeechStarted()
        assertTrue(proactiveManager.isAssistantCurrentlySpeaking)
        assertFalse("No proactive timer may run while assistant is speaking", proactiveManager.isScheduled())

        // Scenario 2 & 3: User speaks about an interview -> RAFEL replies -> User remains silent -> RAFEL initiates relevant follow-up after 60s
        val convManager = RafelConversationManager.getInstance(context)
        convManager.clearAllHistory()
        proactiveManager.onUserSpeechStarted()
        assertTrue(proactiveManager.isUserCurrentlySpeaking)
        proactiveManager.onUserTranscriptReceived("Kal mera software engineering interview hai")
        convManager.addUserVoiceMessage("Kal mera software engineering interview hai")
        proactiveManager.onUserSpeechFinished(isAiSpeaking = true, isSessionConnected = true, isMicMuted = false)
        convManager.addRafelVoiceMessage("Interview ke liye all the best, bas core concepts clear rakhna.")

        // Assistant finishes speaking its reply -> 60s silence timer starts
        triggeredPrompt.set(null)
        proactiveManager.onAssistantSpeechFinished(isAiSpeaking = false, isSessionConnected = true, isMicMuted = false)
        assertTrue("Timer must schedule after assistant finishes reply", proactiveManager.isScheduled())

        advanceTimeBy(60_500L)
        simulatedClockMs.addAndGet(60_500L)
        assertEquals("RAFEL must initiate another relevant turn after 60s silence", 2, triggeredCount.get())
        val followUpPrompt = triggeredPrompt.get()
        assertNotNull(followUpPrompt)
        assertTrue(
            "Follow-up prompt must reference the interview context: $followUpPrompt",
            followUpPrompt!!.contains("interview", ignoreCase = true)
        )

        // Scenario 5: User mutes microphone -> no proactive speech occurs
        triggeredPrompt.set(null)
        proactiveManager.onAssistantSpeechFinished(isAiSpeaking = false, isSessionConnected = true, isMicMuted = true)
        assertFalse("Muted microphone must block proactive scheduling", proactiveManager.isScheduled())
        advanceTimeBy(120_000L)
        assertNull("No proactive prompt while muted", triggeredPrompt.get())

        // Scenario 6: User stops session -> all proactive triggers stop
        proactiveManager.onAssistantSpeechFinished(isAiSpeaking = false, isSessionConnected = true, isMicMuted = false)
        assertTrue(proactiveManager.isScheduled())
        proactiveManager.onVoiceSessionEnded()
        assertFalse("Stopping session must cancel all proactive timers", proactiveManager.isScheduled())
        advanceTimeBy(120_000L)
        assertNull("No proactive prompt after session ended", triggeredPrompt.get())
    }

    /**
     * Scenario 4:
     * - User interrupts RAFEL -> barge-in detected immediately.
     * - Post-AI-speech echo guard (notifyAiSpeechEnded) prevents residual speaker echo from
     *   falsely triggering user speech onset in the 350ms window after playback ends.
     */
    @Test
    fun testScenario4_bargeInAndPostPlaybackEchoGuard() {
        var speechStartedCount = 0
        var bargeInCount = 0
        val vad = VoiceActivityDetector(
            onSpeechStart = { speechStartedCount++ },
            onSpeechChunk = { _, _ -> },
            onSpeechEnd = {},
            onBargeInDetected = { bargeInCount++ }
        )

        val loudSpeechFrame = ShortArray(VoiceConfig.CHUNK_SIZE_SAMPLES) { 3200 }
        val mildEchoFrame = ShortArray(VoiceConfig.CHUNK_SIZE_SAMPLES) { 700 }

        // 1. While AI is speaking, mild speaker echo (RMS 700 < BARGE_IN_THRESHOLD 1750) is ignored
        vad.processFrame(mildEchoFrame, mildEchoFrame.size, isAiSpeaking = true, isWaitingForAi = false)
        vad.processFrame(mildEchoFrame, mildEchoFrame.size, isAiSpeaking = true, isWaitingForAi = false)
        assertEquals("Mild echo during AI speech must not trigger barge-in", 0, bargeInCount)

        // 2. Intentional user speech (RMS 3200 >= BARGE_IN_THRESHOLD) for 2 chunks triggers immediate barge-in
        vad.processFrame(loudSpeechFrame, loudSpeechFrame.size, isAiSpeaking = true, isWaitingForAi = false)
        vad.processFrame(loudSpeechFrame, loudSpeechFrame.size, isAiSpeaking = true, isWaitingForAi = false)
        assertEquals("Intentional user speech during AI playback must trigger barge-in immediately", 1, bargeInCount)
        assertEquals("Barge-in must also start user speech turn", 1, speechStartedCount)

        // 3. Reset and verify post-playback echo guard (350ms after notifyAiSpeechEnded)
        vad.reset()
        speechStartedCount = 0
        val endTime = System.currentTimeMillis()
        vad.notifyAiSpeechEnded(endTime)

        // Residual speaker echo frame (RMS 700, which is > normal SPEECH_THRESHOLD 450 but < BARGE_IN_THRESHOLD 1750)
        // arrives immediately after AI playback finishes -> must be suppressed by echo guard!
        vad.processFrame(mildEchoFrame, mildEchoFrame.size, isAiSpeaking = false, isWaitingForAi = false)
        assertEquals("Post-playback speaker echo tail must NOT be misinterpreted as user speech", 0, speechStartedCount)
    }

    /**
     * Scenario 7 & 8:
     * - Network disconnects / cloud inference disabled -> generateProactiveTurnReply uses local fallback
     *   and honestly reports actualResponseSource = LOCAL_FALLBACK (never falsely claiming Gemini).
     * - Consecutive proactive voice turns create separate RAFEL_VOICE messages instead of merging.
     */
    @Test
    fun testScenario7And8_offlineProactiveFallbackHonestyAndDistinctVoiceTurns() = testScope.runTest {
        val convManager = RafelConversationManager.getInstance(context)
        val modelManager = convManager.aiModelManager
        convManager.clearAllHistory()

        val prevCloudEnabled = modelManager.cloudInferenceEnabled.value
        try {
            modelManager.setCloudInferenceEnabled(false)

            val firstLine = "Aaj kafi shanti hai idhar, lagta hai kisi kaam mein dhyan laga hua hai."
            val firstReply = convManager.generateProactiveTurnReply(
                proactivePrompt = proactiveManager.buildProactiveTurnPrompt(firstLine),
                suggestedOpeningLine = firstLine,
                apiKey = ""
            )
            assertTrue("Offline proactive reply must be non-empty", firstReply.isNotBlank())
            assertEquals("Must honestly report LOCAL_FALLBACK source", "LOCAL_FALLBACK", RafelLiveDiagnostics.actualResponseSource.value)
            assertEquals("Must honestly report LOCAL_FALLBACK provider", "LOCAL_FALLBACK", RafelLiveDiagnostics.activeProvider.value)
            assertEquals("Must honestly report rafel-local-contextual-v1 model", "rafel-local-contextual-v1", RafelLiveDiagnostics.activeModel.value)

            val secondLine = "Waise aaj tumhara din kaisa raha?"
            val secondReply = convManager.generateProactiveTurnReply(
                proactivePrompt = proactiveManager.buildProactiveTurnPrompt(secondLine),
                suggestedOpeningLine = secondLine,
                apiKey = ""
            )
            assertTrue("Second proactive reply must be non-empty", secondReply.isNotBlank())

            val voiceMsgs = convManager.messages.value.filter { !it.isUser }
            assertEquals("Consecutive proactive turns must create 2 distinct assistant messages", 2, voiceMsgs.size)
            assertNotEquals("Consecutive proactive turns must not repeat the same opening", voiceMsgs[0].text, voiceMsgs[1].text)
        } finally {
            modelManager.setCloudInferenceEnabled(prevCloudEnabled)
        }
    }
}
