package com.ffai.assistant.core

import com.ffai.assistant.model.ActionType
import com.ffai.assistant.model.EnsembleResult
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FASE 8: ConfidenceEngine - Sistema de confianza dinámica.
 *
 * La confianza de la IA cambia basada en:
 * - Decisiones correctas (aumenta)
 * - Kills confirmados (aumenta)
 * - Daño a aliados (disminuye severamente)
 * - Muertes (disminuye)
 * - Victoria (aumenta mucho)
 * - Consistencia de decisiones similares
 * 
 * Rango: 0.0 - 1.0
 * - < 0.3: Modo conservador, más defensivo
 * - 0.3 - 0.7: Modo normal
 * - > 0.8: Modo agresivo, toma más riesgos calculados
 */
class ConfidenceEngine {

    companion object {
        const val TAG = "ConfidenceEngine"
        
        // Peso de cada factor en el cálculo de confianza
        const val WEIGHT_KILLS = 0.25f
        const val WEIGHT_DEATHS = 0.20f
        const val WEIGHT_DECISIONS = 0.20f
        const val WEIGHT_FRIENDLY_FIRE = 0.25f
        const val WEIGHT_VICTORIES = 0.10f
        
        // Umbrales de modo
        const val THRESHOLD_CONSERVATIVE = 0.30f
        const val THRESHOLD_AGGRESSIVE = 0.80f
        
        // Límites de cambio por evento
        const val MAX_CHANGE_PER_KILL = 0.05f
        const val MAX_CHANGE_PER_DEATH = -0.08f
        const val MAX_CHANGE_PER_FRIENDLY_FIRE = -0.15f
        const val MAX_CHANGE_PER_VICTORY = 0.20f
        
        // Decay natural (confianza tiende a 0.5 con tiempo)
        const val NATURAL_DECAY_RATE = 0.001f
    }

    // Confianza actual (0.0 - 1.0)
    private val currentConfidence = AtomicReference(0.5f)
    private val baseConfidence = AtomicReference(0.5f)
    
    // Métricas acumuladas
    private val totalKills = AtomicInteger(0)
    private val totalDeaths = AtomicInteger(0)
    private val totalFriendlyFire = AtomicInteger(0)
    private val totalVictories = AtomicInteger(0)
    private val totalDecisions = AtomicInteger(0)
    private val correctDecisions = AtomicInteger(0)
    
    // Historial reciente para análisis de consistencia
    private val decisionHistory = ConcurrentLinkedQueue<DecisionRecord>()
    private val killHistory = ConcurrentLinkedQueue<KillRecord>()
    
    // Estado del modo
    private var currentMode: ConfidenceMode = ConfidenceMode.NORMAL
    
    // Callbacks
    private var onModeChanged: ((ConfidenceMode, ConfidenceMode) -> Unit)? = null
    private var onConfidenceUpdate: ((Float, ConfidenceFactors) -> Unit)? = null

