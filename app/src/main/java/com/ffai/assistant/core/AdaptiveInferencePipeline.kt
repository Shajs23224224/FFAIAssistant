package com.ffai.assistant.core

import android.graphics.Bitmap
import com.ffai.assistant.core.ReasoningModeCore
import com.ffai.assistant.vision.*
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*

/**
 * FASE 2: AdaptiveInferencePipeline - Pipeline de inferencia adaptativo.
 * 
 * Cambia dinámicamente entre modos según situación:
 * - SHORT: YOLO only (~8ms)
 * - MEDIUM: YOLO + Tactical (~15ms)
 * - LONG: Full ensemble (~30ms)
 */
class AdaptiveInferencePipeline(
    private val yoloDetector: YOLODetector,
    private val preprocessor: FramePreprocessor,
    private val postProcessor: DetectionPostProcessor
) {
    companion object {
        const val TAG = "AdaptiveInferencePipeline"
        const val SHORT_TARGET_MS = 8L
        const val MEDIUM_TARGET_MS = 15L
        const val LONG_TARGET_MS = 30L
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentMode = ReasoningMode.MEDIUM
    private var lastInferenceTime = 0L
    
    // Stats
    private var inferenceCount = 0
    private val modeStats = mutableMapOf(
        ReasoningMode.SHORT to ModeStats(),
        ReasoningMode.MEDIUM to ModeStats(),
        ReasoningMode.LONG to ModeStats()
    )
    
    data class ModeStats(var count: Int = 0, var totalTime: Long = 0)
    
    suspend fun processFrame(
        bitmap: Bitmap,
        situation: SituationAnalysis,
        tacticalInference: suspend () -> Unit = {},
        strategicInference: suspend () -> Unit = {}
    ): AdaptiveResult {
        val startTime = System.currentTimeMillis()
        
        // Actualizar modo según situación
        updateMode(situation.recommendedMode)
        
        // Ejecutar pipeline según modo
        val result = when (currentMode) {
            ReasoningMode.SHORT -> processShort(bitmap)
            ReasoningMode.MEDIUM -> processMedium(bitmap, tacticalInference)
            ReasoningMode.LONG -> processLong(bitmap, tacticalInference, strategicInference)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        lastInferenceTime = elapsed
        inferenceCount++
        
        // Update stats
        modeStats[currentMode]?.apply {
            count++
            totalTime += elapsed
        }
        
        if (inferenceCount % 60 == 0) {
            val target = when (currentMode) {
                ReasoningMode.SHORT -> SHORT_TARGET_MS
                ReasoningMode.MEDIUM -> MEDIUM_TARGET_MS
                ReasoningMode.LONG -> LONG_TARGET_MS
            }
            Logger.d(TAG, "Mode: $currentMode, Time: ${elapsed}ms (target: ${target}ms)")
        }
        
        return result.copy(actualLatency = elapsed)
    }
    
    private suspend fun processShort(bitmap: Bitmap): AdaptiveResult {
        // Solo YOLO - pipeline más rápido
        val detections = withContext(Dispatchers.Default) {
            val preprocessed = preprocessor.preprocess(bitmap)
            val raw = yoloDetector.detect(bitmap)
            postProcessor.process(raw, bitmap.width, bitmap.height)
        }
        
        return AdaptiveResult(
            mode = ReasoningMode.SHORT,
            detections = detections,
            hasTacticalData = false,
            hasStrategicData = false,
            actualLatency = 0L
        )
    }
    
    private suspend fun processMedium(
        bitmap: Bitmap,
        tacticalInference: suspend () -> Unit
    ): AdaptiveResult {
        // YOLO + Tactical en paralelo
        val deferredDetections = async {
            val raw = yoloDetector.detect(bitmap)
            postProcessor.process(raw, bitmap.width, bitmap.height)
        }
        
        val deferredTactical = async { tacticalInference() }
        
        val detections = deferredDetections.await()
        deferredTactical.await()
        
        return AdaptiveResult(
            mode = ReasoningMode.MEDIUM,
            detections = detections,
            hasTacticalData = true,
            hasStrategicData = false,
            actualLatency = 0L
        )
    }
    
    private suspend fun processLong(
        bitmap: Bitmap,
        tacticalInference: suspend () -> Unit,
        strategicInference: suspend () -> Unit
    ): AdaptiveResult {
        // Full ensemble - YOLO + Tactical + Strategic
        val deferredDetections = async {
            val raw = yoloDetector.detect(bitmap)
            postProcessor.process(raw, bitmap.width, bitmap.height)
        }
        
        val deferredTactical = async { tacticalInference() }
        val deferredStrategic = async { strategicInference() }
        
        val detections = deferredDetections.await()
        deferredTactical.await()
        deferredStrategic.await()
        
        return AdaptiveResult(
            mode = ReasoningMode.LONG,
            detections = detections,
            hasTacticalData = true,
            hasStrategicData = true,
            actualLatency = 0L
        )
    }
    
    private fun updateMode(recommended: ReasoningModeCore) {
        currentMode = when (recommended) {
            ReasoningModeCore.SHORT -> ReasoningMode.SHORT
            ReasoningModeCore.MEDIUM -> ReasoningMode.MEDIUM
            ReasoningModeCore.LONG -> ReasoningMode.LONG
        }
    }
    
    fun getStats(): PipelineStats {
        val stats = modeStats.mapValues { 
            val avg = if (it.value.count > 0) it.value.totalTime / it.value.count else 0
            Pair(it.value.count, avg)
        }
        return PipelineStats(
            totalInferences = inferenceCount,
            currentMode = currentMode,
            shortModeStats = stats[ReasoningMode.SHORT] ?: Pair(0, 0),
            mediumModeStats = stats[ReasoningMode.MEDIUM] ?: Pair(0, 0),
            longModeStats = stats[ReasoningMode.LONG] ?: Pair(0, 0)
        )
    }
    
    fun onThermalThrottle(thermalState: com.ffai.assistant.core.ThermalState) {
        // Reducir modo de inferencia según el estado térmico
        currentMode = when (thermalState) {
            com.ffai.assistant.core.ThermalState.NORMAL -> currentMode // Mantener modo actual
            com.ffai.assistant.core.ThermalState.MEDIUM -> ReasoningMode.MEDIUM
            com.ffai.assistant.core.ThermalState.SHORT -> ReasoningMode.SHORT
        }
        Logger.w(TAG, "Thermal throttling aplicado: $thermalState, modo cambiado a: $currentMode")
    }

    fun release() {
        scope.cancel()
    }
}

data class AdaptiveResult(
    val mode: ReasoningMode,
    val detections: List<TrackedDetection>,
    val hasTacticalData: Boolean,
    val hasStrategicData: Boolean,
    val actualLatency: Long
)

data class PipelineStats(
    val totalInferences: Int,
    val currentMode: ReasoningMode,
    val shortModeStats: Pair<Int, Long>,
    val mediumModeStats: Pair<Int, Long>,
    val longModeStats: Pair<Int, Long>
)
