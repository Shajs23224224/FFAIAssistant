package com.ffai.assistant.core

import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.perception.RiskAssessor
import com.ffai.assistant.perception.TacticalWorldModel
import com.ffai.assistant.perception.ThreatLevel
import com.ffai.assistant.perception.TacticalPriority
import com.ffai.assistant.perception.Vector2D
import com.ffai.assistant.perception.EnemyInfo
import com.ffai.assistant.perception.CoverInfo
import com.ffai.assistant.utils.Logger

/**
 * FASE 4: FastTacticalEngine - Motor de decisiones tácticas ultra-rápidas.
 * 
 * Objetivo: < 15ms de latencia en Samsung A21S.
 * 
 * Estrategia:
 * - NO usa modelo TFLite pesado
 * - Árbol de decisiones binario ultra-rápido
 * - Sistema de reglas optimizado con lookup tables
 * - Caché de decisiones recientes
 * 
 * Decisiones implementadas:
 * - Cuándo lootear (solo si seguro)
 * - Cuándo curarse (prioridad vida > shield)
 * - Cuándo rotar (posición comprometida o zona)
 * - Cuándo pelear (solo con ventaja)
 * - Cuándo retirarse (riesgo > 70%)
 * - Qué arma usar (según distancia)
 * - Dónde apuntar (lead tracking)
 * - Cuándo gastar recursos
 */
