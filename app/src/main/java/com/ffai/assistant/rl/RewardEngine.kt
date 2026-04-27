package com.ffai.assistant.rl

import com.ffai.assistant.action.ActionType
import com.ffai.assistant.vision.FusedEnemy
import com.ffai.assistant.utils.Logger
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * FASE 3: RewardEngine - Sistema de recompensas multi-objetivo.
 * 
 * Componentes:
 * - Shaping denso (reward en cada step)
 * - Sparse rewards (eventos importantes)
 * - Multi-objetivo (Pareto frontier)
 * - Reward normalization
 */
class RewardEngine {
    
    companion object {
        const val TAG = "RewardEngine"
        
        // Reward shaping constants
        const val AIM_IMPROVEMENT_WEIGHT = 0.1f
        const val DAMAGE_DEALT_WEIGHT = 1.0f
        const val KILL_WEIGHT = 10.0f
        const val SURVIVAL_WEIGHT = 0.01f
        const val POSITIONING_WEIGHT = 0.5f
        const val LOOT_EFFICIENCY_WEIGHT = 0.2f
        
        // Penalties
        const val DAMAGE_TAKEN_PENALTY = -0.5f
        const val DEATH_PENALTY = -10.0f
        const val MISSED_SHOTS_PENALTY = -0.1f
        const val BAD_POSITION_PENALTY = -0.3f
        
        // Sparse rewards
        const val VICTORY_REWARD = 100f
        const val TOP_10_REWARD = 50f
        const val TOP_5_REWARD = 75f
    }
    
    // Estado previo para calcular deltas
    private var previousHealth = 100
    private var previousAmmo = 100
    private var previousEnemyDistance = Float.MAX_VALUE
    private var previousPosition: Pair<Float, Float>? = null
    
    // Stats
    private var totalShots = 0
    private var hits = 0
    private var kills = 0
    private var totalReward = 0f
    
    /**
     * Calcula recompensa completa para un step.
     */
    fun calculateReward(
        action: ActionType,
        currentHealth: Int,
        currentAmmo: Int,
        enemies: List<FusedEnemy>,
        screenWidth: Int,
        screenHeight: Int,
        hitRegistered: Boolean = false,
        damageDealt: Int = 0,
        damageTaken: Int = 0,
        position: Pair<Float, Float>? = null
    ): RewardBreakdown {
        var totalReward = 0f
        val breakdown = mutableMapOf<String, Float>()
        
        // 1. Aim improvement (shaping)
        val screenCenterX = screenWidth / 2f
        val screenCenterY = screenHeight / 2f
        
        val nearestEnemy = enemies.minByOrNull { 
            val dx = it.centerX() - screenCenterX
            val dy = it.centerY() - screenCenterY
            kotlin.math.hypot(dx, dy)
        }
        
        val currentEnemyDistance = nearestEnemy?.let {
            kotlin.math.hypot(it.centerX() - screenCenterX, it.centerY() - screenCenterY)
        } ?: Float.MAX_VALUE
        
        if (previousEnemyDistance != Float.MAX_VALUE && currentEnemyDistance < previousEnemyDistance) {
            val aimReward = AIM_IMPROVEMENT_WEIGHT * (previousEnemyDistance - currentEnemyDistance) / 100f
            totalReward += aimReward
            breakdown["aim_improvement"] = aimReward
        }
        previousEnemyDistance = currentEnemyDistance
        
        // 2. Damage dealt
        if (damageDealt > 0) {
            val damageReward = damageDealt * DAMAGE_DEALT_WEIGHT
            totalReward += damageReward
            breakdown["damage_dealt"] = damageReward
            hits++
        }
        
        // 3. Kill reward
        if (hitRegistered) {
            val killReward = KILL_WEIGHT
            totalReward += killReward
            breakdown["kill"] = killReward
            kills++
        }
        
        // 4. Survival
        val survivalReward = SURVIVAL_WEIGHT
        totalReward += survivalReward
        breakdown["survival"] = survivalReward
        
        // 5. Positioning reward
        if (position != null && previousPosition != null) {
            val moved = kotlin.math.hypot(
                position.first - previousPosition!!.first,
                position.second - previousPosition!!.second
            )
            
            // Reward por moverse hacia cover si hay enemigos cercanos
            if (enemies.isNotEmpty() && moved > 0) {
                val positioningReward = POSITIONING_WEIGHT * min(moved / 100f, 1f)
                totalReward += positioningReward
                breakdown["positioning"] = positioningReward
            }
        }
        previousPosition = position
        
        // 6. Penalties
        if (damageTaken > 0) {
            val penalty = damageTaken * DAMAGE_TAKEN_PENALTY
            totalReward += penalty
            breakdown["damage_taken"] = penalty
        }
        
        // Penalizar quedarse quieto en combate
        if (enemies.isNotEmpty() && action == ActionType.HOLD) {
            val holdPenalty = BAD_POSITION_PENALTY
            totalReward += holdPenalty
            breakdown["hold_in_combat"] = holdPenalty
        }
        
        // Penalizar missed shots
        if (action == ActionType.SHOOT && !hitRegistered && enemies.isNotEmpty()) {
            totalShots++
            if (totalShots > 0 && hits.toFloat() / totalShots < 0.3f) {
                val missPenalty = MISSED_SHOTS_PENALTY
                totalReward += missPenalty
                breakdown["missed_shots"] = missPenalty
            }
        }
        
        // Normalizar
        val normalizedReward = normalizeReward(totalReward)
        this.totalReward += normalizedReward
        
        return RewardBreakdown(
            totalReward = normalizedReward,
            components = breakdown,
            rawReward = totalReward
        )
    }
    
