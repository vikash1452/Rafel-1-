package com.example.floating

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EdgeLightingPreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        EdgeLightingPreferences.init(context)
        EdgeLightingPreferences.resetToDefaults(context)
    }

    @Test
    fun defaultEdgeLightingConfigMatchesSpecification() {
        val config = EdgeLightingConfig.DEFAULT
        assertTrue(config.enabled)
        assertEquals(EdgePosition.BOTH, config.position)
        assertEquals(0xFFFF2A85, config.primaryColor) // Pink/Magenta
        assertEquals(0xFF7C4DFF, config.secondaryColor) // Purple
        assertEquals(0xFF00E5FF, config.tertiaryColor) // Cyan
        assertEquals(EdgeGradientMode.TWO_COLOR, config.gradientMode)
        assertEquals(0.85f, config.brightness, 0.001f)
        assertEquals(0.65f, config.glow, 0.001f)
        assertEquals(2.5f, config.thickness, 0.001f)
        assertEquals(0.0f, config.edgeDistance, 0.001f)
        assertEquals(EdgeAnimationType.SLOW_PULSE, config.animationType)
        assertTrue(config.cornerGlow)
        assertTrue(config.voiceReactive)
    }

    @Test
    fun updateAndPersistEdgeLightingConfig() {
        val customConfig = EdgeLightingConfig(
            enabled = true,
            position = EdgePosition.FULL_FRAME,
            primaryColor = 0xFF00E5FF,
            secondaryColor = 0xFF2979FF,
            tertiaryColor = 0xFF7C4DFF,
            gradientMode = EdgeGradientMode.THREE_COLOR,
            brightness = 0.50f,
            glow = 0.90f,
            thickness = 4.0f,
            edgeDistance = 8.0f,
            animationType = EdgeAnimationType.FLOW,
            animationSpeed = 1.8f,
            cornerGlow = false,
            voiceReactive = false
        )

        EdgeLightingPreferences.updateConfig(context, customConfig)

        val retrieved = EdgeLightingPreferences.getCurrentConfig(context)
        assertEquals(EdgePosition.FULL_FRAME, retrieved.position)
        assertEquals(0xFF00E5FF, retrieved.primaryColor)
        assertEquals(0xFF2979FF, retrieved.secondaryColor)
        assertEquals(0xFF7C4DFF, retrieved.tertiaryColor)
        assertEquals(EdgeGradientMode.THREE_COLOR, retrieved.gradientMode)
        assertEquals(0.50f, retrieved.brightness, 0.001f)
        assertEquals(0.90f, retrieved.glow, 0.001f)
        assertEquals(4.0f, retrieved.thickness, 0.001f)
        assertEquals(8.0f, retrieved.edgeDistance, 0.001f)
        assertEquals(EdgeAnimationType.FLOW, retrieved.animationType)
        assertEquals(1.8f, retrieved.animationSpeed, 0.001f)
        assertFalse(retrieved.cornerGlow)
        assertFalse(retrieved.voiceReactive)
    }

    @Test
    fun resetToDefaultsRestoresInitialState() {
        val modified = EdgeLightingConfig(
            enabled = false,
            position = EdgePosition.LEFT,
            brightness = 0.2f
        )
        EdgeLightingPreferences.updateConfig(context, modified)
        assertEquals(EdgePosition.LEFT, EdgeLightingPreferences.getCurrentConfig(context).position)

        EdgeLightingPreferences.resetToDefaults(context)
        val afterReset = EdgeLightingPreferences.getCurrentConfig(context)
        assertTrue(afterReset.enabled)
        assertEquals(EdgePosition.BOTH, afterReset.position)
        assertEquals(0.85f, afterReset.brightness, 0.001f)
    }

    @Test
    fun colorPresetsIncludeAllRequiredPalettes() {
        val presets = EdgeColorPresets.allPresets
        assertEquals(8, presets.size)

        val presetNames = presets.map { it.name }
        assertTrue(presetNames.contains("Pink"))
        assertTrue(presetNames.contains("Purple"))
        assertTrue(presetNames.contains("Blue"))
        assertTrue(presetNames.contains("Cyan"))
        assertTrue(presetNames.contains("Red"))
        assertTrue(presetNames.contains("Orange"))
        assertTrue(presetNames.contains("Green"))
        assertTrue(presetNames.contains("White"))
    }

    @Test
    fun edgeStyleCardsContainAllEightStyles() {
        val styles = EdgeStyleCards.allStyles
        assertEquals(8, styles.size)
        val names = styles.map { it.name }
        assertTrue(names.contains("Neon Pulse"))
        assertTrue(names.contains("Cyber Flow"))
        assertTrue(names.contains("Aurora"))
        assertTrue(names.contains("Energy Wave"))
        assertTrue(names.contains("Dual Neon"))
        assertTrue(names.contains("Galaxy Edge"))
        assertTrue(names.contains("Minimal Glow"))
        assertTrue(names.contains("Voice Reactive"))
    }

    @Test
    fun customPresetPersistenceSupportsSaveRenameDelete() {
        val customStyle = EdgeLightingConfig(
            primaryColor = 0xFFFF2A85,
            secondaryColor = 0xFF00E5FF,
            animationType = EdgeAnimationType.ENERGY
        )
        val saved = EdgeLightingPreferences.saveCustomPreset(context, "Cyber Dream", customStyle)
        val presetsAfterSave = EdgeLightingPreferences.getSavedPresets(context)
        assertTrue(presetsAfterSave.any { it.name == "Cyber Dream" })

        EdgeLightingPreferences.renamePreset(context, saved.id, "Cyber Dream Renamed")
        val presetsAfterRename = EdgeLightingPreferences.getSavedPresets(context)
        assertTrue(presetsAfterRename.any { it.name == "Cyber Dream Renamed" })

        EdgeLightingPreferences.deletePreset(context, saved.id)
        val presetsAfterDelete = EdgeLightingPreferences.getSavedPresets(context)
        assertFalse(presetsAfterDelete.any { it.id == saved.id })
    }
}
