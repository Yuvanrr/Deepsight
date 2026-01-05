package com.example.deepsight

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedList

/**
 * Orchestrates frame capture and inference.
 * Implements smoothing logic and confidence fusion.
 * Privacy Safeguard: Frames are processed in-memory and immediately discarded after analysis.
 */
class FrameProcessor(
    private val mlManager: MLInferenceManager,
    private val onStatusUpdate: (OverlayService.DetectionStatus) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var processingJob: Job? = null
    
    // Rolling buffer for video frame smoothing (last 5 results)
    private val videoResultsBuffer = LinkedList<Float>()
    private val BUFFER_SIZE = 5

    fun startProcessing(frameProvider: () -> Bitmap?) {
        processingJob = scope.launch {
            while (true) {
                val bitmap = frameProvider()
                if (bitmap != null) {
                    processFrame(bitmap)
                    // Privacy Safeguard: Bitmap should be recycled or left for GC immediately
                    // No storage or logging of the frame data.
                }
                delay(1000L / 2) // ~2 FPS to conserve battery
            }
        }
    }

    fun stopProcessing() {
        processingJob?.cancel()
        videoResultsBuffer.clear()
    }

    private fun processFrame(bitmap: Bitmap) {
        // Run inference (placeholder logic)
        // In a real app, we might check if the current screen content is a video or image.
        val imageProb = mlManager.analyzeImage(bitmap)
        val videoProb = mlManager.analyzeVideoFrame(bitmap)
        
        // Simple fusion / decision logic
        val finalProb = (imageProb + videoProb) / 2
        
        updateVideoBuffer(finalProb)
        val smoothedProb = videoResultsBuffer.average().toFloat()

        val status = when {
            smoothedProb >= 0.75f -> OverlayService.DetectionStatus.LIKELY_AI
            smoothedProb <= 0.35f -> OverlayService.DetectionStatus.LIKELY_REAL
            else -> OverlayService.DetectionStatus.UNCERTAIN
        }
        
        onStatusUpdate(status)
    }

    private fun updateVideoBuffer(prob: Float) {
        videoResultsBuffer.add(prob)
        if (videoResultsBuffer.size > BUFFER_SIZE) {
            videoResultsBuffer.removeFirst()
        }
    }
}
