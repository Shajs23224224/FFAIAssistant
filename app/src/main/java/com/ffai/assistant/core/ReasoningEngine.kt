package com.ffai.assistant.core

import com.ffai.assistant.model.ActionSuggestion
import com.ffai.assistant.model.ActionType
import com.ffai.assistant.model.EnsembleResult
import com.ffai.assistant.model.FastEnsembleResult
import com.ffai.assistant.core.ReasoningMode
import com.ffai.assistant.model.StrategicEnsembleResult
import com.ffai.assistant.model.TacticalEnsembleResult
import com.ffai.assistant.model.ThreatLevel
import com.ffai.assistant.overlay.FrameData
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * FASE 3: ReasoningEngine - Selección automática de modo de razonamiento.
 *
 * 3 modos de "profundidad de pensamiento":
 * - CORTO (<8ms): Instinto, solo CombatNet - Combate cercano, <100ms reacción
 * - MEDIO (15-30ms): Táctico, Combat+Tactical+Recoil - Combate estándar
 * - LARGO (50-80ms): Estratégico, Ensemble completo - Planeación, loot, posicionamiento
 *
 * Selección automática basada en:
 * - Distancia a enemigos
 * - Tiempo desde último combate
 * - Estado de HP/munición
 * - Zona segura/insegura
 * - FPS actual (adaptativo)
 */
