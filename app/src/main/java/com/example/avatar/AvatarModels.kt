package com.example.avatar

/**
 * High-level state of Rafel avatar.
 */
enum class AvatarState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    HAPPY,
    SAD,
    ANGRY,
    SURPRISED,
    CONCERNED,
    CONFUSED
}

/**
 * Expressive emotions supported by Rafel.
 */
enum class AvatarEmotion {
    NEUTRAL,
    HAPPY,
    SAD,
    ANGRY,
    SURPRISED,
    CONFUSED,
    CONCERNED
}

/**
 * Encapsulates an active emotion with its intensity [0f..1f].
 */
data class EmotionState(
    val emotion: AvatarEmotion = AvatarEmotion.NEUTRAL,
    val intensity: Float = 0.5f
)
