package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.perception.TacticalWorldModel
import com.ffai.assistant.memory.TacticalMemory
import com.ffai.assistant.memory.Outcome
import com.ffai.assistant.memory.SituationMatch
import com.ffai.assistant.telemetry.PerformanceMonitor
import com.ffai.assistant.telemetry.PerformanceMonitor.PipelineStage
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
    private var strategicCount: Long = 0
    private var memoryCount: Long = 0
    private var currentStrategicDecision: StrategicDecision? = null
    private var currentSituationMatch: SituationMatch? = null
    private var lastIssuedAction: ActionType = ActionType.HOLD
    private var lastObservedHealth: Float = 1.0f
    private var lastObservedEnemyCount: Int = 0

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
        observeTransition(state)
        currentSituationMatch = recognizeSituation()

        // === NIVEL 1: REFLEJOS (siempre primero) ===
        val reflexAction = reflexEngine.decide(state, features)
        if (reflexAction != null) {
            reflexCount++
            val latency = System.currentTimeMillis() - startTime
            logStats("REFLEX", reflexAction, latency)
            performanceMonitor?.recordStageTime(PipelineStage.RL_DECISION, latency)

            // Si es aim con coordenadas, usar AimController con PID
            if (reflexAction.type == ActionType.AIM && features != null && features.enemyPresent) {
                aimController.updateAim(
                    features.enemyScreenX.toFloat(),
                    features.enemyScreenY.toFloat(),
                    features.enemyConfidence
                )
            }

            lastIssuedAction = reflexAction.type
            return reflexAction
        }

        // === NIVEL 1.5: OVERRIDE ESTRATÉGICO / MEMORIA ===
        val strategicAction = strategicOverrideAction(state, features)
        if (strategicAction != null && strategicAction.type != ActionType.HOLD) {
            strategicCount++
            val latency = System.currentTimeMillis() - startTime
            logStats("STRATEGIC", strategicAction, latency)
            performanceMonitor?.recordStageTime(PipelineStage.RL_DECISION, latency)
            lastIssuedAction = strategicAction.type
            return strategicAction
        }

        val memoryAction = memorySuggestedAction(state)
        if (memoryAction != null && memoryAction.type != ActionType.HOLD) {
            memoryCount++
            val latency = System.currentTimeMillis() - startTime
            logStats("MEMORY", memoryAction, latency)
            performanceMonitor?.recordStageTime(PipelineStage.RL_DECISION, latency)
            lastIssuedAction = memoryAction.type
            return memoryAction
        }

        val pushDefenseAction = predictiveDefenseAction(state)
        if (pushDefenseAction != null && pushDefenseAction.type != ActionType.HOLD) {
            strategicCount++
            val latency = System.currentTimeMillis() - startTime
            logStats("PREDICTIVE_DEFENSE", pushDefenseAction, latency)
            performanceMonitor?.recordStageTime(PipelineStage.RL_DECISION, latency)
            lastIssuedAction = pushDefenseAction.type
            return pushDefenseAction
        }

        // === NIVEL 2: FAST TÁCTICO (nuevo engine ultra-rápido) ===
        if (fastTacticalEngine != null) {
            val tacticalStart = System.currentTimeMillis()
            val fastAction = fastTacticalEngine.decide()
            
            if (fastAction != null && fastAction.type != ActionType.HOLD) {
                tacticalCount++
                val tacticalLatency = System.currentTimeMillis() - tacticalStart
                logStats("FAST_TACTICAL", fastAction, tacticalLatency)
                performanceMonitor?.recordStageTime(PipelineStage.RL_DECISION, tacticalLatency)

                // Si es acción de aim, actualizar controlador
                if (fastAction.type == ActionType.AIM && features != null && features.enemyPresent) {
                    aimController.updateAim(
                        features.enemyScreenX.toFloat(),
                        features.enemyScreenY.toFloat(),
                        features.enemyConfidence
                    )
                }

                lastIssuedAction = fastAction.type
                return fastAction
            }
        }

        // === NIVEL 3: LEGACY TÁCTICO (fallback) ===
        if (tacticalEngine != null) {
            val legacyAction = tacticalEngine.decide(state, features, perceptionBuffer)
            if (legacyAction != null && legacyAction.type != ActionType.HOLD) {
                tacticalCount++
                logStats("LEGACY_TACTICAL", legacyAction, System.currentTimeMillis() - startTime)
                lastIssuedAction = legacyAction.type
                return legacyAction
            }
        }

        // === NIVEL 4: FALLBACK ===
        holdCount++
        lastIssuedAction = ActionType.HOLD
        return Action.hold()
    }
    
    /**
     * Actualiza estrategia de alto nivel (llamar cada 1-2 segundos, NO cada frame).
     */
    fun updateStrategy() {
        currentStrategicDecision = strategicEngine?.updateStrategy()
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
        val total = reflexCount + strategicCount + memoryCount + tacticalCount + holdCount
        val hasV2 = fastTacticalEngine != null || worldModel != null || strategicEngine != null
        return DecisionStats(
            reflexCount = reflexCount,
            strategicCount = strategicCount,
            memoryCount = memoryCount,
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
        strategicCount = 0
        memoryCount = 0
        tacticalCount = 0
        holdCount = 0
        currentStrategicDecision = null
        currentSituationMatch = null
        lastIssuedAction = ActionType.HOLD
        lastObservedHealth = 1.0f
        lastObservedEnemyCount = 0
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
    fun getPerformanceStats(): com.ffai.assistant.telemetry.PerformanceMetrics? {
        return performanceMonitor?.getStats()
    }
    
    /**
     * Obtiene summary del world model si está disponible.
     */
    fun getWorldModelSummary(): String? {
        val worldSummary = worldModel?.getSummary() ?: return null
        val strategicSummary = currentStrategicDecision?.description ?: "No strategic intent"
        val memorySummary = currentSituationMatch?.let {
            "Memory:${it.recommendedAction}@${(it.similarity * 100).toInt()}%"
        } ?: "Memory:none"
        return "$worldSummary | $strategicSummary | $memorySummary"
    }

    private fun logStats(source: String, action: Action, latencyMs: Long) {
        if (reflexCount % 100 == 0L || tacticalCount % 100 == 0L || strategicCount % 50 == 0L) {
            Logger.d("DecisionEngine[$source]: ${action.type} (latency=${latencyMs}ms, " +
                    "reflex=$reflexCount, strategic=$strategicCount, memory=$memoryCount, tactical=$tacticalCount, hold=$holdCount)")
        }
    }

    private fun strategicOverrideAction(state: GameState, features: QuickVisualFeatures?): Action? {
        val decision = currentStrategicDecision ?: return null
        val risk = worldModel?.riskScore ?: 0f

        if (state.enemyPresent && risk < 55f) return null

        return when (decision.type) {
            StrategicDecisionType.MOVE_TO_SAFETY,
            StrategicDecisionType.RETREAT,
            StrategicDecisionType.IMPROVE_POSITION,
            StrategicDecisionType.ROTATE_POSITION -> decision.targetPosition?.let { target ->
                moveActionToward(target.x, target.y, confidence = strategicConfidence(decision))
            }

            StrategicDecisionType.HUNT_ENEMIES -> {
                if (state.enemyPresent && features?.enemyPresent == true) {
                    Action.aim(features.enemyScreenX, features.enemyScreenY)
                } else {
                    decision.targetPosition?.let { target ->
                        moveActionToward(target.x, target.y, confidence = strategicConfidence(decision))
                    }
                }
            }

            StrategicDecisionType.LOOT_AREA -> {
                if (state.lootNearby && !state.enemyPresent) {
                    Action.loot()
                } else {
                    decision.targetDirection?.let { dir ->
                        moveActionToward(dir.x, dir.y, confidence = strategicConfidence(decision))
                    }
                }
            }

            StrategicDecisionType.HOLD_POSITION -> {
                if (state.enemyPresent && !state.isCrouching) Action.crouch() else Action.hold()
            }
        }
    }

    private fun memorySuggestedAction(state: GameState): Action? {
        val match = currentSituationMatch ?: return null
        val wm = worldModel ?: return null
        val successRate = tacticalMemory?.getActionSuccessRate(match.recommendedAction, wm) ?: 0.5f
        if (successRate < 0.65f || state.enemyPresent) return null
        return actionFromName(match.recommendedAction)
    }

    private fun predictiveDefenseAction(state: GameState): Action? {
        val wm = worldModel ?: return null
        val pushPrediction = tacticalMemory?.predictEnemyPush(wm) ?: return null
        if (state.enemyPresent || pushPrediction.likelihood < 0.62f) return null

        return when {
            state.healthRatio < 0.45f && state.hasHealItems -> Action.heal()
            wm.distanceToNearestCover < 0.12f && !state.isCrouching -> Action.crouch()
            pushPrediction.likelyDirection.x > 0.2f -> Action.moveLeft(180)
            pushPrediction.likelyDirection.x < -0.2f -> Action.moveRight(180)
            else -> Action.moveBackward(180)
        }
    }

    private fun observeTransition(state: GameState) {
        val wm = worldModel ?: run {
            lastObservedHealth = state.healthRatio
            lastObservedEnemyCount = state.enemyCount
            return
        }

        if (lastIssuedAction != ActionType.HOLD) {
            val healthDelta = state.healthRatio - lastObservedHealth
            val enemyDelta = lastObservedEnemyCount - state.enemyCount
            val outcome = when {
                state.isDead || healthDelta < -0.12f -> Outcome.FAILURE
                healthDelta >= 0.05f || enemyDelta > 0 || (wm.riskScore < 35f && !state.enemyPresent) -> Outcome.SUCCESS
                else -> Outcome.PARTIAL
            }
            tacticalMemory?.learnSituation(wm, lastIssuedAction.name, outcome)
        }

        lastObservedHealth = state.healthRatio
        lastObservedEnemyCount = state.enemyCount
    }

    private fun actionFromName(name: String): Action? {
        return when (ActionType.fromName(name)) {
            ActionType.HEAL -> Action.heal()
            ActionType.RELOAD -> Action.reload()
            ActionType.CROUCH -> Action.crouch()
            ActionType.JUMP -> Action.jump()
            ActionType.LOOT -> Action.loot()
            ActionType.ROTATE_LEFT -> Action.rotateLeft()
            ActionType.ROTATE_RIGHT -> Action.rotateRight()
            ActionType.MOVE_FORWARD -> Action.moveForward(160)
            ActionType.MOVE_BACKWARD -> Action.moveBackward(160)
            ActionType.MOVE_LEFT -> Action.moveLeft(160)
            ActionType.MOVE_RIGHT -> Action.moveRight(160)
            else -> null
        }
    }

    private fun moveActionToward(x: Float, y: Float, confidence: Float): Action {
        return when {
            kotlin.math.abs(x) > kotlin.math.abs(y) -> {
                if (x >= 0f) Action(ActionType.MOVE_RIGHT, confidence = confidence, duration = 180)
                else Action(ActionType.MOVE_LEFT, confidence = confidence, duration = 180)
            }

            else -> {
                if (y >= 0f) Action(ActionType.MOVE_FORWARD, confidence = confidence, duration = 180)
                else Action(ActionType.MOVE_BACKWARD, confidence = confidence, duration = 180)
            }
        }
    }

    private fun strategicConfidence(decision: StrategicDecision): Float {
        return when (decision.priority) {
            StrategicPriority.CRITICAL -> 0.95f
            StrategicPriority.HIGH -> 0.82f
            StrategicPriority.MEDIUM -> 0.68f
            StrategicPriority.LOW -> 0.55f
        }
    }

    data class DecisionStats(
        val reflexCount: Long,
        val strategicCount: Long,
        val memoryCount: Long,
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
