package com.ffai.assistant.perception

import android.graphics.Bitmap
import android.graphics.Color
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * FASE 3: LightweightVisionProcessor - Percepción optimizada para Samsung A21S.
 * 
 * Optimizaciones:
 * - Resolución reducida: 320x320 (en lugar de 640x640)
 * - ROI tracking: solo procesar región de interés
 * - Skip frames: procesar 1 de cada 2-3 frames si no hay amenaza
 * - Feature extraction rápido sin modelo pesado
 * - Modelo TFLite cuantizado INT8 (<5MB)
 * 
 * Target: < 20ms por frame en A21S.
 */
class LightweightVisionProcessor(
    private val targetWidth: Int = 320,
    private val targetHeight: Int = 320
) {
    
    companion object {
        const val TAG = "LightweightVisionProcessor"
        const val MAX_PROCESSING_TIME_MS = 20L
        const val FRAME_SKIP_LOW_RISK = 3  // Procesar 1 de cada 3 frames
        const val FRAME_SKIP_HIGH_RISK = 1 // Procesar todos los frames
        const val ROI_MARGIN = 50 // Pixels de margen alrededor del enemigo
    }
    
    // Modelo TFLite ligero
    private var interpreter: Interpreter? = null
    private var isModelLoaded = false
    
    // ROI tracking
    private var currentROI: Rect? = null
    private var roiFramesRemaining = 0
    private val ROI_PERSISTENCE_FRAMES = 5
    
    // Frame skipping
    private var frameCounter = 0
    private var currentSkipRatio = FRAME_SKIP_LOW_RISK
    
    // Reusar buffers para evitar allocations
    private var reusableBitmap: Bitmap? = null
    private var reusableIntArray: IntArray? = null
    private var reusableFloatArray: FloatArray? = null
    
    // Estadísticas
    private var totalFramesProcessed = 0
    private var totalProcessingTimeMs = 0L
    private var slowFrames = 0
    
    /**
     * Inicializa el procesador con modelo ligero.
     */
    fun initialize(modelPath: String? = null) {
        try {
            if (modelPath != null && File(modelPath).exists()) {
                loadModel(modelPath)
            } else {
                Logger.w(TAG, "No model provided, using heuristic mode")
            }
            
            // Pre-allocate buffers
            reusableBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            reusableIntArray = IntArray(targetWidth * targetHeight)
            reusableFloatArray = FloatArray(targetWidth * targetHeight * 3)
            
            Logger.i(TAG, "LightweightVisionProcessor initialized: ${targetWidth}x${targetHeight}")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize", e)
        }
    }
    
    /**
     * Procesa un frame y extrae información.
     * Target: < 20ms en Samsung A21S.
     */
    fun processFrame(
        frame: Bitmap,
        threatLevel: ThreatLevel = ThreatLevel.NONE
    ): VisionResult {
        val startTime = System.currentTimeMillis()
        
        // Frame skipping adaptativo
        if (shouldSkipFrame(threatLevel)) {
            return VisionResult.SKIPPED
        }
        
        // Determinar ROI
        val roi = determineROI(frame.width, frame.height, threatLevel)
        
        // Preprocesar (resize + normalize)
        val preprocessed = preprocessFrame(frame, roi)
        
        // Extraer features
        val features = if (isModelLoaded && interpreter != null) {
            extractWithModel(preprocessed)
        } else {
            extractWithHeuristics(preprocessed, roi)
        }
        
        // Detección rápida de enemigos
        val detections = detectEnemiesQuick(features, frame.width, frame.height)
        
        // Actualizar ROI para siguiente frame
        updateROI(detections, frame.width, frame.height)
        
        // Ajustar frame skip según performance
        val processingTime = System.currentTimeMillis() - startTime
        adaptFrameSkip(processingTime, threatLevel)
        
        // Actualizar estadísticas
        updateStats(processingTime)
        
        return VisionResult(
            detections = detections,
            roi = roi,
            processingTimeMs = processingTime,
            features = features
        )
    }
    
    /**
     * Determina si debe saltar este frame.
     */
    private fun shouldSkipFrame(threatLevel: ThreatLevel): Boolean {
        frameCounter++
        
        // Ajustar ratio según amenaza
        currentSkipRatio = when (threatLevel) {
            ThreatLevel.CRITICAL, ThreatLevel.HIGH -> FRAME_SKIP_HIGH_RISK
            else -> currentSkipRatio
        }
        
        return frameCounter % currentSkipRatio != 0
    }
    
    /**
     * Determina región de interés para procesar.
     */
    private fun determineROI(frameWidth: Int, frameHeight: Int, threatLevel: ThreatLevel): Rect? {
        // Si hay ROI activa, usarla
        if (roiFramesRemaining > 0 && currentROI != null) {
            roiFramesRemaining--
            return currentROI
        }
        
        // Si amenaza alta, procesar todo el frame
        if (threatLevel >= ThreatLevel.MEDIUM) {
            return null // Procesar todo
        }
        
        // Si no hay ROI, procesar todo
        return null
    }
    
    /**
     * Preprocesa frame: resize + normalización.
     */
    private fun preprocessFrame(frame: Bitmap, roi: Rect?): Bitmap {
        val sourceBitmap = if (roi != null) {
            // Extraer ROI
            Bitmap.createBitmap(
                frame,
                roi.x.coerceIn(0, frame.width - roi.width),
                roi.y.coerceIn(0, frame.height - roi.height),
                roi.width.coerceIn(1, frame.width - roi.x),
                roi.height.coerceIn(1, frame.height - roi.y)
            )
        } else {
            frame
        }
        
        // Resize al target
        val resized = if (sourceBitmap.width != targetWidth || sourceBitmap.height != targetHeight) {
            Bitmap.createScaledBitmap(sourceBitmap, targetWidth, targetHeight, false)
        } else {
            sourceBitmap
        }
        
        // Si creamos bitmap temporal, reciclar
        if (sourceBitmap !== frame && sourceBitmap !== resized) {
            sourceBitmap.recycle()
        }
        
        return resized
    }
    
    /**
     * Extrae features usando modelo TFLite.
     */
    private fun extractWithModel(bitmap: Bitmap): FloatArray {
        return try {
            // Convertir bitmap a array normalizado
            bitmap.getPixels(reusableIntArray, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            
            // Normalizar a 0-1 y separar canales (RGB)
            var idx = 0
            for (i in reusableIntArray!!.indices) {
                val pixel = reusableIntArray!![i]
                reusableFloatArray!![idx++] = (Color.red(pixel) / 255.0f)
                reusableFloatArray!![idx++] = (Color.green(pixel) / 255.0f)
                reusableFloatArray!![idx++] = (Color.blue(pixel) / 255.0f)
            }
            
            // Inferencia
            val input = Array(1) { Array(targetHeight) { Array(targetWidth) { FloatArray(3) } } }
            // Llenar input con reusableFloatArray
            var flatIdx = 0
            for (y in 0 until targetHeight) {
                for (x in 0 until targetWidth) {
                    for (c in 0 until 3) {
                        input[0][y][x][c] = reusableFloatArray!![flatIdx++]
                    }
                }
            }
            
            val output = Array(1) { FloatArray(100) } // Features del modelo
            interpreter?.run(input, output)
            
            output[0]
        } catch (e: Exception) {
            Logger.e(TAG, "Model inference failed", e)
            FloatArray(100) { 0f }
        }
    }
    
    /**
     * Extrae features usando heurísticas (sin modelo).
     */
    private fun extractWithHeuristics(bitmap: Bitmap, roi: Rect?): FloatArray {
        val features = FloatArray(20) // 20 features heurísticas
        
        // Análisis de histograma por regiones
        val regionsX = 4
        val regionsY = 4
        val regionWidth = targetWidth / regionsX
        val regionHeight = targetHeight / regionsY
        
        var featureIdx = 0
        
        for (ry in 0 until regionsY) {
            for (rx in 0 until regionsX) {
                val startX = rx * regionWidth
                val startY = ry * regionHeight
                
                // Calcular color promedio de región
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                var count = 0
                
                for (y in startY until (startY + regionHeight).coerceAtMost(targetHeight)) {
                    for (x in startX until (startX + regionWidth).coerceAtMost(targetWidth)) {
                        val pixel = bitmap.getPixel(x, y)
                        rSum += Color.red(pixel)
                        gSum += Color.green(pixel)
                        bSum += Color.blue(pixel)
                        count++
                    }
                }
                
                if (count > 0) {
                    // Brightness de región
                    val brightness = (rSum + gSum + bSum) / (3f * count * 255f)
                    features[featureIdx++] = brightness
                    
                    // Color dominance
                    if (featureIdx < features.size) {
                        val rNorm = rSum / (count * 255f)
                        val gNorm = gSum / (count * 255f)
                        val bNorm = bSum / (count * 255f)
                        
                        // Simple "enemy-like" detection (skin tones, dark figures)
                        val enemyLikeness = if (rNorm > 0.3f && rNorm < 0.7f && 
                                                gNorm > 0.2f && gNorm < 0.6f &&
                                                bNorm > 0.15f && bNorm < 0.5f) 0.7f else 0.1f
                        
                        features[featureIdx++] = enemyLikeness
                    }
                }
            }
        }
        
        return features
    }
    
    /**
     * Detección rápida de enemigos basada en features.
     */
    private fun detectEnemiesQuick(
        features: FloatArray,
        frameWidth: Int,
        frameHeight: Int
    ): List<QuickDetection> {
        val detections = mutableListOf<QuickDetection>()
        
        // Si usamos modelo, buscar en features
        if (features.size >= 100) {
            // Buscar peaks en features que indican enemigos
            for (i in features.indices) {
                if (features[i] > 0.7f) {
                    // Calcular posición aproximada
                    val regionIdx = i % 16 // Asumir 16 regiones
                    val rx = regionIdx % 4
                    val ry = regionIdx / 4
                    
                    val screenX = (rx + 0.5f) / 4f * frameWidth
                    val screenY = (ry + 0.5f) / 4f * frameHeight
                    
                    detections.add(QuickDetection(
                        x = screenX / frameWidth,
                        y = screenY / frameHeight,
                        confidence = features[i],
                        type = DetectionType.ENEMY
                    ))
                }
            }
        } else {
            // Modo heurístico: buscar regiones con "enemyLikeness" alto
            for (i in 1 until features.size step 2) {
                if (features[i] > 0.6f) {
                    val regionIdx = i / 2
                    val rx = regionIdx % 4
                    val ry = regionIdx / 4
                    
                    val screenX = (rx + 0.5f) / 4f * frameWidth
                    val screenY = (ry + 0.5f) / 4f * frameHeight
                    
                    detections.add(QuickDetection(
                        x = screenX / frameWidth,
                        y = screenY / frameHeight,
                        confidence = features[i],
                        type = DetectionType.POTENTIAL_ENEMY
                    ))
                }
            }
        }
        
        // Agrupar detecciones cercanas (NMS simplificado)
        return nonMaxSuppression(detections)
    }
    
    /**
     * Non-maximum suppression simplificado.
     */
    private fun nonMaxSuppression(detections: List<QuickDetection>): List<QuickDetection> {
        if (detections.size <= 1) return detections
        
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<QuickDetection>()
        val suppressed = BooleanArray(sorted.size)
        
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            
            result.add(sorted[i])
            
            // Suprimir cercanos
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j]) {
                    val dist = kotlin.math.hypot(
                        sorted[i].x - sorted[j].x,
                        sorted[i].y - sorted[j].y
                    )
                    if (dist < 0.15f) { // Umbral de proximidad
                        suppressed[j] = true
                    }
                }
            }
        }
        
        return result
    }
    
    /**
     * Actualiza ROI basado en detecciones.
     */
    private fun updateROI(detections: List<QuickDetection>, frameWidth: Int, frameHeight: Int) {
        if (detections.isEmpty()) {
            // Decrementar persistencia
            roiFramesRemaining = (roiFramesRemaining - 1).coerceAtLeast(0)
            if (roiFramesRemaining <= 0) {
                currentROI = null
            }
            return
        }
        
        // Crear ROI alrededor de la detección más confiable
        val best = detections.maxByOrNull { it.confidence } ?: return
        
        val cx = (best.x * frameWidth).toInt()
        val cy = (best.y * frameHeight).toInt()
        
        currentROI = Rect(
            x = (cx - ROI_MARGIN * 2).coerceAtLeast(0),
            y = (cy - ROI_MARGIN * 2).coerceAtLeast(0),
            width = ROI_MARGIN * 4,
            height = ROI_MARGIN * 4
        )
        
        roiFramesRemaining = ROI_PERSISTENCE_FRAMES
    }
    
    /**
     * Adapta frame skip según tiempo de procesamiento.
     */
    private fun adaptFrameSkip(processingTime: Long, threatLevel: ThreatLevel) {
        if (threatLevel >= ThreatLevel.MEDIUM) {
            currentSkipRatio = FRAME_SKIP_HIGH_RISK
            return
        }
        
        // Si es muy lento, saltar más frames
        currentSkipRatio = when {
            processingTime > 30 -> 5
            processingTime > 25 -> 4
            processingTime > 20 -> 3
            else -> FRAME_SKIP_LOW_RISK
        }
    }
    
    /**
     * Carga modelo TFLite.
     */
    private fun loadModel(modelPath: String) {
        try {
            val file = File(modelPath)
            val buffer = FileInputStream(file).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(2) // Usar 2 threads para A21S
                // NNAPI deshabilitado en A21S (poco compatible)
                useNNAPI = false
            }
            
            interpreter = Interpreter(buffer, options)
            isModelLoaded = true
            
            Logger.i(TAG, "Model loaded: $modelPath (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load model", e)
            isModelLoaded = false
        }
    }
    
    /**
     * Actualiza estadísticas.
     */
    private fun updateStats(processingTime: Long) {
        totalFramesProcessed++
        totalProcessingTimeMs += processingTime
        if (processingTime > MAX_PROCESSING_TIME_MS) {
            slowFrames++
        }
    }
    
    /**
     * Obtiene estadísticas de performance.
     */
    fun getStats(): VisionStats {
        val avgTime = if (totalFramesProcessed > 0) {
            totalProcessingTimeMs / totalFramesProcessed
        } else 0
        
        return VisionStats(
            totalFramesProcessed = totalFramesProcessed,
            averageProcessingTimeMs = avgTime,
            slowFrameRatio = if (totalFramesProcessed > 0) {
                slowFrames.toFloat() / totalFramesProcessed
            } else 0f,
            currentSkipRatio = currentSkipRatio,
            isModelLoaded = isModelLoaded
        )
    }
    
    /**
     * Reset y liberar recursos.
     */
    fun release() {
        interpreter?.close()
        interpreter = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        isModelLoaded = false
        Logger.i(TAG, "LightweightVisionProcessor released")
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class VisionResult(
    val detections: List<QuickDetection>,
    val roi: Rect?,
    val processingTimeMs: Long,
    val features: FloatArray
) {
    companion object {
        val SKIPPED = VisionResult(
            detections = emptyList(),
            roi = null,
            processingTimeMs = 0,
            features = FloatArray(0)
        )
    }
}

data class QuickDetection(
    val x: Float, // Normalizado 0-1
    val y: Float, // Normalizado 0-1
    val confidence: Float,
    val type: DetectionType
)

data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class VisionStats(
    val totalFramesProcessed: Int,
    val averageProcessingTimeMs: Long,
    val slowFrameRatio: Float,
    val currentSkipRatio: Int,
    val isModelLoaded: Boolean
)

enum class DetectionType {
    ENEMY,
    POTENTIAL_ENEMY,
    LOOT,
    UNKNOWN
}

private fun Bitmap.getPixels(pixels: IntArray?, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
    // Compatibilidad - implementación básica
    var idx = 0
    for (yy in y until (y + height).coerceAtMost(this.height)) {
        for (xx in x until (x + width).coerceAtMost(this.width)) {
            pixels?.set(offset + (yy - y) * stride + (xx - x), getPixel(xx, yy))
        }
    }
}
