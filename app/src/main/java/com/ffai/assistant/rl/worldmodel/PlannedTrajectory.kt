package com.ffai.assistant.rl.worldmodel

import com.ffai.assistant.utils.Logger

/**
 * PlannedTrajectory - Gestión de trayectorias planificadas.
 * Almacena y evalúa trayectorias imaginadas por el World Model.
 */
class PlannedTrajectory(
    val trajectory: ImaginedTrajectory,
    val actionSequence: List<Int>,
    val planningTimeMs: Long
) {
    companion object {
        const val TAG = "PlannedTrajectory"
    }
    
    // Caché de evaluaciones
    private var cachedValue: Float? = null
    private var cachedRisk: Float? = null
    
    /**
     * Calcula valor total con descuento.
     */
    fun calculateValue(gamma: Float = 0.99f): Float {
        cachedValue?.let { return it }
        
        var value = 0f
        var discount = 1f
        
        for (i in trajectory.rewards.indices) {
            val terminalFactor = 1f - trajectory.terminals.getOrElse(i) { 0f }
            value += discount * trajectory.rewards[i] * terminalFactor
            discount *= gamma
        }
        
        cachedValue = value
        return value
    }
    
    /**
     * Calcula riesgo (probabilidad de terminal prematuro).
     */
    fun calculateRisk(): Float {
        cachedRisk?.let { return it }
        
        val risk = trajectory.terminals.average().toFloat()
        cachedRisk = risk
        return risk
    }
    
    /**
     * Calcula recompensa promedio por step.
     */
    fun averageReward(): Float {
        return if (trajectory.rewards.isNotEmpty()) {
            trajectory.rewards.average().toFloat()
        } else 0f
    }
    
    /**
     * Duración esperada antes de terminal.
     */
    fun expectedDuration(): Float {
        var expectedSteps = 0f
        var survivalProb = 1f
        
        for (terminalProb in trajectory.terminals) {
            survivalProb *= (1f - terminalProb)
            expectedSteps += survivalProb
        }
        
        return expectedSteps
    }
    
    /**
     * Valor ponderado por riesgo (mean-variance optimization).
     */
    fun riskAdjustedValue(riskAversion: Float = 0.5f): Float {
        val value = calculateValue()
        val risk = calculateRisk()
        return value - riskAversion * risk * 100f
    }
    
    /**
     * Compara con otra trayectoria.
     */
    fun isBetterThan(other: PlannedTrajectory, metric: ComparisonMetric = ComparisonMetric.VALUE): Boolean {
        return when (metric) {
            ComparisonMetric.VALUE -> calculateValue() > other.calculateValue()
            ComparisonMetric.RISK_ADJUSTED -> riskAdjustedValue() > other.riskAdjustedValue()
            ComparisonMetric.AVG_REWARD -> averageReward() > other.averageReward()
            ComparisonMetric.DURATION -> expectedDuration() > other.expectedDuration()
        }
    }
    
    /**
     * Obtiene resumen de la trayectoria.
     */
    fun getSummary(): TrajectorySummary {
        return TrajectorySummary(
            length = trajectory.states.size,
            totalReward = trajectory.rewards.sum(),
            averageReward = averageReward(),
            terminalProbability = calculateRisk(),
            expectedDuration = expectedDuration(),
            valueEstimate = calculateValue(),
            firstAction = actionSequence.firstOrNull() ?: -1,
            planningTimeMs = planningTimeMs
        )
    }
    
    /**
     * Formatea para logging.
     */
    fun formatForLog(): String {
        val summary = getSummary()
        return buildString {
            append("Trajectory[${summary.length} steps]: ")
            append("reward=${"%.2f".format(summary.totalReward)}, ")
            append("value=${"%.2f".format(summary.valueEstimate)}, ")
            append("risk=${"%.2f".format(summary.terminalProbability)}, ")
            append("action=${summary.firstAction}, ")
            append("time=${summary.planningTimeMs}ms")
        }
    }
    
    enum class ComparisonMetric {
        VALUE,
        RISK_ADJUSTED,
        AVG_REWARD,
        DURATION
    }
}

/**
 * Gestor de múltiples trayectorias planificadas.
 */
