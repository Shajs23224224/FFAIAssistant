package com.ffai.assistant.rl.hierarchical

import kotlin.math.abs

/**
 * GoalConditionedRL - RL condicionado por goals (UVFA + HER).
 * 
 * Universal Value Function Approximators (UVFA):
 * V(s, g) - value depends on state AND goal
 * 
 * Hindsight Experience Replay (HER):
 * Re-label failed trajectories with achieved goals.
 */
class GoalConditionedRL(
    private val stateDim: Int = 256,
    private val goalDim: Int = 64
) {
    
    companion object {
        const val TAG = "GoalConditionedRL"
        
        // HER strategies
        const val HER_FINAL = "final"
        const val HER_FUTURE = "future"
        const val HER_EPISODE = "episode"
        const val HER_RANDOM = "random"
    }
    
    // Goal sampling strategy
    private var herStrategy = HER_FUTURE
    private var herProbability = 0.8f
    
    // Buffer de experiencias con goals
    private val goalReplayBuffer = mutableListOf<GoalExperience>()
    private val achievedGoals = mutableListOf<FloatArray>()
    
    // Stats
    private var herReplacements = 0
    private var totalExperiences = 0
    
    /**
     * Concatena estado y goal.
     * Input para red: [state || goal]
     */
    fun concatenateStateGoal(state: FloatArray, goal: FloatArray): FloatArray {
        return state + goal
    }
    
    /**
     * Computa reward esparso para goal-conditioned RL.
     * r(s, a, g) = -1 if goal not achieved, 0 if achieved
     */
    fun computeSparseReward(state: FloatArray, goal: FloatArray): Float {
        val distance = euclideanDistance(state, goal)
        return if (distance < 0.1f) 0f else -1f
    }
    
    /**
     * Computa reward denso (alternativa).
     */
    fun computeDenseReward(state: FloatArray, goal: FloatArray): Float {
        val distance = euclideanDistance(state, goal)
        return -distance
    }
    
    /**
     * Aplica HER para relabeling de experiencias.
     * Cuando goal no se alcanza, reemplazar con achieved goal.
     */
    fun applyHER(experiences: List<GoalExperience>): List<GoalExperience> {
        val augmented = mutableListOf<GoalExperience>()
        
        experiences.forEach { exp ->
            // Agregar original
            augmented.add(exp)
            
            // Aplicar HER con probabilidad
            if (kotlin.random.Random.nextFloat() < herProbability) {
                val achievedGoal = sampleAchievedGoal(exp, experiences)
                
                if (achievedGoal != null) {
                    // Crear nueva experiencia con achieved goal
                    val herExp = exp.copy(
                        goal = achievedGoal,
                        reward = computeSparseReward(exp.nextState, achievedGoal),
                        isHER = true
                    )
                    augmented.add(herExp)
                    herReplacements++
                }
            }
        }
        
        totalExperiences += experiences.size
        return augmented
    }
    
    /**
     * Samplea achieved goal según estrategia.
     */
    private fun sampleAchievedGoal(
        exp: GoalExperience,
        episode: List<GoalExperience>
    ): FloatArray? {
        return when (herStrategy) {
            HER_FINAL -> episode.lastOrNull()?.nextState?.sliceArray(0 until goalDim)
            HER_FUTURE -> {
                val futureIdx = episode.indexOf(exp) + 
                    kotlin.random.Random.nextInt(1, episode.size - episode.indexOf(exp))
                if (futureIdx < episode.size) episode[futureIdx].nextState.sliceArray(0 until goalDim)
                else null
            }
            HER_EPISODE -> episode.randomOrNull()?.nextState?.sliceArray(0 until goalDim)
            HER_RANDOM -> achievedGoals.randomOrNull()
            else -> null
        }
    }
    
    /**
     * Samplea goal para nuevo episodio.
     */
    fun sampleGoal(): FloatArray {
        return FloatArray(goalDim) { kotlin.random.Random.nextFloat() * 2 - 1 }
    }
    
    /**
     * Detecta si goal fue alcanzado.
     */
    fun isGoalAchieved(state: FloatArray, goal: FloatArray, threshold: Float = 0.1f): Boolean {
        return euclideanDistance(state.sliceArray(0 until goalDim), goal) < threshold
    }
    
    /**
     * Agrega achieved goal al buffer.
     */
    fun addAchievedGoal(state: FloatArray) {
        val goal = state.sliceArray(0 until goalDim)
        achievedGoals.add(goal)
        if (achievedGoals.size > 1000) achievedGoals.removeAt(0)
    }
    
    /**
     * Set HER strategy.
     */
    fun setHERStrategy(strategy: String, probability: Float = 0.8f) {
        herStrategy = strategy
        herProbability = probability
    }
    
    /**
     * Goal embedding desde descripción.
     */
    fun goalEmbedding(goal: MetaController.Goal): FloatArray {
        return FloatArray(goalDim) { i ->
            kotlin.math.sin(goal.ordinal * 0.7f + i * 0.1f)
        }
    }
    
    /**
     * Reset episódico.
     */
    fun resetEpisode() {
        goalReplayBuffer.clear()
        achievedGoals.clear()
    }
    
    /**
     * Estadísticas.
     */
    fun getStats() = GoalConditionedStats(
        herReplacements = herReplacements,
        totalExperiences = totalExperiences,
        herRate = if (totalExperiences > 0) herReplacements.toFloat() / totalExperiences else 0f,
        achievedGoalBufferSize = achievedGoals.size
    )
    
    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return kotlin.math.sqrt(sum)
    }
}

data class GoalExperience(
    val state: FloatArray,
    val action: Int,
    val goal: FloatArray,
    val reward: Float,
    val nextState: FloatArray,
    val done: Boolean,
    val isHER: Boolean = false
)

data class GoalConditionedStats(
    val herReplacements: Int,
    val totalExperiences: Int,
    val herRate: Float,
    val achievedGoalBufferSize: Int
)
