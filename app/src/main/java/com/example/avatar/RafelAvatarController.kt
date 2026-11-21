package com.example.avatar

import com.example.voice.RafelVoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central controller coordinating:
 * - Avatar states (IDLE, LISTENING, THINKING, SPEAKING, HAPPY, SAD, ANGRY, SURPRISED, CONCERNED, CONFUSED)
 * - Emotion & intensity
 * - Real-time lip-sync
 * - Voice state mapping & barge-in clearing
 */
class RafelAvatarController(
    val lipSyncController: LipSyncController = LipSyncController(),
    val renderer: AvatarRenderer = OfficialAvatarRenderer()
) {
    private val _avatarState = MutableStateFlow(AvatarState.IDLE)
    val avatarState: StateFlow<AvatarState> = _avatarState.asStateFlow()

    private val _emotionState = MutableStateFlow(EmotionState(AvatarEmotion.NEUTRAL, 0.5f))
    val emotionState: StateFlow<EmotionState> = _emotionState.asStateFlow()

    val mouthOpenness: StateFlow<Float> = lipSyncController.mouthOpenness

    private var lastUserText: String? = null
    private var lastAssistantText: String? = null
    private var currentVoiceState: RafelVoiceState = RafelVoiceState.IDLE

    companion object {
        val shared = RafelAvatarController()
    }

    fun onVoiceStateChanged(voiceState: RafelVoiceState) {
        currentVoiceState = voiceState
        val mappedState = when (voiceState) {
            RafelVoiceState.IDLE -> {
                // If there is an active strong emotion, reflect it in the idle avatar state
                when (_emotionState.value.emotion) {
                    AvatarEmotion.HAPPY -> AvatarState.HAPPY
                    AvatarEmotion.CONCERNED -> AvatarState.CONCERNED
                    AvatarEmotion.SURPRISED -> AvatarState.SURPRISED
                    AvatarEmotion.CONFUSED -> AvatarState.CONFUSED
                    AvatarEmotion.SAD -> AvatarState.SAD
                    AvatarEmotion.ANGRY -> AvatarState.ANGRY
                    else -> AvatarState.IDLE
                }
            }
            RafelVoiceState.LISTENING -> AvatarState.LISTENING
            RafelVoiceState.USER_SPEAKING -> AvatarState.LISTENING
            RafelVoiceState.WAITING_FOR_AI -> AvatarState.THINKING
            RafelVoiceState.AI_SPEAKING -> AvatarState.SPEAKING
            RafelVoiceState.INTERRUPTED -> {
                lipSyncController.reset()
                AvatarState.LISTENING
            }
            RafelVoiceState.MIC_OFF -> AvatarState.IDLE
            RafelVoiceState.ERROR -> AvatarState.IDLE
        }

        _avatarState.value = mappedState

        if (voiceState == RafelVoiceState.INTERRUPTED) {
            lipSyncController.reset()
            _emotionState.value = EmotionState(AvatarEmotion.SURPRISED, 0.6f)
        } else {
            // Update context-aware emotion
            val newEmotion = EmotionController.analyzeEmotion(lastUserText, lastAssistantText, voiceState)
            _emotionState.value = newEmotion
        }
    }

    fun onUserSpeechDetected(text: String?) {
        lastUserText = text
    }

    fun onAssistantSpeechGenerated(text: String?) {
        lastAssistantText = text
        val newEmotion = EmotionController.analyzeEmotion(
            lastUserText,
            lastAssistantText,
            RafelVoiceState.AI_SPEAKING
        )
        _emotionState.value = newEmotion
    }

    fun onAudioChunk(pcmData: ByteArray) {
        lipSyncController.processAudioChunk(pcmData)
    }

    fun setAvatarState(state: AvatarState) {
        _avatarState.value = state
    }

    fun setEmotion(emotion: AvatarEmotion, intensity: Float = 0.7f) {
        _emotionState.value = EmotionState(emotion, intensity.coerceIn(0f, 1f))
        // If currently in an idle or relaxed state, mirror the state
        if (currentVoiceState == RafelVoiceState.IDLE) {
            _avatarState.value = when (emotion) {
                AvatarEmotion.HAPPY -> AvatarState.HAPPY
                AvatarEmotion.CONCERNED -> AvatarState.CONCERNED
                AvatarEmotion.SURPRISED -> AvatarState.SURPRISED
                AvatarEmotion.CONFUSED -> AvatarState.CONFUSED
                AvatarEmotion.SAD -> AvatarState.SAD
                AvatarEmotion.ANGRY -> AvatarState.ANGRY
                AvatarEmotion.NEUTRAL -> AvatarState.IDLE
            }
        }
    }

    fun reset() {
        lipSyncController.reset()
        _avatarState.value = AvatarState.IDLE
        _emotionState.value = EmotionState(AvatarEmotion.NEUTRAL, 0.5f)
    }
}
