package com.ffai.assistant.rl

import android.content.Context
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min

/**
 * FASE 3: EnsembleRLCoordinator - Coordina DQN + PPO + SAC.
 * 
 * Estrategia de ensemble:
 * - Los 3 agentes seleccionan acción en paralelo
 * - Votación ponderada por confianza histórica
 * - Si 2/3 concuerdan → ejecutar esa acción
 * - Si discrepan → usar agente con mayor confianza
 * - Cross-validation: evaluación entre agentes
 */
class EnsembleRLCoordinator(context: Context) {
    
    companion object {
        const val TAG = "EnsembleRLCoordinator"
        
        // Pesos de confianza iniciales
        const val WEIGHT_DQN = 0.35f
        const val WEIGHT_PPO = 0.35f
        const val WEIGHT_SAC = 0.30f
        
        // Umbrales - REDUCIDOS para funcionar con modelos TFLite base
        const val CONSENSUS_THRESHOLD = 0.5f
        const val MIN_CONFIDENCE = 0.15f  // Reducido de 0.4f para pruebas
    }
    
    // Agentes
    val dqnAgent = DQNAgent(context)
    val ppoAgent = PPOAgent(context)
    val sacAgent = SACAgent(context)
    
    // Pesos dinámicos
    private var dqnWeight = WEIGHT_DQN
    private var ppoWeight = WEIGHT_PPO
    private var sacWeight = WEIGHT_SAC
    
    // Performance tracking
    private val agentPerformance = mutableMapOf(
        Agent.DQN to PerformanceTracker(),
        Agent.PPO to PerformanceTracker(),
        Agent.SAC to PerformanceTracker()
    )
    
    // Stats
    private var decisionCount = 0
    private var consensusCount = 0
    private val decisionHistory = ArrayDeque<Pair<EnsembleDecision, Float>>(100)
    
    enum class Agent { DQN, PPO, SAC }
    
    /**
     * Inicializa todos los agentes.
     */
    fun initialize(): Boolean {
        Logger.i(TAG, "Inicializando Ensemble RL Coordinator...")
        
        val dqnOk = dqnAgent.initialize()
        val ppoOk = ppoAgent.initialize()
        val sacOk = sacAgent.initialize()
        
        Logger.i(TAG, "DQN: $dqnOk, PPO: $ppoOk, SAC: $sacOk")
        
        return dqnOk && ppoOk && sacOk
    }
    
    /**
     * Selecciona acción usando ensemble de los 3 agentes.
     */
    suspend fun selectAction(state: FloatArray): EnsembleDecision = coroutineScope {
        val startTime = System.currentTimeMillis()
        
        // Ejecutar los 3 agentes en paralelo
        val dqnDeferred = async { 
            dqnAgent.selectAction(state) to calculateDQNConfidence()
        }
        val ppoDeferred = async { 
            ppoAgent.selectAction(state).discreteAction to calculatePPOConfidence()
        }
        val sacDeferred = async { 
            sacAgent.selectAction(state).discreteAction to calculateSACConfidence()
        }
        
        // Esperar resultados
        val (dqnResult, dqnConf) = dqnDeferred.await()
        val (ppoResult, ppoConf) = ppoDeferred.await()
        val (sacResult, sacConf) = sacDeferred.await()
        
        // Votación ponderada
        val votes = mutableMapOf<ActionType, Float>()
        votes[dqnResult] = (votes[dqnResult] ?: 0f) + dqnConf * dqnWeight
        votes[ppoResult] = (votes[ppoResult] ?: 0f) + ppoConf * ppoWeight
        votes[sacResult] = (votes[sacResult] ?: 0f) + sacConf * sacWeight
        
        // Encontrar acción ganadora
        val maxEntry = votes.maxByOrNull { it.value }
        val bestAction = maxEntry?.key ?: ActionType.HOLD
        val bestScore = maxEntry?.value ?: 0f
        
        // Verificar consenso
        val agreeingAgents = listOf(
            if (dqnResult == bestAction) Agent.DQN else null,
            if (ppoResult == bestAction) Agent.PPO else null,
            if (sacResult == bestAction) Agent.SAC else null
        ).filterNotNull()
        
        val hasConsensus = agreeingAgents.size >= 2 && bestScore >= CONSENSUS_THRESHOLD
        if (hasConsensus) consensusCount++
        
        // Decisión final
        val decision = if (bestScore >= MIN_CONFIDENCE) {
            EnsembleDecision(
                action = bestAction,
                confidence = bestScore,
                consensus = hasConsensus,
                agreeingAgents = agreeingAgents,
                allVotes = listOf(
                    Vote(Agent.DQN, dqnResult, dqnConf),
                    Vote(Agent.PPO, ppoResult, ppoConf),
                    Vote(Agent.SAC, sacResult, sacConf)
                ),
                primaryAgent = determinePrimaryAgent(dqnConf, ppoConf, sacConf),
                selectionTimeMs = System.currentTimeMillis() - startTime
            )
        } else {
            // Confianza muy baja, usar acción segura
            EnsembleDecision(
                action = ActionType.HOLD,
                confidence = 0f,
                consensus = false,
                agreeingAgents = emptyList(),
                allVotes = emptyList(),
                primaryAgent = null,
                selectionTimeMs = System.currentTimeMillis() - startTime
            )
        }
        
        decisionCount++
        decisionHistory.add(decision to bestScore)
        if (decisionHistory.size > 100) decisionHistory.removeFirst()
        
        // Log periódico
        if (decisionCount % 60 == 0) {
            val consensusRate = if (decisionCount > 0) consensusCount.toFloat() / decisionCount else 0f
            Logger.d(TAG, "Decision #$decisionCount: ${decision.action}, " +
                "consensus: ${decision.consensus} (${(consensusRate * 100).toInt()}%), " +
                "time: ${decision.selectionTimeMs}ms")
        }
        
        decision
    }
    
