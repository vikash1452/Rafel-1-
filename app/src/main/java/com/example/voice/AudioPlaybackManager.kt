package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Clean, reliable streaming audio playback manager for Rafel.
 *
 * Requirements:
 * - One AudioTrack instance per active response.
 * - MODE_STREAM, STREAM_MUSIC, 24kHz, 16-bit PCM Mono.
 * - Initializes and plays AudioTrack immediately on first audio chunk.
 * - Writes incoming chunks immediately without artificial delay or complicated buffering.
 * - Drains queued PCM to hardware on response complete, then stops and releases AudioTrack.
 * - Instant (<5ms) barge-in / cancellation on user speech onset.
 */
class AudioPlaybackManager(
    private val context: Context,
    private val onPlaybackStarted: () -> Unit,
    private val onPlaybackFinished: () -> Unit,
    private val onAudioPlayed: ((ByteArray) -> Unit)? = null,
    private val onAmplitudeChanged: ((Float) -> Unit)? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentTrack: AudioTrack? = null

    val activeGenerationId = AtomicLong(1L)
    private val lastCancelledGenerationId = AtomicLong(0L)

    private sealed class QueueItem {
        data class Chunk(val generationId: Long, val pcmData: ByteArray) : QueueItem()
        data class TurnComplete(val generationId: Long) : QueueItem()
    }

    private val audioQueue = LinkedBlockingQueue<QueueItem>(VoiceConfig.MAX_AUDIO_QUEUE_SIZE)
    private val isLoopRunning = AtomicBoolean(true)
    private val isCurrentlySpeaking = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    // Dedicated single-thread executor to run lip-sync and amplitude processing
    // completely off the realtime AudioTrack write thread.
    private val lipSyncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RafelLipSyncThread").apply { isDaemon = true }
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                interruptAndFlush()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try { currentTrack?.setVolume(0.2f) } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try { currentTrack?.setVolume(1.0f) } catch (_: Exception) {}
            }
        }
    }

    init {
        startPlaybackLoop()
    }

    private fun startPlaybackLoop() {
        playbackThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                RafelLogger.logError("Failed to set audio thread priority", e)
            }
            runLoop()
        }, "RafelAudioPlaybackThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop() {
        var currentGen = -1L
        var totalFramesWrittenThisGen = 0L
        var pendingLeftoverByte: Byte? = null
        var firstPcmWriteLogged = false

        while (isLoopRunning.get()) {
            try {
                val item = audioQueue.take()
                val cancelledGen = lastCancelledGenerationId.get()

                when (item) {
                    is QueueItem.Chunk -> {
                        if (item.generationId <= cancelledGen && cancelledGen > 0L) {
                            continue
                        }

                        // Align PCM 16-bit frame boundary (2 bytes per sample)
                        val alignedChunk = alignPcm(item.pcmData, pendingLeftoverByte) { leftover ->
                            pendingLeftoverByte = leftover
                        }
                        if (alignedChunk.isEmpty()) continue

                        // First chunk of this response generation:
                        if (item.generationId != currentGen || currentTrack == null) {
                            // Release any prior track
                            releaseTrackInternal()

                            currentGen = item.generationId
                            totalFramesWrittenThisGen = 0L
                            pendingLeftoverByte = null
                            firstPcmWriteLogged = false

                            // Ensure volume and request audio focus
                            ensureSpeakerOutput()
                            requestAudioFocus()

                            // Initialize fresh AudioTrack for this response
                            val track = createAudioTrack()
                            if (track == null) {
                                RafelLogger.logError("AudioTrack creation returned null or uninitialized")
                                continue
                            }
                            currentTrack = track

                            try {
                                track.play()
                                android.util.Log.i("RAFEL-AUDIO", "[AUDIO] AudioTrack started")
                                android.util.Log.i("RAFEL-AUDIO", "[AUDIO] playbackStarted")
                                android.util.Log.i("RAFEL-AUDIO", "[AUDIO] PLAYBACK_STARTED")
                                android.util.Log.i("RAFEL-DIAGNOSTIC", "AUDIO_PLAYBACK_STARTED: genId=${item.generationId}, sampleRate=24000, format=PCM_16BIT, state=${track.playState}")
                            } catch (e: Exception) {
                                RafelLogger.logError("AudioTrack.play() failed", e)
                                releaseTrackInternal()
                                continue
                            }

                            isCurrentlySpeaking.set(true)
                            onPlaybackStarted()
                        }

                        val track = currentTrack
                        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                            val written = writeToTrack(track, alignedChunk, item.generationId)
                            if (written > 0) {
                                if (!firstPcmWriteLogged) {
                                    firstPcmWriteLogged = true
                                    android.util.Log.i("RAFEL-AUDIO", "[AUDIO] first PCM write successful")
                                }
                                totalFramesWrittenThisGen += (written / 2).toLong()

                                // Offload lip-sync and visualizer processing completely off the AudioTrack write thread.
                                // AudioTrack.write() never waits for lip-sync processing.
                                val chunkCopy = alignedChunk.copyOf()
                                lipSyncExecutor.execute {
                                    onAudioPlayed?.invoke(chunkCopy)
                                    dispatchAmplitude(chunkCopy)
                                }
                            }
                        }
                    }

                    is QueueItem.TurnComplete -> {
                        if (item.generationId == currentGen && item.generationId > cancelledGen) {
                            val track = currentTrack
                            if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING && totalFramesWrittenThisGen > 0) {
                                drainTrack(track, totalFramesWrittenThisGen, item.generationId)
                            }
                            releaseTrackInternal()
                            currentGen = -1L
                            totalFramesWrittenThisGen = 0L
                            firstPcmWriteLogged = false
                            isCurrentlySpeaking.set(false)
                            onAmplitudeChanged?.invoke(0f)
                            abandonAudioFocus()
                            android.util.Log.i("RAFEL-AUDIO", "[AUDIO] playback completed")
                            android.util.Log.i("RAFEL-AUDIO", "[AUDIO] playbackCompleted")
                            android.util.Log.i("RAFEL-AUDIO", "[AUDIO] PLAYBACK_COMPLETED")
                            onPlaybackFinished()
                        }
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                RafelLogger.logError("Error in audio playback loop", e)
            }
        }
    }

    private fun writeToTrack(track: AudioTrack, chunk: ByteArray, genId: Long): Int {
        var written = 0
        while (written < chunk.size && isLoopRunning.get()) {
            if (genId <= lastCancelledGenerationId.get() && lastCancelledGenerationId.get() > 0L) {
                break
            }
            val toWrite = chunk.size - written
            val ret = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(chunk, written, toWrite, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(chunk, written, toWrite)
            }

            if (ret > 0) {
                written += ret
            } else if (ret == 0) {
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            } else {
                RafelLogger.logError("AudioTrack.write error code: $ret")
                break
            }
        }
        return written
    }

    private fun drainTrack(track: AudioTrack, targetFrames: Long, genId: Long) {
        val startTime = System.currentTimeMillis()
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val remainingFrames = (targetFrames - head).coerceAtLeast(0L)
        val expectedMs = (remainingFrames * 1000L / VoiceConfig.SAMPLE_RATE_OUT) + 60L
        val maxWaitMs = expectedMs.coerceIn(40L, 3500L)

        while (isLoopRunning.get() && (System.currentTimeMillis() - startTime < maxWaitMs)) {
            if (genId <= lastCancelledGenerationId.get() && lastCancelledGenerationId.get() > 0L) {
                break
            }
            val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (currentHead >= targetFrames) {
                break
            }
            try { Thread.sleep(15) } catch (_: InterruptedException) { break }
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        return try {
            val minBufSize = AudioTrack.getMinBufferSize(
                VoiceConfig.SAMPLE_RATE_OUT,
                VoiceConfig.CHANNEL_OUT,
                VoiceConfig.AUDIO_FORMAT
            )
            val bufferSize = if (minBufSize > 0) {
                maxOf(minBufSize * 2, VoiceConfig.AUDIOTRACK_BUFFER_BYTES)
            } else {
                VoiceConfig.AUDIOTRACK_BUFFER_BYTES
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()

            val format = android.media.AudioFormat.Builder()
                .setSampleRate(VoiceConfig.SAMPLE_RATE_OUT)
                .setChannelMask(VoiceConfig.CHANNEL_OUT)
                .setEncoding(VoiceConfig.AUDIO_FORMAT)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state == AudioTrack.STATE_INITIALIZED) track else {
                track.release()
                null
            }
        } catch (e: Exception) {
            RafelLogger.logError("AudioTrack creation exception", e)
            null
        }
    }

    private fun releaseTrackInternal() {
        currentTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                RafelLogger.logError("Error releasing AudioTrack", e)
            }
        }
        currentTrack = null
    }

    private fun ensureSpeakerOutput() {
        try {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (currentVol == 0 && maxVol > 0) {
                val targetVol = (maxVol * 0.70).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
        } catch (e: Exception) {
            RafelLogger.logError("Error ensuring speaker volume", e)
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        try {
            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } catch (e: Exception) {
            RafelLogger.logError("Audio focus request error", e)
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            RafelLogger.logError("Audio focus abandon error", e)
        } finally {
            hasAudioFocus = false
        }
    }

    private inline fun alignPcm(
        chunk: ByteArray,
        previousLeftover: Byte?,
        onNewLeftover: (Byte?) -> Unit
    ): ByteArray {
        val hasLeftover = previousLeftover != null
        val totalLen = (if (hasLeftover) 1 else 0) + chunk.size

        if (totalLen % 2 == 0) {
            onNewLeftover(null)
            return if (hasLeftover) {
                val combined = ByteArray(totalLen)
                combined[0] = previousLeftover!!
                System.arraycopy(chunk, 0, combined, 1, chunk.size)
                combined
            } else {
                chunk
            }
        } else {
            val evenLen = totalLen - 1
            val combined = ByteArray(evenLen)
            if (hasLeftover) {
                combined[0] = previousLeftover!!
                if (evenLen > 1) {
                    System.arraycopy(chunk, 0, combined, 1, chunk.size - 1)
                }
                onNewLeftover(chunk[chunk.size - 1])
            } else {
                System.arraycopy(chunk, 0, combined, 0, evenLen)
                onNewLeftover(chunk[chunk.size - 1])
            }
            return combined
        }
    }

    private fun dispatchAmplitude(chunk: ByteArray) {
        if (onAmplitudeChanged == null || chunk.size < 2) return
        var maxSample = 0
        var i = 0
        while (i < chunk.size - 1) {
            val s = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
            val s16 = s.toShort()
            val absVal = abs(s16.toInt())
            if (absVal > maxSample) maxSample = absVal
            i += 4
        }
        val norm = (maxSample / 16000f).coerceIn(0f, 1f)
        onAmplitudeChanged.invoke(norm)
    }

    fun enqueueChunk(generationId: Long, pcmBytes: ByteArray) {
        val cancelledGen = lastCancelledGenerationId.get()
        if (generationId <= cancelledGen && cancelledGen > 0L) {
            return
        }
        if (generationId > activeGenerationId.get()) {
            activeGenerationId.set(generationId)
        }
        audioQueue.offer(QueueItem.Chunk(generationId, pcmBytes))
    }

    fun markTurnComplete(generationId: Long) {
        val cancelledGen = lastCancelledGenerationId.get()
        if (generationId > cancelledGen || cancelledGen == 0L) {
            audioQueue.offer(QueueItem.TurnComplete(generationId))
        }
    }

    fun prepareForPlayback() {
        ensureSpeakerOutput()
        requestAudioFocus()
    }

    @Synchronized
    fun flushPriorTurnAudio(newGenerationId: Long) {
        lastCancelledGenerationId.set(newGenerationId - 1)
        audioQueue.clear()
        if (isCurrentlySpeaking.get()) {
            releaseTrackInternal()
            isCurrentlySpeaking.set(false)
            onAmplitudeChanged?.invoke(0f)
            abandonAudioFocus()
            onPlaybackFinished()
        }
    }

    @Synchronized
    fun interruptAndFlush() {
        val curGen = activeGenerationId.getAndIncrement()
        lastCancelledGenerationId.set(curGen)
        audioQueue.clear()
        releaseTrackInternal()
        isCurrentlySpeaking.set(false)
        onAmplitudeChanged?.invoke(0f)
        abandonAudioFocus()
        onPlaybackFinished()
    }

    fun isSpeaking(): Boolean = isCurrentlySpeaking.get()

    fun getQueueSize(): Int = audioQueue.size

    fun getQueuedBytes(): Long {
        var bytes = 0L
        for (item in audioQueue) {
            if (item is QueueItem.Chunk) {
                bytes += item.pcmData.size
            }
        }
        return bytes
    }

    fun getAudioTrackStateString(): String {
        val track = currentTrack ?: return "STATE_UNINITIALIZED"
        return when (track.state) {
            AudioTrack.STATE_INITIALIZED -> "STATE_INITIALIZED"
            else -> "STATE_UNINITIALIZED"
        }
    }

    fun getAudioTrackPlayStateString(): String {
        val track = currentTrack ?: return "PLAYSTATE_STOPPED"
        return when (track.playState) {
            AudioTrack.PLAYSTATE_PLAYING -> "PLAYSTATE_PLAYING"
            AudioTrack.PLAYSTATE_PAUSED -> "PLAYSTATE_PAUSED"
            else -> "PLAYSTATE_STOPPED"
        }
    }

    fun getActiveOutputDeviceDescription(): String {
        val track = currentTrack
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && track != null) {
            try {
                val device = track.routedDevice
                if (device != null) {
                    val typeName = when (device.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
                        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                        else -> "TYPE_${device.type}"
                    }
                    return "${device.productName ?: "Device"} ($typeName)"
                }
            } catch (_: Exception) {}
        }
        return "Built-in Phone Speaker"
    }

    fun getAudioHardwareReport(): String {
        val musicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "AudioTrack: ${getAudioTrackStateString()} (${getAudioTrackPlayStateString()}), Volume: $musicVol/$maxVol"
    }

    @Synchronized
    fun release() {
        isLoopRunning.set(false)
        playbackThread?.interrupt()
        interruptAndFlush()
        try {
            lipSyncExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
