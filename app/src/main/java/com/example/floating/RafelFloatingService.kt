package com.example.floating

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.RafelForegroundService

/**
 * Backward compatibility facade. Directs all floating overlay control through RafelForegroundService
 * to prevent duplicate services and maintain a single unified lifecycle as specified in Requirement 10.
 */
class RafelFloatingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RafelForegroundService.showFloating()
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        fun isRunning(context: Context): Boolean {
            val prefs = context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("floating_enabled", true)
        }

        fun start(context: Context) {
            context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("floating_enabled", true)
                .apply()
            RafelForegroundService.showFloating()
        }

        fun stop(context: Context) {
            context.getSharedPreferences("RafelPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("floating_enabled", false)
                .apply()
            RafelForegroundService.hideFloating()
        }
    }
}