    /**
     * Calcula confianza actual basada en todos los factores.
     */
    fun calculateConfidence(): Float {
        val kills = totalKills.get()
        val deaths = totalDeaths.get()
        val friendlyFire = totalFriendlyFire.get()
        val victories = totalVictories.get()
        val decisions = totalDecisions.get()
        val correct = correctDecisions.get()
        
        // Factor kills
        val killFactor = (kills * MAX_CHANGE_PER_KILL).coerceIn(0f, WEIGHT_KILLS)
        
        // Factor deaths (penaliza más)
        val deathFactor = (deaths * MAX_CHANGE_PER_DEATH).coerceIn(-WEIGHT_DEATHS, 0f)
        
        // Factor friendly fire (penaliza severamente)
        val ffFactor = (friendlyFire * MAX_CHANGE_PER_FRIENDLY_FIRE).coerceIn(-WEIGHT_FRIENDLY_FIRE, 0f)
        
        // Factor victories
        val victoryFactor = (victories * MAX_CHANGE_PER_VICTORY).coerceIn(0f, WEIGHT_VICTORIES)
        
        // Factor decisiones
        val decisionAccuracy = if (decisions > 0) correct.toFloat() / decisions else 0.5f
        val decisionFactor = (decisionAccuracy - 0.5f) * WEIGHT_DECISIONS * 2f
        
        // Factor consistencia (decisiones recientes similares = más confianza)
        val consistencyFactor = calculateConsistencyFactor() * 0.1f
        
        // Calcular confianza base
        val newConfidence = 0.5f + killFactor + deathFactor + ffFactor + victoryFactor + decisionFactor + consistencyFactor
        
        // Aplicar decay natural hacia 0.5
        val decayedConfidence = applyNaturalDecay(newConfidence)
        
        // Limitar rango
        val clampedConfidence = decayedConfidence.coerceIn(0.05f, 0.95f)
        
        baseConfidence.set(clampedConfidence)
        currentConfidence.set(clampedConfidence)
        
        // Actualizar modo si es necesario
        updateMode(clampedConfidence)
        
        // Notificar
        val factors = ConfidenceFactors(
            killContribution = killFactor,
            deathContribution = deathFactor,
            friendlyFireContribution = ffFactor,
            victoryContribution = victoryFactor,
            decisionContribution = decisionFactor,
            consistencyContribution = consistencyFactor
        )
        onConfidenceUpdate?.invoke(clampedConfidence, factors)
        
        return clampedConfidence
    }

    /**
     * Reporta un kill confirmado de enemigo.
     */
    fun reportEnemyKill(weapon: String? = null, wasHeadshot: Boolean = false) {
        totalKills.incrementAndGet()
        
        val bonus = if (wasHeadshot) 0.02f else 0f
        val newConfidence = (currentConfidence.get() + MAX_CHANGE_PER_KILL + bonus).coerceAtMost(0.95f)
        currentConfidence.set(newConfidence)
        
        killHistory.add(KillRecord(
            timestamp = System.currentTimeMillis(),
            isEnemy = true,
            wasHeadshot = wasHeadshot
        ))
        
        // Limpiar historial antiguo
        cleanupOldRecords()
        
        Logger.d(TAG, "Kill enemigo reportado (headshot: $wasHeadshot). Confianza: $newConfidence")
    }

    /**
     * Reporta daño/muerte a aliado (FRIENDLY FIRE).
     */
    fun reportFriendlyFire(victim: String, damage: Float = 100f) {
        totalFriendlyFire.incrementAndGet()
        
        // PENALIZACIÓN SEVERÍSIMA
        val penalty = MAX_CHANGE_PER_FRIENDLY_FIRE * (damage / 100f)
        val newConfidence = (currentConfidence.get() + penalty).coerceAtLeast(0.05f)
        currentConfidence.set(newConfidence)
        
        Logger.w(TAG, "FRIENDLY FIRE detectado! Victima: $victim, Daño: $damage. " +
                "Confianza reducida a: $newConfidence (${penalty * 100}%)")
        
        // Forzar modo conservador inmediatamente
        if (newConfidence < THRESHOLD_CONSERVATIVE) {
            updateMode(newConfidence)
        }
    }

    /**
     * Reporta muerte propia.
     */
    fun reportDeath(cause: DeathCause = DeathCause.UNKNOWN) {
        totalDeaths.incrementAndGet()
        
        val penaltyMultiplier = when (cause) {
            DeathCause.FRIENDLY_FIRE -> 2.0f // Doble penalización si mató aliado
            DeathCause.ZONE -> 0.8f
            DeathCause.FALL -> 1.0f
            DeathCause.VEHICLE -> 1.0f
            DeathCause.ENEMY -> 1.0f
            DeathCause.UNKNOWN -> 1.0f
        }
        
        val penalty = MAX_CHANGE_PER_DEATH * penaltyMultiplier
        val newConfidence = (currentConfidence.get() + penalty).coerceAtLeast(0.05f)
        currentConfidence.set(newConfidence)
        
        Logger.w(TAG, "Muerte reportada ($cause). Confianza: $newConfidence")
        
        updateMode(newConfidence)
    }

