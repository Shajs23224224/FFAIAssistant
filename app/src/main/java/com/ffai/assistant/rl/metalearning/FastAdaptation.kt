package com.ffai.assistant.rl.metalearning

import com.ffai.assistant.utils.Logger

/**
 * FastAdaptation - Adaptación rápida durante gameplay.
 * 
 * Detecta cambios de contexto y adapta la política en tiempo real.
 * Optimizado para dispositivos móviles (A21S).
 */
class FastAdaptation(private val mamlAgent: MAMLAgent) {
    
    companion object {
        const val TAG = "FastAdaptation"
        
        // Thresholds
        const val ADAPTATION_THRESHOLD = 0.7f
        const val MIN_ADAPTATION_STEPS = 3
        const val MAX_ADAPTATION_STEPS = 10
        
        // Memory
        const val ADAPTATION_MEMORY_SIZE = 50
    }
    
    // Estado de adaptación
    private var isAdapting = false
    private var currentContext: ContextChange? = null
    private var adaptationBuffer = mutableListOf<MAMLExperience>()
    private var adaptationProgress = 0f
    
    // Historial
    private var adaptationHistory = mutableListOf<AdaptationRecord>()
    private var totalAdaptations = 0
    private var successfulAdaptations = 0
    
    /**
     * Detecta cambio de contexto y inicia adaptación si es necesario.
     */
    fun detectAndAdapt(
        state: FloatArray,
        currentWeapon: Int,
        currentMap: Int,
        health: Float,
        enemyBehavior: String
    ): FastAdaptationResult {
        val contextChange = detectContextChange(
            currentWeapon, currentMap, health, enemyBehavior
        )
        
        if (contextChange != null && shouldAdapt(contextChange)) {
            return startAdaptation(contextChange, state)
        }
        
        // Si ya estamos adaptando, continuar
        if (isAdapting) {
            return continueAdaptation(state)
        }
        
        return FastAdaptationResult(false, 0f, "No change")
    }
    
    /**
     * Detecta cambio de contexto relevante.
     */
    private fun detectContextChange(
        weapon: Int,
        map: Int,
        health: Float,
        behavior: String
    ): ContextChange? {
        // Placeholder: implementar detección real
        return when {
            health < 0.3f -> ContextChange.LowHealth(health)
            behavior != "previous" -> ContextChange.NewEnemyBehavior(behavior)
            else -> null
        }
    }
    
    /**
     * Determina si adaptación es necesaria.
     */
    private fun shouldAdapt(change: ContextChange): Boolean {
        return when (change) {
            is ContextChange.NewWeapon -> true
            is ContextChange.NewMap -> true
            is ContextChange.NewEnemyBehavior -> true
            is ContextChange.LowHealth -> change.health < 0.2f
        }
    }
    
    /**
     * Inicia proceso de adaptación.
     */
    private fun startAdaptation(change: ContextChange, state: FloatArray): FastAdaptationResult {
        isAdapting = true
        currentContext = change
        adaptationBuffer.clear()
        adaptationProgress = 0f
        totalAdaptations++
        
        Logger.i(TAG, "Starting adaptation for ${change::class.simpleName}")
        
        // Adaptar inmediatamente con MAML
        val result = mamlAgent.fastAdapt(state, change)
        
        return FastAdaptationResult(
            adapted = result.adapted,
            adaptationMagnitude = result.adaptationMagnitude,
            contextType = result.contextType
        )
    }
    
    /**
     * Continúa adaptación con nuevos datos.
     */
    private fun continueAdaptation(state: FloatArray): FastAdaptationResult {
        adaptationProgress += 0.1f
        
        // Finalizar si progreso completo
        if (adaptationProgress >= 1.0f) {
            finalizeAdaptation()
        }
        
        return FastAdaptationResult(
            adapted = true,
            adaptationMagnitude = adaptationProgress,
            contextType = currentContext?.let { it::class.simpleName } ?: "Unknown"
        )
    }
    
    /**
     * Agrega experiencia para adaptación online.
     */
    fun addExperience(experience: MAMLExperience) {
        if (!isAdapting) return
        
        adaptationBuffer.add(experience)
        if (adaptationBuffer.size > ADAPTATION_MEMORY_SIZE) {
            adaptationBuffer.removeAt(0)
        }
    }
    
    /**
     * Finaliza adaptación.
     */
    private fun finalizeAdaptation() {
        val success = adaptationBuffer.size >= MIN_ADAPTATION_STEPS
        
        if (success) {
            successfulAdaptations++
            Logger.i(TAG, "Adaptation successful - ${adaptationBuffer.size} samples")
        }
        
        adaptationHistory.add(AdaptationRecord(
            context = currentContext!!,
            duration = adaptationBuffer.size,
            success = success,
            timestamp = System.currentTimeMillis()
        ))
        
        isAdapting = false
        currentContext = null
        adaptationBuffer.clear()
        adaptationProgress = 0f
    }
    
    /**
     * Forza adaptación inmediata.
     */
    fun forceAdaptation(context: ContextChange, state: FloatArray): FastAdaptationResult {
        return startAdaptation(context, state)
    }
    
    /**
     * Obtiene progreso de adaptación actual.
     */
    fun getAdaptationProgress(): Float = adaptationProgress
    
    /**
     * Verifica si está adaptando.
     */
    fun isCurrentlyAdapting(): Boolean = isAdapting
    
    /**
     * Estadísticas.
     */
    fun getStats() = FastAdaptationStats(
        totalAdaptations = totalAdaptations,
        successfulAdaptations = successfulAdaptations,
        successRate = if (totalAdaptations > 0) successfulAdaptations.toFloat() / totalAdaptations else 0f,
        isCurrentlyAdapting = isAdapting,
        adaptationBufferSize = adaptationBuffer.size,
        historySize = adaptationHistory.size
    )
    
    fun reset() {
        isAdapting = false
        currentContext = null
        adaptationBuffer.clear()
        adaptationHistory.clear()
        totalAdaptations = 0
        successfulAdaptations = 0
    }
}

data class AdaptationRecord(
    val context: ContextChange,
    val duration: Int,
    val success: Boolean,
    val timestamp: Long
)

data class FastAdaptationStats(
    val totalAdaptations: Int,
    val successfulAdaptations: Int,
    val successRate: Float,
    val isCurrentlyAdapting: Boolean,
    val adaptationBufferSize: Int,
    val historySize: Int
)