class TrajectoryManager {
    companion object {
        const val TAG = "TrajectoryManager"
        const val MAX_STORED_TRAJECTORIES = 100
    }
    
    private val trajectories = mutableListOf<PlannedTrajectory>()
    private var bestTrajectory: PlannedTrajectory? = null
    
    /**
     * Agrega nueva trayectoria.
     */
    fun addTrajectory(trajectory: PlannedTrajectory) {
        trajectories.add(trajectory)
        
        // Update best
        if (bestTrajectory == null || trajectory.isBetterThan(bestTrajectory!!)) {
            bestTrajectory = trajectory
        }
        
        // Limit size
        if (trajectories.size > MAX_STORED_TRAJECTORIES) {
            val oldest = trajectories.removeAt(0)
            if (oldest == bestTrajectory) {
                recalculateBest()
            }
        }
    }
    
    /**
     * Obtiene mejor trayectoria.
     */
    fun getBest(metric: PlannedTrajectory.ComparisonMetric = PlannedTrajectory.ComparisonMetric.VALUE): PlannedTrajectory? {
        return when (metric) {
            PlannedTrajectory.ComparisonMetric.VALUE -> bestTrajectory
            else -> trajectories.maxByOrNull {
                when (metric) {
                    PlannedTrajectory.ComparisonMetric.RISK_ADJUSTED -> it.riskAdjustedValue()
                    PlannedTrajectory.ComparisonMetric.AVG_REWARD -> it.averageReward()
                    PlannedTrajectory.ComparisonMetric.DURATION -> it.expectedDuration()
                    else -> it.calculateValue()
                }
            }
        }
    }
    
    /**
     * Obtiene top-K trayectorias.
     */
    fun getTopK(k: Int, metric: PlannedTrajectory.ComparisonMetric = PlannedTrajectory.ComparisonMetric.VALUE): List<PlannedTrajectory> {
        return trajectories.sortedByDescending {
            when (metric) {
                PlannedTrajectory.ComparisonMetric.VALUE -> it.calculateValue()
                PlannedTrajectory.ComparisonMetric.RISK_ADJUSTED -> it.riskAdjustedValue()
                PlannedTrajectory.ComparisonMetric.AVG_REWARD -> it.averageReward()
                PlannedTrajectory.ComparisonMetric.DURATION -> it.expectedDuration()
            }
        }.take(k)
    }
    
    /**
     * Agrega estadísticas de las trayectorias.
     */
    fun aggregateStats(): TrajectoryAggregateStats {
        if (trajectories.isEmpty()) return TrajectoryAggregateStats(0, 0f, 0f, 0f, 0f, 0f)
        
        val values = trajectories.map { it.calculateValue() }
        val rewards = trajectories.map { it.averageReward() }
        val risks = trajectories.map { it.calculateRisk() }
        
        return TrajectoryAggregateStats(
            count = trajectories.size,
            avgValue = values.average().toFloat(),
            bestValue = values.maxOrNull() ?: 0f,
            avgReward = rewards.average().toFloat(),
            avgRisk = risks.average().toFloat(),
            avgPlanningTime = trajectories.map { it.planningTimeMs }.average().toFloat()
        )
    }
    
    /**
     * Limpia todas las trayectorias.
     */
    fun clear() {
        trajectories.clear()
        bestTrajectory = null
        Logger.d(TAG, "Trajectory manager cleared")
    }
    
    /**
     * Exporta para análisis.
     */
    fun exportForAnalysis(): List<TrajectorySummary> {
        return trajectories.map { it.getSummary() }
    }
    
    private fun recalculateBest() {
        bestTrajectory = trajectories.maxByOrNull { it.calculateValue() }
    }
}

data class TrajectorySummary(
    val length: Int,
    val totalReward: Float,
    val averageReward: Float,
    val terminalProbability: Float,
    val expectedDuration: Float,
    val valueEstimate: Float,
    val firstAction: Int,
    val planningTimeMs: Long
)

data class TrajectoryAggregateStats(
    val count: Int,
    val avgValue: Float,
    val bestValue: Float,
    val avgReward: Float,
    val avgRisk: Float,
    val avgPlanningTime: Float
)
