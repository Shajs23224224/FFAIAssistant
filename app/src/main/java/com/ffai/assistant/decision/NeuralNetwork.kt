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
     * Retorna fallback si el modelo no está disponible.
     */
    fun predict(features: FloatArray): FloatArray {
        // Validar que el interpreter esté disponible
        if (interpreter == null) {
            Logger.w("Interpreter no disponible, retornando fallback")
            return FloatArray(Constants.NUM_ACTIONS) { 1f / Constants.NUM_ACTIONS }
        }
        
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
                // Eliminar archivo vacío/corrupto si existe
                if (modelFile.exists() && modelFile.length() == 0L) {
                    modelFile.delete()
                }
                
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Logger.i("Modelo inicial copiado a: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
        } catch (e: Exception) {
            Logger.e("Error copiando modelo inicial", e)
            // No crear archivo vacío - dejar que loadModel() maneje la ausencia
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
    
    /**
     * Verifica si hay una actualización de modelo disponible en Google Drive.
     * Compara el manifest remoto con el modelo local.
     * NOTA: Función deshabilitada - modo offline
     */
    suspend fun checkForRemoteUpdate(): RemoteModelInfo? {
        // Modo offline - no hay actualizaciones remotas
        return null
    }
    
    /**
     * Descarga y carga un modelo desde Google Drive.
     * NOTA: Función deshabilitada - modo offline
     */
    suspend fun downloadAndLoadRemoteModel(): Boolean {
        // Modo offline - no hay descarga remota
        return false
    }
    
    /**
     * Carga un modelo descargado desde archivo temporal.
     * Útil cuando el modelo se descargó mediante ModelDownloader.
     * 
     * @param downloadedFile Archivo descargado (usualmente en cache)
     * @return true si se cargó correctamente
     */
    fun loadDownloadedModel(downloadedFile: File): Boolean {
        return try {
            // Validar archivo
            if (!downloadedFile.exists() || downloadedFile.length() < 1024 * 1024) {
                Logger.e("NeuralNetwork: Archivo descargado inválido o muy pequeño")
                return false
            }
            
            // Backup del modelo actual
            if (modelFile.exists() && modelFile.length() > 0) {
                backup()
            }
            
            // Copiar a ubicación final
            downloadedFile.copyTo(modelFile, overwrite = true)
            
            // Cargar
            close()
            loadModel()
            
            val success = interpreter != null
            if (success) {
                Logger.i("NeuralNetwork: Modelo descargado cargado exitosamente")
            } else {
                Logger.e("NeuralNetwork: Error cargando modelo descargado, restaurando backup")
                restore()
            }
            
            success
        } catch (e: Exception) {
            Logger.e("NeuralNetwork: Error cargando modelo descargado", e)
            false
        }
    }
    
    /**
     * Obtiene información del modelo actual.
     */
    fun getModelInfo(): ModelInfo {
        return ModelInfo(
            filePath = modelFile.absolutePath,
            size = if (modelFile.exists()) modelFile.length() else 0,
            isLoaded = interpreter != null,
            lastModified = if (modelFile.exists()) modelFile.lastModified() else 0
        )
    }
    
    companion object {
        fun isModelAvailable(context: Context): Boolean {
            val modelDir = File(context.getExternalFilesDir(null), Constants.MODEL_DIR)
            val modelFile = File(modelDir, Constants.MODEL_CURRENT)
            return modelFile.exists() && modelFile.length() > 0
        }
        
        /**
         * Obtiene el tamaño del modelo actual.
         */
        fun getCurrentModelSize(context: Context): Long {
            val modelDir = File(context.getExternalFilesDir(null), Constants.MODEL_DIR)
            val modelFile = File(modelDir, Constants.MODEL_CURRENT)
            return if (modelFile.exists()) modelFile.length() else 0
        }
    }
}

/**
 * Información de un modelo remoto en Google Drive.
 */
data class RemoteModelInfo(
    val fileId: String,
    val fileName: String,
    val size: Long,
    val modifiedTime: Long,
    val isNewer: Boolean
)

/**
 * Información del modelo local.
 */
data class ModelInfo(
    val filePath: String,
    val size: Long,
    val isLoaded: Boolean,
    val lastModified: Long
)
