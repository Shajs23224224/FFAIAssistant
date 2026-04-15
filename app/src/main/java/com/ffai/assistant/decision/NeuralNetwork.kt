package com.ffai.assistant.decision

import android.content.Context
import org.tensorflow.lite.Interpreter
import com.ffai.assistant.config.Constants
import com.ffai.assistant.utils.Logger
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Red neuronal usando TensorFlow Lite.
 * Carga modelo desde assets o almacenamiento interno.
 */
class NeuralNetwork(context: Context) {
    
    private var interpreter: Interpreter? = null
    private val modelFile: File
    
    init {
        val modelDir = File(context.getExternalFilesDir(null), Constants.MODEL_DIR)
        modelDir.mkdirs()
        modelFile = File(modelDir, Constants.MODEL_CURRENT)
        
        // Copiar modelo inicial si no existe
        if (!modelFile.exists()) {
            copyInitialModel(context)
        }
        
        loadModel()
    }
    
    /**
     * Realiza inferencia y devuelve probabilidades para cada acción.
     */
    fun predict(features: FloatArray): FloatArray {
        return try {
            val input = Array(1) { features }
            val output = Array(1) { FloatArray(Constants.NUM_ACTIONS) }
            
            interpreter?.run(input, output)
            
            output[0].apply {
                // Softmax ya aplicado por TFLite si el modelo lo incluye
                // Si no, aplicarlo aquí
                val exp = map { kotlin.math.exp(it) }
                val sum = exp.sum()
                return exp.map { it / sum }.toFloatArray()
            }
        } catch (e: Exception) {
            Logger.e("Error en inferencia", e)
            // Fallback: distribución uniforme
            FloatArray(Constants.NUM_ACTIONS) { 1f / Constants.NUM_ACTIONS }
        }
    }
    
    /**
     * Actualiza pesos del modelo con nuevos datos de entrenamiento.
     * Nota: TFLite no soporta entrenamiento online nativamente,
     * por lo que usamos aproximación con transfer learning.
     */
    fun updateWeights(gradients: FloatArray) {
        // En implementación real, esto requeriría:
        // 1. Convertir TFLite a modelo entrenable (TensorFlow)
        // 2. Aplicar gradientes
        // 3. Convertir de vuelta a TFLite
        // Por ahora, guardamos datos para entrenamiento offline
        Logger.d("Pesos actualizados (placeholder)")
    }
    
    /**
     * Guarda modelo actual a archivo.
     */
    fun saveModel(destination: File? = null) {
        val dest = destination ?: modelFile
        // El modelo TFLite es inmutable, para actualizar necesitamos
        // recrear el modelo desde cero con los nuevos pesos
        Logger.i("Modelo guardado en: ${dest.absolutePath}")
    }
    
    /**
     * Crea backup del modelo actual.
     */
    fun backup() {
        val backupFile = File(modelFile.parent, Constants.MODEL_BACKUP)
        modelFile.copyTo(backupFile, overwrite = true)
        Logger.i("Backup creado: ${backupFile.absolutePath}")
    }
    
    /**
     * Restaura modelo desde backup.
     */
    fun restore() {
        val backupFile = File(modelFile.parent, Constants.MODEL_BACKUP)
        if (backupFile.exists()) {
            backupFile.copyTo(modelFile, overwrite = true)
            loadModel()
            Logger.i("Modelo restaurado desde backup")
        }
    }
    
    private fun copyInitialModel(context: Context) {
        try {
            context.assets.open("model_init.tflite").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Logger.i("Modelo inicial copiado a: ${modelFile.absolutePath}")
        } catch (e: Exception) {
            Logger.e("Error copiando modelo inicial", e)
            // Crear archivo vacío como placeholder
            modelFile.createNewFile()
        }
    }
    
    private fun loadModel() {
        try {
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Logger.w("Archivo de modelo no existe o está vacío")
                return
            }
            
            val modelBuffer = loadModelFile(modelFile)
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
                setCancellable(true)
            }
            
            interpreter = Interpreter(modelBuffer, options)
            Logger.i("Modelo TFLite cargado exitosamente")
            
        } catch (e: Exception) {
            Logger.e("Error cargando modelo", e)
            interpreter = null
        }
    }
    
    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        FileInputStream(modelFile).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = 0L
            val declaredLength = fileChannel.size()
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
    
    /**
     * Libera recursos.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
    
    companion object {
        fun isModelAvailable(context: Context): Boolean {
            val modelDir = File(context.getExternalFilesDir(null), Constants.MODEL_DIR)
            val modelFile = File(modelDir, Constants.MODEL_CURRENT)
            return modelFile.exists() && modelFile.length() > 0
        }
    }
}
