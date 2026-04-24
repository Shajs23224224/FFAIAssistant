package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer

/**
 * TacticalEngine - Nivel 2 de inteligencia: modelo TFLite para decisiones tácticas.
 *
 * Envuelve PolicyModel TFLite y lo adapta al sistema de acciones.
 * Si el modelo no está disponible, usa heurísticas mejoradas como fallback.
 *
 * Latencia objetivo: <30ms
 */
class TacticalEngine(
    private val policyModel: PolicyModel? = null
) {
    // Fallback heurístico si no hay modelo
    private val useFallback: Boolean get() = policyModel == null || !policyModel.isAvailable()

    // Historial de acciones (para evitar repetición excesiva)
    private val actionHistory = ArrayDeque<ActionType>(5)
    private val maxHistorySize = 5

    // Cooldowns internos
    private var lastExploreTime: Long = 0L
    private var lastLootTime: Long = 0L
    private val exploreCooldownMs = 5000L
    private val lootCooldownMs = 3000L

    /**
     * Decide una acción táctica basada en estado del juego.
     *
     * @param state Estado del juego
     * @param features Features visuales rápidos
     * @param perceptionBuffer ByteBuffer para PerceptionModel (si se integra)
     * @return Action táctica, o null si debe ceder a hold
     */
    fun decide(
        state: GameState,
        features: QuickVisualFeatures?,
        perceptionBuffer: ByteBuffer?
    ): Action? {
        return if (useFallback) {
            decideFallback(state, features)
        } else {
            decideWithModel(state, features, perceptionBuffer)
        }
    }

    /**
     * Usa el modelo TFLite para decidir.
     */
    private fun decideWithModel(
        state: GameState,
        features: QuickVisualFeatures?,
        perceptionBuffer: ByteBuffer?
    ): Action? {
        try {
            // Construir feature vector para el modelo
            val featureVector = buildFeatureVector(state, features)

            // Inferencia TFLite
            val output = policyModel?.predict(featureVector) ?: return null

            // Mapear output a Action
            val actionType = mapOutputToAction(output)
            val params = extractActionParams(output)

            // Evitar repetición excesiva
            if (isRepetitive(actionType)) {
                return Action.hold()
            }

            recordAction(actionType)

            return createAction(actionType, params, state)

        } catch (e: Exception) {
            Logger.w("TacticalEngine: Model inference failed, falling back to heuristics", e)
            return decideFallback(state, features)
        }
    }

    /**
     * Fallback heurístico mejorado (cuando no hay modelo).
     */
    private fun decideFallback(state: GameState, features: QuickVisualFeatures?): Action? {
        val now = System.currentTimeMillis()

        // Explorar si no hay enemigo y no hay loot cercano
        if (!state.enemyPresent && !state.lootNearby &&
            now - lastExploreTime > exploreCooldownMs) {
            lastExploreTime = now

            // Rotar para buscar enemigos
            return if (kotlin.random.Random.nextBoolean()) {
                Action(ActionType.ROTATE_LEFT, confidence = 0.4f)
            } else {
                Action(ActionType.ROTATE_RIGHT, confidence = 0.4f)
            }
        }

        // Saquear si hay loot y es seguro
        if (state.lootNearby && !state.enemyPresent &&
            now - lastLootTime > lootCooldownMs) {
            lastLootTime = now
            return Action(ActionType.LOOT, confidence = 0.5f)
        }

        // Agacharse si hay enemigo a distancia media (menos visible)
        if (state.enemyPresent && state.enemyDistance > 0.5f && !state.isCrouching) {
            return Action(ActionType.CROUCH, confidence = 0.6f)
        }

        // Saltar periódicamente (evitar ser objetivo estático)
        if (state.enemyPresent && kotlin.random.Random.nextFloat() < 0.1f) {
            return Action(ActionType.JUMP, confidence = 0.3f)
        }

        // No hay acción táctica que tomar
        return Action.hold()
    }

    /**
     * Construye vector de features para el modelo TFLite.
     * Features: 32 valores normalizados [0,1]
     */
    private fun buildFeatureVector(state: GameState, features: QuickVisualFeatures?): FloatArray {
        val featureVector = FloatArray(32) { 0f }

        // Estado básico (0-7)
        featureVector[0] = state.healthRatio
        featureVector[1] = state.ammoRatio
        featureVector[2] = if (state.enemyPresent) 1f else 0f
        featureVector[3] = state.enemyX  // Ya normalizado -1 a 1
        featureVector[4] = state.enemyY  // Ya normalizado -1 a 1
        featureVector[5] = state.enemyDistance
        featureVector[6] = state.shootCooldown
        featureVector[7] = state.healCooldown

        // Features visuales (8-11)
        if (features != null) {
            featureVector[8] = features.healthRatio
            featureVector[9] = features.ammoRatio
            featureVector[10] = if (features.enemyPresent) 1f else 0f
            featureVector[11] = features.enemyConfidence
        }

        // Estado de zona (12-14)
        featureVector[12] = if (state.isInSafeZone) 1f else 0f
        featureVector[13] = if (state.safeZoneShrinking) 1f else 0f
        featureVector[14] = state.distanceToSafeZone

        // Estado del jugador (15-18)
        featureVector[15] = if (state.isCrouching) 1f else 0f
        featureVector[16] = if (state.isAiming) 1f else 0f
        featureVector[17] = if (state.hasArmor) 1f else 0f
        featureVector[18] = if (state.hasHealItems) 1f else 0f

        // Información de equipo (19-20)
        featureVector[19] = (state.teammatesAlive / 4f).coerceIn(0f, 1f)
        featureVector[20] = if (state.teammateNeedsRevive) 1f else 0f

        // Loot (21-22)
        featureVector[21] = if (state.lootNearby) 1f else 0f
        featureVector[22] = if (state.hasGoodWeapon) 1f else 0f

        // Historial de acciones codificado (23-27)
        // One-hot encoding de últimas 5 acciones (solo las últimas 5 slots)
        val recentActions = actionHistory.takeLast(5)
        for ((i, action) in recentActions.withIndex()) {
            val actionIdx = action.ordinal
            if (23 + actionIdx < 32) {
                featureVector[23 + actionIdx] = 1f
            }
        }

        // Ruido aleatorio suave para evitar determinismo (28-29)
        featureVector[28] = kotlin.random.Random.nextFloat() * 0.1f
        featureVector[29] = kotlin.random.Random.nextFloat() * 0.1f

        // Bias/placeholder (30-31)
        featureVector[30] = 1f  // Bias term
        featureVector[31] = 0f  // Reservado

        return featureVector
    }

    /**
     * Mapea output del modelo a ActionType.
     * Output[0-14]: probabilidades para cada acción.
     */
    private fun mapOutputToAction(output: FloatArray): ActionType {
        if (output.size < 15) return ActionType.HOLD

        // Encontrar acción con mayor probabilidad
        var maxIdx = 0
        var maxProb = output[0]
        for (i in 1 until minOf(15, output.size)) {
            if (output[i] > maxProb) {
                maxProb = output[i]
                maxIdx = i
            }
        }

        return ActionType.fromIndex(maxIdx) ?: ActionType.HOLD
    }

    /**
     * Extrae parámetros de la acción del modelo.
     * Output[15-18]: x, y, duration, confidence
     */
    private fun extractActionParams(output: FloatArray): ActionParams {
        return ActionParams(
            x = if (output.size > 15) (output[15] * 1080).toInt() else 0,
            y = if (output.size > 16) (output[16] * 2400).toInt() else 0,
            duration = if (output.size > 17) (output[17] * 1000).toLong() else 100L,
            confidence = if (output.size > 18) output[18].coerceIn(0f, 1f) else 0.5f
        )
    }

    private fun createAction(type: ActionType, params: ActionParams, state: GameState): Action {
        return when (type) {
            ActionType.AIM -> Action.aim(params.x, params.y)
            ActionType.MOVE_FORWARD, ActionType.MOVE_BACKWARD,
            ActionType.MOVE_LEFT, ActionType.MOVE_RIGHT -> Action(type, confidence = params.confidence, duration = params.duration.toInt())
            ActionType.SHOOT -> Action(type = ActionType.SHOOT, confidence = params.confidence)
            else -> Action(type)
        }
    }

    private fun isRepetitive(actionType: ActionType): Boolean {
        // Si las últimas 3 acciones son iguales, considerar repetitivo
        if (actionHistory.size < 3) return false
        val lastThree = actionHistory.takeLast(3)
        return lastThree.all { it == actionType }
    }

    private fun recordAction(actionType: ActionType) {
        actionHistory.addLast(actionType)
        if (actionHistory.size > maxHistorySize) {
            actionHistory.removeFirst()
        }
    }

    fun reset() {
        actionHistory.clear()
        lastExploreTime = 0L
        lastLootTime = 0L
    }

    data class ActionParams(
        val x: Int,
        val y: Int,
        val duration: Long,
        val confidence: Float
    )
}
