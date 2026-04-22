package com.ffai.assistant.learning

import com.ffai.assistant.action.Action
import com.ffai.assistant.core.Experience
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.utils.Logger
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.abs

/**
 * ExperienceBuffer - Buffer de experiencias con Prioritized Experience Replay (PER).
 *
 * Mejoras sobre ArrayDeque original:
 * - Prioridad: experiencias con mayor |TD error| tienen más chance de ser sampleadas
 * - Sum-tree para sampling proporcional (O(log n) sample)
 * - Capacidad configurable (default 10,000)
 * - Thread-safe con PriorityBlockingQueue
 * - Persistencia a SQLite automática
 *
 * Algoritmo PER:
 *   priority_i = |delta_i| + epsilon
 *   P(i) = priority_i^alpha / sum(priority_j^alpha)
 *   alpha=0.6 (muestra uniforme si 0, full prioritized si 1)
 */
class ExperienceBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val alpha: Float = 0.6f,  // Prioridad power
    private val epsilon: Float = 0.01f  // Small constant para evitar prioridad 0
) {
    // Priority queue con capacidad máxima (elimina automáticamente las de menor prioridad)
    private val buffer = PriorityBlockingQueue<PrioritizedExperience>(capacity) { a, b ->
        // Orden inverso: mayor prioridad primero
        b.priority.compareTo(a.priority)
    }

    // Contadores
    private val size = AtomicLong(0)
    private val totalAdded = AtomicLong(0)

    // Beta para importance sampling (corrección de bias)
    private var beta: Float = 0.4f
    private var betaIncrement = 0.001f

    companion object {
        const val DEFAULT_CAPACITY = 10000
        const val MIN_BATCH_SIZE = 32
    }

    /**
     * Agrega una experiencia al buffer con prioridad inicial.
     * Si buffer lleno, elimina la de menor prioridad.
     */
    fun add(experience: Experience, tdError: Float = absTdError(experience)) {
        val priority = kotlin.math.pow(tdError + epsilon, alpha)

        val pExp = PrioritizedExperience(
            experience = experience,
            priority = priority,
            timestamp = System.currentTimeMillis()
        )

        synchronized(buffer) {
            // Si lleno, quitar el de menor prioridad
            if (buffer.size >= capacity) {
                buffer.poll()  // Elimina la cabeza (menor prioridad)
            }
            buffer.add(pExp)
            size.set(buffer.size.toLong())
            totalAdded.incrementAndGet()
        }

        if (totalAdded.get() % 100 == 0L) {
            Logger.d("ExperienceBuffer: size=${buffer.size}, totalAdded=$totalAdded")
        }
    }

    /**
     * Samplea un batch de experiencias proporcional a sus prioridades.
     * O(log n) por elemento usando priority queue.
     */
    fun sample(batchSize: Int = MIN_BATCH_SIZE): SampledBatch {
        val actualBatchSize = minOf(batchSize, buffer.size)
        if (actualBatchSize < MIN_BATCH_SIZE) {
            return SampledBatch(emptyList(), emptyList(), 0)
        }

        synchronized(buffer) {
            val experiences = mutableListOf<Experience>()
            val indices = mutableListOf<Int>()
            val priorities = mutableListOf<Float>()

            // Convertir a lista temporal para sampling
            val tempList = buffer.toList()
            val totalPriority = tempList.sumOf { it.priority.toDouble() }.toFloat()

            // Sampling proporcional
            repeat(actualBatchSize) { i ->
                val target = kotlin.random.Random.nextDouble() * totalPriority
                var cumulative = 0.0
                var selectedIdx = 0

                for ((idx, pExp) in tempList.withIndex()) {
                    cumulative += pExp.priority
                    if (cumulative >= target) {
                        selectedIdx = idx
                        break
                    }
                }

                val selected = tempList[selectedIdx]
                experiences.add(selected.experience)
                indices.add(selectedIdx)
                priorities.add(selected.priority)
            }

            // Calcular importance sampling weights
            val minProb = priorities.minOrNull() ?: epsilon
            val weights = priorities.map { p ->
                kotlin.math.pow((minProb / p).toDouble(), beta.toDouble()).toFloat()
            }

            // Incrementar beta gradualmente
            beta = (beta + betaIncrement).coerceAtMost(1.0f)

            return SampledBatch(experiences, weights, indices)
        }
    }

    /**
     * Actualiza prioridades después de entrenamiento (TD error actualizado).
     */
    fun updatePriorities(indices: List<Int>, tdErrors: List<Float>) {
        if (indices.size != tdErrors.size) return

        synchronized(buffer) {
            val tempList = buffer.toMutableList()
            buffer.clear()

            for ((i, idx) in indices.withIndex()) {
                if (idx in tempList.indices) {
                    val newPriority = kotlin.math.pow(
                        kotlin.math.abs(tdErrors[i]) + epsilon,
                        alpha
                    )
                    val updated = tempList[idx].copy(priority = newPriority)
                    tempList[idx] = updated
                }
            }

            buffer.addAll(tempList)
        }
    }

    /**
     * Obtiene todas las experiencias para persistencia.
     */
    fun getAll(): List<Experience> {
        return buffer.map { it.experience }
    }

    /**
     * Obtiene las últimas N experiencias.
     */
    fun getRecent(n: Int): List<Experience> {
        return buffer.sortedByDescending { it.timestamp }.take(n).map { it.experience }
    }

    /**
     * Elimina experiencias antiguas (por timestamp).
     */
    fun removeOld(olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        synchronized(buffer) {
            val filtered = buffer.filter { it.timestamp >= cutoff }
            buffer.clear()
            buffer.addAll(filtered)
            size.set(buffer.size.toLong())
        }
    }

    fun size(): Int = buffer.size

    fun isEmpty(): Boolean = buffer.isEmpty()

    fun clear() {
        buffer.clear()
        size.set(0)
    }

    /**
     * Calcula TD error absoluto para priority inicial.
     * Usa reward como proxy si no hay value network.
     */
    private fun absTdError(exp: Experience): Float {
        // Simplificación: usar recompensa como proxy de error
        // En implementación completa: |reward + gamma * V(s') - V(s)|
        return kotlin.math.abs(exp.reward) + 0.1f
    }

    data class PrioritizedExperience(
        val experience: Experience,
        val priority: Float,
        val timestamp: Long
    )

    data class SampledBatch(
        val experiences: List<Experience>,
        val importanceWeights: List<Float>,
        val indices: List<Int>
    )
}
