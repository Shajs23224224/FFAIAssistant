package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.utils.Logger

/**
 * ReflexEngine - Nivel 1 de inteligencia: reflejos ultra-rápidos (<5ms).
 * 
 * Reglas puras sin modelo, ejecutan ANTES que el motor táctico.
 * Prioridad: supervivencia > combate > zona > loot.
 * 
 * Nunca falla, siempre tiene respuesta inmediata.
 * Si no hay acción de reflejo, retorna null → cede al TacticalEngine.
 */
class ReflexEngine {

    // Cooldowns internos para evitar spam de acciones
    private var lastHealTime: Long = 0L
    private var lastReloadTime: Long = 0L
    private var lastShootTime: Long = 0L

    private val healCooldownMs: Long = 3000L
    private val reloadCooldownMs: Long = 2000L
    private val shootCooldownMs: Long = 200L

    /**
     * Decide una acción de reflejo basada en estado del juego y features visuales.
     * 
     * @param state Estado del juego actual
     * @param features Features visuales rápidos (del Preprocessor)
     * @return Action si hay reflejo que disparar, null si debe ceder al táctico
     */
    fun decide(state: GameState, features: QuickVisualFeatures?): Action? {
        val now = System.currentTimeMillis()

        // === PRIORIDAD 1: SUPERVIVENCIA ===

        // Vida crítica → curar (si hay items y no está en cooldown)
        if (state.healthRatio < 0.25f && state.hasHealItems && 
            now - lastHealTime > healCooldownMs) {
            lastHealTime = now
            Logger.d("ReflexEngine: CRITICAL HEAL (health=${state.healthRatio})")
            return Action(ActionType.HEAL, confidence = 1.0f)
        }

        // Sin munición y enemigo presente → recargar urgente
        if (state.ammoRatio < 0.1f && state.enemyPresent &&
            now - lastReloadTime > reloadCooldownMs) {
            lastReloadTime = now
            Logger.d("ReflexEngine: URGENT RELOAD (ammo=${state.ammoRatio}, enemy present)")
            return Action(ActionType.RELOAD, confidence = 1.0f)
        }

        // === PRIORIDAD 2: COMBATE ===

        // Enemigo detectado + munición + puede atacar → disparar
        if (features != null && features.enemyPresent && features.enemyConfidence > 0.5f &&
            state.ammoRatio > 0.1f && now - lastShootTime > shootCooldownMs) {
            lastShootTime = now
            Logger.d("ReflexEngine: ENGAGE ENEMY at (${features.enemyScreenX}, ${features.enemyScreenY})")
            return Action.aim(features.enemyScreenX, features.enemyScreenY)
                .copy(type = ActionType.SHOOT, confidence = features.enemyConfidence)
        }

        // Enemigo presente (sin features visuales) → aim hacia él
        if (state.enemyPresent && state.ammoRatio > 0.1f && 
            now - lastShootTime > shootCooldownMs) {
            lastShootTime = now
            // Usar coordenadas normalizadas del estado
            val screenX = (state.enemyX + 1f) / 2f * 1080f  // Aproximar a pantalla
            val screenY = (state.enemyY + 1f) / 2f * 2400f
            return Action.aim(screenX.toInt(), screenY.toInt())
        }

        // === PRIORIDAD 3: ZONA SEGURA ===

        // Fuera de zona segura y zona encogiéndose → mover
        if (!state.isInSafeZone && state.safeZoneShrinking && 
            state.distanceToSafeZone > 0.3f) {
            Logger.d("ReflexEngine: MOVE TO SAFE ZONE")
            return Action(ActionType.MOVE_FORWARD, confidence = 0.8f)
        }

        // === PRIORIDAD 4: LOOT/REVIVE ===

        // Compañero necesita revivir y no hay enemigo
        if (state.teammateNeedsRevive && !state.enemyPresent) {
            return Action(ActionType.REVIVE, confidence = 0.7f)
        }

        // Vida baja pero no crítica → curar si es seguro
        if (state.healthRatio < 0.5f && state.hasHealItems && !state.enemyPresent &&
            now - lastHealTime > healCooldownMs) {
            lastHealTime = now
            return Action(ActionType.HEAL, confidence = 0.6f)
        }

        // Munición baja pero no crítica → recargar si es seguro
        if (state.ammoRatio < 0.3f && !state.enemyPresent &&
            now - lastReloadTime > reloadCooldownMs) {
            lastReloadTime = now
            return Action(ActionType.RELOAD, confidence = 0.6f)
        }

        // Sin reflejo → ceder al motor táctico
        return null
    }

    /**
     * Resetea cooldowns (al inicio de partida).
     */
    fun reset() {
        lastHealTime = 0L
        lastReloadTime = 0L
        lastShootTime = 0L
    }
}
