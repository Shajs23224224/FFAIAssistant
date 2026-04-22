package com.ffai.assistant.core

import android.content.Context
import com.ffai.assistant.config.Constants
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * PolicyModel - Modelo TFLite MLP INT8 para decisiones tácticas.
 *
 * Arquitectura: 32 (input) → 64 → 32 → 19 (output)
 *   - Input: 32 features del estado del juego
 *   - Hidden 1: 64 neuronas con ReLU
 *   - Hidden 2: 32 neuronas con ReLU
 *   - Output: 15 acciones + 4 parámetros (x, y, duration, confidence)
 *
 * Modelo: policy.tflite (~1MB INT8)
 */
class PolicyModel(context: Context) {

    private var interpreter: Interpreter? = null
    private val modelFile: File

    // Buffers reutilizables (evitar GC durante inferencia)
    private val inputBuffer = Array(1) { FloatArray(32) }
    private val outputBuffer = Array(1) { FloatArray(19) }

    var isLoaded: Boolean = false
        private set

    init {
        val modelDir = File(context.getExternalFilesDir(null), "models")
        modelDir.mkdirs()
        modelFile = File(modelDir, "policy.tflite")

        // Cargar modelo si existe
        if (modelFile.exists() && modelFile.length() > 0) {
            loadModel()
        } else {
            Logger.w("PolicyModel: Model file not found at ${modelFile.absolutePath}")
            isLoaded = false
        }
    }

    /**
     * Carga el modelo TFLite desde archivo.
     */
    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile(modelFile)

            val options = Interpreter.Options().apply {
                setNumThreads(2)  // Balance performance/battery
                setUseXNNPACK(true)  // Optimización para CPU ARM
                // setAllowFp16PrecisionForFp32(true)  // Permitir FP16 para más velocidad
            }

            interpreter = Interpreter(modelBuffer, options)
            isLoaded = true
            Logger.i("PolicyModel: Loaded successfully (${modelFile.length()} bytes)")

        } catch (e: Exception) {
            Logger.e("PolicyModel: Failed to load model", e)
            interpreter = null
            isLoaded = false
        }
    }

    /**
     * Ejecuta inferencia y devuelve output del modelo.
     *
     * @param features Vector de 32 features normalizados
     * @return FloatArray con 19 valores (15 acciones + 4 params)
     */
    fun predict(features: FloatArray): FloatArray? {
        if (!isLoaded || interpreter == null) return null

        return try {
            // Copiar features al buffer de entrada
            System.arraycopy(features, 0, inputBuffer[0], 0, minOf(features.size, 32))

            // Inferencia
            interpreter?.run(inputBuffer, outputBuffer)

            // Aplicar softmax a las 15 primeras salidas (probabilidades de acción)
            val actionProbs = outputBuffer[0].take(15).toFloatArray()
            val softmaxProbs = softmax(actionProbs)

            // Combinar con parámetros
            val result = FloatArray(19)
            System.arraycopy(softmaxProbs, 0, result, 0, 15)
            result[15] = outputBuffer[0][15]  // x (0-1, escalar a screen)
            result[16] = outputBuffer[0][16]  // y (0-1)
            result[17] = outputBuffer[0][17].coerceIn(0f, 1f)  // duration
            result[18] = outputBuffer[0][18].coerceIn(0f, 1f)  // confidence

            result

        } catch (e: Exception) {
            Logger.e("PolicyModel: Inference error", e)
            null
        }
    }

    /**
     * Actualiza el modelo con uno nuevo descargado del servidor.
     */
    fun updateModel(newModelBytes: ByteArray): Boolean {
        return try {
            // Backup del modelo actual
            if (modelFile.exists()) {
                val backupFile = File(modelFile.parent, "policy_backup.tflite")
                modelFile.copyTo(backupFile, overwrite = true)
            }

            // Escribir nuevo modelo
            modelFile.writeBytes(newModelBytes)

            // Recargar
            interpreter?.close()
            loadModel()

            Logger.i("PolicyModel: Updated with ${newModelBytes.size} bytes")
            isLoaded

        } catch (e: Exception) {
            Logger.e("PolicyModel: Update failed", e)
            false
        }
    }

    /**
     * Restaura el modelo desde backup.
     */
    fun restoreFromBackup(): Boolean {
        val backupFile = File(modelFile.parent, "policy_backup.tflite")
        return if (backupFile.exists()) {
            try {
                backupFile.copyTo(modelFile, overwrite = true)
                interpreter?.close()
                loadModel()
                Logger.i("PolicyModel: Restored from backup")
                isLoaded
            } catch (e: Exception) {
                Logger.e("PolicyModel: Restore failed", e)
                false
            }
        } else {
            Logger.w("PolicyModel: No backup available")
            false
        }
    }

    /**
     * Verifica si el modelo está disponible para inferencia.
     */
    fun isAvailable(): Boolean = isLoaded && interpreter != null

    /**
     * Obtiene información del modelo.
     */
    fun getModelInfo(): ModelInfo {
        return ModelInfo(
            isLoaded = isLoaded,
            fileSize = if (modelFile.exists()) modelFile.length() else 0,
            inputShape = "[1, 32]",
            outputShape = "[1, 19]"
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }

    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        FileInputStream(modelFile).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
        }
    }

    private fun softmax(input: FloatArray): FloatArray {
        val exp = input.map { kotlin.math.exp(it) }
        val sum = exp.sum()
        return if (sum > 0) exp.map { it / sum }.toFloatArray() else FloatArray(input.size) { 1f / input.size }
    }

    data class ModelInfo(
        val isLoaded: Boolean,
        val fileSize: Long,
        val inputShape: String,
        val outputShape: String
    )
}
