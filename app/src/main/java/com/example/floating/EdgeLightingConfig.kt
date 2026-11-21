package com.example.floating

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class EdgePosition(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
    BOTH("Both"),
    FULL_FRAME("Full Frame")
}

enum class EdgeGradientMode(val label: String) {
    SINGLE("Single Color"),
    TWO_COLOR("Two Color Gradient"),
    THREE_COLOR("Three Color Gradient")
}

enum class EdgeAnimationType(val label: String) {
    STATIC("Static"),
    SLOW_PULSE("Pulse"),
    FLOW("Flow"),
    WAVE("Wave"),
    ENERGY("Energy"),
    VOICE_REACTIVE("Voice Reactive"),
    LISTENING_REACTIVE("Listening Reactive")
}

data class EdgeColorPreset(
    val name: String,
    val primaryColor: Long,
    val secondaryColor: Long,
    val tertiaryColor: Long
)

object EdgeColorPresets {
    val Pink = EdgeColorPreset("Pink", 0xFFFF2A85, 0xFFE040FB, 0xFF7C4DFF)
    val Purple = EdgeColorPreset("Purple", 0xFF7C4DFF, 0xFF651FFF, 0xFF00E5FF)
    val Blue = EdgeColorPreset("Blue", 0xFF2979FF, 0xFF3D5AFE, 0xFF7C4DFF)
    val Cyan = EdgeColorPreset("Cyan", 0xFF00E5FF, 0xFF00B0FF, 0xFF2979FF)
    val Red = EdgeColorPreset("Red", 0xFFFF1744, 0xFFFF5252, 0xFFFF9100)
    val Orange = EdgeColorPreset("Orange", 0xFFFF9100, 0xFFFFAB40, 0xFFFFD700)
    val Green = EdgeColorPreset("Green", 0xFF00E676, 0xFF69F0AE, 0xFF00E5FF)
    val White = EdgeColorPreset("White", 0xFFFFFFFF, 0xFFE0E0E0, 0xFFB0BEC5)

    val allPresets = listOf(Pink, Purple, Blue, Cyan, Red, Orange, Green, White)
}

/**
 * Visual Style definition for Edge Lighting cards.
 */
data class EdgeStyleCard(
    val id: String,
    val styleNumber: String,
    val name: String,
    val description: String,
    val defaultGradientMode: EdgeGradientMode,
    val defaultAnim: EdgeAnimationType,
    val primaryColor: Long,
    val secondaryColor: Long,
    val tertiaryColor: Long,
    val glow: Float,
    val thickness: Float,
    val speed: Float,
    val voiceReactive: Boolean = false
)

object EdgeStyleCards {
    val Style01NeonPulse = EdgeStyleCard(
        id = "style_01",
        styleNumber = "STYLE 01",
        name = "Neon Pulse",
        description = "Intense pulsing neon edges with high vibrancy",
        defaultGradientMode = EdgeGradientMode.TWO_COLOR,
        defaultAnim = EdgeAnimationType.SLOW_PULSE,
        primaryColor = 0xFFFF2A85,
        secondaryColor = 0xFF7C4DFF,
        tertiaryColor = 0xFF00E5FF,
        glow = 0.80f,
        thickness = 3.0f,
        speed = 1.2f
    )

    val Style02CyberFlow = EdgeStyleCard(
        id = "style_02",
        styleNumber = "STYLE 02",
        name = "Cyber Flow",
        description = "Smooth cybernetic energy stream traveling vertically",
        defaultGradientMode = EdgeGradientMode.TWO_COLOR,
        defaultAnim = EdgeAnimationType.FLOW,
        primaryColor = 0xFF00E5FF,
        secondaryColor = 0xFF2979FF,
        tertiaryColor = 0xFF7C4DFF,
        glow = 0.65f,
        thickness = 2.4f,
        speed = 1.0f
    )

    val Style03Aurora = EdgeStyleCard(
        id = "style_03",
        styleNumber = "STYLE 03",
        name = "Aurora",
        description = "Breathtaking multi-spectrum northern lights bloom",
        defaultGradientMode = EdgeGradientMode.THREE_COLOR,
        defaultAnim = EdgeAnimationType.WAVE,
        primaryColor = 0xFF00E676,
        secondaryColor = 0xFF00E5FF,
        tertiaryColor = 0xFF7C4DFF,
        glow = 0.85f,
        thickness = 2.8f,
        speed = 0.8f
    )

