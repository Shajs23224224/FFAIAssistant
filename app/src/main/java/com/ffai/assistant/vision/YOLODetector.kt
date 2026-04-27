package com.ffai.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * FASE 1: YOLODetector - Detector de objetos usando YOLOv8n en TFLite.
 * 
 * Arquitectura:
 * - Input: 640x640 RGB bitmap
 * - Modelo: YOLOv8n-FP16 (3.2MB)
 * - Output: Detecciones [x, y, w, h, conf, class_id]
 * - Latencia target: 10-15ms en Samsung A21S (Mali-G52 MC2)
 * 
 * Clases detectadas:
 * - 0: enemy (enemigo)
 * - 1: loot_weapon (arma)
 * - 2: loot_heal (botiquín)
 * - 3: loot_ammo (munición)
 * - 4: vehicle (vehículo)
 * - 5: cover (cobertura)
 */
class YOLODetector(private val context: Context) {
    
    companion object {
        const val TAG = "YOLODetector"
        const val MODEL_NAME = "yolov8n_fp16.tflite"
        const val INPUT_SIZE = 640
        const val NUM_CLASSES = 6
        const val NUM_BOXES = 8400  // 80x80 + 40x40 + 20x20
        const val CONFIDENCE_THRESHOLD = 0.25f
        const val NMS_IOU_THRESHOLD = 0.45f
        
        // Class names
        val CLASS_NAMES = listOf("enemy", "loot_weapon", "loot_heal", "loot_ammo", "vehicle", "cover")
        val CLASS_COLORS = listOf(
            0xFFFF0000.toInt(),  // enemy - red
            0xFF00FF00.toInt(),  // weapon - green
            0xFF00FFFF.toInt(),  // heal - cyan
            0xFFFFFF00.toInt(),  // ammo - yellow
            0xFFFF00FF.toInt(),  // vehicle - magenta
            0xFF888888.toInt()   // cover - gray
        )
    }
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isInitialized = false
    
