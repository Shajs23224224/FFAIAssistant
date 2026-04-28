package com.ffai.assistant.vision

import com.ffai.assistant.model.CombatOutput
import com.ffai.assistant.model.EnemyDetection
import com.ffai.assistant.model.VisionOutput
import com.ffai.assistant.utils.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * FASE 1: VisionFusionEngine - Fusión de YOLO con modelos legacy.
 * 
 * Estrategia de fusión:
 * - YOLO es la fuente primaria de detecciones
 * - CombatNet legacy actúa como validador secundario
 * - Si hay conflicto (YOLO detecta, CombatNet no), investigar con VisionNet
 * - Votación ponderada por confianza histórica de cada fuente
 * 
 * Optimizado para robustez: si un modelo falla, el sistema sigue funcionando.
 */
class VisionFusionEngine {
    
    companion object {
        const val TAG = "VisionFusionEngine"
        
        // Pesos de confianza por fuente
        const val WEIGHT_YOLO = 0.6f
        const val WEIGHT_COMBAT = 0.25f
        const val WEIGHT_VISION = 0.15f
        
        // Umbrales
        const val CONFIDENCE_MERGE_THRESHOLD = 0.4f
        const val DISTANCE_MERGE_THRESHOLD = 50f  // pixels
        const val MIN_SOURCES_FOR_TRUSTED = 2
    }
    
    // Historial de confianza por fuente
    private var yoloAccuracy = 0.8f
    private var combatAccuracy = 0.7f
    private var visionAccuracy = 0.6f
    
    // Contadores para actualizar pesos
    private var yoloDetections = 0
    private var yoloCorrect = 0
    private var combatDetections = 0
    private var combatCorrect = 0
    
    /**
     * Fusiona detecciones de múltiples fuentes.
     * @param yoloDetections Detecciones de YOLO (fuente primaria)
     * @param combatOutput Salida del CombatNet legacy
     * @param visionOutput Salida del VisionNet legacy
     * @return Lista fusionada de detecciones de enemigos
     */
    fun fuseEnemyDetections(
        yoloDetections: List<com.ffai.assistant.vision.Detection>,
        combatOutput: CombatOutput?,
        visionOutput: VisionOutput?
    ): List<FusedEnemy> {
        val startTime = System.currentTimeMillis()
        
        // Convertir salidas legacy a formato común
        val combatEnemies = combatOutput?.enemies?.map { 
            LegacyDetection(it, Source.COMBAT) 
        } ?: emptyList()
        
        val visionEnemies = visionOutput?.objects
            ?.filter { it.type.name.contains("ENEMY") }
            ?.map { LegacyDetection(it, Source.VISION) }
            ?: emptyList()
        
        // Lista completa de todas las detecciones
        val allDetections = mutableListOf<FusedDetection>()
        
        // Agregar YOLO detections (fuente primaria)
        yoloDetections.filter { it.classId == 0 }  // class 0 = enemy
            .forEach { detection ->
                allDetections.add(FusedDetection(
                    x = detection.centerX(),
                    y = detection.centerY(),
                    width = detection.width,
                    height = detection.height,
                    confidence = detection.confidence * getSourceWeight(Source.YOLO),
                    sources = mutableListOf(DetectionSource(
                        source = Source.YOLO,
                        confidence = detection.confidence,
                        trackId = 0,
                        velocityX = 0f,
                        velocityY = 0f
                    )),
                    isPredicted = false
                ))
            }
        
        // Agregar detecciones legacy
        combatEnemies.forEach { mergeOrAddLegacy(it, allDetections) }
        visionEnemies.forEach { mergeOrAddLegacy(it, allDetections) }
        
        // Convertir a FusedEnemy final
        val result = allDetections.map { it.toFusedEnemy() }
            .sortedByDescending { it.fusedConfidence }
        
        // Actualizar estadísticas
        updateAccuracyStats(yoloDetections.size, combatEnemies.size)
        
        val elapsed = System.currentTimeMillis() - startTime
        Logger.d(TAG, "Fusion: ${yoloDetections.size} YOLO + ${combatEnemies.size} Combat + ${visionEnemies.size} Vision → ${result.size} fused (${elapsed}ms)")
        
        return result
    }
    