    val Style04EnergyWave = EdgeStyleCard(
        id = "style_04",
        styleNumber = "STYLE 04",
        name = "Energy Wave",
        description = "Dynamic rhythmic energy crests surging up the edges",
        defaultGradientMode = EdgeGradientMode.TWO_COLOR,
        defaultAnim = EdgeAnimationType.ENERGY,
        primaryColor = 0xFFFF9100,
        secondaryColor = 0xFFFF1744,
        tertiaryColor = 0xFFFFD700,
        glow = 0.70f,
        thickness = 3.2f,
        speed = 1.5f
    )

    val Style05DualNeon = EdgeStyleCard(
        id = "style_05",
        styleNumber = "STYLE 05",
        name = "Dual Neon",
        description = "Left pink/purple and right cyan/blue signature glow",
        defaultGradientMode = EdgeGradientMode.TWO_COLOR,
        defaultAnim = EdgeAnimationType.FLOW,
        primaryColor = 0xFFFF2A85,
        secondaryColor = 0xFF7C4DFF,
        tertiaryColor = 0xFF00E5FF,
        glow = 0.65f,
        thickness = 2.2f,
        speed = 1.0f
    )

    val Style06GalaxyEdge = EdgeStyleCard(
        id = "style_06",
        styleNumber = "STYLE 06",
        name = "Galaxy Edge",
        description = "Deep cosmos purples with starlight white blooms",
        defaultGradientMode = EdgeGradientMode.THREE_COLOR,
        defaultAnim = EdgeAnimationType.WAVE,
        primaryColor = 0xFF7C4DFF,
        secondaryColor = 0xFFE040FB,
        tertiaryColor = 0xFFFFFFFF,
        glow = 0.75f,
        thickness = 2.6f,
        speed = 0.9f
    )

    val Style07MinimalGlow = EdgeStyleCard(
        id = "style_07",
        styleNumber = "STYLE 07",
        name = "Minimal Glow",
        description = "Crisp razor-thin border with refined ambient bleed",
        defaultGradientMode = EdgeGradientMode.SINGLE,
        defaultAnim = EdgeAnimationType.SLOW_PULSE,
        primaryColor = 0xFFFFFFFF,
        secondaryColor = 0xFFFFFFFF,
        tertiaryColor = 0xFF00E5FF,
        glow = 0.35f,
        thickness = 1.5f,
        speed = 0.7f
    )

    val Style08VoiceReactive = EdgeStyleCard(
        id = "style_08",
        styleNumber = "STYLE 08",
        name = "Voice Reactive",
        description = "Real-time acoustic illumination responding to voice & speech",
        defaultGradientMode = EdgeGradientMode.TWO_COLOR,
        defaultAnim = EdgeAnimationType.VOICE_REACTIVE,
        primaryColor = 0xFFFF2A85,
        secondaryColor = 0xFF00E5FF,
        tertiaryColor = 0xFF7C4DFF,
        glow = 0.75f,
        thickness = 2.8f,
        speed = 1.2f,
        voiceReactive = true
    )

    val allStyles = listOf(
        Style01NeonPulse,
        Style02CyberFlow,
        Style03Aurora,
        Style04EnergyWave,
        Style05DualNeon,
        Style06GalaxyEdge,
        Style07MinimalGlow,
        Style08VoiceReactive
    )
}

/**
 * Saved user preset model.
 */
data class SavedEdgePreset(
    val id: String,
    val name: String,
    val config: EdgeLightingConfig
)

/**
 * Configuration data class for Rafel's customizable Edge Lighting.
 */
