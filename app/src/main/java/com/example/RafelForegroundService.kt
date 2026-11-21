package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.ai.AiBackend
import com.example.ai.AiBackendManager
import com.example.floating.FloatingOverlayManager
import com.example.live.LiveSessionManager
import com.example.memory.ConversationTurnEntity
import com.example.memory.RafelMemoryDatabase
import com.example.tools.AssistantIntent
import com.example.tools.AssistantIntentRouter
import com.example.tools.ToolExecutionEngine
import com.example.tools.ToolSafetyGate
import com.example.voice.AudioPlaybackManager
import com.example.voice.MicrophoneManager
import com.example.voice.ProactiveConversationManager
import com.example.voice.RafelLogger
import com.example.voice.RafelVoiceState
import com.example.voice.SpeechTranscriptionManager
import com.example.voice.UserTurnManager
import com.example.voice.VoiceActivityDetector
import com.example.voice.VoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service for Rafel Online Assistant.
 *
 * Coordinates the pure online real-time voice pipeline:
 * - MicrophoneManager (16kHz audio capture with AEC/NS)
 * - VoiceActivityDetector (VAD with pre-roll buffering & barge-in detection)
 * - LiveSessionManager (Gemini Live WebSocket streaming on models/gemini-3.8-live)
 * - AudioPlaybackManager (24kHz progressive PCM audio output with AudioFocus & cancellation)
 * - Centralized Voice State Machine (Single source of truth)
 */
