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
import android.view.WindowManager

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

    init {
        windowManager.defaultDisplay.getRealMetrics(metrics)
    }

    fun startCapture() {
        // Use low resolution to conserve battery and memory
        val width = 480
        val height = (width * (metrics.heightPixels.toFloat() / metrics.widthPixels)).toInt()
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
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
    }

    fun acquireLatestBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        
        // Privacy Safeguard: Process only if content is not protected.
        // FLAG_SECURE / DRM handling usually happens at the OS level (black frames), 
        // but we can check for null/blank images if needed.
        
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
        image.close()

        // Crop to actual size if there's padding
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection.stop()
    }
}