data class EdgeLightingConfig(
    val enabled: Boolean = true,
    val position: EdgePosition = EdgePosition.BOTH,
    val primaryColor: Long = 0xFFFF2A85, // Default Left: Pink/Magenta
    val secondaryColor: Long = 0xFF7C4DFF, // Default Purple
    val tertiaryColor: Long = 0xFF00E5FF, // Default Right: Cyan
    val gradientMode: EdgeGradientMode = EdgeGradientMode.TWO_COLOR,
    val brightness: Float = 0.85f, // 0.0f .. 1.0f
    val glow: Float = 0.65f, // 0.0f .. 1.0f (bloom width / intensity)
    val thickness: Float = 2.5f, // 1.0f .. 8.0f dp
    val edgeDistance: Float = 0.0f, // 0.0f .. 24.0f dp inward
    val animationType: EdgeAnimationType = EdgeAnimationType.SLOW_PULSE,
    val animationSpeed: Float = 1.0f, // 0.3f .. 2.5f
    val cornerGlow: Boolean = true,
    val voiceReactive: Boolean = true,
    // Advanced controls
    val glowSoftness: Float = 0.60f, // 0.0f .. 1.0f
    val particleAmount: Float = 0.40f, // 0.0f .. 1.0f
    val fadeLength: Float = 0.50f // 0.0f .. 1.0f
) {
    companion object {
        val DEFAULT = EdgeLightingConfig()
    }
}

/**
 * Singleton manager for persisting and observing Edge Lighting settings and user presets.
 */
object EdgeLightingPreferences {
    private const val PREFS_NAME = "RafelEdgeLightingPrefs"
    private const val KEY_ENABLED = "edge_lighting_enabled"
    private const val KEY_POSITION = "edge_position"
    private const val KEY_PRIMARY_COLOR = "primary_color"
    private const val KEY_SECONDARY_COLOR = "secondary_color"
    private const val KEY_TERTIARY_COLOR = "tertiary_color"
    private const val KEY_GRADIENT_MODE = "gradient_mode"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_GLOW = "glow"
    private const val KEY_THICKNESS = "thickness"
    private const val KEY_DISTANCE = "edge_distance"
    private const val KEY_ANIMATION_TYPE = "animation_type"
    private const val KEY_ANIMATION_SPEED = "animation_speed"
    private const val KEY_CORNER_GLOW = "corner_glow"
    private const val KEY_VOICE_REACTIVE = "voice_reactive"
    private const val KEY_GLOW_SOFTNESS = "glow_softness"
    private const val KEY_PARTICLE_AMOUNT = "particle_amount"
    private const val KEY_FADE_LENGTH = "fade_length"
    private const val KEY_SAVED_PRESETS_JSON = "saved_presets_json"

    private val _configFlow = MutableStateFlow(EdgeLightingConfig.DEFAULT)
    val configFlow: StateFlow<EdgeLightingConfig> = _configFlow.asStateFlow()

