package com.example.avatar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Interface decoupling the high-level avatar controller from the rendering implementation.
 *
 * Current implementation: VectorLayerAvatarRenderer (Anime Rafel with layered expressive features).
 * Future implementation: Live2DAvatarRenderer (Live2D Cubism SDK integration) without rewriting controller or AI logic.
 */
interface AvatarRenderer {
    @Composable
    fun Render(
        avatarState: AvatarState,
        emotionState: EmotionState,
        mouthOpenness: Float,
        modifier: Modifier
    )
}
