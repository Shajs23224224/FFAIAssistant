package com.ffai.assistant.telemetry

import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FASE 6: PerformanceMonitor - Monitoreo de performance en tiempo real.
 * 
 * Métricas:
 * - Latencia por componente (capture → preprocess → inference → decision → gesture)
 * - FPS efectivo
 * - Uso de recursos (CPU, memoria)
 * - Accuracy tracking
 */
class PerformanceMonitor(private val scope: CoroutineScope) {
    
    companion object {
        const val TAG = "PerformanceMonitor"
        const val REPORT_INTERVAL_MS = 5000L
        
        // Targets de latencia
        const val TARGET_CAPTURE_MS = 2L
        const val TARGET_PREPROCESS_MS = 3L
        const val TARGET_YOLO_MS = 15L
        const val TARGET_RL_MS = 5L
        const val TARGET_GESTURE_MS = 3L
        const val TARGET_TOTAL_MS = 30L
    }
    
    // Pipeline stages timing
    private val stageTimings = mutableMapOf<String, StageMetrics>()
    
    // Frame tracking
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0
    
    // Latency history
    private val latencyHistory = ConcurrentLinkedQueue<Long>()
    
    // Stats
    private var totalFrames = 0
    private var droppedFrames = 0
    private var violations = 0  // Excedió target
    
    // Running job
    private var monitoringJob: Job? = null
    
    /**
     * Inicia monitoreo periódico.
     */
    fun startMonitoring() {
        monitoringJob = scope.launch {
            while (isActive) {
                delay(REPORT_INTERVAL_MS)
                reportMetrics()
            }
        }
        Logger.i(TAG, "Performance monitoring started")
    }
    
    /**
     * Registra tiempo de etapa del pipeline.
     */
    fun recordStageTime(stage: PipelineStage, durationMs: Long) {
        val metrics = stageTimings.getOrPut(stage.name) { StageMetrics() }
        metrics.record(durationMs)
        
        // Verificar target
        val target = when (stage) {
            PipelineStage.CAPTURE -> TARGET_CAPTURE_MS
            PipelineStage.PREPROCESS -> TARGET_PREPROCESS_MS
            PipelineStage.YOLO_INFERENCE -> TARGET_YOLO_MS
            PipelineStage.RL_DECISION -> TARGET_RL_MS
            PipelineStage.GESTURE_EXECUTION -> TARGET_GESTURE_MS
        }
        
        if (durationMs > target) {
            violations++
            if (violations % 100 == 0) {
                Logger.w(TAG, "Stage ${stage.name} exceeded target: ${durationMs}ms > ${target}ms")
            }
        }
    }
    
    /**
     * Registra frame completado.
     */
    fun recordFrame(totalLatencyMs: Long) {
        frameCount++
        totalFrames++
        
        latencyHistory.offer(totalLatencyMs)
        if (latencyHistory.size > 1000) latencyHistory.poll()
        
        // Calcular FPS
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = now
        }
        
        // Verificar si es frame drop
        if (totalLatencyMs > TARGET_TOTAL_MS) {
            droppedFrames++
        }
    }
    
    /**
     * Obtiene métricas actuales.
     */
    fun getStats(): PerformanceMetrics = getMetrics()
    
    /**
     * Obtiene métricas actuales.
     */
    fun getMetrics(): PerformanceMetrics {
        val stageMetrics = stageTimings.mapValues { (_, metrics) ->
            StagePerformance(
                average = metrics.getAverage(),
                min = metrics.getMin(),
                max = metrics.getMax(),
                p95 = metrics.getPercentile(95),
                violations = metrics.violations
            )
        }
        
        val avgLatency = if (latencyHistory.isNotEmpty()) {
            latencyHistory.average().toLong()
        } else 0L
        
        val p99Latency = if (latencyHistory.isNotEmpty()) {
            latencyHistory.sorted().let { it[(it.size * 0.99).toInt().coerceAtMost(it.size - 1)] }
        } else 0L
        
        return PerformanceMetrics(
            fps = currentFps,
            averageLatency = avgLatency,
            p99Latency = p99Latency,
            totalFrames = totalFrames,
            droppedFrames = droppedFrames,
            dropRate = if (totalFrames > 0) droppedFrames.toFloat() / totalFrames else 0f,
            stageMetrics = stageMetrics,
            violations = violations
        )
    }
    
    /**
     * Reporta métricas.
     */
    private fun reportMetrics() {
        val metrics = getMetrics()
        
        Logger.i(TAG, """
            Performance Report:
            FPS: ${metrics.fps}
            Latency: avg=${metrics.averageLatency}ms, p99=${metrics.p99Latency}ms
            Drop rate: ${"%.2f".format(metrics.dropRate * 100)}%
            Stages: ${metrics.stageMetrics.map { "${it.key}=${it.value.average}ms" }.joinToString(", ")}
        """.trimIndent())
    }
    
    /**
     * Reiniza métricas.
     */
    fun reset() {
        stageTimings.clear()
        latencyHistory.clear()
        frameCount = 0
        totalFrames = 0
        droppedFrames = 0
        violations = 0
        currentFps = 0
        lastFpsTime = System.currentTimeMillis()
        Logger.i(TAG, "Performance metrics reset")
    }
    
    /**
     * Detiene monitoreo.
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        Logger.i(TAG, "Performance monitoring stopped")
    }
    
    /**
     * Etapas del pipeline.
     */
    enum class PipelineStage {
        CAPTURE,          // Screen capture
        PREPROCESS,       // Frame preprocessing
        YOLO_INFERENCE,   // YOLO detection
        RL_DECISION,      // RL agent decision
        GESTURE_EXECUTION // Touch execution
    }
    
    /**
     * Métricas de etapa.
     */
    private class StageMetrics {
        private val timings = ArrayDeque<Long>(100)
        var violations = 0
        
        fun record(duration: Long) {
            timings.add(duration)
            if (timings.size > 100) timings.removeFirst()
        }
        
        fun getAverage(): Long = if (timings.isNotEmpty()) timings.average().toLong() else 0
        fun getMin(): Long = timings.minOrNull() ?: 0
        fun getMax(): Long = timings.maxOrNull() ?: 0
        fun getPercentile(p: Int): Long {
            if (timings.isEmpty()) return 0
            val sorted = timings.sorted()
            val index = (sorted.size * p / 100).coerceAtMost(sorted.size - 1)
            return sorted[index]
        }
    }
}

/**
 * Métricas de performance.
 */
data class PerformanceMetrics(
    val fps: Int,
    val averageLatency: Long,
    val p99Latency: Long,
    val totalFrames: Int,
    val droppedFrames: Int,
    val dropRate: Float,
    val stageMetrics: Map<String, StagePerformance>,
    val violations: Int
)

/**
 * Performance de etapa.
 */
data class StagePerformance(
    val average: Long,
    val min: Long,
    val max: Long,
    val p95: Long,
    val violations: Int
)