    private val _presetsFlow = MutableStateFlow<List<SavedEdgePreset>>(emptyList())
    val presetsFlow: StateFlow<List<SavedEdgePreset>> = _presetsFlow.asStateFlow()

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        _configFlow.value = loadFromPrefs(context)
        _presetsFlow.value = loadPresetsFromPrefs(context)
        isInitialized = true
    }

    fun getCurrentConfig(context: Context): EdgeLightingConfig {
        if (!isInitialized) {
            init(context)
        }
        return _configFlow.value
    }

    fun updateConfig(context: Context, newConfig: EdgeLightingConfig) {
        _configFlow.value = newConfig
        saveToPrefs(context, newConfig)
    }

    fun resetToDefaults(context: Context) {
        val defaultConfig = EdgeLightingConfig.DEFAULT
        _configFlow.value = defaultConfig
        saveToPrefs(context, defaultConfig)
    }

    fun getSavedPresets(context: Context): List<SavedEdgePreset> {
        if (!isInitialized) init(context)
        return _presetsFlow.value
    }

    fun saveCustomPreset(context: Context, name: String, config: EdgeLightingConfig): SavedEdgePreset {
        val newPreset = SavedEdgePreset(
            id = "preset_${System.currentTimeMillis()}",
            name = name,
            config = config
        )
        val updatedList = _presetsFlow.value + newPreset
        _presetsFlow.value = updatedList
        savePresetsToPrefs(context, updatedList)
        return newPreset
    }

    fun renamePreset(context: Context, presetId: String, newName: String) {
        val updatedList = _presetsFlow.value.map {
            if (it.id == presetId) it.copy(name = newName) else it
        }
        _presetsFlow.value = updatedList
        savePresetsToPrefs(context, updatedList)
    }

    fun deletePreset(context: Context, presetId: String) {
        val updatedList = _presetsFlow.value.filterNot { it.id == presetId }
        _presetsFlow.value = updatedList
        savePresetsToPrefs(context, updatedList)
    }

    private fun loadFromPrefs(context: Context): EdgeLightingConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            EdgeLightingConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                position = EdgePosition.valueOf(prefs.getString(KEY_POSITION, EdgePosition.BOTH.name) ?: EdgePosition.BOTH.name),
                primaryColor = prefs.getLong(KEY_PRIMARY_COLOR, 0xFFFF2A85),
                secondaryColor = prefs.getLong(KEY_SECONDARY_COLOR, 0xFF7C4DFF),
                tertiaryColor = prefs.getLong(KEY_TERTIARY_COLOR, 0xFF00E5FF),
                gradientMode = EdgeGradientMode.valueOf(prefs.getString(KEY_GRADIENT_MODE, EdgeGradientMode.TWO_COLOR.name) ?: EdgeGradientMode.TWO_COLOR.name),
                brightness = prefs.getFloat(KEY_BRIGHTNESS, 0.85f),
                glow = prefs.getFloat(KEY_GLOW, 0.65f),
                thickness = prefs.getFloat(KEY_THICKNESS, 2.5f),
                edgeDistance = prefs.getFloat(KEY_DISTANCE, 0.0f),
                animationType = EdgeAnimationType.valueOf(prefs.getString(KEY_ANIMATION_TYPE, EdgeAnimationType.SLOW_PULSE.name) ?: EdgeAnimationType.SLOW_PULSE.name),
                animationSpeed = prefs.getFloat(KEY_ANIMATION_SPEED, 1.0f),
                cornerGlow = prefs.getBoolean(KEY_CORNER_GLOW, true),
                voiceReactive = prefs.getBoolean(KEY_VOICE_REACTIVE, true),
                glowSoftness = prefs.getFloat(KEY_GLOW_SOFTNESS, 0.60f),
                particleAmount = prefs.getFloat(KEY_PARTICLE_AMOUNT, 0.40f),
                fadeLength = prefs.getFloat(KEY_FADE_LENGTH, 0.50f)
            )
        } catch (e: Exception) {
            EdgeLightingConfig.DEFAULT
        }
    }

    private fun saveToPrefs(context: Context, config: EdgeLightingConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_POSITION, config.position.name)
            putLong(KEY_PRIMARY_COLOR, config.primaryColor)
            putLong(KEY_SECONDARY_COLOR, config.secondaryColor)
            putLong(KEY_TERTIARY_COLOR, config.tertiaryColor)
            putString(KEY_GRADIENT_MODE, config.gradientMode.name)
            putFloat(KEY_BRIGHTNESS, config.brightness)
            putFloat(KEY_GLOW, config.glow)
            putFloat(KEY_THICKNESS, config.thickness)
            putFloat(KEY_DISTANCE, config.edgeDistance)
            putString(KEY_ANIMATION_TYPE, config.animationType.name)
            putFloat(KEY_ANIMATION_SPEED, config.animationSpeed)
            putBoolean(KEY_CORNER_GLOW, config.cornerGlow)
            putBoolean(KEY_VOICE_REACTIVE, config.voiceReactive)
            putFloat(KEY_GLOW_SOFTNESS, config.glowSoftness)
            putFloat(KEY_PARTICLE_AMOUNT, config.particleAmount)
            putFloat(KEY_FADE_LENGTH, config.fadeLength)
            apply()
        }
    }

    private fun loadPresetsFromPrefs(context: Context): List<SavedEdgePreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SAVED_PRESETS_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            // Provide sensible initial presets
            return listOf(
                SavedEdgePreset(
                    id = "preset_default_1",
                    name = "Pink Cyber",
                    config = EdgeLightingConfig(
                        primaryColor = 0xFFFF2A85,
                        secondaryColor = 0xFF7C4DFF,
                        tertiaryColor = 0xFF00E5FF,
                        gradientMode = EdgeGradientMode.TWO_COLOR,
                        animationType = EdgeAnimationType.FLOW,
                        glow = 0.70f,
                        thickness = 2.6f
                    )
                ),
                SavedEdgePreset(
                    id = "preset_default_2",
                    name = "Blue Energy",
                    config = EdgeLightingConfig(
                        primaryColor = 0xFF00E5FF,
                        secondaryColor = 0xFF2979FF,
                        tertiaryColor = 0xFF7C4DFF,
                        gradientMode = EdgeGradientMode.TWO_COLOR,
                        animationType = EdgeAnimationType.ENERGY,
                        glow = 0.80f,
                        thickness = 3.0f
                    )
                ),
                SavedEdgePreset(
                    id = "preset_default_3",
                    name = "Rafel Neon",
                    config = EdgeLightingConfig.DEFAULT
                )
            )
        }
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<SavedEdgePreset>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cfgObj = obj.getJSONObject("config")
                val cfg = EdgeLightingConfig(
                    enabled = cfgObj.optBoolean("enabled", true),
                    position = EdgePosition.valueOf(cfgObj.optString("position", EdgePosition.BOTH.name)),
                    primaryColor = cfgObj.optLong("primaryColor", 0xFFFF2A85),
                    secondaryColor = cfgObj.optLong("secondaryColor", 0xFF7C4DFF),
                    tertiaryColor = cfgObj.optLong("tertiaryColor", 0xFF00E5FF),
                    gradientMode = EdgeGradientMode.valueOf(cfgObj.optString("gradientMode", EdgeGradientMode.TWO_COLOR.name)),
                    brightness = cfgObj.optDouble("brightness", 0.85).toFloat(),
                    glow = cfgObj.optDouble("glow", 0.65).toFloat(),
                    thickness = cfgObj.optDouble("thickness", 2.5).toFloat(),
                    edgeDistance = cfgObj.optDouble("edgeDistance", 0.0).toFloat(),
                    animationType = EdgeAnimationType.valueOf(cfgObj.optString("animationType", EdgeAnimationType.SLOW_PULSE.name)),
                    animationSpeed = cfgObj.optDouble("animationSpeed", 1.0).toFloat(),
                    cornerGlow = cfgObj.optBoolean("cornerGlow", true),
                    voiceReactive = cfgObj.optBoolean("voiceReactive", true),
                    glowSoftness = cfgObj.optDouble("glowSoftness", 0.60).toFloat(),
                    particleAmount = cfgObj.optDouble("particleAmount", 0.40).toFloat(),
                    fadeLength = cfgObj.optDouble("fadeLength", 0.50).toFloat()
                )
                list.add(SavedEdgePreset(id = obj.getString("id"), name = obj.getString("name"), config = cfg))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePresetsToPrefs(context: Context, presets: List<SavedEdgePreset>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val array = JSONArray()
            for (p in presets) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                val cfgObj = JSONObject()
                cfgObj.put("enabled", p.config.enabled)
                cfgObj.put("position", p.config.position.name)
                cfgObj.put("primaryColor", p.config.primaryColor)
                cfgObj.put("secondaryColor", p.config.secondaryColor)
                cfgObj.put("tertiaryColor", p.config.tertiaryColor)
                cfgObj.put("gradientMode", p.config.gradientMode.name)
                cfgObj.put("brightness", p.config.brightness.toDouble())
                cfgObj.put("glow", p.config.glow.toDouble())
                cfgObj.put("thickness", p.config.thickness.toDouble())
                cfgObj.put("edgeDistance", p.config.edgeDistance.toDouble())
                cfgObj.put("animationType", p.config.animationType.name)
                cfgObj.put("animationSpeed", p.config.animationSpeed.toDouble())
                cfgObj.put("cornerGlow", p.config.cornerGlow)
                cfgObj.put("voiceReactive", p.config.voiceReactive)
                cfgObj.put("glowSoftness", p.config.glowSoftness.toDouble())
                cfgObj.put("particleAmount", p.config.particleAmount.toDouble())
                cfgObj.put("fadeLength", p.config.fadeLength.toDouble())
                obj.put("config", cfgObj)
                array.put(obj)
            }
            prefs.edit().putString(KEY_SAVED_PRESETS_JSON, array.toString()).apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
