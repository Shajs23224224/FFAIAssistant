package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.perception.GameState

/**
 * Representa una experiencia (s, a, r, s') para aprendizaje por refuerzo
 */
data class Experience(
    val id: Long = 0,
    val state: GameState,
    val action: Action,
    val reward: Float,
    val nextState: GameState,
    val done: Boolean,  // true si terminó la partida
    val priority: Float = 1.0f,  // Para prioritized experience replay
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Buffer de experiencias con límite de tamaño
 */
class ExperienceBuffer(private val maxSize: Int = 10000) {
    private val buffer = ArrayDeque<Experience>(maxSize)
    
    fun add(experience: Experience) {
        if (buffer.size >= maxSize) {
            buffer.removeFirst()
        }
        buffer.addLast(experience)
    }
    
    fun sample(batchSize: Int): List<Experience> {
        if (buffer.size < batchSize) return buffer.toList()
        return buffer.shuffled().take(batchSize)
    }
    
    fun size(): Int = buffer.size
    
    fun clear() = buffer.clear()
    
    fun getAll(): List<Experience> = buffer.toList()
    
    fun getRecent(n: Int): List<Experience> = buffer.takeLast(n)
}

/**
 * Estadísticas de un episodio (partida)
 */
data class EpisodeStats(
    val episodeId: Long = System.currentTimeMillis(),
    val totalReward: Float = 0f,
    val totalActions: Int = 0,
    val kills: Int = 0,
    val placement: Int = 50,
    val survivalTimeMs: Long = 0,
    val damageDealt: Float = 0f,
    val damageTaken: Float = 0f,
    val shotsFired: Int = 0,
    val shotsHit: Int = 0,
    val healsUsed: Int = 0,
    val reloads: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null
) {
    val isWin: Boolean get() = placement == 1
    val accuracy: Float get() = if (shotsFired > 0) shotsHit.toFloat() / shotsFired else 0f
    val durationMs: Long get() = endTime?.let { it - startTime } ?: (System.currentTimeMillis() - startTime)
}
