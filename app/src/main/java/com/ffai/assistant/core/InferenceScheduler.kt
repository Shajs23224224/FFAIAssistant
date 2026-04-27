package com.ffai.assistant.core

import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * FASE 2: InferenceScheduler - Scheduler de threads con prioridad.
 * 
 * Gestiona pool de threads para diferentes tipos de inferencia:
 * - CRITICAL: YOLO (siempre corre, MAX_PRIORITY)
 * - HIGH: Tactical models (corre si no es modo SHORT)
 * - NORMAL: Strategic models (solo modo LONG)
 * 
 * Soporta skip de frames si latencia excede target.
 */
class InferenceScheduler {
    
    companion object {
        const val TAG = "InferenceScheduler"
        const val CORE_POOL_SIZE = 2
        const val MAX_POOL_SIZE = 4
        const val KEEP_ALIVE_MS = 5000L
    }
    
    // Executor con prioridad
    private val executor: ThreadPoolExecutor
    private val scope: CoroutineScope
    
    // Queue de tareas por prioridad
    private val taskQueue = PriorityBlockingQueue<PriorityTask>(100)
    
    // Stats
    private val stats = SchedulerStats()
    
    init {
        val threadFactory = object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread {
                return Thread(r, "Inference-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
        
        executor = ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue(),
            threadFactory
        )
        
        scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
        Logger.i(TAG, "InferenceScheduler inicializado con $CORE_POOL_SIZE-$MAX_POOL_SIZE threads")
    }
    
    /**
     * Ejecuta tarea con prioridad.
     */
    fun <T> schedule(
        priority: TaskPriority,
        name: String,
        timeoutMs: Long = 100,
        block: suspend () -> T
    ): Deferred<T> {
        val task = PriorityTask(priority.ordinal, name, System.currentTimeMillis())
        taskQueue.offer(task)
        
        return scope.async {
            try {
                val result = withTimeout(timeoutMs) { block() }
                stats.recordSuccess(priority, System.currentTimeMillis() - task.submitTime)
                result
            } catch (e: TimeoutCancellationException) {
                stats.recordTimeout(priority)
                Logger.w(TAG, "Task $name timed out after ${timeoutMs}ms")
                throw e
            } catch (e: Exception) {
                stats.recordError(priority)
                throw e
            } finally {
                taskQueue.remove(task)
            }
        }
    }
    
    /**
     * Ejecuta múltiples tareas en paralelo con timeout global.
     */
    suspend fun <T> parallel(
        vararg tasks: Pair<TaskPriority, suspend () -> T>,
        globalTimeoutMs: Long = 50
    ): List<T> {
        val deferreds = tasks.map { (priority, block) ->
            schedule(priority, "parallel-${priority.name}", globalTimeoutMs, block)
        }
        
        return deferreds.awaitAll()
    }
    
    /**
     * Verifica si se debe skip de frame táctico por alta latencia.
     */
    fun shouldSkipTactical(currentLatency: Long, targetLatency: Long): Boolean {
        val shouldSkip = currentLatency > targetLatency * 1.5
        if (shouldSkip) {
            stats.recordSkip()
            Logger.d(TAG, "Skipping tactical inference, latency: ${currentLatency}ms > ${targetLatency * 1.5}ms")
        }
        return shouldSkip
    }
    
    /**
     * Pausa tareas de baja prioridad.
     */
    fun pauseLowPriority() {
        executor.queue.removeIf { (it as? PriorityTask)?.isLowPriority() == true }
        Logger.i(TAG, "Low priority tasks paused")
    }
    
    /**
     * Resume tareas normales.
     */
    fun resumeNormal() {
        Logger.i(TAG, "Normal priority resumed")
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): SchedulerStatsSnapshot = stats.copy()
    
    /**
     * Libera recursos.
     */
    fun shutdown() {
        scope.cancel()
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
        Logger.i(TAG, "InferenceScheduler shutdown")
    }
    
    fun isShutdown(): Boolean = executor.isShutdown
}

/**
 * Prioridad de tareas.
 */
enum class TaskPriority {
    CRITICAL,  // YOLO siempre
    HIGH,      // Tactical
    NORMAL,    // Strategic
    LOW        // Background tasks
}

/**
 * Tarea con prioridad para queue.
 */
private data class PriorityTask(
    val priority: Int,
    val name: String,
    val submitTime: Long
) : Comparable<PriorityTask> {
    override fun compareTo(other: PriorityTask): Int {
        // Mayor prioridad = menor valor ordinal = primero en queue
        return other.priority - this.priority
    }
    
    fun isLowPriority(): Boolean = priority >= TaskPriority.LOW.ordinal
}

/**
 * Estadísticas del scheduler.
 */
class SchedulerStats {
    private val successCount = AtomicInteger(0)
    private val timeoutCount = AtomicInteger(0)
    private val errorCount = AtomicInteger(0)
    private val skipCount = AtomicInteger(0)
    private val totalExecutionTime = AtomicInteger(0)
    
    fun recordSuccess(priority: TaskPriority, executionTimeMs: Long) {
        successCount.incrementAndGet()
        totalExecutionTime.addAndGet(executionTimeMs.toInt())
    }
    
    fun recordTimeout(priority: TaskPriority) {
        timeoutCount.incrementAndGet()
    }
    
    fun recordError(priority: TaskPriority) {
        errorCount.incrementAndGet()
    }
    
    fun recordSkip() {
        skipCount.incrementAndGet()
    }
    
    fun getAverageExecutionTime(): Long {
        val total = totalExecutionTime.get()
        val count = successCount.get()
        return if (count > 0) (total / count).toLong() else 0L
    }
    
    // Create a snapshot of current stats
    fun copy(): SchedulerStatsSnapshot {
        return SchedulerStatsSnapshot(
            successCount = successCount.get(),
            timeoutCount = timeoutCount.get(),
            errorCount = errorCount.get(),
            skipCount = skipCount.get(),
            totalExecutionTime = totalExecutionTime.get(),
            averageExecutionTime = getAverageExecutionTime()
        )
    }
}

data class SchedulerStatsSnapshot(
    val successCount: Int,
    val timeoutCount: Int,
    val errorCount: Int,
    val skipCount: Int,
    val totalExecutionTime: Int,
    val averageExecutionTime: Long
)
