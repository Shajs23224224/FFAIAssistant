package com.ffai.assistant.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.ffai.assistant.utils.Logger

/**
 * FASE 8: PerformanceMonitor - Monitoreo de rendimiento para Samsung A21S.
 * 
 * Monitorea:
 * - Frame processing time (target: <33ms para 30 FPS)
 * - Memory usage (target: <150MB)
 * - Decision latency (target: <15ms táctico)
 * - Battery usage estimado
 * 
 * Ajusta calidad dinámicamente si hay lag.
 */
class PerformanceMonitor(private val context: Context) {
    
    companion object {
        const val TAG = "PerformanceMonitor"
        
        // Targets para Samsung A21S
        const val TARGET_FRAME_TIME_MS = 33L        // 30 FPS
        const val MAX_FRAME_TIME_MS = 50L         // Frame skip si excede
        const val TARGET_DECISION_TIME_MS = 15L   // Táctico
        const val TARGET_REFLEX_TIME_MS = 3L      // Reflejo
        const val MAX_MEMORY_MB = 200L          // Memoria máxima
        const val WARNING_MEMORY_MB = 150L       // Advertencia
        
        // Límites de calidad
        const val MIN_INPUT_SIZE = 160
        const val MAX_INPUT_SIZE = 320
    }
    
    // Métricas de frame
    private var frameStartTime: Long = 0L
    private var frameCount: Long = 0
    private var slowFrameCount: Long = 0
    private var totalFrameTimeMs: Long = 0
    private var lastFrameTimeMs: Long = 0
    
    // Métricas de decisión
    private var totalDecisions: Long = 0
    private var totalDecisionTimeMs: Long = 0
    private var slowDecisions: Long = 0
    
    // Métricas de reflex
    private var totalReflexes: Long = 0
    private var totalReflexTimeMs: Long = 0
    private var slowReflexes: Long = 0
    
    // Historial para adaptación
    private val frameTimeHistory: ArrayDeque<Long> = ArrayDeque(30)
    private val memoryHistory: ArrayDeque<Long> = ArrayDeque(10)
    
    // Estado de adaptación
    private var currentQualityLevel: QualityLevel = QualityLevel.MEDIUM
    private var consecutiveSlowFrames: Int = 0
    private var consecutiveFastFrames: Int = 0
    
    // ActivityManager para memoria
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    /**
     * Inicia medición de frame.
     */
    fun startFrame() {
        frameStartTime = System.currentTimeMillis()
    }
    
    /**
     * Finaliza medición de frame y reporta.
     */
    fun endFrame() {
        lastFrameTimeMs = System.currentTimeMillis() - frameStartTime
        
        frameCount++
        totalFrameTimeMs += lastFrameTimeMs
        
        frameTimeHistory.addLast(lastFrameTimeMs)
        if (frameTimeHistory.size > 30) {
            frameTimeHistory.removeFirst()
        }
        
        if (lastFrameTimeMs > TARGET_FRAME_TIME_MS) {
            slowFrameCount++
            consecutiveSlowFrames++
            consecutiveFastFrames = 0
        } else {
            consecutiveSlowFrames = 0
            consecutiveFastFrames++
        }
        
        // Adaptar calidad si necesario
        if (consecutiveSlowFrames >= 5) {
            reduceQuality()
        } else if (consecutiveFastFrames >= 30 && currentQualityLevel != QualityLevel.HIGH) {
            increaseQuality()
        }
    }
    
    /**
     * Mide tiempo de decisión táctica.
     */
    fun measureDecision(decisionTimeMs: Long) {
        totalDecisions++
        totalDecisionTimeMs += decisionTimeMs
        
        if (decisionTimeMs > TARGET_DECISION_TIME_MS) {
            slowDecisions++
        }
    }
    
    /**
     * Mide tiempo de reflejo.
     */
    fun measureReflex(reflexTimeMs: Long) {
        totalReflexes++
        totalReflexTimeMs += reflexTimeMs
        
        if (reflexTimeMs > TARGET_REFLEX_TIME_MS) {
            slowReflexes++
        }
    }
    
