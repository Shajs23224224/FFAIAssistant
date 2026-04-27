package com.ffai.assistant.memory

import com.ffai.assistant.action.ActionType
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * FASE 3: EpisodicMemory - Memoria episódica con contexto.
 * 
 * Almacena episodios completos (partida completa) para:
 * - Recuperar experiencias similares
 * - Meta-learning (aprender a aprender)
 * - Transfer learning entre situaciones
 */
class EpisodicMemory {
    
    companion object {
        const val TAG = "EpisodicMemory"
        const val MAX_EPISODES = 1000
        const val SIMILARITY_THRESHOLD = 0.7f
    }
    
    // Buffer de episodios
    private val episodes = ConcurrentLinkedQueue<Episode>()
    
    // Episodio en construcción
    private var currentEpisode: EpisodeBuilder? = null
    
    // Stats
    private var totalEpisodes = 0
    
    /**
     * Inicia nuevo episodio.
     */
    fun startEpisode(
        initialLocation: String,
        initialSituation: String,
        initialState: FloatArray
    ) {
        currentEpisode = EpisodeBuilder(
            id = totalEpisodes,
            startTime = System.currentTimeMillis(),
            initialLocation = initialLocation,
            initialSituation = initialSituation,
            initialStateSnapshot = initialState.copyOf()
        )
        Logger.d(TAG, "Episode $totalEpisodes started: $initialSituation at $initialLocation")
    }
    
    /**
     * Agrega transición al episodio actual.
     */
    fun recordTransition(
        state: FloatArray,
        action: ActionType,
        reward: Float,
        context: EpisodeContext
    ) {
        currentEpisode?.addTransition(
            EpisodeTransition(
                state = state.copyOf(),
                action = action,
                reward = reward,
                timestamp = System.currentTimeMillis(),
                context = context
            )
        )
    }
    
    /**
     * Finaliza episodio y lo almacena.
     */
    fun endEpisode(
        finalReward: Float,
        placement: Int,
        durationMs: Long
    ): Episode {
        val builder = currentEpisode ?: return Episode.empty()
        
        val episode = builder.build(
            finalReward = finalReward,
            placement = placement,
            durationMs = durationMs
        )
        
        // Almacenar
        if (episodes.size >= MAX_EPISODES) {
            // Eliminar episodio menos útil (menor reward)
            episodes.minByOrNull { it.value }?.let { episodes.remove(it) }
        }
        
        episodes.offer(episode)
        totalEpisodes++
        
        Logger.i(TAG, "Episode ${episode.id} ended: placement=$placement, " +
            "reward=${"%.2f".format(finalReward)}, duration=${durationMs / 1000}s")
        
        currentEpisode = null
        return episode
    }
    