class ReasoningEngine(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        const val TAG = "ReasoningEngine"
        
        // Umbrales de tiempo para cada modo
        const val SHORT_MODE_MAX_MS = 8L
        const val MEDIUM_MODE_MAX_MS = 30L
        const val LONG_MODE_MAX_MS = 80L
        
        // Factores de decisión
        const val CRITICAL_DISTANCE_THRESHOLD = 30f // metros
        const val HIGH_DISTANCE_THRESHOLD = 50f
        const val COMBAT_COOLDOWN_MS = 500L // tiempo para considerar "fuera de combate"
        const val HP_CRITICAL_THRESHOLD = 30
        const val FPS_THRESHOLD_LOW = 20
        const val FPS_THRESHOLD_HIGH = 30
    }

    // Estado actual
    private val currentMode = AtomicReference(ReasoningMode.MEDIUM)
    private val lastCombatTime = AtomicReference<Long>(0)
    private val lastModeChangeTime = AtomicReference<Long>(0)
    private val isInCombat = AtomicBoolean(false)
    
    // Métricas para adaptación
    private var averageInferenceTime = 0L
    private var currentFPS = 30
    private var consecutiveSlowInferences = 0
    private var consecutiveFastInferences = 0
    
    // Historial para transiciones suaves
    private val modeHistory = ArrayDeque<ReasoningMode>(10)
    
    // Callbacks
    private var onModeChanged: ((ReasoningMode, ReasoningMode) -> Unit)? = null
    private var onReasoningStats: ((ReasoningStats) -> Unit)? = null

    /**
     * Determina el modo de razonamiento óptimo para el frame actual.
     * Análisis rápido (< 1ms) basado en heurísticas simples.
     */
    fun determineMode(frameData: FrameData, lastResult: EnsembleResult?): ReasoningMode {
        val now = System.currentTimeMillis()
        
        // Factores de decisión
        val hasCloseEnemies = lastResult?.mergedEnemies?.any {
            it.confidence > 0.6f && calculateDistance(it) < CRITICAL_DISTANCE_THRESHOLD
        } ?: false
        
        val hasEnemiesNearby = lastResult?.mergedEnemies?.any {
            it.confidence > 0.5f && calculateDistance(it) < HIGH_DISTANCE_THRESHOLD
        } ?: false
        
        val timeSinceCombat = now - lastCombatTime.get()
        val inActiveCombat = timeSinceCombat < COMBAT_COOLDOWN_MS
        
        val isCriticalHP = lastResult?.uiOutput?.hpInfo?.status?.ordinal ?: 0 >= 2 // INJURED or worse
        
        // Decisión de modo
        val newMode = when {
            // CORTO: Combate cercano crítico o FPS muy bajo
            (hasCloseEnemies && inActiveCombat) || currentFPS < FPS_THRESHOLD_LOW -> {
                ReasoningMode.SHORT
            }
            
            // LARGO: Fuera de combate, FPS bueno, necesita estrategia
            (!hasEnemiesNearby && timeSinceCombat > COMBAT_COOLDOWN_MS && 
             currentFPS >= FPS_THRESHOLD_HIGH && shouldUseLongMode(lastResult)) -> {
                ReasoningMode.LONG
            }
            
            // MEDIO: Default o combate estándar
            else -> ReasoningMode.MEDIUM
        }
        
        // Aplicar histeresis para evitar cambios bruscos
        val finalMode = applyHysteresis(newMode)
        
        // Notificar cambio si hubo
        if (finalMode != currentMode.get()) {
            val oldMode = currentMode.getAndSet(finalMode)
            lastModeChangeTime.set(now)
            onModeChanged?.invoke(oldMode, finalMode)
            Logger.i(TAG, "Modo cambiado: $oldMode -> $finalMode (enemigos cercanos: $hasCloseEnemies, FPS: $currentFPS)")
        }
        
        // Actualizar historial
        modeHistory.addLast(finalMode)
        if (modeHistory.size > 10) modeHistory.removeFirst()
        
        // Actualizar estado de combate
        if (hasEnemiesNearby) {
            lastCombatTime.set(now)
            isInCombat.set(true)
        } else if (timeSinceCombat > COMBAT_COOLDOWN_MS) {
            isInCombat.set(false)
        }
        
        return finalMode
    }
    
    /**
     * Aplica histeresis para evitar oscilaciones entre modos.
     */
    private fun applyHysteresis(proposedMode: ReasoningMode): ReasoningMode {
        val current = currentMode.get()
        val timeSinceChange = System.currentTimeMillis() - lastModeChangeTime.get()
        
        // Si cambio reciente (< 500ms), mantener modo actual
        if (timeSinceChange < 500) {
            return current
        }
        
        // Si modo propuesto es diferente, verificar consistencia
        if (proposedMode != current) {
            // Contar cuántas veces aparece el modo propuesto en historial reciente
            val recentHistory = modeHistory.takeLast(5)
            val proposedCount = recentHistory.count { it == proposedMode }
            
            // Solo cambiar si el modo propuesto aparece > 60% del tiempo reciente
            if (proposedCount < 3 && timeSinceChange < 2000) {
                return current
            }
        }
        
        return proposedMode
    }
    
    /**
     * Determina si debería usar modo largo basado en situación.
     */
    private fun shouldUseLongMode(result: EnsembleResult?): Boolean {
        result ?: return false
        
        // Situaciones que requieren planeación profunda
        return when {
            // Cambio de zona inminente
            result.tacticalOutput?.situation?.timeToNextZone != null && 
            result.tacticalOutput.situation.timeToNextZone < 30 -> true
            
            // Looting seguro (no hay enemigos cerca)
            result.mergedEnemies.isEmpty() && result.tacticalOutput?.lootPriority?.isNotEmpty() == true -> true
            
            // Fase de juego cambió
            result.tacticalOutput?.requiresStrategicUpdate == true -> true
            
            else -> false
        }
    }
    
    /**
     * Calcula distancia aproximada a enemigo.
     */
    private fun calculateDistance(enemy: com.ffai.assistant.model.MergedEnemy): Float {
        // Simplificación: usar confianza inversa como proxy de distancia
        return (1f - enemy.confidence) * 100f + 20f
    }
    
    /**
     * Selecciona acción final basada en resultados del ensemble y modo actual.
     */
    fun selectAction(
        mode: ReasoningMode,
        shortResult: FastEnsembleResult?,
        mediumResult: TacticalEnsembleResult?,
        longResult: StrategicEnsembleResult?,
        baseResult: EnsembleResult
    ): ActionSuggestion {
        val now = System.currentTimeMillis()
        
        // Prioridad de resultados según modo
        val selectedAction = when (mode) {
            ReasoningMode.SHORT -> {
                // Prioridad: 1. Fast combat, 2. Base ensemble
                shortResult?.suggestedAction ?: baseResult.suggestedAction
            }
            
            ReasoningMode.MEDIUM -> {
                // Prioridad: 1. Tactical, 2. Combat, 3. Base
                mediumResult?.suggestedAction 
                    ?: mediumResult?.combatOutput?.suggestedAction
                    ?: baseResult.suggestedAction
            }
            
            ReasoningMode.LONG -> {
                // Prioridad: 1. Strategy, 2. Tactical, 3. Base
                longResult?.strategyOutput?.let { strategy ->
                    // Convertir recomendación de estrategia a acción
                    if (strategy.riskAssessment.overallRisk > 0.7f) {
                        ActionSuggestion(ActionType.MOVE_BACKWARD, 0.8f)
                    } else {
                        baseResult.suggestedAction
                    }
                } ?: mediumResult?.suggestedAction ?: baseResult.suggestedAction
            }
        }
        
        // Ajustar confianza basada en consistencia entre modos
        val adjustedConfidence = adjustConfidence(selectedAction, mode, baseResult.confidence)
        
        return selectedAction.copy(confidence = adjustedConfidence)
    }
    
    /**
     * Ajusta confianza basada en consistencia y contexto.
     */
    private fun adjustConfidence(
        action: ActionSuggestion,
        mode: ReasoningMode,
        baseConfidence: Float
    ): Float {
        var confidence = action.confidence * baseConfidence
        
        // Penalizar si modo es muy conservador
        if (mode == ReasoningMode.LONG && action.type == ActionType.HOLD) {
            confidence *= 0.9f
        }
        
        // Boost si confianza del sistema es alta
        if (baseConfidence > 0.8f) {
            confidence = (confidence * 1.1f).coerceAtMost(1f)
        }
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Reporta tiempo de inferencia para adaptación.
     */
    fun reportInferenceTime(mode: ReasoningMode, timeMs: Long) {
        // Actualizar promedio móvil
        averageInferenceTime = (averageInferenceTime * 0.7 + timeMs * 0.3).toLong()
        
        // Detectar lentitud
        val maxTime = when (mode) {
            ReasoningMode.SHORT -> SHORT_MODE_MAX_MS
            ReasoningMode.MEDIUM -> MEDIUM_MODE_MAX_MS
            ReasoningMode.LONG -> LONG_MODE_MAX_MS
        }
        
        if (timeMs > maxTime) {
            consecutiveSlowInferences++
            consecutiveFastInferences = 0
            
            // Si muchas inferencias lentas, degradar modo
            if (consecutiveSlowInferences > 5 && mode.ordinal > 0) {
                val lowerMode = ReasoningMode.values()[mode.ordinal - 1]
                Logger.w(TAG, "Inferencias lentas detectadas, degradando de $mode a $lowerMode")
            }
        } else {
            consecutiveFastInferences++
            consecutiveSlowInferences = 0
        }
        
        // Notificar estadísticas
        onReasoningStats?.invoke(ReasoningStats(
            currentMode = mode,
            lastInferenceTimeMs = timeMs,
            averageInferenceTimeMs = averageInferenceTime,
            targetTimeMs = maxTime,
            isMeetingTarget = timeMs <= maxTime,
            currentFPS = currentFPS
        ))
    }
    
    /**
     * Actualiza FPS actual para adaptación.
     */
    fun updateFPS(fps: Int) {
        currentFPS = fps
    }
    
    /**
     * Obtiene el modo actual.
     */
    fun getCurrentMode(): ReasoningMode = currentMode.get()
    
    /**
     * Verifica si está en combate activo.
     */
    fun isInCombat(): Boolean = isInCombat.get()
    
    /**
     * Fuerza un modo específico (para override manual).
     */
    fun forceMode(mode: ReasoningMode) {
        val oldMode = currentMode.getAndSet(mode)
        lastModeChangeTime.set(System.currentTimeMillis())
        onModeChanged?.invoke(oldMode, mode)
        Logger.i(TAG, "Modo forzado manualmente: $oldMode -> $mode")
    }
    
    /**
     * Resetea estado.
     */
    fun reset() {
        currentMode.set(ReasoningMode.MEDIUM)
        lastCombatTime.set(0)
        lastModeChangeTime.set(0)
        isInCombat.set(false)
        averageInferenceTime = 0
        consecutiveSlowInferences = 0
        consecutiveFastInferences = 0
        modeHistory.clear()
        currentFPS = 30
        Logger.i(TAG, "ReasoningEngine reseteado")
    }

    // ============================================
    // CALLBACKS
    // ============================================

    fun setOnModeChangedListener(listener: (ReasoningMode, ReasoningMode) -> Unit) {
        onModeChanged = listener
    }

    fun setOnReasoningStatsListener(listener: (ReasoningStats) -> Unit) {
        onReasoningStats = listener
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class ReasoningStats(
    val currentMode: ReasoningMode,
    val lastInferenceTimeMs: Long,
    val averageInferenceTimeMs: Long,
    val targetTimeMs: Long,
    val isMeetingTarget: Boolean,
    val currentFPS: Int
) {
    fun getModeDescription(): String = when (currentMode) {
        ReasoningMode.SHORT -> "CORTO (Instinto)"
        ReasoningMode.MEDIUM -> "MEDIO (Táctico)"
        ReasoningMode.LONG -> "LARGO (Estratégico)"
    }
}
