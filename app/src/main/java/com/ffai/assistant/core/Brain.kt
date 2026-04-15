package com.ffai.assistant.core

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.config.Constants
import com.ffai.assistant.decision.NeuralNetwork
import com.ffai.assistant.decision.PolicyNetwork
import com.ffai.assistant.learning.LearningEngine
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.perception.VisionProcessor
import com.ffai.assistant.utils.Logger

/**
 * Núcleo central de la IA.
 * Coordina percepción, decisión y aprendizaje.
 */
class Brain(context: Context) {
    
    private val vision = VisionProcessor()
    private val network = NeuralNetwork(context)
    private val policy = PolicyNetwork(network)
    private val learning = LearningEngine(context, network)
    
    // Estado actual y anterior
    private var currentState: GameState = GameState.DEFAULT
    private var previousState: GameState = GameState.DEFAULT
    private var lastAction: Action = Action.hold()
    
    // Control de cooldowns
    private val cooldowns = mutableMapOf<ActionType, Long>()
    
    // Estadísticas del episodio actual
    private var episodeStats = EpisodeStats()
    private var episodeStartTime = System.currentTimeMillis()
    
    init {
        Logger.i("Brain inicializado")
    }
    
    /**
     * Procesa un frame y decide la siguiente acción
     */
    fun processFrame(bitmap: Bitmap): Action {
        // 1. Percepción: analizar imagen
        previousState = currentState
        currentState = vision.analyze(bitmap)
        
        // 2. Decisión: qué acción tomar
        val action = decideAction()
        
        // 3. Calcular recompensa del paso anterior
        if (lastAction.type != ActionType.HOLD) {
            val reward = learning.calculateReward(previousState, currentState, lastAction)
            learning.recordExperience(previousState, lastAction, reward, currentState)
            episodeStats = episodeStats.copy(totalReward = episodeStats.totalReward + reward)
        }
        
        // 4. Actualizar estadísticas
        updateStats(action)
        
        // 5. Actualizar cooldowns
        updateCooldowns(action)
        
        // 6. Aprender cada N pasos
        learning.trainStep()
        
        lastAction = action
        return action
    }
    
    /**
     * Decide la acción basada en estado actual
     */
    private fun decideAction(): Action {
        // Verificar cooldowns
        val now = System.currentTimeMillis()
        val availableActions = ActionType.values().filter { actionType ->
            val cooldownEnd = cooldowns[actionType] ?: 0
            now >= cooldownEnd
        }
        
        // Si no hay enemigo, priorizar rotación
        if (!currentState.enemyPresent) {
            // Alternar entre ROTATE_LEFT y ROTATE_RIGHT
            return if (System.currentTimeMillis() % 2000 < 1000) {
                Action.rotateLeft()
            } else {
                Action.rotateRight()
            }
        }
        
        // Usar red neuronal para decidir
        val actionType = policy.selectAction(currentState.toFeatureVector(), availableActions)
        
        // Preparar parámetros según acción
        return when (actionType) {
            ActionType.AIM -> Action.aim(currentState.enemyX, currentState.enemyY)
            ActionType.SHOOT -> Action.shoot()
            ActionType.MOVE_FORWARD -> Action.moveForward()
            ActionType.MOVE_BACKWARD -> Action.moveBackward(if (currentState.enemyPresent) 0.8f else 0.5f)
            ActionType.MOVE_LEFT -> Action.moveLeft()
            ActionType.MOVE_RIGHT -> Action.moveRight()
            ActionType.HEAL -> if (currentState.needsUrgentHeal()) Action.heal() else Action.hold()
            ActionType.RELOAD -> if (currentState.needsReload()) Action.reload() else Action.hold()
            ActionType.CROUCH -> Action.crouch()
            ActionType.JUMP -> Action.jump()
            ActionType.LOOT -> if (currentState.lootNearby) Action.loot() else Action.hold()
            ActionType.REVIVE -> if (currentState.teammateNeedsRevive) Action.revive() else Action.hold()
            ActionType.ROTATE_LEFT -> Action.rotateLeft()
            ActionType.ROTATE_RIGHT -> Action.rotateRight()
            ActionType.HOLD -> Action.hold()
        }
    }
    
    /**
     * Actualiza cooldowns después de ejecutar acción
     */
    private fun updateCooldowns(action: Action) {
        if (action.cooldownMs > 0) {
            cooldowns[action.type] = System.currentTimeMillis() + action.cooldownMs
        }
        
        // Cooldown global entre acciones
        cooldowns[ActionType.AIM] = System.currentTimeMillis() + 50
    }
    
    /**
     * Actualiza estadísticas del episodio
     */
    private fun updateStats(action: Action) {
        episodeStats = episodeStats.copy(
            totalActions = episodeStats.totalActions + 1
        )
        
        when (action.type) {
            ActionType.SHOOT -> episodeStats = episodeStats.copy(shotsFired = episodeStats.shotsFired + 1)
            ActionType.HEAL -> episodeStats = episodeStats.copy(healsUsed = episodeStats.healsUsed + 1)
            ActionType.RELOAD -> episodeStats = episodeStats.copy(reloads = episodeStats.reloads + 1)
            else -> {}
        }
        
        // Detectar cambios en kills/daño
        if (currentState.kills > previousState.kills) {
            episodeStats = episodeStats.copy(kills = currentState.kills)
        }
        if (currentState.damageDealt > previousState.damageDealt) {
            episodeStats = episodeStats.copy(
                damageDealt = currentState.damageDealt,
                shotsHit = episodeStats.shotsHit + 1
            )
        }
    }
    
    /**
     * Notifica fin de episodio (partida terminada)
     */
    fun endEpisode(finalPlacement: Int) {
        val finalStats = episodeStats.copy(
            placement = finalPlacement,
            endTime = System.currentTimeMillis(),
            survivalTimeMs = System.currentTimeMillis() - episodeStartTime
        )
        
        Logger.i("Episodio terminado: $finalStats")
        
        // Guardar estadísticas y aprender
        learning.endEpisode(finalStats)
        
        // Resetear para siguiente episodio
        episodeStats = EpisodeStats()
        episodeStartTime = System.currentTimeMillis()
        cooldowns.clear()
    }
    
    /**
     * Obtiene el estado actual
     */
    fun getCurrentState(): GameState = currentState
    
    /**
     * Obtiene estadísticas del episodio
     */
    fun getEpisodeStats(): EpisodeStats = episodeStats
    
    /**
     * Libera recursos
     */
    fun destroy() {
        learning.saveModel()
        network.close()
    }
}