    /**
     * Obtiene uso actual de memoria (MB).
     */
    fun getMemoryUsage(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        
        // Memoria nativa
        val nativeMem = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        
        // Memoria del proceso
        val pid = Process.myPid()
        val memInfo = activityManager.getProcessMemoryInfo(intArrayOf(pid))
        val pssMem = memInfo[0].totalPss / 1024L
        
        memoryHistory.addLast(usedMem)
        if (memoryHistory.size > 10) {
            memoryHistory.removeFirst()
        }
        
        return MemoryStats(
            usedJavaHeapMb = usedMem,
            usedNativeHeapMb = nativeMem,
            processPssMb = pssMem,
            isCritical = pssMem > MAX_MEMORY_MB,
            isWarning = pssMem > WARNING_MEMORY_MB
        )
    }
    
    /**
     * Verifica si debe saltar frame.
     */
    fun shouldSkipFrame(): Boolean {
        return consecutiveSlowFrames >= 3 || lastFrameTimeMs > MAX_FRAME_TIME_MS
    }
    
    /**
     * Obtiene estadísticas completas.
     */
    fun getStats(): PerformanceStats {
        val avgFrameTime = if (frameCount > 0) totalFrameTimeMs / frameCount else 0
        val avgDecisionTime = if (totalDecisions > 0) totalDecisionTimeMs / totalDecisions else 0
        val avgReflexTime = if (totalReflexes > 0) totalReflexTimeMs / totalReflexes else 0
        
        val memory = getMemoryUsage()
        
        // Calcular FPS efectivo
        val effectiveFps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
        
        return PerformanceStats(
            totalFrames = frameCount,
            averageFrameTimeMs = avgFrameTime,
            slowFrameRatio = if (frameCount > 0) slowFrameCount.toFloat() / frameCount else 0f,
            effectiveFps = effectiveFps,
            averageDecisionTimeMs = avgDecisionTime,
            averageReflexTimeMs = avgReflexTime,
            memoryUsage = memory,
            currentQualityLevel = currentQualityLevel,
            lastFrameTimeMs = lastFrameTimeMs,
            p95FrameTimeMs = calculatePercentile(0.95f),
            p99FrameTimeMs = calculatePercentile(0.99f)
        )
    }
    
    /**
     * Obtiene resumen para UI/debug.
     */
    fun getSummary(): String {
        val stats = getStats()
        return buildString {
            append("[Performance] ")
            append("FPS: ${stats.effectiveFps.toInt()} | ")
            append("Frame: ${stats.averageFrameTimeMs}ms | ")
            append("Decision: ${stats.averageDecisionTimeMs}ms | ")
            append("Memory: ${stats.memoryUsage.processPssMb}MB | ")
            append("Quality: ${currentQualityLevel}")
        }
    }
    
    /**
     * Reduce calidad para mejorar performance.
     */
    private fun reduceQuality() {
        val oldLevel = currentQualityLevel
        currentQualityLevel = when (currentQualityLevel) {
            QualityLevel.HIGH -> QualityLevel.MEDIUM
            QualityLevel.MEDIUM -> QualityLevel.LOW
            QualityLevel.LOW -> QualityLevel.MINIMAL
            QualityLevel.MINIMAL -> QualityLevel.MINIMAL
        }
        
        if (oldLevel != currentQualityLevel) {
            Logger.w(TAG, "Reduciendo calidad: $oldLevel -> $currentQualityLevel (lag detectado)")
            consecutiveSlowFrames = 0
        }
    }
    
    /**
     * Incrementa calidad si hay margen.
     */
    private fun increaseQuality() {
        val oldLevel = currentQualityLevel
        currentQualityLevel = when (currentQualityLevel) {
            QualityLevel.MINIMAL -> QualityLevel.LOW
            QualityLevel.LOW -> QualityLevel.MEDIUM
            QualityLevel.MEDIUM -> QualityLevel.HIGH
            QualityLevel.HIGH -> QualityLevel.HIGH
        }
        
        if (oldLevel != currentQualityLevel) {
            Logger.i(TAG, "Aumentando calidad: $oldLevel -> $currentQualityLevel (performance buena)")
            consecutiveFastFrames = 0
        }
    }
    
