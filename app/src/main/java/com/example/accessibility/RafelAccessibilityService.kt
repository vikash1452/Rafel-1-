package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service for Rafel Assistant.
 * Provides accessibility capabilities for screen navigation.
 * Note: Automated sending of messages without explicit user tap is disabled by design.
 * Message preview is shown and the user must manually tap Send in WhatsApp / messaging apps.
 */
class RafelAccessibilityService : AccessibilityService() {

    companion object {
        // Disabled: No automatic message sending or auto-clicking
        val shouldAutoClick = false
        var targetAppName = "whatsapp"
        var instance: RafelAccessibilityService? = null

        fun dispatchGestureClick(x: Float, y: Float): Boolean {
            val inst = instance ?: return false
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)

            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            val gesture = builder.build()

            return inst.dispatchGesture(gesture, null, null)
        }

        fun clickTextOnScreen(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    val bounds = Rect()
                    current.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && (current.isClickable || current == node)) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        if (dispatchGestureClick(x, y)) return true
                    }
                    current = current.parent
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RafelAccessibility", "Rafel Accessibility Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Explicitly disabled: Automatic clicking of Send button in WhatsApp is disabled.
        // User must manually preview and tap Send to ensure safety and consent.
    }

    override fun onInterrupt() {
        Log.d("RafelAccessibility", "Rafel Accessibility Service Interrupted")
    }
}
