package com.example.deepsight

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * AccessibilityService that provides the overlay UI for DeepSight.
 */
class OverlayService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var statusTextView: TextView
    private lateinit var warningTextView: TextView
    private lateinit var appPreferences: AppPreferences
    
    private var isOverlayAdded = false
    private var isOverlayVisible = false
    private var currentForegroundPackage: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "DeepSight_Overlay"
        var instance: OverlayService? = null
        val actionTriggerFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AccessibilityService connected")
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appPreferences = AppPreferences(this)
        
        createOverlay()
        setupCommandCollection()
        
        val canDraw = Settings.canDrawOverlays(this)
        Log.d(TAG, "SYSTEM_ALERT_WINDOW permission granted: $canDraw")
    }

    private fun setupCommandCollection() {
        serviceScope.launch {
            OverlayBridge.overlayCommandFlow.collect { command ->
                when (command) {
                    OverlayBridge.OverlayCommand.SHOW -> showOverlay()
                    OverlayBridge.OverlayCommand.HIDE -> hideOverlay()
                }
            }
        }
    }

    private fun createOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        statusTextView = overlayView.findViewById(R.id.statusText)
        warningTextView = overlayView.findViewById(R.id.warningText)
        
        Log.d(TAG, "Overlay view created")

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 200

        overlayView.visibility = View.GONE
        
        if (!isOverlayAdded) {
            try {
                windowManager.addView(overlayView, params)
                isOverlayAdded = true
            } catch (e: Exception) {
                Log.e(TAG, "addView failed: $e")
            }
        }
    }

    private fun showOverlay() {
        if (::overlayView.isInitialized && !isOverlayVisible) {
            overlayView.post {
                overlayView.visibility = View.VISIBLE
                isOverlayVisible = true
            }
        }
    }

    private fun hideOverlay() {
        if (::overlayView.isInitialized && isOverlayVisible) {
            overlayView.post {
                overlayView.visibility = View.GONE
                isOverlayVisible = false
            }
        }
    }

    fun updateStatus(status: DetectionStatus, isSimulation: Boolean = false) {
        if (!::statusTextView.isInitialized) return
        statusTextView.post {
            statusTextView.text = status.text
            overlayView.setBackgroundColor(status.color)
            
            if (isSimulation) {
                warningTextView.visibility = View.VISIBLE
                warningTextView.text = "⚠️ Simulation Mode (Model Load Failed)"
            } else {
                warningTextView.visibility = View.GONE
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val pkg = event.packageName?.toString() ?: return

        val blocklist = setOf(
            packageName,
            "com.android.systemui",
            "com.android.launcher3"
        )
        if (pkg in blocklist) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val selectedApps = appPreferences.getSelectedApps()
            if (selectedApps.contains(pkg)) {
                currentForegroundPackage = pkg
                DetectionService.currentForegroundPackage = pkg
                showOverlay()
            } else {
                currentForegroundPackage = null
                DetectionService.currentForegroundPackage = null
                hideOverlay()
            }
        }

        if (appPreferences.isAppSelected(pkg)) {
            when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    actionTriggerFlow.tryEmit(pkg)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeOverlaySafely()
        instance = null
    }

    private fun removeOverlaySafely() {
        if (isOverlayAdded && ::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
                isOverlayAdded = false
            } catch (e: Exception) {
                Log.e(TAG, "removeView failed: $e")
            }
        }
    }

    enum class DetectionStatus(val text: String, val color: Int) {
        LIKELY_AI("LIKELY AI", Color.parseColor("#CCFF0000")),
        LIKELY_REAL("LIKELY REAL", Color.parseColor("#CC00FF00")),
        UNCERTAIN("UNCERTAIN", Color.parseColor("#CCFFFF00")),
        IDLE("DeepSight Active", Color.parseColor("#CC0000FF"))
    }
}