    /**
     * Obtiene configuración de calidad actual.
     */
    fun getQualityConfig(): QualityConfig {
        return when (currentQualityLevel) {
            QualityLevel.HIGH -> QualityConfig(
                inputSize = MAX_INPUT_SIZE,
                modelComplexity = 1.0f,
                skipFrames = 1,
                enableFullFeatures = true
            )
            QualityLevel.MEDIUM -> QualityConfig(
                inputSize = 256,
                modelComplexity = 0.7f,
                skipFrames = 2,
                enableFullFeatures = true
            )
            QualityLevel.LOW -> QualityConfig(
                inputSize = 224,
                modelComplexity = 0.5f,
                skipFrames = 3,
                enableFullFeatures = false
            )
            QualityLevel.MINIMAL -> QualityConfig(
                inputSize = MIN_INPUT_SIZE,
                modelComplexity = 0.3f,
                skipFrames = 5,
                enableFullFeatures = false
            )
        }
    }
    
    /**
     * Calcula percentil de tiempos de frame.
     */
    private fun calculatePercentile(percentile: Float): Long {
        if (frameTimeHistory.isEmpty()) return 0
        
        val sorted = frameTimeHistory.sorted()
        val index = (percentile * sorted.size).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
    
    /**
     * Reset de estadísticas.
     */
    fun reset() {
        frameCount = 0
        slowFrameCount = 0
        totalFrameTimeMs = 0
        totalDecisions = 0
        slowDecisions = 0
        totalReflexes = 0
        slowReflexes = 0
        frameTimeHistory.clear()
        memoryHistory.clear()
        consecutiveSlowFrames = 0
        consecutiveFastFrames = 0
        Logger.i(TAG, "PerformanceMonitor reseteado")
    }
    
    /**
     * Reporta anomalías.
     */
    fun reportAnomalies(): List<String> {
        val anomalies = mutableListOf<String>()
        val stats = getStats()
        
        if (stats.effectiveFps < 20) {
            anomalies.add("FPS muy bajo: ${stats.effectiveFps.toInt()}")
        }
        if (stats.averageFrameTimeMs > 50) {
            anomalies.add("Frame time alto: ${stats.averageFrameTimeMs}ms")
        }
        if (stats.averageDecisionTimeMs > 20) {
            anomalies.add("Decision lenta: ${stats.averageDecisionTimeMs}ms")
        }
        if (stats.memoryUsage.processPssMb > MAX_MEMORY_MB) {
            anomalies.add("Memoria crítica: ${stats.memoryUsage.processPssMb}MB")
        }
        
        return anomalies
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class PerformanceStats(
    val totalFrames: Long,
    val averageFrameTimeMs: Long,
    val slowFrameRatio: Float,
    val effectiveFps: Float,
    val averageDecisionTimeMs: Long,
    val averageReflexTimeMs: Long,
    val memoryUsage: MemoryStats,
    val currentQualityLevel: QualityLevel,
    val lastFrameTimeMs: Long,
    val p95FrameTimeMs: Long,
    val p99FrameTimeMs: Long
)

data class MemoryStats(
    val usedJavaHeapMb: Long,
    val usedNativeHeapMb: Long,
    val processPssMb: Long,
    val isCritical: Boolean,
    val isWarning: Boolean
)

data class QualityConfig(
    val inputSize: Int,
    val modelComplexity: Float,
    val skipFrames: Int,
    val enableFullFeatures: Boolean
)

enum class QualityLevel {
    HIGH,       // Todo activado
    MEDIUM,     // Balanceado
    LOW,        // Optimizado
    MINIMAL     // Mínimo viable
}
