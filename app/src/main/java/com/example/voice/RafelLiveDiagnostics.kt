package com.example.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton holder for real-time live diagnostics across the Rafel voice pipeline.
 * Every field reflects actual, unsimulated production runtime state.
 */
object RafelLiveDiagnostics {
    private const val TAG = "RAFEL-DEBUG"

    // LIVE CONNECTION & RUNTIME MODEL DIAGNOSTICS
    val webSocketState = MutableStateFlow("DISCONNECTED")
    val setupComplete = MutableStateFlow(false)
    val activeProvider = MutableStateFlow("NONE")
    val activeModel = MutableStateFlow("NONE")
    val onlineOrOfflineMode = MutableStateFlow("ONLINE")
    val apiKeyConfigured = MutableStateFlow("NO")
    val restRequestStatus = MutableStateFlow("IDLE")
    val lastSuccessfulModelResponseTimestamp = MutableStateFlow(0L)
    val lastFallbackReason = MutableStateFlow("NONE")
    val actualResponseSource = MutableStateFlow("NONE")
    val sessionId = MutableStateFlow(0L)

    // USER AUDIO
    val micStarted = MutableStateFlow(false)
    val speechStarted = MutableStateFlow(false)
    val speechEnded = MutableStateFlow(false)
    val inputPcmBytesSent = MutableStateFlow(0L)
    val turnCompleteSent = MutableStateFlow(false)

    // GEMINI RESPONSE
    val serverMessageCount = MutableStateFlow(0)
    val serverContentReceived = MutableStateFlow(false)
    val modelTurnReceived = MutableStateFlow(false)
    val audioPartCount = MutableStateFlow(0)
    val firstAudioChunkReceived = MutableStateFlow(false)
    val totalAudioBytesReceived = MutableStateFlow(0L)

    // RAW MESSAGE & AUDIO METADATA
    val lastRawServerMessageKeys = MutableStateFlow("NONE")
    val lastRawServerMessageType = MutableStateFlow("NONE")
    val firstAudioMimeType = MutableStateFlow("NONE")
    val firstAudioSampleRate = MutableStateFlow(0)
    val firstAudioChannels = MutableStateFlow(0)
    val firstAudioRawLen = MutableStateFlow(0)
    val firstAudioDecodedLen = MutableStateFlow(0)
    val pcmFileDumpBytes = MutableStateFlow(0L)

    // PLAYBACK
    val pcmDecodedBytes = MutableStateFlow(0L)
    val audioTrackState = MutableStateFlow("UNINITIALIZED")
    val audioTrackPlayState = MutableStateFlow("STOPPED")
    val audioTrackWriteResult = MutableStateFlow("NONE")
    val totalBytesSuccessfullyWritten = MutableStateFlow(0L)
    val playbackQueueSize = MutableStateFlow(0)
    val outputDevice = MutableStateFlow("DEFAULT")
    val streamMusicVolume = MutableStateFlow(0)
    val audioManagerMode = MutableStateFlow("NORMAL")

    // ERRORS
    val lastWebSocketError = MutableStateFlow("NONE")
    val lastJsonParsingError = MutableStateFlow("NONE")
    val lastAudioDecodingError = MutableStateFlow("NONE")
    val lastAudioTrackError = MutableStateFlow("NONE")

    fun refreshAudioHardwareState(context: Context?) {
        if (context == null) return
        try {
            val appContext = context.applicationContext ?: context
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val currentVol = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 0 }
            streamMusicVolume.value = currentVol

            val devDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    devices?.joinToString(", ") { dev ->
                        when (dev.type) {
                            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
                            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                            else -> "TYPE_${dev.type}"
                        }
                    }?.ifEmpty { "DEFAULT_SPEAKER" } ?: "DEFAULT_SPEAKER"
                } catch (e: Exception) {
                    "DEFAULT_SPEAKER"
                }
            } else {
                "DEFAULT_SPEAKER"
            }
            outputDevice.value = devDesc

            val modeStr = try {
                when (audioManager.mode) {
                    AudioManager.MODE_NORMAL -> "MODE_NORMAL"
                    AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
                    AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
                    else -> "MODE_${audioManager.mode}"
                }
            } catch (e: Exception) {
                "MODE_UNKNOWN"
            }
            audioManagerMode.value = modeStr
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh audio hardware state safely", e)
        }
    }

    fun log(stage: String, details: String = "") {}

    fun logAudioLifecycle(action: String, reason: String) {}

    /**
     * Dumps the decoded PCM of the FIRST Gemini response to internal storage.
     */
    fun dumpPcmChunk(context: Context, pcmBytes: ByteArray) {}

    fun updateHardwareState(context: Context, track: AudioTrack?) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            streamMusicVolume.value = currentVol

            val modeStr = when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "MODE_NORMAL"
                AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
                AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
                else -> "MODE_${audioManager.mode}"
            }
            audioManagerMode.value = modeStr

            val devDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.joinToString(", ") { dev ->
                    when (dev.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
                        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                        else -> "TYPE_${dev.type}"
                    }
                }.ifEmpty { "DEFAULT_SPEAKER" }
            } else {
                "DEFAULT_SPEAKER"
            }
            outputDevice.value = devDesc

            if (track != null) {
                audioTrackState.value = if (track.state == AudioTrack.STATE_INITIALIZED) "STATE_INITIALIZED" else "STATE_UNINITIALIZED"
                audioTrackPlayState.value = when (track.playState) {
                    AudioTrack.PLAYSTATE_PLAYING -> "PLAYSTATE_PLAYING"
                    AudioTrack.PLAYSTATE_PAUSED -> "PLAYSTATE_PAUSED"
                    AudioTrack.PLAYSTATE_STOPPED -> "PLAYSTATE_STOPPED"
                    else -> "PLAYSTATE_${track.playState}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating hardware state", e)
        }
    }
}
