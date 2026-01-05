package com.example.deepsight

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * AccessibilityService that provides the overlay UI for DeepSight.
 * TYPE_ACCESSIBILITY_OVERLAY allows it to be drawn over other apps.
 */
class OverlayService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var statusTextView: TextView

    companion object {
        var instance: OverlayService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    private fun createOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        statusTextView = overlayView.findViewById(R.id.statusText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCHABLE ensure the overlay does not block user interaction
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 200

        windowManager.addView(overlayView, params)
    }

    fun updateStatus(status: DetectionStatus) {
        statusTextView.text = status.text
        overlayView.setBackgroundColor(status.color)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        instance = null
    }

    enum class DetectionStatus(val text: String, val color: Int) {
        LIKELY_AI("Likely AI", Color.parseColor("#80FF0000")), // Translucent Red
        LIKELY_REAL("Likely Real", Color.parseColor("#8000FF00")), // Translucent Green
        UNCERTAIN("Uncertain", Color.parseColor("#80FFFF00")), // Translucent Yellow
        PROTECTED("Cannot analyze protected content", Color.GRAY),
        IDLE("DeepSight Active", Color.parseColor("#800000FF")) // Translucent Blue
    }
}
