package com.example.avatar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * High-level Composable integrating RafelAvatarController and the active AvatarRenderer.
 */
@Composable
fun RafelAvatarView(
    controller: RafelAvatarController = RafelAvatarController.shared,
    modifier: Modifier = Modifier
) {
    val avatarState by controller.avatarState.collectAsState()
    val emotionState by controller.emotionState.collectAsState()
    val mouthOpenness by controller.mouthOpenness.collectAsState()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        controller.renderer.Render(
            avatarState = avatarState,
            emotionState = emotionState,
            mouthOpenness = mouthOpenness,
            modifier = Modifier.fillMaxSize()
        )
    }
}
