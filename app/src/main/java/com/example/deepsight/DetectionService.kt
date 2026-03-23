package com.example.deepsight

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DetectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var frameProcessor: FrameProcessor? = null
    private lateinit var mlInferenceManager: MLInferenceManager
    private lateinit var appPreferences: AppPreferences
    
    private var serviceScope: CoroutineScope? = null
    private var actionTriggerJob: Job? = null

    companion object {
        private const val TAG = "DeepSight"
        const val CHANNEL_ID = "DeepSightDetectionChannel"
        const val NOTIFICATION_ID = 101
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        
        var isRunning = false
        var currentForegroundPackage: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DetectionService onCreate")
        mlInferenceManager = MLInferenceManager(this)
        appPreferences = AppPreferences(this)
        createNotificationChannel()
    }

    private fun setupActionTriggerCollection() {
        Log.d(TAG, "setupActionTriggerCollection started")
        serviceScope?.launch {
            OverlayService.actionTriggerFlow.collectLatest { triggeringPackage ->
                Log.d(TAG, "Action received from $triggeringPackage, waiting 500ms")
                actionTriggerJob?.cancel()
                actionTriggerJob = launch {
                    try {
                        delay(500)
                        if (appPreferences.getSelectedApps().contains(triggeringPackage)) {
                            Log.d(TAG, "Delay done, calling captureFrame() for $triggeringPackage")
                            val bitmap = screenCaptureManager?.captureFrame()
                            if (bitmap != null) {
                                Log.d(TAG, "Frame captured, passing to FrameProcessor")
                                // Unified path: Use FrameProcessor for smoothing and status updates
                                frameProcessor?.processSingleFrame(bitmap)
                            }
                        } else {
                            Log.d(TAG, "Package $triggeringPackage no longer selected")
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Capture job cancelled: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DetectionService starting fresh")
        
        if (mediaProjection != null) {
            mediaProjection?.stop()
            mediaProjection = null
        }
        
        serviceScope?.cancel()
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        setupActionTriggerCollection()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtraCompat<Intent>(EXTRA_DATA)

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(resultCode, data)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService(resultCode: Int, data: Intent) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepSight Active")
            .setContentText("Monitoring for AI-generated content...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection != null) {
            setupDetection(mediaProjection!!)
            isRunning = true
            
            // Use Bridge to Emit SHOW command
            OverlayBridge.overlayCommandFlow.tryEmit(OverlayBridge.OverlayCommand.SHOW)
        } else {
            stopSelf()
        }
    }

    private fun setupDetection(projection: MediaProjection) {
        screenCaptureManager = ScreenCaptureManager(this, projection)
        frameProcessor = FrameProcessor(mlInferenceManager) { status, isSimulation ->
            OverlayService.instance?.updateStatus(status, isSimulation)
        }

        Log.d(TAG, "DetectionService: forcing VirtualDisplay init on start")
        serviceScope?.launch {
            screenCaptureManager?.initVirtualDisplay()
        }

        frameProcessor?.startProcessing {
            // Check if current foreground app is selected before allowing 2FPS polling capture
            val pkg = currentForegroundPackage
            if (pkg != null && appPreferences.isAppSelected(pkg)) {
                screenCaptureManager?.captureFrame()
            } else {
                null
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepSight Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DetectionService onDestroy")
        
        OverlayBridge.overlayCommandFlow.tryEmit(OverlayBridge.OverlayCommand.HIDE)
        
        frameProcessor?.stopProcessing()
        screenCaptureManager?.stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
        mlInferenceManager.close()
        serviceScope?.cancel()
        isRunning = false
    }
}

private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