    /**
     * Intenta fusionar una detección legacy con existente, o agrega nueva.
     */
    private fun mergeOrAddLegacy(
        legacy: LegacyDetection,
        detections: MutableList<FusedDetection>
    ) {
        val centerX = legacy.x
        val centerY = legacy.y
        
        // Buscar detección existente cercana
        val matchingDetection = detections.find { existing ->
            val distance = kotlin.math.hypot(
                existing.x - centerX,
                existing.y - centerY
            )
            distance < DISTANCE_MERGE_THRESHOLD
        }
        
        if (matchingDetection != null) {
            // Fusionar: promediar posiciones, sumar confianza ponderada
            val weight = getSourceWeight(legacy.source)
            val legacyConfidence = legacy.confidence * weight
            
            // Promedio ponderado de posición
            val totalWeight = matchingDetection.sources.sumOf { (it.confidence * getSourceWeight(it.source)).toDouble() } + legacyConfidence
            
            matchingDetection.x = (
                (matchingDetection.x * matchingDetection.sources.size + centerX) / 
                (matchingDetection.sources.size + 1)
            )
            matchingDetection.y = (
                (matchingDetection.y * matchingDetection.sources.size + centerY) / 
                (matchingDetection.sources.size + 1)
            )
            
            // Actualizar dimensiones
            matchingDetection.width = max(matchingDetection.width, legacy.width)
            matchingDetection.height = max(matchingDetection.height, legacy.height)
            
            // Agregar fuente
            matchingDetection.sources.add(DetectionSource(
                source = legacy.source,
                confidence = legacy.confidence,
                trackId = -1,
                velocityX = 0f,
                velocityY = 0f
            ))
            
            // Recalcular confianza fusionada
            matchingDetection.confidence = calculateFusedConfidence(matchingDetection.sources)
        } else {
            // Agregar como nueva detección
            val weight = getSourceWeight(legacy.source)
            detections.add(FusedDetection(
                x = centerX,
                y = centerY,
                width = legacy.width,
                height = legacy.height,
                confidence = legacy.confidence * weight,
                sources = mutableListOf(DetectionSource(
                    source = legacy.source,
                    confidence = legacy.confidence,
                    trackId = -1,
                    velocityX = 0f,
                    velocityY = 0f
                )),
                isPredicted = false
            ))
        }
    }
    
    /**
     * Calcula confianza fusionada de múltiples fuentes.
     */
    private fun calculateFusedConfidence(sources: List<DetectionSource>): Float {
        if (sources.isEmpty()) return 0f
        
        // Sumar confianzas ponderadas
        val weightedSum = sources.sumOf {
            (it.confidence * getSourceWeight(it.source)).toDouble()
        }
        
        // Boost si múltiples fuentes concuerdan
        val agreementBoost = if (sources.size >= MIN_SOURCES_FOR_TRUSTED) 1.2f else 1.0f
        
        // Boost adicional si YOLO está presente (más confiable)
        val yoloBoost = if (sources.any { it.source == Source.YOLO }) 1.1f else 1.0f
        
        return min((weightedSum * agreementBoost * yoloBoost).toFloat(), 1.0f)
    }
    
    /**
     * Obtiene peso de confianza por fuente.
     */
    private fun getSourceWeight(source: Source): Float {
        return when (source) {
            Source.YOLO -> WEIGHT_YOLO * yoloAccuracy
            Source.COMBAT -> WEIGHT_COMBAT * combatAccuracy
            Source.VISION -> WEIGHT_VISION * visionAccuracy
        }
    }
    
    /**
     * Actualiza estadísticas de precisión.
     */
    private fun updateAccuracyStats(yoloCount: Int, combatCount: Int) {
        yoloDetections += yoloCount
        combatDetections += combatCount
        
        // Decay suave de precisión hacia baseline
        yoloAccuracy = yoloAccuracy * 0.99f + 0.8f * 0.01f
        combatAccuracy = combatAccuracy * 0.99f + 0.7f * 0.01f
    }
    
    /**
     * Reporta si una detección fue correcta (para actualizar pesos).
     */
    fun reportDetectionResult(source: Source, wasCorrect: Boolean) {
        when (source) {
            Source.YOLO -> {
                yoloCorrect += if (wasCorrect) 1 else 0
                yoloAccuracy = if (yoloDetections > 0) yoloCorrect.toFloat() / yoloDetections else 0.8f
            }
            Source.COMBAT -> {
                combatCorrect += if (wasCorrect) 1 else 0
                combatAccuracy = if (combatDetections > 0) combatCorrect.toFloat() / combatDetections else 0.7f
            }
            else -> { /* No tracking para VISION */ }
        }
    }
    