    /**
     * Recompensa sparse (eventos importantes).
     */
    fun calculateSparseReward(
        event: SparseEvent,
        placement: Int? = null,
        totalPlayers: Int? = null
    ): Float {
        return when (event) {
            SparseEvent.VICTORY -> {
                Logger.i(TAG, "VICTORY! Reward: $VICTORY_REWARD")
                VICTORY_REWARD
            }
            SparseEvent.TOP_5 -> {
                Logger.i(TAG, "TOP 5! Reward: $TOP_5_REWARD")
                TOP_5_REWARD
            }
            SparseEvent.TOP_10 -> {
                Logger.i(TAG, "TOP 10! Reward: $TOP_10_REWARD")
                TOP_10_REWARD
            }
            SparseEvent.DEATH -> {
                Logger.w(TAG, "Death. Penalty: $DEATH_PENALTY")
                DEATH_PENALTY
            }
            SparseEvent.TEAM_WIPE -> -5f
            SparseEvent.REVIVE -> 2f
            SparseEvent.HEADSHOT -> 5f
        }
    }
    
    /**
     * Calcula recompensa multi-objetivo (Pareto frontier).
     */
    fun calculateMultiObjectiveReward(
        primaryReward: Float,
        objectives: Map<String, Float>,
        weights: Map<String, Float> = defaultWeights()
    ): MultiObjectiveReward {
        val weightedSum = objectives.map { (key, value) ->
            val weight = weights[key] ?: 1f
            value * weight
        }.sum()
        
        // Pareto dominance check
        val dominated = objectives.all { (key, value) ->
            val threshold = weights[key] ?: 0f
            value >= threshold
        }
        
        return MultiObjectiveReward(
            scalarReward = (primaryReward + weightedSum) / 2f,
            objectives = objectives,
            dominated = dominated,
            distanceToFrontier = calculateParetoDistance(objectives, weights)
        )
    }
    
    /**
     * Normaliza recompensa a rango [-1, 1].
     */
    private fun normalizeReward(reward: Float): Float {
        // Usar tanh para normalizar
        return kotlin.math.tanh(reward / 10f)
    }
    
    /**
     * Calcula distancia a Pareto frontier.
     */
    private fun calculateParetoDistance(
        objectives: Map<String, Float>,
        weights: Map<String, Float>
    ): Float {
        // Simplificado: suma de distancias normalizadas
        return objectives.map { (key, value) ->
            val weight = weights[key] ?: 1f
            kotlin.math.abs(value - weight)
        }.average().toFloat()
    }
    
    /**
     * Pesos por defecto.
     */
    private fun defaultWeights(): Map<String, Float> {
        return mapOf(
            "aggression" to 0.3f,
            "survival" to 0.4f,
            "efficiency" to 0.3f
        )
    }
    
    /**
     * Reset entre episodios.
     */
    fun reset() {
        previousHealth = 100
        previousAmmo = 100
        previousEnemyDistance = Float.MAX_VALUE
        previousPosition = null
        totalShots = 0
        hits = 0
        kills = 0
        Logger.i(TAG, "RewardEngine reset")
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): RewardStats {
        val accuracy = if (totalShots > 0) hits.toFloat() / totalShots else 0f
        return RewardStats(
            totalShots = totalShots,
            hits = hits,
            accuracy = accuracy,
            kills = kills,
            totalReward = totalReward
        )
    }
    
    enum class SparseEvent {
        VICTORY, TOP_5, TOP_10, DEATH, TEAM_WIPE, REVIVE, HEADSHOT
    }
}

/**
 * Desglose de recompensa.
 */
data class RewardBreakdown(
    val totalReward: Float,
    val components: Map<String, Float>,
    val rawReward: Float
)

/**
 * Recompensa multi-objetivo.
 */
data class MultiObjectiveReward(
    val scalarReward: Float,
    val objectives: Map<String, Float>,
    val dominated: Boolean,
    val distanceToFrontier: Float
)

/**
 * Estadísticas de recompensas.
 */
data class RewardStats(
    val totalShots: Int,
    val hits: Int,
    val accuracy: Float,
    val kills: Int,
    val totalReward: Float
)
