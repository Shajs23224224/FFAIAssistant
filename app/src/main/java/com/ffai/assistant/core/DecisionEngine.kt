package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.perception.TacticalWorldModel
import com.ffai.assistant.memory.TacticalMemory
import com.ffai.assistant.memory.SituationMatch
import com.ffai.assistant.telemetry.PerformanceMonitor
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer

/**
 * DecisionEngine V2 - Orquestador mejorado con IA 100% Local.
 *
 * Pipeline de decisión (optimizado para Samsung A21S):
 *   1. ReflexEngine (<3ms): Reglas ultra-rápidas. Supervivencia primero.
 *   2. FastTacticalEngine (<15ms): Árbol de decisiones binario. NO usa modelo pesado.
 *   3. StrategicEngine (cada 2s): Decisiones de alto nivel (rutas, fases).
 *   4. TacticalMemory: Aprendizaje de situaciones y adaptación.
 *   5. Fallback: HOLD (no hacer nada dañino).
 *
 * Nuevos features:
 * - TacticalWorldModel: Estado completo del juego
 * - RiskAssessor: Evaluación de riesgo en tiempo real
 * - PerformanceMonitor: Adaptación dinámica de calidad
 * - Decisiones inteligentes: loot, heal, fight, retreat según contexto
 */
class DecisionEngine(
    private val reflexEngine: ReflexEngine,
    private val tacticalEngine: TacticalEngine? = null, // Legacy (opcional)
    private val aimController: AimController,
    // NUEVOS COMPONENTES V2:
    private val fastTacticalEngine: FastTacticalEngine? = null,
    private val strategicEngine: StrategicEngine? = null,
    private val worldModel: TacticalWorldModel? = null,
    private val tacticalMemory: TacticalMemory? = null,
    private val performanceMonitor: PerformanceMonitor? = null
) {
    // Métricas
    private var reflexCount: Long = 0
    private var tacticalCount: Long = 0
    private var holdCount: Long = 0

    /**
     * Decide la siguiente acción a ejecutar.
     *
     * @param state Estado del juego actual
     * @param features Features visuales rápidos del Preprocessor
     * @param perceptionBuffer ByteBuffer del frame preprocesado (para PerceptionModel)
     * @return Action a ejecutar (nunca null)
     */
    fun decide(
        state: GameState,
        features: QuickVisualFeatures?,
        perceptionBuffer: ByteBuffer?
    ): Action {
        val startTime = System.currentTimeMillis()

        // === ACTUALIZAR WORLD MODEL (si está disponible) ===
        worldModel?.updateFromGameState(state)

        // === NIVEL 1: REFLEJOS (siempre primero) ===
        val reflexAction = reflexEngine.decide(state, features)
        if (reflexAction != null) {
            reflexCount++
            val latency = System.currentTimeMillis() - startTime
            logStats("REFLEX", reflexAction, latency)
            performanceMonitor?.recordStageTime(com.ffai.assistant.telemetry.PerformanceMonitor.PipelineStage.RL_DECISION, latency)

            // Si es aim con coordenadas, usar AimController con PID
            if (reflexAction.type == ActionType.AIM && features != null && features.enemyPresent) {
                aimController.updateAim(
                    features.enemyScreenX.toFloat(),
                    features.enemyScreenY.toFloat(),
                    features.enemyConfidence
                )
            }

            return reflexAction
        }

        // === NIVEL 2: FAST TÁCTICO (nuevo engine ultra-rápido) ===
        if (fastTacticalEngine != null) {
            val tacticalStart = System.currentTimeMillis()
            val fastAction = fastTacticalEngine.decide()
            
            if (fastAction != null && fastAction.type != ActionType.HOLD) {
                tacticalCount++
                val tacticalLatency = System.currentTimeMillis() - tacticalStart
                logStats("FAST_TACTICAL", fastAction, tacticalLatency)
                performanceMonitor?.recordStageTime(com.ffai.assistant.telemetry.PerformanceMonitor.PipelineStage.RL_DECISION, tacticalLatency)

                // Si es acción de aim, actualizar controlador
                if (fastAction.type == ActionType.AIM && features != null && features.enemyPresent) {
                    aimController.updateAim(
                        features.enemyScreenX.toFloat(),
                        features.enemyScreenY.toFloat(),
                        features.enemyConfidence
                    )
                }

                return fastAction
            }
        }

        // === NIVEL 3: LEGACY TÁCTICO (fallback) ===
        if (tacticalEngine != null) {
            val legacyAction = tacticalEngine.decide(state, features, perceptionBuffer)
            if (legacyAction != null && legacyAction.type != ActionType.HOLD) {
                tacticalCount++
                logStats("LEGACY_TACTICAL", legacyAction, System.currentTimeMillis() - startTime)
                return legacyAction
            }
        }

        // === NIVEL 4: FALLBACK ===
        holdCount++
        return Action.hold()
    }
    
    /**
     * Actualiza estrategia de alto nivel (llamar cada 1-2 segundos, NO cada frame).
     */
    fun updateStrategy() {
        strategicEngine?.updateStrategy()
    }
    
    /**
     * Reconoce situación actual usando memoria táctica.
     */
    fun recognizeSituation(): SituationMatch? {
        return worldModel?.let { tacticalMemory?.recognizeSituation(it) }
    }

    /**
     * Decide acción de combate urgente (para llamadas síncronas desde GameLoop).
     * Solo consulta ReflexEngine, ignorando táctico.
     */
    fun decideUrgent(state: GameState, features: QuickVisualFeatures?): Action {
        return reflexEngine.decide(state, features) ?: Action.hold()
    }

    /**
     * Fuerza una acción específica (para overrides manuales o comandos del usuario).
     */
    fun forceAction(action: Action): Action {
        Logger.d("DecisionEngine: FORCE action=${action.type}")
        return action
    }

    /**
     * Obtiene estadísticas del motor de decisión.
     */
    fun getStats(): DecisionStats {
        val total = reflexCount + tacticalCount + holdCount
        val hasV2 = fastTacticalEngine != null || worldModel != null || strategicEngine != null
        return DecisionStats(
            reflexCount = reflexCount,
            tacticalCount = tacticalCount,
            holdCount = holdCount,
            reflexRatio = if (total > 0) reflexCount.toFloat() / total else 0f,
            tacticalRatio = if (total > 0) tacticalCount.toFloat() / total else 0f,
            hasV2Components = hasV2
        )
    }

    /**
     * Resetea estadísticas y engines.
     */
    fun reset() {
        reflexCount = 0
        tacticalCount = 0
        holdCount = 0
        reflexEngine.reset()
        tacticalEngine?.reset()
        fastTacticalEngine?.reset()
        strategicEngine?.reset()
        // TODO: Agregar métodos reset a estos componentes
        // worldModel?.reset()
        // tacticalMemory?.reset()
        // performanceMonitor?.reset()
        aimController.stopAiming()
        Logger.i("DecisionEngine V2: Reset completo")
    }
    
    /**
     * Obtiene performance stats si está disponible.
     */
    fun getPerformanceStats(): PerformanceStats? {
        return performanceMonitor?.getStats()
    }
    
    /**
     * Obtiene summary del world model si está disponible.
     */
    fun getWorldModelSummary(): String? {
        // TODO: Implementar getSummary en TacticalWorldModel
        return "WorldModel summary not available"
        // return worldModel?.getSummary()
    }

    private fun logStats(source: String, action: Action, latencyMs: Long) {
        if (reflexCount % 100 == 0L || tacticalCount % 100 == 0L) {
            Logger.d("DecisionEngine[$source]: ${action.type} (latency=${latencyMs}ms, " +
                    "reflex=$reflexCount, tactical=$tacticalCount, hold=$holdCount)")
        }
    }

    data class DecisionStats(
        val reflexCount: Long,
        val tacticalCount: Long,
        val holdCount: Long,
        val reflexRatio: Float,
        val tacticalRatio: Float,
        val hasV2Components: Boolean = false
    ) {
        fun getV2Status(): String {
            return if (hasV2Components) "V2: ACTIVO (FastTactical + WorldModel + Strategic)" else "V2: No disponible"
        }
    }
}