class RafelForegroundService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var toolEngine: ToolExecutionEngine
    lateinit var liveSessionManager: LiveSessionManager
        private set
    private lateinit var playbackManager: AudioPlaybackManager
    private lateinit var micManager: MicrophoneManager
    private lateinit var vadEngine: VoiceActivityDetector
    private lateinit var floatingOverlayManager: FloatingOverlayManager
    lateinit var proactiveManager: ProactiveConversationManager
        private set

    lateinit var aiBackendManager: AiBackendManager
        private set
    lateinit var speechTranscriptionManager: SpeechTranscriptionManager
        private set
    val userTurnManager = UserTurnManager()
    private var currentActiveTurnId: Long = 0L
    private val memoryDao by lazy { RafelMemoryDatabase.getInstance(this).memoryDao() }

    companion object {
        const val CHANNEL_ID = "RAFEL_VOICE_CHANNEL"
        private val _currentState = MutableStateFlow(RafelVoiceState.IDLE)
        val currentStateFlow: StateFlow<RafelVoiceState> = _currentState.asStateFlow()
        val voiceStateFlow: StateFlow<RafelVoiceState> = currentStateFlow

        val currentState: RafelVoiceState
            get() = _currentState.value

        var onStateChange: ((RafelVoiceState) -> Unit)? = null

        private val _messages = MutableStateFlow<List<String>>(emptyList())
        val messages: StateFlow<List<String>> = _messages.asStateFlow()

        private val _isMicMuted = MutableStateFlow(false)
        val isMicMutedFlow: StateFlow<Boolean> = _isMicMuted.asStateFlow()

        private val _visualizerAmplitude = MutableStateFlow(0f)
        val visualizerAmplitudeFlow: StateFlow<Float> = _visualizerAmplitude.asStateFlow()

        var activeService: RafelForegroundService? = null
        val activeToolEngine: ToolExecutionEngine?
            get() = activeService?.toolEngine
        val activeBackendManager: AiBackendManager?
            get() = activeService?.aiBackendManager

        fun toggleMicMute() {
            activeService?.toggleMicMuted()
        }

        fun showFloating() {
            activeService?.showFloatingOverlay()
        }

        fun hideFloating() {
            activeService?.hideFloatingOverlay()
        }

        fun isFloatingShowing(): Boolean {
            return activeService?.isFloatingOverlayShowing() ?: false
        }

        fun isServiceRunning(): Boolean = activeService != null

        fun isLiveConnected(): Boolean = activeService?.liveSessionManager?.isConnected() == true

        fun isMicActive(): Boolean = activeService?.isMicRunning() == true

        fun isPlaybackActive(): Boolean = activeService?.playbackManager?.isSpeaking() == true
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        RafelLogger.logVoiceState(RafelVoiceState.IDLE, RafelVoiceState.IDLE)

        toolEngine = ToolExecutionEngine(this)

        // Initialize AI Backend Manager
        aiBackendManager = AiBackendManager(
            context = this,
            geminiKeyProvider = { getGeminiApiKey() }
        )

        // Initialize Speech Transcription Manager for STT
        speechTranscriptionManager = SpeechTranscriptionManager(
            context = this,
            userTurnManager = userTurnManager
        ).apply {
            initialize()
        }

        // 1. Audio Playback Manager (Outputs Gemini Live speech at 24kHz)
        playbackManager = AudioPlaybackManager(
            context = this,
            onPlaybackStarted = {
                if (::proactiveManager.isInitialized) {
                    proactiveManager.onAssistantSpeechStarted()
                }
                updateVoiceState(RafelVoiceState.AI_SPEAKING)
            },
            onPlaybackFinished = {
                if (::vadEngine.isInitialized) {
                    vadEngine.notifyAiSpeechEnded()
                }
                if (_currentState.value == RafelVoiceState.AI_SPEAKING) {
                    _visualizerAmplitude.value = 0f
                    if (_isMicMuted.value) {
                        updateVoiceState(RafelVoiceState.MIC_OFF)
                    } else {
                        updateVoiceState(RafelVoiceState.LISTENING)
                    }
                    if (::proactiveManager.isInitialized) {
                        proactiveManager.onAssistantSpeechFinished(
                            isAiSpeaking = playbackManager.isSpeaking(),
                            isSessionConnected = isVoiceSessionReadyForProactive(),
                            isMicMuted = _isMicMuted.value
                        )
                    }
                }
            },
            onAudioPlayed = { pcmBytes ->
                com.example.avatar.RafelAvatarController.shared.onAudioChunk(pcmBytes)
            },
            onAmplitudeChanged = { amp ->
                if (_currentState.value == RafelVoiceState.AI_SPEAKING) {
                    _visualizerAmplitude.value = amp
                }
            }
        )

        // 2. Gemini Live Session Manager (models/gemini-3.8-live)
        liveSessionManager = LiveSessionManager(
            context = this,
            toolEngine = toolEngine,
            onAudioOut = { generationId, audioData ->
                playbackManager.enqueueChunk(generationId, audioData)
            },
            onTurnComplete = { generationId ->
                playbackManager.markTurnComplete(generationId)
            },
            onServerInterrupted = {
                playbackManager.interruptAndFlush()
                _visualizerAmplitude.value = 0f
                updateVoiceState(RafelVoiceState.INTERRUPTED)
            },
            onStateChange = { sessionState ->
                updateVoiceState(sessionState)
            }
        ).apply {
            getPlaybackQueueBytes = { playbackManager.getQueuedBytes() }
            onSessionReady = {
                if (::proactiveManager.isInitialized) {
                    proactiveManager.onSessionActivated(
                        isAiSpeaking = playbackManager.isSpeaking(),
                        isSessionConnected = true,
                        isMicMuted = _isMicMuted.value
                    )
                }
            }
        }

        // Proactive Conversation Manager (30-60s initial session delay + 60s silence delay + 3-5m subsequent cooldown)
        proactiveManager = ProactiveConversationManager(
            context = this,
            scope = serviceScope,
            onTriggerProactiveTurn = { prompt: String ->
                if (!_isMicMuted.value && !playbackManager.isSpeaking() && _currentState.value != RafelVoiceState.USER_SPEAKING) {
                    if (liveSessionManager.isConnected()) {
                        playbackManager.prepareForPlayback()
                        liveSessionManager.sendProactiveTurn(prompt)
                    } else if (isVoiceSessionReadyForProactive()) {
                        serviceScope.launch {
                            updateVoiceState(RafelVoiceState.WAITING_FOR_AI)
                            val openingLine = proactiveManager.lastGeneratedOpeningLine
                                ?: proactiveManager.selectNextProactiveOpeningLine()
                            val reply = com.example.conversation.RafelConversationManager.getInstance(this@RafelForegroundService)
                                .generateProactiveTurnReply(
                                    proactivePrompt = prompt,
                                    suggestedOpeningLine = openingLine,
                                    apiKey = getGeminiApiKey()
                                )
                            if (reply.isNotBlank()) {
                                val currentMsgs = _messages.value
                                _messages.value = (currentMsgs + "Rafel: $reply").takeLast(50)
                                com.example.avatar.RafelAvatarController.shared.onAssistantSpeechGenerated("Rafel: $reply")
                            }
                            updateVoiceState(if (_isMicMuted.value) RafelVoiceState.MIC_OFF else RafelVoiceState.LISTENING)
                            proactiveManager.onAssistantSpeechFinished(
                                isAiSpeaking = false,
                                isSessionConnected = isVoiceSessionReadyForProactive(),
                                isMicMuted = _isMicMuted.value
                            )
                        }
                    }
                }
            }
        )

        // Screen Capture Integration (Streams screen frames into Gemini Live)
        val screenManager = com.example.screen.ScreenCaptureManager.getInstance(this)
        screenManager.onFrameCaptured = { frameBase64 ->
            liveSessionManager.sendImageFrame(frameBase64)
        }
        screenManager.onSharingStateChanged = { isSharing ->
            updateForegroundServiceTypes()
        }

        // 3. VAD Engine (Detects user speech onset, utterance end, and barge-in)
        vadEngine = VoiceActivityDetector(
            onSpeechStart = { preRollChunks ->
                if (::proactiveManager.isInitialized) {
                    proactiveManager.onUserSpeechStarted()
                }
                val isSpeaking = _currentState.value == RafelVoiceState.AI_SPEAKING ||
                        playbackManager.isSpeaking()

                // If user starts speaking while AI is speaking -> Instant Barge-in!
                if (isSpeaking) {
                    RafelLogger.logInterruption("Barge-in on speech start! Halting playback.")
                    playbackManager.interruptAndFlush()
                    liveSessionManager.notifyBargeIn()
                    updateVoiceState(RafelVoiceState.INTERRUPTED)
                }
                updateVoiceState(RafelVoiceState.USER_SPEAKING)

                val turnId = userTurnManager.startNewTurn()
                currentActiveTurnId = turnId

                // Initialize fresh turn state and flush any prior turn audio
                liveSessionManager.onTurnAudioStart()
                playbackManager.flushPriorTurnAudio(liveSessionManager.getCurrentGenerationId())
                if (!_isMicMuted.value && liveSessionManager.isConnected()) {
                    preRollChunks.forEach { chunk ->
                        liveSessionManager.sendAudioData(chunk, chunk.size)
                    }
                }
            },
            onSpeechChunk = { _, _ ->
                // Audio is streamed continuously from onAudioChunkRead
            },
            onSpeechEnd = {
                _visualizerAmplitude.value = 0f
                val isSpeaking = playbackManager.isSpeaking()

                if (!isSpeaking && _currentState.value != RafelVoiceState.AI_SPEAKING) {
                    if (liveSessionManager.isConnected()) {
                        if (::proactiveManager.isInitialized) {
                            proactiveManager.onUserSpeechFinished(
                                isAiSpeaking = true,
                                isSessionConnected = true,
                                isMicMuted = _isMicMuted.value
                            )
                        }
                        liveSessionManager.onUserSpeechFinished()
                        playbackManager.prepareForPlayback()
                        updateVoiceState(RafelVoiceState.WAITING_FOR_AI)
                    } else {
                        RafelLogger.logDiagnostic("Speech ended but Gemini Live is disconnected.")
                        liveSessionManager.onTurnAudioEnd()
                        if (isVoiceSessionReadyForProactive()) {
                            updateVoiceState(RafelVoiceState.LISTENING)
                            if (::proactiveManager.isInitialized) {
                                proactiveManager.onUserSpeechFinished(
                                    isAiSpeaking = false,
                                    isSessionConnected = true,
                                    isMicMuted = _isMicMuted.value
                                )
                            }
                        } else {
                            updateVoiceState(RafelVoiceState.IDLE)
                        }
                    }
                } else {
                    RafelLogger.logVad("Speech ended but Rafel is already speaking. Preserving AI_SPEAKING.")
                }
            },
            onBargeInDetected = {
                android.util.Log.i("RAFEL-DIAGNOSTIC", "BARGE_IN_DETECTED: user interrupted playback")
                RafelLogger.logLive("[BARGE_IN] instant user speech detected -> halting playback")
                RafelLogger.logInterruption("Instant barge-in detected. Stopping AI voice immediately.")
                if (::proactiveManager.isInitialized) {
                    proactiveManager.onUserSpeechStarted()
                }
                playbackManager.interruptAndFlush()
                _visualizerAmplitude.value = 0f
                liveSessionManager.notifyBargeIn()
                updateVoiceState(RafelVoiceState.INTERRUPTED)
            }
        )

        // 4. Microphone Manager (16kHz capture)
        micManager = MicrophoneManager(
            context = this,
            onAudioChunkRead = { buffer, length ->
                val isSpeaking = _currentState.value == RafelVoiceState.AI_SPEAKING ||
                        playbackManager.isSpeaking()
                val isWaiting = _currentState.value == RafelVoiceState.WAITING_FOR_AI
                vadEngine.processFrame(buffer, length, isSpeaking, isWaiting)

                // Continuous audio streaming into Gemini Live while user is speaking or listening
                // Never stream ambient mic audio while WAITING_FOR_AI or AI_SPEAKING so proactive/model turns are not interrupted by mic noise
                if (!isSpeaking && !isWaiting && !_isMicMuted.value && liveSessionManager.isConnected()) {
                    liveSessionManager.sendAudioData(buffer, length)
                }
            },
            onError = { errorMsg ->
                RafelLogger.logError("Microphone error: $errorMsg")
                updateVoiceState(RafelVoiceState.ERROR)
            },
            onAmplitudeChanged = { amp ->
                if (!_isMicMuted.value && (_currentState.value == RafelVoiceState.USER_SPEAKING || _currentState.value == RafelVoiceState.LISTENING)) {
                    _visualizerAmplitude.value = amp
                }
            }
        )

        // Observe messages from Gemini Live for UI logs and emotion detection
        serviceScope.launch {
            liveSessionManager.messages.collect { msgList ->
                _messages.value = msgList
                val lastMsg = msgList.lastOrNull()
                if (lastMsg != null) {
                    if (lastMsg.startsWith("Rafel:") || lastMsg.startsWith("Rafel is ready")) {
                        com.example.avatar.RafelAvatarController.shared.onAssistantSpeechGenerated(lastMsg)
                    } else if (lastMsg.startsWith("You:")) {
                        val userText = lastMsg.removePrefix("You:").trim()
                        com.example.avatar.RafelAvatarController.shared.onUserSpeechDetected(lastMsg)
                        if (::proactiveManager.isInitialized) {
                            proactiveManager.onUserTranscriptReceived(userText)
                        }
                    }
                }
            }
        }

        createNotificationChannel()
        startForegroundServiceWithNotification()

        // Start subsystems
        floatingOverlayManager = FloatingOverlayManager(this)

        val micStarted = micManager.start()
        if (micStarted) {
            proactiveManager.onVoiceSessionStarted()
            updateVoiceState(RafelVoiceState.WAITING_FOR_AI)
            if (aiBackendManager.isGeminiActive()) {
                liveSessionManager.startSession()
                if (!liveSessionManager.isConnected() && isVoiceSessionReadyForProactive()) {
                    updateVoiceState(if (_isMicMuted.value) RafelVoiceState.MIC_OFF else RafelVoiceState.LISTENING)
                    proactiveManager.onSessionActivated(
                        isAiSpeaking = false,
                        isSessionConnected = true,
                        isMicMuted = _isMicMuted.value
                    )
                }
            } else if (isVoiceSessionReadyForProactive()) {
                updateVoiceState(if (_isMicMuted.value) RafelVoiceState.MIC_OFF else RafelVoiceState.LISTENING)
                proactiveManager.onSessionActivated(
                    isAiSpeaking = false,
                    isSessionConnected = true,
                    isMicMuted = _isMicMuted.value
                )
            }
        } else {
            proactiveManager.onVoiceSessionEnded()
            updateVoiceState(RafelVoiceState.ERROR)
        }
    }

    private fun isVoiceSessionReadyForProactive(): Boolean {
        if (!isMicRunning()) return false
        val aiManager = com.example.ai.router.AIModelManager.getInstance(this)
        val isOfflineOrFallback = !aiManager.healthMonitor.isOnline.value ||
            !aiManager.cloudInferenceEnabled.value ||
            getGeminiApiKey().isBlank()
        return (::liveSessionManager.isInitialized && liveSessionManager.isConnected()) || isOfflineOrFallback
    }

    private fun updateVoiceState(newState: RafelVoiceState) {
        val oldState = _currentState.value
        if (oldState != newState) {
            if (oldState == RafelVoiceState.WAITING_FOR_AI) {
                android.util.Log.i("RAFEL-STATE", "[STATE] THINKING_EXIT")
            }
            if (oldState == RafelVoiceState.AI_SPEAKING) {
                android.util.Log.i("RAFEL-STATE", "[STATE] SPEAKING_EXIT")
            }
            if (newState == RafelVoiceState.WAITING_FOR_AI) {
                android.util.Log.i("RAFEL-STATE", "[STATE] THINKING_ENTER")
            }
            if (newState == RafelVoiceState.AI_SPEAKING) {
                android.util.Log.i("RAFEL-STATE", "[STATE] SPEAKING_ENTER")
            }

            _currentState.value = newState
            if (newState == RafelVoiceState.AI_SPEAKING) {
                RafelLogger.trace9UiStateSpeaking()
            } else if (newState == RafelVoiceState.IDLE || newState == RafelVoiceState.LISTENING) {
                RafelLogger.trace11UiStateIdle()
            }
            if (newState == RafelVoiceState.LISTENING &&
                ::proactiveManager.isInitialized &&
                !proactiveManager.isScheduled() &&
                ::liveSessionManager.isInitialized &&
                ::playbackManager.isInitialized
            ) {
                proactiveManager.onSessionActivated(
                    isAiSpeaking = playbackManager.isSpeaking(),
                    isSessionConnected = isVoiceSessionReadyForProactive(),
                    isMicMuted = _isMicMuted.value
                )
            }
            RafelLogger.logDiagnostic("Rafel voice state: [${oldState.name}] -> [${newState.name}] ('${newState.displayText}')")
            RafelLogger.logVoiceState(oldState, newState)
            com.example.avatar.RafelAvatarController.shared.onVoiceStateChanged(newState)
            onStateChange?.invoke(newState)
            updateNotification(newState.displayText)
        }
    }

    private fun startForegroundServiceWithNotification() {
        updateForegroundServiceTypes()
    }

    fun updateForegroundServiceTypes() {
        val isScreenSharing = com.example.screen.ScreenCaptureManager.getInstance(this).isSharingFlow.value
        val label = if (isScreenSharing) "Live Screen Sharing & Voice Active" else _currentState.value.displayText
        val notification = createNotification(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = if (isScreenSharing) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(101, notification, types)
            } catch (e: Exception) {
                try { startForeground(101, notification) } catch (ignore: Exception) {}
            }
        } else {
            try { startForeground(101, notification) } catch (ignore: Exception) {}
        }
    }

    private fun updateNotification(contentText: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(101, createNotification(contentText))
        } catch (e: Exception) {
            RafelLogger.logError("Failed to update notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rafel Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time voice assistant status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val micActionTitle = if (_isMicMuted.value) "Resume mic" else "Pause mic"
        val toggleMicPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RafelForegroundService::class.java).apply { action = "TOGGLE_MIC" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RafelForegroundService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayStatus = if (_isMicMuted.value) "Mic Muted" else contentText

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RAFel is active")
            .setContentText(displayStatus)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(openAppIntent)
            .addAction(
                if (_isMicMuted.value) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_lock_silent_mode,
                micActionTitle,
                toggleMicPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_agenda,
                "Open Rafel",
                openAppIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Rafel",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "TOGGLE_MIC" -> {
                toggleMicMuted()
                return START_STICKY
            }
            "SHOW_FLOATING" -> {
                showFloatingOverlay()
                return START_STICKY
            }
            "HIDE_FLOATING" -> {
                hideFloatingOverlay()
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun sendTextMessage(text: String) {
        val convManager = com.example.conversation.RafelConversationManager.getInstance(this)
        convManager.processUserTextMessage(text, getGeminiApiKey(), toolEngine)
    }

    fun getGeminiApiKey(): String {
        val buildKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }
        val prefs = getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("api_key", "") ?: prefs.getString("gemini_api_key", "") ?: ""
        return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY" && buildKey != "YOUR_API_KEY") {
            buildKey
        } else {
            saved
        }
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
        micManager.setMuted(muted)
        updateNotification(if (muted) "Mic Muted" else _currentState.value.displayText)
        if (muted) {
            if (::proactiveManager.isInitialized) {
                proactiveManager.cancelScheduledPrompt("Microphone muted by user")
            }
            _visualizerAmplitude.value = 0f
            if (!playbackManager.isSpeaking() && _currentState.value != RafelVoiceState.AI_SPEAKING) {
                vadEngine.reset()
                updateVoiceState(RafelVoiceState.MIC_OFF)
            }
        } else {
            if (_currentState.value == RafelVoiceState.MIC_OFF) {
                updateVoiceState(RafelVoiceState.LISTENING)
            }
            if (::proactiveManager.isInitialized && isVoiceSessionReadyForProactive() && !playbackManager.isSpeaking()) {
                proactiveManager.onSessionActivated(
                    isAiSpeaking = false,
                    isSessionConnected = true,
                    isMicMuted = false
                )
            }
        }
    }

    fun toggleMicMuted() {
        setMicMuted(!_isMicMuted.value)
    }

    fun showFloatingOverlay() {
        floatingOverlayManager.showOverlay()
    }

    fun hideFloatingOverlay() {
        floatingOverlayManager.hideOverlay()
    }

    fun isFloatingOverlayShowing(): Boolean {
        return floatingOverlayManager.isShowing()
    }

    fun isMicRunning(): Boolean {
        return ::micManager.isInitialized && micManager.isRunning() && !_isMicMuted.value
    }

    fun reconnectSession() {
        RafelLogger.logLiveReconnect()
        vadEngine.reset()
        playbackManager.interruptAndFlush()
        liveSessionManager.stopSession()
        if (!micManager.isRunning()) {
            micManager.start()
        }
        if (::proactiveManager.isInitialized) {
            proactiveManager.onVoiceSessionStarted()
        }
        updateVoiceState(if (_isMicMuted.value) RafelVoiceState.MIC_OFF else RafelVoiceState.WAITING_FOR_AI)
        liveSessionManager.startSession()
        if (!liveSessionManager.isConnected() && isVoiceSessionReadyForProactive()) {
            updateVoiceState(if (_isMicMuted.value) RafelVoiceState.MIC_OFF else RafelVoiceState.LISTENING)
            if (::proactiveManager.isInitialized) {
                proactiveManager.onSessionActivated(
                    isAiSpeaking = false,
                    isSessionConnected = true,
                    isMicMuted = _isMicMuted.value
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::proactiveManager.isInitialized) {
            proactiveManager.onVoiceSessionEnded()
        }
        activeService = null
        RafelLogger.logVoiceState(_currentState.value, RafelVoiceState.IDLE)
        _currentState.value = RafelVoiceState.IDLE
        onStateChange?.invoke(RafelVoiceState.IDLE)

        floatingOverlayManager.destroy()
        micManager.stop()
        playbackManager.release()
        liveSessionManager.stopSession()
        speechTranscriptionManager.destroy()
        serviceJob.cancel()
    }
}