    // Input/output buffers
    private val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())
    
    // Output buffer: [1, 84, 8400] -> [batch, box_features + num_classes, num_boxes]
    private val outputBuffer = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(NUM_BOXES) } }
    
    // Performance tracking
    private var inferenceCount = 0
    private var totalInferenceTime = 0L
    private var lastFpsTime = 0L
    private var frameCount = 0
    
    /**
     * Inicializa el detector con el modelo YOLO.
     * Intenta usar GPU delegate si está disponible.
     */
    fun initialize(): Boolean {
        return try {
            Logger.i(TAG, "Inicializando YOLOv8n detector...")
            
            val modelBuffer = loadModelFile()
            
            // Intentar GPU delegate primero
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                
                // Intentar GPU acceleration
                try {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                    Logger.i(TAG, "GPU delegate activado")
                } catch (e: Exception) {
                    Logger.w(TAG, "GPU no disponible, usando CPU")
                }
            }
            
            interpreter = Interpreter(modelBuffer, options)
            isInitialized = true
            
            // Warm-up inference
            warmup()
            
            Logger.i(TAG, "YOLODetector inicializado correctamente")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error inicializando YOLO", e)
            isInitialized = false
            false
        }
    }
    
    /**
     * Detecta objetos en un bitmap.
     * @param bitmap Imagen de entrada (cualquier tamaño, se redimensionará)
     * @return Lista de detecciones ordenadas por confianza
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isInitialized) {
            Logger.w(TAG, "Detector no inicializado")
            return emptyList()
        }
        
        val startTime = System.currentTimeMillis()
        
        // Preprocesar imagen
        preprocess(bitmap)
        
        // Inferencia
        interpreter?.run(inputBuffer, outputBuffer)
        
        // Post-procesar salida
        val detections = postprocess(bitmap.width, bitmap.height)
        
        // Tracking performance
        val inferenceTime = System.currentTimeMillis() - startTime
        totalInferenceTime += inferenceTime
        inferenceCount++
        
        // Log FPS cada segundo
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val avgLatency = if (inferenceCount > 0) totalInferenceTime / inferenceCount else 0
            Logger.d(TAG, "FPS: $frameCount, Latencia: ${inferenceTime}ms, Promedio: ${avgLatency}ms")
            frameCount = 0
            lastFpsTime = now
        }
        
        return detections
    }
    
    /**
     * Detecta objetos con límite de confianza ajustable.
     * Útil para modo HIGH_PRECISION (bajar threshold).
     */
    fun detectWithThreshold(bitmap: Bitmap, confidenceThreshold: Float): List<Detection> {
        val detections = detect(bitmap)
        return detections.filter { it.confidence >= confidenceThreshold }
    }
    
    /**
     * Preprocesa el bitmap para YOLO.
     * Letterbox resize + normalización.
     */
    private fun preprocess(bitmap: Bitmap) {
        // Calcular dimensiones letterbox
        val scale = min(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        // Resize manteniendo aspect ratio
        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // Calcular padding
        val padX = (INPUT_SIZE - newWidth) / 2
        val padY = (INPUT_SIZE - newHeight) / 2
        
        // Llenar buffer
        inputBuffer.rewind()
        
        // Fondo negro para padding
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            inputBuffer.putFloat(0f)  // R
            inputBuffer.putFloat(0f)  // G
            inputBuffer.putFloat(0f)  // B
        }
        
        // Copiar pixels de la imagen redimensionada
        val pixels = IntArray(newWidth * newHeight)
        resized.getPixels(pixels, 0, newWidth, 0, 0, newWidth, newHeight)
        
        inputBuffer.rewind()
        
        // Saltar padding superior
        inputBuffer.position(padY * INPUT_SIZE * 3 * 4)
        
        for (y in 0 until newHeight) {
            // Saltar padding izquierdo
            inputBuffer.position(((padY + y) * INPUT_SIZE + padX) * 3 * 4)
            
            for (x in 0 until newWidth) {
                val pixel = pixels[y * newWidth + x]
                
                // Extraer RGB
                val r = ((pixel shr 16) and 0xFF).toFloat() / 255.0f
                val g = ((pixel shr 8) and 0xFF).toFloat() / 255.0f
                val b = (pixel and 0xFF).toFloat() / 255.0f
                
                // Normalización ImageNet
                inputBuffer.putFloat((r - 0.485f) / 0.229f)
                inputBuffer.putFloat((g - 0.456f) / 0.224f)
                inputBuffer.putFloat((b - 0.406f) / 0.225f)
            }
        }
        
        inputBuffer.rewind()
        
        // Liberar bitmap temporal
        if (resized != bitmap) {
            resized.recycle()
        }
    }
    
    /**
     * Post-procesa la salida de YOLO.
     * Convierte de formato raw a detecciones con NMS.
     */
    private fun postprocess(imgWidth: Int, imgHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        // Parsear cajas
        for (i in 0 until NUM_BOXES) {
            // Extraer coordenadas (formato cx, cy, w, h normalizado)
            val cx = outputBuffer[0][0][i]
            val cy = outputBuffer[0][1][i]
            val w = outputBuffer[0][2][i]
            val h = outputBuffer[0][3][i]
            
            // Encontrar clase con mayor confianza
            var maxClassConf = 0f
            var classId = 0
            
            for (c in 0 until NUM_CLASSES) {
                val conf = outputBuffer[0][4 + c][i]
                if (conf > maxClassConf) {
                    maxClassConf = conf
                    classId = c
                }
            }
            
            // Filtrar por confianza
            if (maxClassConf > CONFIDENCE_THRESHOLD) {
                // Convertir a coordenadas imagen
                val x1 = (cx - w / 2) * imgWidth
                val y1 = (cy - h / 2) * imgHeight
                val x2 = (cx + w / 2) * imgWidth
                val y2 = (cy + h / 2) * imgHeight
                
                detections.add(Detection(
                    x = x1,
                    y = y1,
                    width = x2 - x1,
                    height = y2 - y1,
                    confidence = maxClassConf,
                    classId = classId,
                    className = CLASS_NAMES[classId]
                ))
            }
        }
        
        // Aplicar NMS
        return applyNMS(detections)
    }
    
    /**
     * Non-Maximum Suppression para eliminar duplicados.
     */
    private fun applyNMS(detections: List<Detection>): List<Detection> {
        // Ordenar por confianza descendente
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)
        
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            
            result.add(sorted[i])
            
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                
                // Calcular IoU
                val iou = calculateIoU(sorted[i].toRectF(), sorted[j].toRectF())
                
                // Suprimir si IoU alto y misma clase
                if (iou > NMS_IOU_THRESHOLD && sorted[i].classId == sorted[j].classId) {
                    suppressed[j] = true
                }
            }
        }
        
        return result
    }
    
    /**
     * Calcula Intersection over Union entre dos rectángulos.
     */
    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersectionLeft = max(rect1.left, rect2.left)
        val intersectionTop = max(rect1.top, rect2.top)
        val intersectionRight = min(rect1.right, rect2.right)
        val intersectionBottom = min(rect1.bottom, rect2.bottom)
        
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * 
                               (intersectionBottom - intersectionTop)
        
        val rect1Area = rect1.width() * rect1.height()
        val rect2Area = rect2.width() * rect2.height()
        
        val unionArea = rect1Area + rect2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * Carga el archivo del modelo desde assets.
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_NAME)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
    
    /**
     * Warm-up para inicializar caches.
     */
    private fun warmup() {
        try {
            val dummyBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            preprocess(dummyBitmap)
            interpreter?.run(inputBuffer, outputBuffer)
            dummyBitmap.recycle()
            Logger.d(TAG, "Warm-up completado")
        } catch (e: Exception) {
            Logger.w(TAG, "Warm-up falló (no crítico)", e)
        }
    }
    
    /**
     * Obtiene estadísticas de performance.
     */
    fun getStats(): YoloStats {
        val avgLatency = if (inferenceCount > 0) totalInferenceTime / inferenceCount else 0
        return YoloStats(
            inferenceCount = inferenceCount,
            averageLatencyMs = avgLatency,
            isGpuAccelerated = gpuDelegate != null
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
        isInitialized = false
        Logger.i(TAG, "YOLODetector liberado")
    }
    
    fun isInitialized(): Boolean = isInitialized
}

/**
 * Representa una detección de objeto.
 */
data class Detection(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val classId: Int,
    val className: String
) {
    fun toRectF(): RectF = RectF(x, y, x + width, y + height)
    
    fun centerX(): Float = x + width / 2
    fun centerY(): Float = y + height / 2
    
    fun area(): Float = width * height
}

/**
 * Estadísticas del detector.
 */
data class YoloStats(
    val inferenceCount: Int,
    val averageLatencyMs: Long,
    val isGpuAccelerated: Boolean
)