class FastTacticalEngine(
    private val worldModel: TacticalWorldModel,
    private val riskAssessor: RiskAssessor
) {
    
    companion object {
        const val TAG = "FastTacticalEngine"
        const val MAX_DECISION_LATENCY_MS = 15L
        
        // Umbrales de decisión
        const val CRITICAL_HEALTH = 25f
        const val LOW_HEALTH = 50f
        const val CRITICAL_AMMO = 3
        const val LOW_AMMO = 8
        const val HIGH_RISK_THRESHOLD = 70f
        const val CRITICAL_RISK_THRESHOLD = 85f
    }
    
    // Historial para evitar repetición
    private val actionHistory = ArrayDeque<ActionType>(5)
    private var lastActionTime: Long = 0L
    
    // Cooldowns específicos
    private var lastLootTime: Long = 0L
    private var lastHealTime: Long = 0L
    private var lastReloadTime: Long = 0L
    private var lastJumpTime: Long = 0L
    private var lastCrouchTime: Long = 0L
    
    // Configuración de cooldowns
    private val LOOT_COOLDOWN_MS = 5000L
    private val HEAL_COOLDOWN_MS = 3000L
    private val RELOAD_COOLDOWN_MS = 2000L
    private val JUMP_COOLDOWN_MS = 1000L
    private val CROUCH_COOLDOWN_MS = 800L
    
    /**
     * Decide acción táctica basada en estado actual.
     * Target: < 15ms de ejecución.
     */
    fun decide(): Action? {
        val startTime = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        
        // Evaluar situación actual (rápido, sin allocations)
        val riskScore = quickRiskAssessment()
        val priority = determinePriority(riskScore, now)
        
        // Seleccionar protocolo según prioridad
        val action = when (priority) {
            TacticalPriority.SURVIVE -> emergencyProtocol()
            TacticalPriority.HEAL -> healProtocol(now)
            TacticalPriority.RELOAD -> reloadProtocol(now)
            TacticalPriority.MOVE_TO_ZONE -> zoneProtocol()
            TacticalPriority.RETREAT -> retreatProtocol()
            TacticalPriority.ENGAGE -> engageProtocol()
            TacticalPriority.HUNT -> huntProtocol()
            TacticalPriority.LOOT -> lootProtocol(now)
            TacticalPriority.EXPLORE -> exploreProtocol()
        }
        
        // Verificar que no sea repetitiva
        val finalAction = if (isRepetitive(action.type)) {
            getAlternativeAction(action, priority)
        } else {
            action
        }
        
        // Actualizar historial
        recordAction(finalAction.type)
        lastActionTime = now
        
        // Log si hay lag
        val latency = System.currentTimeMillis() - startTime
        if (latency > MAX_DECISION_LATENCY_MS) {
            Logger.w(TAG, "Decision lenta: ${latency}ms (target: ${MAX_DECISION_LATENCY_MS}ms)")
        }
        
        return if (finalAction.type == ActionType.HOLD) null else finalAction
    }
    
    /**
     * Evaluación rápida de riesgo (sin crear objetos).
     */
    private fun quickRiskAssessment(): Float {
        var score = 0f
        
        // Health
        when {
            worldModel.playerHealth < CRITICAL_HEALTH -> score += 40f
            worldModel.playerHealth < LOW_HEALTH -> score += 25f
            worldModel.playerHealth < 75f -> score += 10f
        }
        
        // Enemigos
        score += worldModel.visibleEnemies.size * 15f
        score += worldModel.activeThreatCount * 20f
        
        // Ammo
        if (worldModel.currentAmmo == 0 && worldModel.totalAmmo == 0) score += 30f
        else if (worldModel.currentAmmo == 0) score += 15f
        else if (worldModel.currentAmmo < CRITICAL_AMMO) score += 10f
        
        // Zone
        if (!worldModel.isInSafeZone) {
            score += 20f
            if (worldModel.isZoneShrinking) score += 15f
        }
        
        // Cover
        if (worldModel.distanceToNearestCover > 0.3f) score += 15f
        if (worldModel.isCoverCompromised) score += 25f
        
        return score.coerceIn(0f, 100f)
    }
    
    /**
     * Determina prioridad táctica actual.
     */
    private fun determinePriority(riskScore: Float, now: Long): TacticalPriority {
        return when {
            // CRÍTICO: Supervivencia inmediata
            riskScore > CRITICAL_RISK_THRESHOLD -> TacticalPriority.SURVIVE
            worldModel.playerHealth < CRITICAL_HEALTH -> TacticalPriority.HEAL
            worldModel.currentAmmo == 0 && worldModel.totalAmmo == 0 -> TacticalPriority.RETREAT
            
            // ALTO: Recursos o zona
            worldModel.playerHealth < LOW_HEALTH && isSafeToHeal(now) -> TacticalPriority.HEAL
            worldModel.currentAmmo < CRITICAL_AMMO && isSafeToReload(now) -> TacticalPriority.RELOAD
            !worldModel.isInSafeZone -> TacticalPriority.MOVE_TO_ZONE
            riskScore > HIGH_RISK_THRESHOLD -> TacticalPriority.RETREAT
            
            // MEDIO: Combate
            worldModel.visibleEnemies.isNotEmpty() -> {
                if (shouldEngage()) TacticalPriority.ENGAGE
                else TacticalPriority.RETREAT
            }
            worldModel.recentEnemies.isNotEmpty() && worldModel.isSafeToLoot -> TacticalPriority.HUNT
            
            // BAJO: Explorar/lootear
            worldModel.inventory.needsLoot() && isSafeToLoot(now) -> TacticalPriority.LOOT
            else -> TacticalPriority.EXPLORE
        }
    }
    
    // ============================================
    // PROTOCOLOS DE DECISIÓN
    // ============================================
    
    /**
     * Protocolo de emergencia (supervivencia crítica).
     */
    private fun emergencyProtocol(): Action {
        Logger.d(TAG, "EMERGENCY PROTOCOL")
        
        // Prioridad 1: Curar si es posible
        if (worldModel.playerHealth < CRITICAL_HEALTH && worldModel.inventory.hasHeals()) {
            if (worldModel.isInCover || worldModel.visibleEnemies.isEmpty()) {
                return Action(ActionType.HEAL, confidence = 1.0f)
            }
        }
        
        // Prioridad 2: Buscar cobertura inmediata
        if (worldModel.distanceToNearestCover > 0.15f && worldModel.nearestCover != null) {
            val coverDir = calculateDirectionTo(worldModel.nearestCover!!.position)
            return moveTowards(coverDir, confidence = 0.9f)
        }
        
        // Prioridad 3: Recargar si no hay balas
        if (worldModel.currentAmmo == 0 && worldModel.totalAmmo > 0) {
            return Action(ActionType.RELOAD, confidence = 0.8f)
        }
        
        // Prioridad 4: Retirada
        val retreatDir = calculateRetreatDirection()
        return moveTowards(retreatDir, confidence = 0.8f)
    }
    
    /**
     * Protocolo de curación.
     */
    private fun healProtocol(now: Long): Action {
        if (now - lastHealTime < HEAL_COOLDOWN_MS) {
            return Action.hold()
        }
        
        // Solo curar si es seguro o vida crítica
        if (worldModel.visibleEnemies.isEmpty() || 
            worldModel.playerHealth < CRITICAL_HEALTH ||
            (worldModel.isInCover && worldModel.playerHealth < 40f)) {
            
            lastHealTime = now
            Logger.d(TAG, "HEAL: HP=${worldModel.playerHealth.toInt()}")
            return Action(ActionType.HEAL, confidence = 0.85f)
        }
        
        // No es seguro curar, buscar cover primero
        return moveToNearestCover(confidence = 0.8f)
    }
    
    /**
     * Protocolo de recarga.
     */
    private fun reloadProtocol(now: Long): Action {
        if (now - lastReloadTime < RELOAD_COOLDOWN_MS) {
            return Action.hold()
        }
        
        // Recargar solo si seguro o necesario
        if (worldModel.visibleEnemies.isEmpty() || worldModel.currentAmmo == 0) {
            lastReloadTime = now
            return Action(ActionType.RELOAD, confidence = 0.8f)
        }
        
        // Enemigos presentes, no recargar ahora
        return Action.hold()
    }
    
    /**
     * Protocolo de zona segura.
     */
    private fun zoneProtocol(): Action {
        val directionToZone = worldModel.directionToZoneCenter
        val urgency = if (worldModel.isZoneShrinking) 0.9f else 0.7f
        
        // Si hay cobertura en el camino a zona, usarla
        val bestCoverTowardsZone = findBestCoverTowards(directionToZone)
        if (bestCoverTowardsZone != null && bestCoverTowardsZone.distance < 0.3f) {
            return moveTowardsCover(bestCoverTowardsZone, confidence = urgency)
        }
        
        return moveTowards(directionToZone, confidence = urgency)
    }
    
    /**
     * Protocolo de retirada.
     */
    private fun retreatProtocol(): Action {
        val retreatDir = calculateRetreatDirection()
        
        // Buscar cobertura en dirección de retirada
        val coverInRetreatDir = findCoverInDirection(retreatDir)
        if (coverInRetreatDir != null) {
            return moveTowardsCover(coverInRetreatDir, confidence = 0.85f)
        }
        
        return moveTowards(retreatDir, confidence = 0.8f)
    }
    
    /**
     * Protocolo de combate.
     */
    private fun engageProtocol(): Action {
        if (worldModel.visibleEnemies.isEmpty()) {
            return Action.hold()
        }
        
        // Seleccionar enemigo más peligroso
        val target = selectBestTarget() ?: return Action.hold()
        
        // Evaluar si atacar o reposicionar
        if (shouldRepositionBeforeEngaging()) {
            return moveToBetterPosition(target)
        }
        
        // Verificar munición
        if (worldModel.currentAmmo == 0) {
            if (worldModel.totalAmmo > 0 && isSafeToReload(System.currentTimeMillis())) {
                return Action(ActionType.RELOAD, confidence = 0.7f)
            }
            return Action.hold() // No atacar sin balas
        }
        
        // Apuntar y disparar
        val aimAction = Action.aim(target.screenX.toInt(), target.screenY.toInt())
        
        // Determinar si disparar o solo apuntar
        return if (shouldShoot(target)) {
            aimAction.copy(type = ActionType.SHOOT, confidence = 0.9f)
        } else {
            aimAction.copy(confidence = 0.7f)
        }
    }
    
    /**
     * Protocolo de caza.
     */
    private fun huntProtocol(): Action {
        // Moverse hacia última posición conocida de enemigo
        val lastEnemy = worldModel.recentEnemies.values.firstOrNull() ?: return Action.hold()
        
        val direction = calculateDirectionTo(lastEnemy.lastKnownPosition)
        return moveTowards(direction, confidence = 0.6f)
    }
    
    /**
     * Protocolo de saqueo.
     */
    private fun lootProtocol(now: Long): Action {
        if (now - lastLootTime < LOOT_COOLDOWN_MS) {
            return Action.hold()
        }
        
        if (!isSafeToLoot(now)) {
            return Action.hold()
        }
        
        lastLootTime = now
        return Action(ActionType.LOOT, confidence = 0.6f)
    }
    
    /**
     * Protocolo de exploración.
     */
    private fun exploreProtocol(): Action {
        // Rotar para buscar enemigos
        if (worldModel.visibleEnemies.isEmpty() && worldModel.recentEnemies.isEmpty()) {
            // Alternar rotación
            return if ((System.currentTimeMillis() / 2000) % 2 == 0L) {
                Action(ActionType.ROTATE_RIGHT, confidence = 0.4f)
            } else {
                Action(ActionType.ROTATE_LEFT, confidence = 0.4f)
            }
        }
        
        // Moverse hacia zona o posición ventajosa
        if (!worldModel.isInSafeZone) {
            return zoneProtocol()
        }
        
        return Action.hold()
    }
    
    // ============================================
    // HELPERS DE DECISIÓN
    // ============================================
    
    private fun shouldEngage(): Boolean {
        // No pelear si:
        // - Vida muy baja
        // - Sin munición
        // - Ratio muy desfavorable
        // - Sin cobertura y enemigos tienen ventaja
        
        if (worldModel.playerHealth < CRITICAL_HEALTH) return false
        if (worldModel.currentAmmo == 0 && worldModel.totalAmmo == 0) return false
        if (worldModel.visibleEnemies.size > 2) return false // Outnumbered
        if (worldModel.isAtDisadvantage && worldModel.playerHealth < 50f) return false
        
        return true
    }
    
    private fun shouldShoot(target: EnemyInfo): Boolean {
        // Disparar si:
        // - Confianza en aim alta
        // - Munición disponible
        // - Distancia apropiada para arma
        
        if (worldModel.currentAmmo <= 0) return false
        if (target.distanceEstimate > worldModel.currentWeapon.optimalRange * 1.5f) return false
        if (target.threatLevel == ThreatLevel.CRITICAL) return true // Siempre disparar si amenaza crítica
        
        return true
    }
    
    private fun shouldRepositionBeforeEngaging(): Boolean {
        // Reposicionar si:
        // - No hay cobertura
        // - Posición comprometida
        // - Ratio desfavorable
        
        return worldModel.distanceToNearestCover > 0.25f ||
               worldModel.isCoverCompromised ||
               (worldModel.visibleEnemies.size > 1 && worldModel.playerHealth < 60f)
    }
    
    private fun isSafeToHeal(now: Long): Boolean {
        return worldModel.visibleEnemies.isEmpty() ||
               (worldModel.isInCover && worldModel.playerHealth < 30f)
    }
    
    private fun isSafeToReload(now: Long): Boolean {
        return worldModel.visibleEnemies.isEmpty() ||
               worldModel.currentAmmo > 3 ||
               worldModel.distanceToNearestCover < 0.1f
    }
    
    private fun isSafeToLoot(now: Long): Boolean {
        return worldModel.visibleEnemies.isEmpty() &&
               worldModel.recentEnemies.isEmpty() &&
               worldModel.immediateThreat < ThreatLevel.MEDIUM &&
               worldModel.isInSafeZone
    }
    
    private fun selectBestTarget(): EnemyInfo? {
        // Priorizar por:
        // 1. Nivel de amenaza
        // 2. Distancia (más cercano)
        // 3. Salud estimada (baja = fácil kill)
        
        return worldModel.visibleEnemies
            .sortedWith(compareBy<EnemyInfo> { it.threatLevel.ordinal }
                .thenBy { it.distanceEstimate })
            .firstOrNull()
    }
    
    private fun calculateRetreatDirection(): Vector2D {
        // Dirección opuesta a enemigos
        if (worldModel.visibleEnemies.isEmpty()) {
            return Vector2D(0f, 0f) // No retirada necesaria
        }
        
        val avgEnemyX = worldModel.visibleEnemies.map { it.position.x }.average().toFloat()
        val avgEnemyY = worldModel.visibleEnemies.map { it.position.y }.average().toFloat()
        
        // Dirección opuesta
        return Vector2D(
            worldModel.playerPosition.x - avgEnemyX,
            worldModel.playerPosition.y - avgEnemyY
        ).normalize()
    }
    
    private fun calculateDirectionTo(position: com.ffai.assistant.perception.Vector3D): Vector2D {
        return Vector2D(
            position.x - worldModel.playerPosition.x,
            position.y - worldModel.playerPosition.y
        ).normalize()
    }
    
    private fun moveTowards(direction: Vector2D, confidence: Float): Action {
        return when {
            kotlin.math.abs(direction.x) > kotlin.math.abs(direction.y) -> {
                if (direction.x > 0) Action(ActionType.MOVE_RIGHT, confidence = confidence)
                else Action(ActionType.MOVE_LEFT, confidence = confidence)
            }
            else -> {
                if (direction.y > 0) Action(ActionType.MOVE_FORWARD, confidence = confidence)
                else Action(ActionType.MOVE_BACKWARD, confidence = confidence)
            }
        }
    }
    
    private fun moveToNearestCover(confidence: Float): Action {
        val cover = worldModel.nearestCover ?: return Action.hold()
        val dir = calculateDirectionTo(cover.position)
        return moveTowards(dir, confidence)
    }
    
    private fun moveTowardsCover(cover: com.ffai.assistant.perception.CoverInfo, confidence: Float): Action {
        val dir = calculateDirectionTo(cover.position)
        
        // Agacharse si cerca de cover
        if (cover.distance < 0.1f) {
            return Action(ActionType.CROUCH, confidence = confidence)
        }
        
        return moveTowards(dir, confidence)
    }
    
    private fun moveToBetterPosition(target: EnemyInfo): Action {
        // Mover perpendicular al enemigo para flanquear
        val enemyDir = calculateDirectionTo(target.position)
        val perpendicular = Vector2D(-enemyDir.y, enemyDir.x) // Rotar 90°
        
        return moveTowards(perpendicular, confidence = 0.75f)
    }
    
    private fun findBestCoverTowards(direction: Vector2D): com.ffai.assistant.perception.CoverInfo? {
        return worldModel.coverPositions
            .filter { it.quality > 0.5f }
            .minByOrNull { it.distance }
    }
    
    private fun findCoverInDirection(direction: Vector2D): com.ffai.assistant.perception.CoverInfo? {
        return worldModel.coverPositions
            .filter { it.quality > 0.3f }
            .minByOrNull { 
                val coverDir = calculateDirectionTo(it.position)
                // Preferir cover en dirección similar
                val alignment = coverDir.x * direction.x + coverDir.y * direction.y
                it.distance - alignment * 0.2f
            }
    }
    
    private fun isRepetitive(actionType: ActionType): Boolean {
        if (actionHistory.size < 3) return false
        return actionHistory.takeLast(3).all { it == actionType }
    }
    
    private fun getAlternativeAction(original: Action, priority: TacticalPriority): Action {
        // Si la acción es repetitiva, probar alternativa
        return when (original.type) {
            ActionType.ROTATE_LEFT -> Action(ActionType.ROTATE_RIGHT, confidence = original.confidence)
            ActionType.ROTATE_RIGHT -> Action(ActionType.ROTATE_LEFT, confidence = original.confidence)
            ActionType.MOVE_FORWARD -> Action(ActionType.JUMP, confidence = 0.5f)
            else -> Action.hold()
        }
    }
    
    private fun recordAction(actionType: ActionType) {
        actionHistory.addLast(actionType)
        if (actionHistory.size > 5) {
            actionHistory.removeFirst()
        }
    }
    
    /**
     * Reset del engine.
     */
    fun reset() {
        actionHistory.clear()
        lastActionTime = 0L
        lastLootTime = 0L
        lastHealTime = 0L
        lastReloadTime = 0L
        lastJumpTime = 0L
        lastCrouchTime = 0L
        Logger.i(TAG, "FastTacticalEngine reseteado")
    }
    
    /**
     * Obtiene estadísticas del engine.
     */
    fun getStats(): TacticalStats {
        return TacticalStats(
            lastDecisionLatencyMs = System.currentTimeMillis() - lastActionTime,
            recentActions = actionHistory.toList(),
            lastPriority = worldModel.suggestedPriority
        )
    }
    
    data class TacticalStats(
        val lastDecisionLatencyMs: Long,
        val recentActions: List<ActionType>,
        val lastPriority: TacticalPriority
    )
}
