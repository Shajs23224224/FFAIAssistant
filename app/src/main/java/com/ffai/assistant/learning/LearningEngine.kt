package com.ffai.assistant.learning

import android.content.Context
import com.ffai.assistant.action.Action
import com.ffai.assistant.config.Constants
import com.ffai.assistant.core.EpisodeStats
import com.ffai.assistant.core.Experience
import com.ffai.assistant.core.ExperienceBuffer
import com.ffai.assistant.decision.NeuralNetwork
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.utils.Logger

/**
 * Motor de aprendizaje por refuerzo.
 * Coordina el almacenamiento de experiencias y entrenamiento.
 */
class LearningEngine(
    context: Context,
    private val network: NeuralNetwork
) {
    private val database = LearningDatabase(context)
    private val buffer = ExperienceBuffer(Constants.MEMORY_SIZE)
    private val rewardFunction = RewardFunction()
    
    private var trainingStep = 0
    private var episodeCount = 0
    
    init {
        Logger.i("LearningEngine inicializado")
        // Cargar experiencias previas
        val savedExperiences = database.loadRecentExperiences(1000)
        savedExperiences.forEach { buffer.add(it) }
        Logger.i("${savedExperiences.size} experiencias cargadas de BD")
    }
    
    /**
     * Calcula recompensa para una transición estado-acción.
     */
    fun calculateReward(prevState: GameState, currentState: GameState, action: Action): Float {
        return rewardFunction.calculate(prevState, currentState, action)
    }
    
    /**
     * Registra una nueva experiencia.
     */
    fun recordExperience(
        state: GameState,
        action: Action,
        reward: Float,
        nextState: GameState,
        done: Boolean = false
    ) {
        val experience = Experience(
            state = state,
            action = action,
            reward = reward,
            nextState = nextState,
            done = done
        )
        
        buffer.add(experience)
        database.saveExperience(experience)
        
        // Log cada 100 experiencias
        if (buffer.size() % 100 == 0) {
            Logger.d("Buffer: ${buffer.size()} experiencias")
        }
    }
    
    /**
     * Ejecuta un paso de entrenamiento.
     */
    fun trainStep() {
        trainingStep++
        
        // Entrenar cada 10 pasos con batch de 32
        if (trainingStep % 10 == 0 && buffer.size() >= Constants.BATCH_SIZE) {
            performTraining()
        }
        
        // Guardar modelo cada 1000 pasos
        if (trainingStep % 1000 == 0) {
            network.saveModel()
        }
    }
    
    /**
     * Realiza entrenamiento con un batch de experiencias.
     */
    private fun performTraining() {
        val batch = buffer.sample(Constants.BATCH_SIZE)
        
        // Calcular gradientes aproximados (simplificado)
        // En implementación real, esto usaría backpropagation
        val gradients = FloatArray(Constants.NUM_ACTIONS) { 0f }
        
        for (exp in batch) {
            val actionIdx = exp.action.type.index
            // Ajustar gradiente basado en recompensa
            gradients[actionIdx] += exp.reward * 0.01f
        }
        
        // Aplicar gradientes al modelo
        network.updateWeights(gradients)
        
        Logger.d("Training step $trainingStep: batch=${batch.size}, avg reward=${batch.map { it.reward }.average()}")
    }
    
    /**
     * Notifica fin de episodio.
     */
    fun endEpisode(stats: EpisodeStats) {
        episodeCount++
        database.saveEpisodeStats(stats)
        
        // Calcular recompensa final
        val finalReward = calculateFinalReward(stats)
        
        // Actualizar última experiencia con recompensa final
        // y marcar como done
        
        Logger.i("Episodio $episodeCount terminado - Reward final: $finalReward, Placement: ${stats.placement}")
        
        // Backup periódico del modelo
        if (episodeCount % 10 == 0) {
            network.backup()
        }
    }
    
    /**
     * Calcula recompensa final del episodio.
     */
    private fun calculateFinalReward(stats: EpisodeStats): Float {
        var reward = 0f
        
        // Recompensas por posición final
        reward += when {
            stats.placement == 1 -> Constants.REWARD_WIN
            stats.placement <= 5 -> Constants.REWARD_TOP_5
            stats.placement <= 10 -> Constants.REWARD_TOP_10
            else -> 0f
        }
        
        // Recompensas por kills
        reward += stats.kills * Constants.REWARD_KILL
        
        // Penalización por muerte rápida
        if (stats.durationMs < 60000) {  // Menos de 1 minuto
            reward -= 100f
        }
        
        return reward
    }
    
    /**
     * Obtiene estadísticas de aprendizaje.
     */
    fun getStats(): LearningStats {
        return LearningStats(
            episodeCount = episodeCount,
            experienceCount = buffer.size(),
            totalTrainingSteps = trainingStep
        )
    }
    
    /**
     * Guarda modelo y datos.
     */
    fun saveModel() {
        network.saveModel()
        database.close()
    }
    
    data class LearningStats(
        val episodeCount: Int,
        val experienceCount: Int,
        val totalTrainingSteps: Int
    )
}
