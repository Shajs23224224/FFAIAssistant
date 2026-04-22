package com.ffai.assistant.learning

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.config.Constants
import com.ffai.assistant.perception.GameState

/**
 * Calcula recompensas para transiciones estado-acción.
 */
class RewardFunction {
    
    private var previousKills = 0
    private var previousDamage = 0f
    private var previousHealth = 1f
    
    /**
     * Calcula recompensa para una transición.
     */
    fun calculate(prevState: GameState, currentState: GameState, action: Action): Float {
        var reward = 0f
        
        // Recompensas de combate
        reward += calculateCombatReward(prevState, currentState)
        
        // Recompensas por supervivencia
        reward += calculateSurvivalReward(prevState, currentState)
        
        // Recompensas específicas de acción
        reward += calculateActionReward(prevState, currentState, action)
        
        // Penalizaciones
        reward += calculatePenalties(prevState, currentState)
        
        return reward
    }
    
    private fun calculateCombatReward(prev: GameState, current: GameState): Float {
        var reward = 0f
        
        // Kill confirmado
        if (current.kills > prev.kills) {
            reward += Constants.REWARD_KILL
        }
        
        // Daño infligido
        val damageDelta = current.damageDealt - prev.damageDealt
        if (damageDelta > 0) {
            reward += damageDelta * Constants.REWARD_HIT
        }
        
        return reward
    }
    
    private fun calculateSurvivalReward(prev: GameState, current: GameState): Float {
        var reward = 0f
        
        // Bonus por sobrevivir cada segundo
        reward += Constants.REWARD_SURVIVAL
        
        // Curación efectiva
        if (current.healthRatio > prev.healthRatio && prev.healthRatio < 0.5f) {
            reward += Constants.REWARD_HEAL
        }
        
        // Recarga oportuna
        if (current.ammoRatio > prev.ammoRatio && prev.ammoRatio < 0.3f) {
            reward += Constants.REWARD_RELOAD
        }
        
        return reward
    }
    
    private fun calculateActionReward(prev: GameState, current: GameState, action: Action): Float {
        var reward = 0f
        
        when (action.type) {
            ActionType.SHOOT -> {
                // Recompensar disparos que aciertan
                if (current.damageDealt > prev.damageDealt) {
                    reward += 5f  // Hit confirmado
                } else if (!current.enemyPresent) {
                    reward -= 1f  // Disparo sin enemigo (desperdicio)
                }
            }
            
            ActionType.HEAL -> {
                // Recompensar curación cuando era necesaria
                if (prev.healthRatio < 0.5f && current.healthRatio > prev.healthRatio) {
                    reward += 3f
                } else if (prev.healthRatio > 0.8f) {
                    reward -= 2f  // Curarse con vida alta (waste)
                }
            }
            
            ActionType.RELOAD -> {
                // Recompensar recarga cuando era necesaria
                if (prev.ammoRatio < 0.2f && !prev.enemyPresent) {
                    reward += 2f
                } else if (prev.enemyPresent && prev.ammoRatio > 0.5f) {
                    reward -= 5f  // Recargar en combate con munición
                }
            }
            
            ActionType.MOVE_BACKWARD -> {
                // Recompensar retroceso cuando hay enemigo cerca
                if (prev.enemyPresent && prev.enemyDistance < 0.3f) {
                    reward += 2f  // Tactical retreat
                }
            }
            
            else -> {} // Otras acciones no tienen reward específico
        }
        
        return reward
    }
    
    private fun calculatePenalties(prev: GameState, current: GameState): Float {
        var penalty = 0f
        
        // Daño recibido
        val damageTaken = current.damageTaken - prev.damageTaken
        if (damageTaken > 0) {
            penalty += damageTaken * Constants.REWARD_DAMAGE_TAKEN
        }
        
        // Muerte
        if (current.isDead && !prev.isDead) {
            penalty += Constants.REWARD_DEATH
        }
        
        // Quedarse sin munición en combate
        if (current.ammoRatio == 0f && current.enemyPresent && prev.ammoRatio > 0f) {
            penalty -= 10f
        }
        
        // Estar fuera de zona segura
        if (!current.isInSafeZone && prev.isInSafeZone) {
            penalty -= 5f  // Acaba de salir de zona segura
        }
        
        return penalty
    }
}
