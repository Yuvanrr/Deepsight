package com.example.deepsight

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DetectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var frameProcessor: FrameProcessor? = null
    private lateinit var mlInferenceManager: MLInferenceManager

    companion object {
        const val CHANNEL_ID = "DeepSightDetectionChannel"
        const val NOTIFICATION_ID = 101
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        mlInferenceManager = MLInferenceManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

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
        
        setupDetection(mediaProjection!!)
        isRunning = true
    }

    private fun setupDetection(projection: MediaProjection) {
        screenCaptureManager = ScreenCaptureManager(this, projection)
        frameProcessor = FrameProcessor(mlInferenceManager) { status ->
            OverlayService.instance?.updateStatus(status)
        }

        screenCaptureManager?.startCapture()
        frameProcessor?.startProcessing {
            screenCaptureManager?.acquireLatestBitmap()
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
        frameProcessor?.stopProcessing()
        screenCaptureManager?.stopCapture()
        mediaProjection?.stop()
        mlInferenceManager.close()
        isRunning = false
    }
}
