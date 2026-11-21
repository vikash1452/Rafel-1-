package com.example.avatar

import com.example.voice.RafelVoiceState
import java.util.Locale

/**
 * Detects context-aware emotion and intensity from user prompt, AI generated response,
 * and current voice state.
 *
 * Rules:
 * - Emotions are NEVER random.
 * - Transitions are based on semantic sentiment and assistant context.
 * - Uses intensity [0f..1f] for natural expressive blending.
 */
object EmotionController {

    fun analyzeEmotion(
        userMessage: String?,
        assistantResponse: String?,
        voiceState: RafelVoiceState
    ): EmotionState {
        // Base mapping when listening or waiting
        if (voiceState == RafelVoiceState.LISTENING || voiceState == RafelVoiceState.USER_SPEAKING) {
            return EmotionState(AvatarEmotion.NEUTRAL, 0.4f)
        }
        if (voiceState == RafelVoiceState.WAITING_FOR_AI) {
            return EmotionState(AvatarEmotion.CONFUSED, 0.35f) // Subtle thinking/attentive
        }

        val text = ((assistantResponse ?: "") + " " + (userMessage ?: "")).lowercase(Locale.ROOT)
        if (text.isBlank()) {
            return EmotionState(AvatarEmotion.NEUTRAL, 0.5f)
        }

        // Happy / Cheerful / Playful / Relieved
        if (text.contains("achha laga") || text.contains("wah") || text.contains("badhiya") ||
            text.contains("shabash") || text.contains("happy") || text.contains("great") ||
            text.contains("smile") || text.contains("kushi") || text.contains("khushi") ||
            text.contains("maza") || text.contains("congrats") || text.contains("mubarak") ||
            text.contains("mast") || text.contains("superb") || text.contains("yay") ||
            text.contains("love") || text.contains("awesome")
        ) {
            val strong = text.contains("bahut") || text.contains("awesome") || text.contains("superb")
            return EmotionState(AvatarEmotion.HAPPY, if (strong) 0.85f else 0.65f)
        }

        // Concerned / Empathetic / Supportive
        if (text.contains("mood kharab") || text.contains("problem") || text.contains("chinta") ||
            text.contains("pareshan") || text.contains("kya hua") || text.contains("thak") ||
            text.contains("batao main sun rahi") || text.contains("sad") || text.contains("tension") ||
            text.contains("dard") || text.contains("bura") || text.contains("help")
        ) {
            return EmotionState(AvatarEmotion.CONCERNED, 0.7f)
        }

        // Sad / Depressed
        if (text.contains("dukh") || text.contains("ro raha") || text.contains("udas") ||
            text.contains("fail") || text.contains("nuksan") || text.contains("chali gayi") ||
            text.contains("chhod")
        ) {
            return EmotionState(AvatarEmotion.SAD, 0.75f)
        }

        // Surprised / Astonished
        if (text.contains("kya sach mein") || text.contains("really") || text.contains("sach") ||
            text.contains("are baap re") || text.contains("omg") || text.contains("wow") ||
            text.contains("bakwas") || text.contains("unexpected")
        ) {
            return EmotionState(AvatarEmotion.SURPRISED, 0.8f)
        }

        // Confused / Clarifying
        if (text.contains("samajh nahi aaya") || text.contains("kaunsi") || text.contains("kya karna hai") ||
            text.contains("kya matlab") || text.contains("kya change") || text.contains("confused")
        ) {
            return EmotionState(AvatarEmotion.CONFUSED, 0.6f)
        }

        // Angry / Frustrated
        if (text.contains("gussa") || text.contains("hate") || text.contains("angry") ||
            text.contains("chup") || text.contains("shutup")
        ) {
            return EmotionState(AvatarEmotion.ANGRY, 0.65f)
        }

        // Default to warm neutral
        return EmotionState(AvatarEmotion.NEUTRAL, 0.5f)
    }
}