    /**
     * Reporta victoria (Booyah!).
     */
    fun reportVictory(placement: Int = 1) {
        if (placement == 1) {
            totalVictories.incrementAndGet()
            
            val newConfidence = (currentConfidence.get() + MAX_CHANGE_PER_VICTORY).coerceAtMost(0.95f)
            currentConfidence.set(newConfidence)
            
            Logger.i(TAG, "VICTORIA (Booyah!) reportada. Confianza: $newConfidence")
        }
    }

    /**
     * Reporta decisión tomada y su resultado.
     */
    fun reportDecision(decision: ActionType, result: DecisionResult) {
        totalDecisions.incrementAndGet()
        
        if (result == DecisionResult.SUCCESS || result == DecisionResult.GOOD) {
            correctDecisions.incrementAndGet()
        }
        
        decisionHistory.add(DecisionRecord(
            timestamp = System.currentTimeMillis(),
            action = decision,
            result = result
        ))
        
        // Limpiar historial antiguo
        if (decisionHistory.size > 100) {
            decisionHistory.poll()
        }
    }

    /**
     * Ajusta confianza basada en consistencia de modelos.
     */
    fun adjustFromModelConsistency(modelResults: List<EnsembleResult?>) {
        if (modelResults.size < 2) return
        
        // Calcular consistencia (qué tan similares son las decisiones)
        val actions = modelResults.mapNotNull { it?.suggestedAction?.type }
        if (actions.size < 2) return
        
        val mostCommon = actions.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val consistency = (mostCommon?.value ?: 0).toFloat() / actions.size
        
        // Si alta consistencia, boost de confianza
        if (consistency > 0.7f) {
            val boost = (consistency - 0.7f) * 0.1f
            val newConfidence = (currentConfidence.get() + boost).coerceAtMost(0.95f)
            currentConfidence.set(newConfidence)
        }
    }

    /**
     * Obtiene confianza actual.
     */
    fun getCurrentConfidence(): Float = currentConfidence.get()

    /**
     * Obtiene modo actual basado en confianza.
     */
    fun getCurrentMode(): ConfidenceMode = currentMode

    /**
     * Determina si debe comportarse de forma conservadora.
     */
    fun shouldBeConservative(): Boolean = currentConfidence.get() < THRESHOLD_CONSERVATIVE

    /**
     * Determina si puede comportarse de forma agresiva.
     */
    fun canBeAggressive(): Boolean = currentConfidence.get() > THRESHOLD_AGGRESSIVE

    /**
     * Calcula peso de exploración para RL (menor confianza = más exploración).
     */
    fun getExplorationWeight(): Float {
        return (1f - currentConfidence.get()).coerceIn(0.1f, 0.5f)
    }

    /**
     * Obtiene estadísticas completas.
     */
    fun getStats(): ConfidenceStats {
        return ConfidenceStats(
            currentConfidence = currentConfidence.get(),
            baseConfidence = baseConfidence.get(),
            mode = currentMode,
            totalKills = totalKills.get(),
            totalDeaths = totalDeaths.get(),
            totalFriendlyFire = totalFriendlyFire.get(),
            totalVictories = totalVictories.get(),
            decisionAccuracy = if (totalDecisions.get() > 0) 
                correctDecisions.get().toFloat() / totalDecisions.get() else 0.5f,
            recentKillStreak = calculateKillStreak(),
            recentDeathStreak = calculateDeathStreak()
        )
    }

    // ============================================
    // MÉTODOS PRIVADOS
    // ============================================

    private fun calculateConsistencyFactor(): Float {
        if (decisionHistory.size < 5) return 0f
        
        val recent = decisionHistory.toList().takeLast(10)
        val actions = recent.map { it.action }
        
        // Calcular cuántas decisiones iguales seguidas
        var maxStreak = 1
        var currentStreak = 1
        
        for (i in 1 until actions.size) {
            if (actions[i] == actions[i - 1]) {
                currentStreak++
                maxStreak = kotlin.math.max(maxStreak, currentStreak)
            } else {
                currentStreak = 1
            }
        }
        
        return (maxStreak - 1) * 0.02f // Pequeño bonus por consistencia
    }

