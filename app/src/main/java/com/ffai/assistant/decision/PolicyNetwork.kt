package com.ffai.assistant.decision

import com.ffai.assistant.action.ActionType
import com.ffai.assistant.config.Constants
import kotlin.random.Random

/**
 * Política de selección de acciones.
 * Implementa epsilon-greedy con decay.
 */
class PolicyNetwork(private val network: NeuralNetwork) {
    
    private var epsilon: Float = Constants.EPSILON_START
    private val random = Random(System.currentTimeMillis())
    
    /**
     * Selecciona una acción basada en el estado actual.
     * 
     * @param state Vector de características del estado
     * @param availableActions Lista de acciones disponibles (no en cooldown)
     * @return Tipo de acción seleccionada
     */
    fun selectAction(state: FloatArray, availableActions: List<ActionType>): ActionType {
        if (availableActions.isEmpty()) return ActionType.HOLD
        
        // Exploración aleatoria (epsilon-greedy)
        if (random.nextFloat() < epsilon) {
            return availableActions.random()
        }
        
        // Explotación: usar red neuronal
        val probabilities = network.predict(state)
        
        // Seleccionar la acción con mayor probabilidad disponible
        var bestAction = availableActions.first()
        var bestProb = -1f
        
        for (action in availableActions) {
            val prob = probabilities[action.index]
            if (prob > bestProb) {
                bestProb = prob
                bestAction = action
            }
        }
        
        return bestAction
    }
    
    /**
     * Reduce epsilon (más explotación con el tiempo).
     */
    fun decayEpsilon() {
        epsilon = maxOf(Constants.EPSILON_MIN, epsilon * Constants.EPSILON_DECAY)
    }
    
    /**
     * Obtiene el valor actual de epsilon.
     */
    fun getEpsilon(): Float = epsilon
    
    /**
     * Fuerza un valor de epsilon específico.
     */
    fun setEpsilon(value: Float) {
        epsilon = value.coerceIn(0f, 1f)
    }
}
