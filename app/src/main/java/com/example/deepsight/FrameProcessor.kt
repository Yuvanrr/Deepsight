package com.example.deepsight

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import java.util.LinkedList
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Orchestrates frame capture and inference.
 * Implements smoothing logic and confidence fusion.
 * Privacy Safeguard: Frames are processed in-memory and immediately discarded after analysis.
 */
class FrameProcessor(
    private val mlManager: MLInferenceManager,
    private val onStatusUpdate: (OverlayService.DetectionStatus, Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var processingJob: Job? = null
    
    // Rolling buffer for frame smoothing (last 5 results)
    private val resultsBuffer = LinkedList<Float>()
    private val BUFFER_SIZE = 5
    
    // Mutex to protect TFLite Interpreter native memory and resultsBuffer from concurrent modification
    private val processingMutex = Mutex()
    
    // ML Kit Face Detector for Phase 1 crop
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )

    fun startProcessing(frameProvider: suspend () -> Bitmap?) {
        processingJob = scope.launch {
            while (true) {
                val bitmap = frameProvider()
                if (bitmap != null) {
                    processFrame(bitmap)
                }
                delay(500) // ~2 FPS
            }
        }
    }

    fun stopProcessing() {
        processingJob?.cancel()
        resultsBuffer.clear()
    }

    fun processSingleFrame(bitmap: Bitmap) {
        scope.launch {
            processFrame(bitmap)
        }
    }

    private suspend fun processFrame(bitmap: Bitmap) {
        processingMutex.withLock {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val detectedFaces = suspendCancellableCoroutine { continuation ->
                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        if (continuation.isActive) continuation.resume(faces)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
            }

            if (detectedFaces.isEmpty()) {
                // If no face found inside the screen, we skip processing to avoid UI false positives
                Log.d("DeepSight_FP", "No faces detected in frame — skipping inference")
                bitmap.recycle()
                return@withLock
            }

            // Get the largest face by bounding box area
            val mainObj = detectedFaces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
            val bounds = mainObj.boundingBox
            
            // Create a square crop around the object to prevent aspect ratio distortion
            val size = Math.max(bounds.width(), bounds.height())
            var left = bounds.centerX() - size / 2
            var top = bounds.centerY() - size / 2
            var right = left + size
            var bottom = top + size

            // Adjust if out of bounds to keep the square shape if possible
            if (left < 0) { right -= left; left = 0 }
            if (top < 0) { bottom -= top; top = 0 }
            if (right > bitmap.width) { left -= (right - bitmap.width); right = bitmap.width }
            if (bottom > bitmap.height) { top -= (bottom - bitmap.height); bottom = bitmap.height }

            // Final clamp to ensure we don't crash on edges
            left = Math.max(0, left)
            top = Math.max(0, top)
            right = Math.min(bitmap.width, right)
            bottom = Math.min(bitmap.height, bottom)
            
            val cropWidth = right - left
            val cropHeight = bottom - top

            val aiProbability = if (cropWidth > 0 && cropHeight > 0) {
                val objectBitmap = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
                val prob = mlManager.analyze(objectBitmap)
                objectBitmap.recycle()
                prob
            } else {
                mlManager.analyze(bitmap)
            }

            val isSimulation = mlManager.isRunningInSimulationMode()
            
            // Prevent memory thrashing by explicitly recycling the full bitmap after use
            bitmap.recycle()
            
            updateBuffer(aiProbability)
            val smoothedProb = resultsBuffer.average().toFloat()

            Log.d("DeepSight_FP", "AI Prob: $aiProbability, Smoothed: $smoothedProb, Faces: ${detectedFaces.size}, FaceBox: ${bounds.width()}x${bounds.height()}")

            val status = when {
                smoothedProb >= 0.15f -> OverlayService.DetectionStatus.LIKELY_AI
                smoothedProb <= 0.08f -> OverlayService.DetectionStatus.LIKELY_REAL
                else -> OverlayService.DetectionStatus.UNCERTAIN
            }
            
            onStatusUpdate(status, isSimulation)
        }
    }

    private fun updateBuffer(prob: Float) {
        resultsBuffer.add(prob)
        if (resultsBuffer.size > BUFFER_SIZE) {
            resultsBuffer.removeFirst()
        }
    }
}
