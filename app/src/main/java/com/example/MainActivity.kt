package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.RafelScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.voice.RafelLogger

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            RafelLogger.logMic("Record audio permission granted by user.")
        } else {
            RafelLogger.logError("Record audio permission was denied by user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RafelLogger.logVoiceState(RafelForegroundService.currentState, RafelForegroundService.currentState)

        val prefs = getSharedPreferences("RafelPrefs", MODE_PRIVATE)
        val isSetupCompleted = prefs.getBoolean("setup_completed", false)
        if (isSetupCompleted) {
            checkPermissions()
        }

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RafelScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App is now foregrounded in full UI; hide floating overlay
        RafelForegroundService.hideFloating()

        android.util.Log.i("RAFEL-APP", "[APP] Activity resumed")
        val service = RafelForegroundService.activeService
        if (service != null) {
            android.util.Log.i("RAFEL-APP", "[SERVICE] existing service reused")
            val liveConnected = RafelForegroundService.isLiveConnected()
            if (liveConnected) {
                android.util.Log.i("RAFEL-APP", "[LIVE] existing Gemini session reused")
            }
            service.proactiveManager.onAppForegrounded(isSessionActive = liveConnected || RafelForegroundService.isMicActive())
        }
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.i("RAFEL-APP", "[APP] Activity backgrounded")
        val service = RafelForegroundService.activeService
        if (service != null) {
            android.util.Log.i("RAFEL-APP", "[SERVICE] Foreground service still running")
            val liveConnected = RafelForegroundService.isLiveConnected()
            val micActive = RafelForegroundService.isMicActive()
            if (liveConnected) {
                android.util.Log.i("RAFEL-APP", "[LIVE] Gemini session still connected")
            }
            if (micActive) {
                android.util.Log.i("RAFEL-APP", "[MIC] microphone still active")
            }
            service.proactiveManager.onAppBackgrounded(
                isSessionStillActive = RafelForegroundService.isServiceRunning() && micActive
            )
        }

        // User exited full app (e.g. pressed Home or switched to another app)
        // If foreground service is running and floating is enabled & permitted, show floating core
        val prefs = getSharedPreferences("RafelPrefs", MODE_PRIVATE)
        val floatingEnabled = prefs.getBoolean("floating_enabled", true)
        if (floatingEnabled && com.example.util.PermissionHelper.isOverlayGranted(this)) {
            RafelForegroundService.showFloating()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
