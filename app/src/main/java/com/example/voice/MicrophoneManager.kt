package com.example.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust Microphone Manager for Rafel.
 *
 * Responsibilities:
 * 1. Safe thread-safe acquisition and lifecycle of AudioRecord.
 * 2. Strict single-instance guard.
 * 3. Integrates hardware Acoustic Echo Cancellation (AEC) and Noise Suppression (NS) when supported.
 * 4. Error isolation to ensure mic failures never crash the host process.
 * 5. Audio data dispatch to VAD and streaming pipeline.
 */
class MicrophoneManager(
    private val context: Context,
    private val onAudioChunkRead: (buffer: ShortArray, length: Int) -> Unit,
    private val onError: (String) -> Unit,
    private val onAmplitudeChanged: ((Float) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val isRecording = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null

    fun setMuted(muted: Boolean) {
        val wasMuted = isMuted.getAndSet(muted)
        if (wasMuted != muted) {
            RafelLogger.logMic("Microphone muted changed: $wasMuted -> $muted")
            if (muted) {
                onAmplitudeChanged?.invoke(0f)
            }
        }
    }

    fun isMuted(): Boolean = isMuted.get()

    @Synchronized
    fun start(): Boolean {
        if (isRecording.get()) {
            RafelLogger.logMic("Microphone is already active.")
            return true
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RafelLogger.logError("Missing RECORD_AUDIO permission.")
            onError("Microphone permission denied.")
            return false
        }

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                VoiceConfig.SAMPLE_RATE_IN,
                VoiceConfig.CHANNEL_IN,
                VoiceConfig.AUDIO_FORMAT
            )
            val bufferSize = if (minBufSize > 0) minBufSize * 4 else 8192

            val recordCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    context.createAttributionContext("rafel_audio")
                } catch (e: Exception) {
                    context
                }
            } else {
                context
            }

            audioRecord = AudioRecord.Builder()
                .setContext(recordCtx)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(VoiceConfig.SAMPLE_RATE_IN)
                        .setChannelMask(VoiceConfig.CHANNEL_IN)
                        .setEncoding(VoiceConfig.AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            val record = audioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                RafelLogger.logError("AudioRecord initialization failed.")
                releaseResources()
                onError("Microphone hardware failed to initialize.")
                return false
            }

            // Enable hardware echo cancellation and noise suppressor if supported
            attachAudioEffects(record.audioSessionId)

            // Inspect and log input audio device
            logInputDevice(record)

            record.startRecording()
            isRecording.set(true)
            RafelLiveDiagnostics.micStarted.value = true
            RafelLiveDiagnostics.log("MIC_STARTED")
            RafelLogger.trackMicStart()
            RafelLogger.logRafelVoice("MIC_STARTED", "sampleRate=16000 channels=1 encoding=PCM_16BIT")
            RafelLogger.logVoice("microphone started (16kHz, mono)")
            RafelLogger.logMic("Microphone started recording at ${VoiceConfig.SAMPLE_RATE_IN}Hz.")

            launchReadLoop(record)
            return true
        } catch (e: SecurityException) {
            RafelLogger.logError("SecurityException while starting microphone", e)
            onError("Microphone permission error.")
            releaseResources()
            return false
        } catch (e: Exception) {
            RafelLogger.logError("Exception while starting microphone", e)
            onError("Microphone unavailable.")
            releaseResources()
            return false
        }
    }

    private fun logInputDevice(record: AudioRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val device = record.routedDevice
                val devType = getDeviceTypeName(device?.type)
                val devName = device?.productName?.toString() ?: "Default Builtin Mic"
                val devId = device?.id ?: 0
                RafelLogger.logInputDevice(type = devType, name = devName, id = devId)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    record.addOnRoutingChangedListener({ audRec ->
                        val rDevice = audRec.routedDevice
                        val rType = getDeviceTypeName(rDevice?.type)
                        val rName = rDevice?.productName?.toString() ?: "Unknown"
                        val rId = rDevice?.id ?: 0
                        RafelLogger.logInputDevice(type = rType, name = rName, id = rId)
                    }, null)
                }
            } catch (e: Exception) {
                RafelLogger.logInputDevice(type = "BUILTIN_MIC", name = "Default Builtin Mic", id = 0)
            }
        } else {
            RafelLogger.logInputDevice(type = "BUILTIN_MIC", name = "Default Builtin Mic", id = 0)
        }
    }

    private fun getDeviceTypeName(type: Int?): String {
        return when (type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            null -> "DEFAULT_MIC"
            else -> "TYPE_$type"
        }
    }

    private fun launchReadLoop(record: AudioRecord) {
        recordingJob = scope.launch {
            val chunkSize = VoiceConfig.CHUNK_SIZE_SAMPLES
            val buffer = ShortArray(chunkSize)

            while (isActive && isRecording.get()) {
                try {
                    val readResult = record.read(buffer, 0, chunkSize)
                    if (readResult > 0) {
                        var maxAmp = 0
                        for (i in 0 until readResult) {
                            val abs = kotlin.math.abs(buffer[i].toInt())
                            if (abs > maxAmp) maxAmp = abs
                        }
                        val currentlyMuted = isMuted.get()
                        if (maxAmp == 0) {
                            kotlinx.coroutines.delay(20)
                        }
                        if (currentlyMuted) {
                            // When muted, drop active conversation audio chunks and report zero amplitude
                            onAmplitudeChanged?.invoke(0f)
                        } else {
                            // Compute normalized amplitude (0f - 1f) for visualizer
                            val normAmp = (maxAmp / 16000f).coerceIn(0f, 1f)
                            onAmplitudeChanged?.invoke(normAmp)

                            if (maxAmp > 0) {
                                onAudioChunkRead(buffer, readResult)
                            }
                        }
                    } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION || readResult == AudioRecord.ERROR_BAD_VALUE) {
                        RafelLogger.logError("AudioRecord read error code: $readResult")
                    }
                } catch (e: Exception) {
                    RafelLogger.logError("Error in microphone read loop", e)
                }
            }
            RafelLogger.logMic("Microphone read loop finished.")
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    RafelLogger.logMic("AcousticEchoCanceler enabled.")
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    RafelLogger.logMic("NoiseSuppressor enabled.")
                }
            }
        } catch (e: Exception) {
            RafelLogger.logError("Failed to configure audio effects", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            RafelLogger.logMic("Microphone stopped.")
        } catch (e: Exception) {
            RafelLogger.logError("Error stopping AudioRecord", e)
        } finally {
            releaseResources()
        }
    }

    private fun releaseResources() {
        try {
            echoCanceler?.release()
        } catch (e: Exception) {}
        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {}
        noiseSuppressor = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }

    fun isRunning(): Boolean = isRecording.get()
}
