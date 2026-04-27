package com.ffai.assistant.rl.curiosity

import com.ffai.assistant.utils.Logger

/**
 * IntrinsicRewardEngine - Gestiona recompensas intrínsecas.
 * Combina ICM, novelty detection y episodic curiosity.
 */
class IntrinsicRewardEngine(private val icmModule: ICMModule) {
    
    companion object {
        const val TAG = "IntrinsicRewardEngine"
        
        // Pesos de recompensas
        const val ICM_WEIGHT = 0.5f
        const val NOVELTY_WEIGHT = 0.3f
        const val COVERAGE_WEIGHT = 0.2f
        
        // EMA para normalización
        const val EMA_ALPHA = 0.01f
    }
    
    // Estadísticas para normalización
    private var intrinsicRewardEMA = 0f
    private var intrinsicRewardStdEMA = 0.05f
    private var extrinsicRewardEMA = 0f
    
    // Historial
    private var totalIntrinsic = 0f
    private var totalExtrinsic = 0f
    private var stepCount = 0
    
    /**
     * Calcula recompensa total combinando extrínseca e intrínseca.
     */
    fun computeTotalReward(
        state: FloatArray,
        action: Int,
        nextState: FloatArray,
        extrinsicReward: Float,
        config: RewardConfig = RewardConfig()
    ): TotalReward {
        stepCount++
        
        // Recompensa ICM
        val icmResult = icmModule.computeIntrinsicReward(state, action, nextState, config.icmScale)
        
        // Novedad
        val features = icmModule.extractFeatures(nextState)
        val novelty = icmModule.computeEpisodicSurprise(features)
        
        // Normalizar ICM
        intrinsicRewardEMA = (1 - EMA_ALPHA) * intrinsicRewardEMA + EMA_ALPHA * icmResult.intrinsicReward
        val normalizedICM = (icmResult.intrinsicReward - intrinsicRewardEMA) / 
                           (intrinsicRewardStdEMA + 1e-8f)
        
        // Combinar
        val intrinsicComponent = ICM_WEIGHT * normalizedICM +
                                 NOVELTY_WEIGHT * novelty +
                                 COVERAGE_WEIGHT * (1f - novelty) * 0.1f
        
        // Total
        val total = config.extrinsicWeight * extrinsicReward +
                   config.intrinsicWeight * intrinsicComponent.coerceIn(-1f, 1f)
        
        // Actualizar estadísticas
        totalIntrinsic += intrinsicComponent
        totalExtrinsic += extrinsicReward
        
        return TotalReward(
            total = total,
            extrinsic = extrinsicReward,
            intrinsic = intrinsicComponent,
            icmReward = icmResult.intrinsicReward,
            novelty = novelty,
            normalizedICM = normalizedICM
        )
    }
    
    /**
     * Balance dinámico basado en progreso.
     */
    fun adaptiveWeights(extrinsicReward: Float): AdaptiveWeights {
        extrinsicRewardEMA = 0.99f * extrinsicRewardEMA + 0.01f * kotlin.math.abs(extrinsicReward)
        
        // Si hay poca recompensa extrínseca, aumentar intrínseca
        val intrinsicRatio = if (extrinsicRewardEMA < 0.1f) 0.7f else 0.3f
        
        return AdaptiveWeights(
            extrinsicWeight = 1f - intrinsicRatio,
            intrinsicWeight = intrinsicRatio
        )
    }
    
    fun getStats() = IntrinsicStats(
        avgIntrinsic = if (stepCount > 0) totalIntrinsic / stepCount else 0f,
        avgExtrinsic = if (stepCount > 0) totalExtrinsic / stepCount else 0f,
        intrinsicEMA = intrinsicRewardEMA,
        stepCount = stepCount
    )
    
    fun reset() {
        totalIntrinsic = 0f
        totalExtrinsic = 0f
        stepCount = 0
        icmModule.resetEpisode()
    }
}

data class RewardConfig(
    val icmScale: Float = 1.0f,
    val extrinsicWeight: Float = 1.0f,
    val intrinsicWeight: Float = 0.5f
)

data class TotalReward(
    val total: Float,
    val extrinsic: Float,
    val intrinsic: Float,
    val icmReward: Float,
    val novelty: Float,
    val normalizedICM: Float
)

data class AdaptiveWeights(val extrinsicWeight: Float, val intrinsicWeight: Float)
data class IntrinsicStats(val avgIntrinsic: Float, val avgExtrinsic: Float, val intrinsicEMA: Float, val stepCount: Int)