    private fun applyNaturalDecay(confidence: Float): Float {
        val distanceFromNeutral = confidence - 0.5f
        val decayedDistance = distanceFromNeutral * (1f - NATURAL_DECAY_RATE)
        return 0.5f + decayedDistance
    }

    private fun updateMode(newConfidence: Float) {
        val newMode = when {
            newConfidence < THRESHOLD_CONSERVATIVE -> ConfidenceMode.CONSERVATIVE
            newConfidence > THRESHOLD_AGGRESSIVE -> ConfidenceMode.AGGRESSIVE
            else -> ConfidenceMode.NORMAL
        }
        
        if (newMode != currentMode) {
            val oldMode = currentMode
            currentMode = newMode
            onModeChanged?.invoke(oldMode, newMode)
            
            Logger.i(TAG, "Modo de confianza cambiado: $oldMode -> $newMode (confianza: $newConfidence)")
        }
    }

    private fun calculateKillStreak(): Int {
        val recent = killHistory.toList().takeLast(10)
        var streak = 0
        
        for (kill in recent.reversed()) {
            if (kill.isEnemy) streak++ else break
        }
        
        return streak
    }

    private fun calculateDeathStreak(): Int {
        // Implementar si tenemos historial de muertes
        return 0
    }

    private fun cleanupOldRecords() {
        val cutoff = System.currentTimeMillis() - 300000 // 5 minutos
        killHistory.removeIf { it.timestamp < cutoff }
    }

    // ============================================
    // CALLBACKS
    // ============================================

    fun setOnModeChangedListener(listener: (ConfidenceMode, ConfidenceMode) -> Unit) {
        onModeChanged = listener
    }

    fun setOnConfidenceUpdateListener(listener: (Float, ConfidenceFactors) -> Unit) {
        onConfidenceUpdate = listener
    }

    /**
     * Resetea todo el estado.
     */
    fun reset() {
        currentConfidence.set(0.5f)
        baseConfidence.set(0.5f)
        totalKills.set(0)
        totalDeaths.set(0)
        totalFriendlyFire.set(0)
        totalVictories.set(0)
        totalDecisions.set(0)
        correctDecisions.set(0)
        decisionHistory.clear()
        killHistory.clear()
        currentMode = ConfidenceMode.NORMAL
        
        Logger.i(TAG, "ConfidenceEngine reseteado")
    }
}

// ============================================
// ENUMS Y DATA CLASSES
// ============================================

enum class ConfidenceMode {
    CONSERVATIVE, // < 0.3, defensivo
    NORMAL,       // 0.3 - 0.8, balanceado
    AGGRESSIVE    // > 0.8, riesgos calculados
}

enum class DecisionResult {
    EXCELLENT, GOOD, SUCCESS, NEUTRAL, POOR, FAILURE
}

enum class DeathCause {
    ENEMY, ZONE, FRIENDLY_FIRE, FALL, VEHICLE, UNKNOWN
}

data class ConfidenceFactors(
    val killContribution: Float,
    val deathContribution: Float,
    val friendlyFireContribution: Float,
    val victoryContribution: Float,
    val decisionContribution: Float,
    val consistencyContribution: Float
)

data class ConfidenceStats(
    val currentConfidence: Float,
    val baseConfidence: Float,
    val mode: ConfidenceMode,
    val totalKills: Int,
    val totalDeaths: Int,
    val totalFriendlyFire: Int,
    val totalVictories: Int,
    val decisionAccuracy: Float,
    val recentKillStreak: Int,
    val recentDeathStreak: Int
) {
    fun getKDRatio(): Float = if (totalDeaths > 0) totalKills.toFloat() / totalDeaths else totalKills.toFloat()
    fun getFriendlyFireRate(): Float = if (totalKills + totalFriendlyFire > 0) 
        totalFriendlyFire.toFloat() / (totalKills + totalFriendlyFire) else 0f
}

private data class DecisionRecord(
    val timestamp: Long,
    val action: ActionType,
    val result: DecisionResult
)

private data class KillRecord(
    val timestamp: Long,
    val isEnemy: Boolean,
    val wasHeadshot: Boolean = false
)