    /**
     * Recupera episodios similares a situación actual.
     */
    fun retrieveSimilarEpisodes(
        currentLocation: String,
        currentSituation: String,
        currentState: FloatArray,
        limit: Int = 5
    ): List<Episode> {
        if (episodes.isEmpty()) return emptyList()
        
        val query = EpisodeQuery(
            location = currentLocation,
            situation = currentSituation,
            state = currentState
        )
        
        return episodes
            .map { it to calculateSimilarity(it, query) }
            .filter { it.second >= SIMILARITY_THRESHOLD }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
    
    /**
     * Extrae patrones ganadores de episodios exitosos.
     */
    fun extractWinningPatterns(): List<WinningPattern> {
        val winningEpisodes = episodes.filter { it.placement <= 5 }
        
        if (winningEpisodes.isEmpty()) return emptyList()
        
        // Agrupar por situación inicial
        val bySituation = winningEpisodes.groupBy { it.initialSituation }
        
        return bySituation.map { (situation, eps) ->
            // Encontrar secuencia de acciones común
            val commonSequence = findCommonActionSequence(eps)
            
            WinningPattern(
                situation = situation,
                actionSequence = commonSequence,
                successRate = eps.count { it.placement <= 3 }.toFloat() / eps.size,
                averageReward = eps.map { it.finalReward }.average().toFloat(),
                sampleEpisodes = eps.take(3).map { it.id }
            )
        }.sortedByDescending { it.successRate }
    }
    
    /**
     * Adapta rápidamente basado en episodios similares (MAML-inspired).
     */
    fun adaptToSimilarEpisodes(
        currentState: FloatArray,
        similarEpisodes: List<Episode>
    ): AdaptationResult {
        if (similarEpisodes.isEmpty()) {
            return AdaptationResult(
                adapted = false,
                recommendedActions = emptyList(),
                learningRate = 0.001f
            )
        }
        
        // Calcular acciones que funcionaron en situaciones similares
        val actionDistribution = similarEpisodes
            .flatMap { it.transitions }
            .groupBy { it.action }
            .mapValues { it.value.size.toFloat() / similarEpisodes.size }
        
        val topActions = actionDistribution
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
        
        // Learning rate adaptativo: más alto si muchos episodios similares
        val learningRate = 0.001f + (similarEpisodes.size / 100f).coerceAtMost(0.01f)
        
        return AdaptationResult(
            adapted = true,
            recommendedActions = topActions,
            learningRate = learningRate,
            sourceEpisodes = similarEpisodes.map { it.id }
        )
    }
    
    /**
     * Calcula similitud entre episodio y query.
     */
    private fun calculateSimilarity(episode: Episode, query: EpisodeQuery): Float {
        var similarity = 0f
        
        // Similitud de situación
        if (episode.initialSituation == query.situation) {
            similarity += 0.4f
        }
        
        // Similitud de ubicación
        if (episode.initialLocation == query.location) {
            similarity += 0.3f
        }
        
        // Similitud de estado inicial
        val stateSimilarity = calculateStateSimilarity(
            episode.initialStateSnapshot,
            query.state
        )
        similarity += stateSimilarity * 0.3f
        
        return similarity.coerceIn(0f, 1f)
    }
    
    /**
     * Calcula similitud de estados.
     */
    private fun calculateStateSimilarity(s1: FloatArray, s2: FloatArray): Float {
        if (s1.size != s2.size) return 0f
        
        val diff = s1.zip(s2).sumOf { (a, b) ->
            abs(a - b).toDouble()
        }
        
        return (1f - (diff / s1.size).toFloat()).coerceIn(0f, 1f)
    }
    
    /**
     * Encuentra secuencia de acciones común.
     */
    private fun findCommonActionSequence(episodes: List<Episode>): List<ActionType> {
        if (episodes.isEmpty()) return emptyList()
        
        // Simplificado: acción más común en primeros 5 steps
        val firstActions = episodes.mapNotNull { 
            it.transitions.take(5).map { t -> t.action }
        }.flatten()
        
        return firstActions
            .groupBy { it }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): EpisodicMemoryStats {
        val avgDuration = if (episodes.isNotEmpty()) {
            episodes.map { it.durationMs }.average().toLong()
        } else 0L
        
        val winRate = if (episodes.isNotEmpty()) {
            episodes.count { it.placement <= 5 }.toFloat() / episodes.size
        } else 0f
        
        return EpisodicMemoryStats(
            totalEpisodes = totalEpisodes,
            storedEpisodes = episodes.size,
            averageDurationMs = avgDuration,
            winRate = winRate,
            currentEpisodeSteps = currentEpisode?.getStepCount() ?: 0
        )
    }
    
    /**
     * Reinicia memoria.
     */
    fun clear() {
        episodes.clear()
        currentEpisode = null
        totalEpisodes = 0
        Logger.i(TAG, "Episodic memory cleared")
    }
}

/**
 * Builder de episodio.
 */
private class EpisodeBuilder(
    val id: Int,
    val startTime: Long,
    val initialLocation: String,
    val initialSituation: String,
    val initialStateSnapshot: FloatArray
) {
    private val transitions = mutableListOf<EpisodeTransition>()
    
    fun addTransition(transition: EpisodeTransition) {
        transitions.add(transition)
    }
    
    fun getStepCount(): Int = transitions.size
    
    fun build(
        finalReward: Float,
        placement: Int,
        durationMs: Long
    ): Episode {
        return Episode(
            id = id,
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            initialLocation = initialLocation,
            initialSituation = initialSituation,
            initialStateSnapshot = initialStateSnapshot,
            transitions = transitions.toList(),
            finalReward = finalReward,
            placement = placement,
            durationMs = durationMs,
            value = calculateEpisodeValue(finalReward, placement, durationMs)
        )
    }
    
    private fun calculateEpisodeValue(reward: Float, placement: Int, duration: Long): Float {
        // Reward normalizado + bonus por placement - penalty por duración excesiva
        val placementBonus = maxOf(0f, (20 - placement) / 20f)
        val durationPenalty = minOf(0.1f, duration / 600000f)  // 10 min max
        return reward / 100f + placementBonus - durationPenalty
    }
}

/**
 * Episodio completo.
 */
data class Episode(
    val id: Int,
    val startTime: Long,
    val endTime: Long,
    val initialLocation: String,
    val initialSituation: String,
    val initialStateSnapshot: FloatArray,
    val transitions: List<EpisodeTransition>,
    val finalReward: Float,
    val placement: Int,
    val durationMs: Long,
    val value: Float
) {
    companion object {
        fun empty() = Episode(
            id = -1,
            startTime = 0,
            endTime = 0,
            initialLocation = "",
            initialSituation = "",
            initialStateSnapshot = floatArrayOf(),
            transitions = emptyList(),
            finalReward = 0f,
            placement = 0,
            durationMs = 0,
            value = 0f
        )
    }
}

/**
 * Transición dentro de episodio.
 */
data class EpisodeTransition(
    val state: FloatArray,
    val action: ActionType,
    val reward: Float,
    val timestamp: Long,
    val context: EpisodeContext
)

/**
 * Contexto de transición.
 */
data class EpisodeContext(
    val enemyCount: Int,
    val hp: Int,
    val ammo: Int,
    val distanceToZone: Float,
    val isInCover: Boolean
)

/**
 * Query para recuperación.
 */
private data class EpisodeQuery(
    val location: String,
    val situation: String,
    val state: FloatArray
)

/**
 * Patrón ganador extraído.
 */
data class WinningPattern(
    val situation: String,
    val actionSequence: List<ActionType>,
    val successRate: Float,
    val averageReward: Float,
    val sampleEpisodes: List<Int>
)

/**
 * Resultado de adaptación.
 */
data class AdaptationResult(
    val adapted: Boolean,
    val recommendedActions: List<ActionType>,
    val learningRate: Float,
    val sourceEpisodes: List<Int> = emptyList()
)

/**
 * Estadísticas de memoria episódica.
 */
data class EpisodicMemoryStats(
    val totalEpisodes: Int,
    val storedEpisodes: Int,
    val averageDurationMs: Long,
    val winRate: Float,
    val currentEpisodeSteps: Int
)
