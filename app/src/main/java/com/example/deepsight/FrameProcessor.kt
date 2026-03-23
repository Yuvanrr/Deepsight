package com.example.deepsight

import android.graphics.Bitmap
import android.util.Log
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
    private val onStatusUpdate: (OverlayService.DetectionStatus, Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var processingJob: Job? = null
    
    // Rolling buffer for frame smoothing (last 5 results)
    private val resultsBuffer = LinkedList<Float>()
    private val BUFFER_SIZE = 5

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

    private fun processFrame(bitmap: Bitmap) {
        // Run inference using the new model
        val aiProbability = mlManager.analyze(bitmap)
        val isSimulation = mlManager.isRunningInSimulationMode()
        
        // Prevent memory thrashing by explicitly recycling the bitmap after use
        bitmap.recycle()
        
        updateBuffer(aiProbability)
        val smoothedProb = resultsBuffer.average().toFloat()

        Log.d("DeepSight_FP", "AI Prob: $aiProbability, Smoothed: $smoothedProb")

        val status = when {
            smoothedProb >= 0.60f -> OverlayService.DetectionStatus.LIKELY_AI
            smoothedProb <= 0.40f -> OverlayService.DetectionStatus.LIKELY_REAL
            else -> OverlayService.DetectionStatus.UNCERTAIN
        }
        
        onStatusUpdate(status, isSimulation)
    }

    private fun updateBuffer(prob: Float) {
        resultsBuffer.add(prob)
        if (resultsBuffer.size > BUFFER_SIZE) {
            resultsBuffer.removeFirst()
        }
    }
}
