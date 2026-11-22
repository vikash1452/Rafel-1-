package com.example.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.voice.RafelLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * ScreenCaptureManager
 *
 * Manages official Android MediaProjection screen capture:
 * - Requires explicit user consent via MediaProjectionManager.createScreenCaptureIntent()
 * - Captures downsampled JPEG frames at controlled intervals (avoiding battery drain / continuous recording)
 * - Encodes frames to Base64 for Gemini Multimodal Live API
 * - Proper lifecycle cleanup (releasing VirtualDisplay, ImageReader, MediaProjection)
 */
class ScreenCaptureManager private constructor(private val context: Context) {

    private val mediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val scope = CoroutineScope(Dispatchers.Default)
    private var framePollingJob: Job? = null

    private val _isSharing = MutableStateFlow(false)
    val isSharingFlow: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _latestFrameBase64 = MutableStateFlow<String?>(null)
    val latestFrameBase64Flow: StateFlow<String?> = _latestFrameBase64.asStateFlow()

    private val _latestFrameTimestamp = MutableStateFlow(0L)
    val latestFrameTimestampFlow: StateFlow<Long> = _latestFrameTimestamp.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastErrorFlow: StateFlow<String?> = _lastError.asStateFlow()

    var onFrameCaptured: ((String) -> Unit)? = null
    var onSharingStateChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val PREFS_NAME = "RafelPrefs"
        const val KEY_SCREEN_PRIVACY_NOTICE_ACCEPTED = "screen_privacy_notice_accepted"

        @Volatile
        private var instance: ScreenCaptureManager? = null

        fun getInstance(context: Context): ScreenCaptureManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenCaptureManager(context.applicationContext).also { instance = it }
            }
        }

        fun isPrivacyNoticeAccepted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SCREEN_PRIVACY_NOTICE_ACCEPTED, false)
        }

        fun setPrivacyNoticeAccepted(context: Context, accepted: Boolean = true) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SCREEN_PRIVACY_NOTICE_ACCEPTED, accepted).apply()
        }
    }

    /**
     * Creates the official system Screen Capture Intent for the user consent dialog.
     */
    fun createScreenCaptureIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    /**
     * Starts screen capture session using the result from the official consent dialog.
     */
    fun startProjection(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            _lastError.value = "Screen share permission was denied by the user."
            RafelLogger.logError("MediaProjection consent denied by user.")
            return false
        }

        try {
            stopProjectionInternal(stopForegroundService = false) // Ensure clean state without stopping active projection FGS

            val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                _lastError.value = "Failed to obtain MediaProjection token."
                RafelLogger.logError("MediaProjection returned null.")
                return false
            }

            mediaProjection = projection

            // Register callback to cleanly handle user/system stopping projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    RafelLogger.logDiagnostic("MediaProjection stopped by system/user.")
                    stopProjection()
                }
            }, null)

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val density = metrics.densityDpi

            // Scale down resolution for optimal Gemini Live latency and memory efficiency (max 720p width)
            val targetWidth = if (screenWidth > 720) 720 else screenWidth
            val targetHeight = (screenHeight * (targetWidth.toFloat() / screenWidth.toFloat())).toInt()

            val thread = HandlerThread("RafelScreenCaptureThread").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)
            backgroundHandler = handler

            val reader = ImageReader.newInstance(
                targetWidth,
                targetHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "RafelScreenSharing",
                targetWidth,
                targetHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            _isSharing.value = true
            _lastError.value = null
            onSharingStateChanged?.invoke(true)
            RafelLogger.logDiagnostic("Screen capture started: ${targetWidth}x${targetHeight} @ ${density}dpi")

            // Start controlled frame capture interval (e.g. capture 1 frame every 4 seconds)
            startControlledFramePolling()
            return true
        } catch (e: Exception) {
            _lastError.value = "Error starting screen capture: ${e.message}"
            RafelLogger.logError("Failed to start MediaProjection: ${e.message}", e)
            stopProjection()
            return false
        }
    }

    /**
     * Captures the current visible frame immediately (on-demand), useful when user asks "What's on my screen?".
     */
    fun captureCurrentFrame(): String? {
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return _latestFrameBase64.value

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop out padding if needed
            val cleanBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            cleanBitmap.recycle()

            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            _latestFrameBase64.value = base64
            _latestFrameTimestamp.value = System.currentTimeMillis()
            onFrameCaptured?.invoke(base64)
            return base64
        } catch (e: Exception) {
            RafelLogger.logError("Error capturing screen frame: ${e.message}", e)
            return _latestFrameBase64.value
        } finally {
            image?.close()
        }
    }

    /**
     * Controlled polling loop captures frames periodically at 4-second intervals.
     * Prevents continuous CPU/battery burn while keeping Gemini Live context fresh.
     */
    private fun startControlledFramePolling() {
        framePollingJob?.cancel()
        framePollingJob = scope.launch {
            // Initial delay to let the virtual display render first frame
            delay(800L)
            while (isActive && _isSharing.value) {
                try {
                    val frame = captureCurrentFrame()
                    if (frame != null) {
                        RafelLogger.logDiagnostic("Periodic screen frame captured (size: ${frame.length} chars)")
                    }
                } catch (e: Exception) {
                    RafelLogger.logError("Periodic frame capture failed: ${e.message}")
                }
                delay(4000L) // 4-second controlled interval
            }
        }
    }

    /**
     * Cleanly stops screen sharing and releases all media projection and virtual display resources.
     */
    fun stopProjection() {
        stopProjectionInternal(stopForegroundService = true)
    }

    private fun stopProjectionInternal(stopForegroundService: Boolean) {
        framePollingJob?.cancel()
        framePollingJob = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {}
        handlerThread = null
        backgroundHandler = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        val wasSharing = _isSharing.value
        _isSharing.value = false
        _latestFrameBase64.value = null
        if (wasSharing) {
            if (stopForegroundService) {
                ScreenProjectionForegroundService.stop(context)
            }
            com.example.RafelForegroundService.activeService?.updateForegroundServiceTypes()
            onSharingStateChanged?.invoke(false)
            RafelLogger.logDiagnostic("Screen capture cleanly stopped and released.")
        }
    }
}
