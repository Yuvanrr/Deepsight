package com.example.deepsight

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.MappedByteBuffer

/**
 * Manages on-device TFLite inference for deepfake detection.
 * Loads the best_model_v2.tflite model.
 */
class MLInferenceManager(private val context: Context) {

    private var detector: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isSimulationMode = false

    companion object {
        private const val TAG = "DeepSight_ML"
        private const val MODEL_FILE = "best_model_v2.tflite"
    }

    init {
        try {
            val options = Interpreter.Options()
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU Delegate added successfully")
            } catch (e: Exception) {
                Log.w(TAG, "GPU Delegate not supported, falling back to CPU", e)
            }

            detector = Interpreter(loadModelFile(MODEL_FILE), options)
            Log.d(TAG, "Model $MODEL_FILE loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model $MODEL_FILE. Entering simulation mode.", e)
            isSimulationMode = true
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        return FileUtil.loadMappedFile(context, modelName)
    }

    /**
     * Runs inference on a bitmap.
     * Input: [1, 224, 224, 3] Float32
     * Normalization: mean=127.5, std=127.5
     * Output: [1, 1] Sigmoid (1.0 = AI-generated)
     */
    fun analyze(bitmap: Bitmap): Float {
        if (isSimulationMode) return (0..100).random() / 100f
        
        val interpreter = detector ?: return 0.5f
        
        val tensorImage = preprocessImage(bitmap)
        val output = Array(1) { FloatArray(1) }
        
        try {
            interpreter.run(tensorImage.buffer, output)
            val rawOutput = output[0][0]
            // best_model_v2: trained with fake=0, real=1 → invert to get AI probability
            val aiProb = 1.0f - rawOutput
            Log.d(TAG, "Raw model output: $rawOutput (real prob) -> AI prob: $aiProb")
            return aiProb
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            return 0.5f
        }
    }

    private fun preprocessImage(bitmap: Bitmap): TensorImage {
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
            // EfficientNetV2B0 has an internal Rescaling layer, feed raw [0, 255] float pixels
            .build()

        val tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        return imageProcessor.process(tensorImage)
    }

    fun isRunningInSimulationMode(): Boolean = isSimulationMode

    fun close() {
        detector?.close()
        gpuDelegate?.close()
    }
}