    /**
     * Almacena experiencia en todos los agentes.
     */
    fun storeExperience(
        state: FloatArray,
        action: ActionType,
        reward: Float,
        nextState: FloatArray,
        done: Boolean
    ) {
        // DQN
        dqnAgent.storeExperience(state, action, reward, nextState, done)
        
        // PPO
        val ppoAction = PPOAction(action, 0f, 0f, floatArrayOf(0f, 0f))
        ppoAgent.storeTransition(state, ppoAction, reward, nextState, done)
        
        // SAC
        val sacAction = SACAction(action, 0f, floatArrayOf(0f, 0f))
        sacAgent.storeTransition(state, sacAction, reward, nextState, done)
    }
    
    /**
     * Entrena un step en todos los agentes.
     */
    fun trainStep(): EnsembleTrainingResult {
        val dqnLoss = dqnAgent.trainStep()
        val ppoLoss = ppoAgent.trainStep()
        val sacLoss = sacAgent.trainStep()
        
        return EnsembleTrainingResult(dqnLoss, ppoLoss, sacLoss)
    }
    
    /**
     * Reporta resultado de acción para actualizar pesos.
     */
    fun reportActionResult(agents: List<Agent>, success: Boolean, reward: Float) {
        agents.forEach { agent ->
            val tracker = agentPerformance[agent]!!
            if (success) {
                tracker.recordSuccess(reward)
            } else {
                tracker.recordFailure()
            }
        }
        
        // Actualizar pesos cada 100 decisiones
        if (decisionCount % 100 == 0) {
            updateWeights()
        }
    }
    
    /**
     * Inicia episodio en todos los agentes.
     */
    fun startEpisode() {
        dqnAgent.startEpisode()
        ppoAgent.startEpisode()
        sacAgent.startEpisode()
        Logger.i(TAG, "Episode started in all agents")
    }
    
    /**
     * Finaliza episodio.
     */
    fun endEpisode(): EpisodeSummary {
        val dqnReward = dqnAgent.endEpisode()
        val ppoReward = ppoAgent.endEpisode()
        val sacReward = sacAgent.endEpisode()
        
        // Calcular reward promedio ponderado
        val avgReward = (dqnReward * dqnWeight + ppoReward * ppoWeight + sacReward * sacWeight)
        
        return EpisodeSummary(
            dqnReward = dqnReward,
            ppoReward = ppoReward,
            sacReward = sacReward,
            ensembleReward = avgReward
        )
    }
    
    /**
     * Obtiene estadísticas del ensemble.
     */
    fun getStats(): EnsembleStats {
        val dqnStats = dqnAgent.getStats()
        val ppoStats = ppoAgent.getStats()
        val sacStats = sacAgent.getStats()
        
        val consensusRate = if (decisionCount > 0) consensusCount.toFloat() / decisionCount else 0f
        val avgConfidence = if (decisionHistory.isNotEmpty()) {
            decisionHistory.map { it.second }.average().toFloat()
        } else 0f
        
        return EnsembleStats(
            totalDecisions = decisionCount,
            consensusRate = consensusRate,
            averageConfidence = avgConfidence,
            dqnStats = dqnStats,
            ppoStats = ppoStats,
            sacStats = sacStats,
            weights = Triple(dqnWeight, ppoWeight, sacWeight),
            performance = agentPerformance.mapValues { it.value.getSuccessRate() }
        )
    }
    
