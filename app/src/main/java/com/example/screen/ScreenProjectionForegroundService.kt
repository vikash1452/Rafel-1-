package com.example.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.RafelForegroundService
import com.example.voice.RafelLogger

/**
 * Dedicated MediaProjection foreground service started ONLY at runtime after the user
 * explicitly grants screen-share consent (API 34+ safe).
 *
 * Keeps [RafelForegroundService] strictly on `foregroundServiceType="microphone"` and
 * satisfies Android 14+ requirement that `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` is
 * promoted after user consent and BEFORE calling `MediaProjectionManager.getMediaProjection()`.
 */
class ScreenProjectionForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "RafelScreenProjectionChannel"
        private const val NOTIFICATION_ID = 102
        private const val ACTION_START_PROJECTION = "com.example.screen.ACTION_START_PROJECTION"
        private const val ACTION_STOP_PROJECTION = "com.example.screen.ACTION_STOP_PROJECTION"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_CONSENT_DATA = "extra_consent_data"

        fun startWithConsent(context: Context, resultCode: Int, consentData: Intent) {
            if (resultCode != Activity.RESULT_OK) return
            val intent = Intent(context, ScreenProjectionForegroundService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_CONSENT_DATA, consentData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ScreenProjectionForegroundService::class.java))
            } catch (_: Exception) {
                // Ignore if service is not running
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_PROJECTION) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val consentData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CONSENT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CONSENT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || consentData == null) {
            RafelLogger.logError("ScreenProjectionForegroundService started without valid user consent.")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
        } catch (e: Exception) {
            RafelLogger.logError("Failed to start mediaProjection foreground service: ${e.message}", e)
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val started = ScreenCaptureManager.getInstance(this).startProjection(resultCode, consentData)
        if (!started) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        RafelForegroundService.activeService?.updateForegroundServiceTypes()
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rafel Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while screen sharing with Rafel"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rafel Screen Sharing")
            .setContentText("Screen sharing active with your consent")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
