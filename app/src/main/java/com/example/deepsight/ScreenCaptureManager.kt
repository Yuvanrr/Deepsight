package com.example.deepsight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages screen capture using MediaProjection API.
 * Captures low-resolution frames for analysis.
 * Privacy Safeguard: Frames are captured only when detection is active and are not stored.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private val metrics = DisplayMetrics()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    private var width: Int = 0
    private var height: Int = 0
    private var dpi: Int = 0
    
    private val captureMutex = Mutex()

    companion object {
        private const val TAG = "DeepSight"
    }

    init {
        windowManager.defaultDisplay.getRealMetrics(metrics)
        width = 480
        height = (width * (metrics.heightPixels.toFloat() / metrics.widthPixels)).toInt()
        dpi = metrics.densityDpi
    }

    fun reset() {
        Log.d(TAG, "ScreenCaptureManager reset")
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    suspend fun initVirtualDisplay() = captureMutex.withLock {
        Log.d(TAG, "initVirtualDisplay() called")
        virtualDisplay?.release()
        
        if (imageReader == null) {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            Log.d(TAG, "ImageReader created: ${imageReader != null}")
        }
        
        // Android 14+ (API 34) requirement: registerCallback BEFORE createVirtualDisplay
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    virtualDisplay?.release()
                    virtualDisplay = null
                    imageReader?.close()
                    imageReader = null
                }
            },
            handler
        )
        
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "DeepSightCapture",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            Log.d(TAG, "VirtualDisplay init: ${virtualDisplay != null}")
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay failed: $e")
        }
    }

    suspend fun startCapture() {
        reset()
        initVirtualDisplay()
    }

    suspend fun captureFrame(): Bitmap? = captureMutex.withLock {
        Log.d(TAG, "captureFrame() entered on thread: ${Thread.currentThread().id}")
        
        if (virtualDisplay == null) {
            Log.e(TAG, "FATAL: VirtualDisplay null, reinitializing")
            // Re-init logic inside the lock (re-using part of initVirtualDisplay logic but without nested lock)
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            }
            try {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "DeepSightCapture",
                    width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, handler
                )
            } catch (e: Exception) {
                Log.e(TAG, "Recovery VirtualDisplay failed: $e")
            }
            return@withLock null
        }

        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            Log.d(TAG, "Image acquired: false")
            return@withLock null
        }
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to a perfect center square to prevent ML model aspect ratio distortion.
            val size = Math.min(image.width, image.height)
            val resultBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(resultBitmap)
            val paint = android.graphics.Paint()
            // Fix RGBA_8888 to ARGB_8888 Red/Blue color swap
            val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                0f, 0f, 1f, 0f, 0f,  // Red channel <- Blue
                0f, 1f, 0f, 0f, 0f,  // Green channel <- Green
                1f, 0f, 0f, 0f, 0f,  // Blue channel <- Red
                0f, 0f, 0f, 1f, 0f   // Alpha channel <- Alpha
            ))
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            
            val dx = -(image.width - size) / 2f
            val dy = -(image.height - size) / 2f
            canvas.drawBitmap(rawBitmap, dx, dy, paint)
            rawBitmap.recycle()
            
            Log.d(TAG, "Bitmap created: true")
            return@withLock resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error processing captured frame", e)
            return@withLock null
        } finally {
            image.close()
        }
    }

    fun stopCapture() {
        reset()
        Log.d(TAG, "ScreenCaptureManager stopped")
    }
}