    /**
     * Obtiene estadísticas de fusión.
     */
    fun getStats(): FusionStats {
        return FusionStats(
            yoloAccuracy = yoloAccuracy,
            combatAccuracy = combatAccuracy,
            visionAccuracy = visionAccuracy,
            totalYOLODetections = yoloDetections,
            totalCombatDetections = combatDetections
        )
    }
    
    /**
     * Reinicia estadísticas.
     */
    fun reset() {
        yoloAccuracy = 0.8f
        combatAccuracy = 0.7f
        visionAccuracy = 0.6f
        yoloDetections = 0
        yoloCorrect = 0
        combatDetections = 0
        combatCorrect = 0
        Logger.i(TAG, "Estadísticas de fusión reiniciadas")
    }
}

/**
 * Fuente de detección.
 */
enum class Source {
    YOLO,      // YOLOv8n detector
    COMBAT,    // CombatNet legacy
    VISION     // VisionNet legacy
}

/**
 * Detección de fuente legacy.
 */
private data class LegacyDetection(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val source: Source
) {
    constructor(enemy: EnemyDetection, source: Source) : this(
        x = enemy.x,
        y = enemy.y,
        width = enemy.width,
        height = enemy.height,
        confidence = enemy.confidence,
        source = source
    )
    
    constructor(obj: com.ffai.assistant.model.DetectedObject, source: Source) : this(
        x = obj.x,
        y = obj.y,
        width = obj.width,
        height = obj.height,
        confidence = obj.confidence,
        source = source
    )
}

/**
 * Detección en proceso de fusión.
 */
private data class FusedDetection(
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var confidence: Float,
    val sources: MutableList<DetectionSource>,
    val isPredicted: Boolean
) {
    fun toFusedEnemy(): FusedEnemy {
        val yoloSource = sources.find { it.source == Source.YOLO }
        
        return FusedEnemy(
            x = x - width / 2,
            y = y - height / 2,
            width = width,
            height = height,
            fusedConfidence = confidence,
            sources = sources.map { it.source.toString() },
            isPredicted = isPredicted,
            hasYOLOConfirmation = yoloSource != null,
            hasLegacyConfirmation = sources.any { it.source != Source.YOLO },
            trackId = yoloSource?.trackId ?: -1,
            velocityX = yoloSource?.velocityX ?: 0f,
            velocityY = yoloSource?.velocityY ?: 0f,
            predictedPosition = if (yoloSource != null) {
                com.ffai.assistant.model.PredictedPosition(
                    x = x + yoloSource.velocityX * 3,
                    y = y + yoloSource.velocityY * 3,
                    timeMs = 100
                )
            } else null
        )
    }
}

/**
 * Fuente individual de detección.
 */
private data class DetectionSource(
    val source: Source,
    val confidence: Float,
    val trackId: Int,
    val velocityX: Float,
    val velocityY: Float
)

/**
 * Enemigo fusionado final.
 */
data class FusedEnemy(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fusedConfidence: Float,
    val sources: List<String>,
    val isPredicted: Boolean,
    val hasYOLOConfirmation: Boolean,
    val hasLegacyConfirmation: Boolean,
    val trackId: Int,
    val velocityX: Float,
    val velocityY: Float,
    val predictedPosition: com.ffai.assistant.model.PredictedPosition?
) {
    fun centerX(): Float = x + width / 2
    fun centerY(): Float = y + height / 2
    fun area(): Float = width * height
    
    /**
     * Indica si es una detección confiable (múltiples fuentes o YOLO).
     */
    fun isTrusted(): Boolean {
        return hasYOLOConfirmation || (hasLegacyConfirmation && fusedConfidence > 0.6f)
    }
    
    /**
     * Convierte a EnemyDetection para compatibilidad legacy.
     */
    fun toEnemyDetection(): EnemyDetection {
        return EnemyDetection(
            id = trackId.coerceAtLeast(0),
            x = x,
            y = y,
            width = width,
            height = height,
            confidence = fusedConfidence,
            distance = 0f,  // Calcular basado en tamaño
            isLocked = fusedConfidence > 0.8f,
            predictedPosition = predictedPosition
        )
    }
}

/**
 * Estadísticas de fusión.
 */
data class FusionStats(
    val yoloAccuracy: Float,
    val combatAccuracy: Float,
    val visionAccuracy: Float,
    val totalYOLODetections: Int,
    val totalCombatDetections: Int
)
