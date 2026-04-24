package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer

/**
 * DecisionEngine - Orquestador del motor de decisión híbrido de 3 niveles.
 *
 * Pipeline de decisión:
 *   1. ReflexEngine (<5ms): Reglas ultra-rápidas. Si tiene acción, ejecuta y retorna.
 *   2. TacticalEngine (<30ms): Modelo TFLite. Si reflejo no decidió, consulta el modelo.
 *   3. Fallback: Si todo falla, HOLD (no hacer nada).
 *
 * El goal es que ReflexEngine maneje 80% de las decisiones en combate intenso,
 * mientras TacticalEngine maneja decisiones estratégicas más complejas.
 */
class DecisionEngine(
    private val reflexEngine: ReflexEngine,
    private val tacticalEngine: TacticalEngine? = null,
    private val aimController: AimController
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

        // === NIVEL 1: REFLEJOS (siempre primero) ===
        val reflexAction = reflexEngine.decide(state, features)
        if (reflexAction != null) {
            reflexCount++
            logStats("REFLEX", reflexAction, System.currentTimeMillis() - startTime)

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

        // === NIVEL 2: TÁCTICO (si reflejo no decidió) ===
        val tacticalAction = tacticalEngine?.decide(state, features, perceptionBuffer)
        if (tacticalAction != null && tacticalAction.type != ActionType.HOLD) {
            tacticalCount++
            logStats("TACTICAL", tacticalAction, System.currentTimeMillis() - startTime)
            return tacticalAction
        }

        // === NIVEL 3: FALLBACK ===
        holdCount++
        return Action.hold()
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
        return DecisionStats(
            reflexCount = reflexCount,
            tacticalCount = tacticalCount,
            holdCount = holdCount,
            reflexRatio = if (total > 0) reflexCount.toFloat() / total else 0f,
            tacticalRatio = if (total > 0) tacticalCount.toFloat() / total else 0f
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
        aimController.stopAiming()
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
        val tacticalRatio: Float
    )
}