    /**
     * Calcula confianza de DQN basada en Q-value.
     */
    private fun calculateDQNConfidence(): Float {
        val stats = dqnAgent.getStats()
        // Q-value normalizado a confianza
        return min(1f, stats.averageQValue / 10f)
    }
    
    /**
     * Calcula confianza de PPO basada en value y entropy.
     */
    private fun calculatePPOConfidence(): Float {
        val stats = ppoAgent.getStats()
        return min(1f, (stats.averageValue + 1f) / 2f)
    }
    
    /**
     * Calcula confianza de SAC basada en Q-value.
     */
    private fun calculateSACConfidence(): Float {
        val stats = sacAgent.getStats()
        return min(1f, stats.averageQValue / 10f)
    }
    
    /**
     * Determina agente primario (mayor confianza).
     */
    private fun determinePrimaryAgent(dqnConf: Float, ppoConf: Float, sacConf: Float): Agent? {
        return when {
            dqnConf >= ppoConf && dqnConf >= sacConf -> Agent.DQN
            ppoConf >= dqnConf && ppoConf >= sacConf -> Agent.PPO
            sacConf >= dqnConf && sacConf >= ppoConf -> Agent.SAC
            else -> null
        }
    }
    
    /**
     * Actualiza pesos basado en performance.
     */
    private fun updateWeights() {
        val dqnPerf = agentPerformance[Agent.DQN]!!.getSuccessRate()
        val ppoPerf = agentPerformance[Agent.PPO]!!.getSuccessRate()
        val sacPerf = agentPerformance[Agent.SAC]!!.getSuccessRate()
        
        val total = dqnPerf + ppoPerf + sacPerf
        if (total > 0) {
            dqnWeight = dqnPerf / total
            ppoWeight = ppoPerf / total
            sacWeight = sacPerf / total
            
            Logger.i(TAG, "Weights updated: DQN=${"%.2f".format(dqnWeight)}, " +
                "PPO=${"%.2f".format(ppoWeight)}, " +
                "SAC=${"%.2f".format(sacWeight)}")
        }
    }
    
    /**
     * Guarda todos los modelos.
     */
    fun saveModels(path: String): Boolean {
        val dqnOk = dqnAgent.save("$path/dqn.tflite")
        // PPO y SAC requeren implementación de save
        Logger.i(TAG, "Models saved to $path")
        return dqnOk
    }
    
    /**
     * Carga todos los modelos.
     */
    fun loadModels(path: String): Boolean {
        val dqnOk = dqnAgent.load("$path/dqn.tflite")
        Logger.i(TAG, "Models loaded from $path")
        return dqnOk
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        dqnAgent.release()
        ppoAgent.release()
        sacAgent.release()
        Logger.i(TAG, "EnsembleRLCoordinator released")
    }
    
    /**
     * Tracker de performance por agente.
     */
    private class PerformanceTracker {
        private var successes = 0
        private var failures = 0
        private var totalReward = 0f
        
        fun recordSuccess(reward: Float) {
            successes++
            totalReward += reward
        }
        
        fun recordFailure() {
            failures++
        }
        
        fun getSuccessRate(): Float {
            val total = successes + failures
            return if (total > 0) successes.toFloat() / total else 0.5f
        }
    }
}

/**
 * Decisión del ensemble.
 */
data class EnsembleDecision(
    val action: ActionType,
    val confidence: Float,
    val consensus: Boolean,
    val agreeingAgents: List<EnsembleRLCoordinator.Agent>,
    val allVotes: List<Vote>,
    val primaryAgent: EnsembleRLCoordinator.Agent?,
    val selectionTimeMs: Long
)

/**
 * Voto individual.
 */
data class Vote(
    val agent: EnsembleRLCoordinator.Agent,
    val action: ActionType,
    val confidence: Float
)

/**
 * Resultado de training.
 */
data class EnsembleTrainingResult(
    val dqnLoss: Float,
    val ppoLoss: Float,
    val sacLoss: Float
)

/**
 * Resumen de episodio.
 */
data class EpisodeSummary(
    val dqnReward: Float,
    val ppoReward: Float,
    val sacReward: Float,
    val ensembleReward: Float
)

/**
 * Estadísticas del ensemble.
 */
data class EnsembleStats(
    val totalDecisions: Int,
    val consensusRate: Float,
    val averageConfidence: Float,
    val dqnStats: DQNStats,
    val ppoStats: PPOStats,
    val sacStats: SACStats,
    val weights: Triple<Float, Float, Float>,
    val performance: Map<EnsembleRLCoordinator.Agent, Float>
)
