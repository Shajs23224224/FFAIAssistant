package com.ffai.assistant.rl

import com.ffai.assistant.action.ActionType
import com.ffai.assistant.model.EnsembleResult
import com.ffai.assistant.model.KillEvent
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FASE 5: RewardShaper - Sistema avanzado de recompensas para RL.
 *
 * Características:
 * - Recompensas diferenciadas claramente (enemigo vs aliado)
 * - Shaping de recompensas temporales
 * - Penalizaciones por daño a aliados (castigo severo)
 * - Recompensas por curación, loot inteligente, posicionamiento
 * - Análisis de killfeed para confirmar kills propios vs aliados
 * - Sistema de recompensas diferidas para secuencias de acciones
 */
class RewardShaper {

    companion object {
        const val TAG = "RewardShaper"
        
        // ===== RECOMPENSAS PRINCIPALES =====
        const val REWARD_KILL_ENEMY = 100f
        const val REWARD_KILL_ALLY_PENALTY = -200f
        const val REWARD_DAMAGE_ENEMY_PER_HP = 10f
        const val REWARD_DAMAGE_ALLY_PENALTY_PER_HP = -50f
        const val REWARD_DEATH = -100f
        const val REWARD_VICTORY_BOOYAH = 500f
        const val REWARD_SURVIVAL_TIME_PER_SEC = 1f
        
        // ===== RECOMPENSAS SECUNDARIAS =====
        const val REWARD_LOOT_WEAPON_UPGRADE = 20f
        const val REWARD_LOOT_ARMOR_UPGRADE = 15f
        const val REWARD_HEAL_PER_10HP = 5f
        const val REWARD_BOOST_USED = 8f
        const val REWARD_RELOAD_TIMELY = 3f
        const val REWARD_HEADSHOT_BONUS = 25f
        
        // ===== PENALIZACIONES =====
        const val PENALTY_AMMO_WASTE = -2f
        const val PENALTY_UNNECESSARY_RELOAD = -3f
        const val PENALTY_EXPOSURE_HIGH_RISK = -5f
        const val PENALTY_OUT_OF_ZONE = -10f
        const val PENALTY_CIRCLE_DAMAGE = -15f
        const val PENALTY_FRIENDLY_FIRE = -100f // Extra castigo
        
        // ===== RECOMPENSAS ESTRATÉGICAS =====
        const val REWARD_GOOD_POSITIONING = 2f
        const val REWARD_COVER_USAGE = 5f
        const val REWARD_HIGH_GROUND = 8f
        const val REWARD_ZONE_ADVANCE = 10f
        const val REWARD_ROTATION_GOOD = 15f
        
        // Ventanas temporales
        const val DAMAGE_WINDOW_MS = 2000L
        const val KILL_CONFIRMATION_WINDOW_MS = 5000L
        const val SEQUENCE_REWARD_WINDOW_MS = 10000L
    }

    // Estado de seguimiento
    private val lastHP = AtomicInteger(100)
    private val lastAmmo = AtomicInteger(30)
    private val lastWeapon = AtomicReference<String>("")
    private val lastArmor = AtomicReference<String>("")
    private val killHistory = ConcurrentLinkedQueue<KillRecord>()
    private val damageDealtHistory = ConcurrentLinkedQueue<DamageRecord>()
    private val actionSequence = ArrayDeque<ActionRecord>(50)
    
    // Métricas acumuladas
    private val totalKills = AtomicInteger(0)
    private val totalAllyKills = AtomicInteger(0)
    private val totalDeaths = AtomicInteger(0)
    private val totalFriendlyFire = AtomicInteger(0)
    private val cumulativeReward = AtomicReference(0f)

    /**
     * Calcula recompensa completa basada en transición de estado.
     */
    fun calculateReward(
        previousState: EnsembleResult?,
        currentState: EnsembleResult,
        actionTaken: com.ffai.assistant.action.ActionType,
        timestamp: Long
    ): Float {
        var reward = 0f
        
        // 1. Recompensas de combate
        reward += calculateCombatRewards(previousState, currentState, timestamp)
        
        // 2. Recompensas de supervivencia/recursos
        reward += calculateSurvivalRewards(previousState, currentState)
        
        // 3. Recompensas estratégicas/posicionamiento
        reward += calculateStrategicRewards(previousState, currentState)
        
        // 4. Penalizaciones por comportamiento malo
        reward += calculatePenalties(previousState, currentState, actionTaken)
        
        // 5. Shaping temporal (recompensas diferidas)
        reward += calculateTemporalShaping(actionTaken, timestamp)
        
        // 6. Bonus por secuencias exitosas
        reward += calculateSequenceBonus(timestamp)
        
        // Actualizar métricas
        cumulativeReward.updateAndGet { it + reward }
        
        // Guardar acción en secuencia
        actionSequence.addLast(ActionRecord(actionTaken, reward, timestamp))
        if (actionSequence.size > 50) actionSequence.removeFirst()
        
        return reward
    }

    /**
     * Recompensas de combate (kills, daño).
     */
    private fun calculateCombatRewards(
        previous: EnsembleResult?,
        current: EnsembleResult,
        timestamp: Long
    ): Float {
        var reward = 0f
        
        // Analizar killfeed
        val newKills = current.uiOutput?.killFeed?.filter { kill ->
            kill.timestamp > (previous?.timestamp ?: 0)
        } ?: emptyList()
        
        newKills.forEach { kill ->
            val isOwnKill = kill.isOwnKill
            val victimIsEnemy = !isTeammate(kill.victim, current)
            
            if (isOwnKill) {
                if (victimIsEnemy) {
                    reward += REWARD_KILL_ENEMY
                    totalKills.incrementAndGet()
                    Logger.d(TAG, "Kill enemigo confirmado! +$REWARD_KILL_ENEMY")
                    
                    // Bonus headshot (detectar por arma/daño)
                    if (kill.weapon?.contains("SNIPER") == true || kill.weapon?.contains("HEAD") == true) {
                        reward += REWARD_HEADSHOT_BONUS
                    }
                } else {
                    // KILL DE ALIADO - CASTIGO SEVERO
                    reward += REWARD_KILL_ALLY_PENALTY
                    totalAllyKills.incrementAndGet()
                    totalFriendlyFire.incrementAndGet()
                    Logger.w(TAG, "FRIENDLY FIRE! Kill aliado: ${kill.victim} $REWARD_KILL_ALLY_PENALTY")
                }
                
                // Registrar kill
                killHistory.add(KillRecord(kill.victim, isOwnKill, victimIsEnemy, timestamp))
            }
        }
        
        // Daño a enemigos (estimado por cambios en confianza/detection)
        val previousEnemies = previous?.mergedEnemies ?: emptyList()
        val currentEnemies = current.mergedEnemies
        
        currentEnemies.forEach { currentEnemy ->
            val previousEnemy = previousEnemies.find { it.id == currentEnemy.id }
            if (previousEnemy != null) {
                // Si enemigo ya no está o confianza bajó, posible daño
                if (!current.mergedEnemies.any { it.id == currentEnemy.id && it.confidence > 0.3f }) {
                    val estimatedDamage = 20f // Estimación conservadora
                    val isConfirmedEnemy = !isTeammateByPosition(currentEnemy.x, currentEnemy.y, current)
                    
                    if (isConfirmedEnemy) {
                        reward += estimatedDamage * REWARD_DAMAGE_ENEMY_PER_HP / 10f
                        damageDealtHistory.add(DamageRecord(true, estimatedDamage, timestamp))
                    } else {
                        reward += estimatedDamage * REWARD_DAMAGE_ALLY_PENALTY_PER_HP / 10f
                        Logger.w(TAG, "Daño posible a aliado! $REWARD_DAMAGE_ALLY_PENALTY_PER_HP")
                    }
                }
            }
        }
        
        // Limpiar historial viejo
        cleanupOldRecords(timestamp)
        
        return reward
    }

    /**
     * Recompensas de supervivencia (HP, ammo, loot).
     */
    private fun calculateSurvivalRewards(
        previous: EnsembleResult?,
        current: EnsembleResult
    ): Float {
        var reward = 0f
        
        // HP mejorado
        val currentHP = current.uiOutput?.hpInfo?.current ?: 100
        val previousHP = previous?.uiOutput?.hpInfo?.current ?: 100
        
        if (currentHP > previousHP) {
            val healed = currentHP - previousHP
            reward += (healed / 10f) * REWARD_HEAL_PER_10HP
            Logger.d(TAG, "Curación: $healed HP +$reward")
        }
        
        // Detección de muerte propia
        if (currentHP == 0 && previousHP > 0) {
            reward += REWARD_DEATH
            totalDeaths.incrementAndGet()
            Logger.w(TAG, "Muerte detectada $REWARD_DEATH")
        }
        
        // Victoria (Booyah!)
        if (current.uiOutput?.gameState?.state?.name == "VICTORY" &&
            previous?.uiOutput?.gameState?.state?.name != "VICTORY") {
            reward += REWARD_VICTORY_BOOYAH
            Logger.i(TAG, "VICTORIA! +$REWARD_VICTORY_BOOYAH")
        }
        
        // Loot upgrades
        val currentWeapon = current.uiOutput?.weaponInfo?.currentWeapon ?: ""
        val previousWeapon = previous?.uiOutput?.weaponInfo?.currentWeapon ?: ""
        
        if (currentWeapon != previousWeapon && isWeaponUpgrade(previousWeapon, currentWeapon)) {
            reward += REWARD_LOOT_WEAPON_UPGRADE
            Logger.d(TAG, "Arma mejorada: $previousWeapon -> $currentWeapon +$REWARD_LOOT_WEAPON_UPGRADE")
        }
        
        // Armor upgrade
        // Reload oportuno (si ammo bajo y no en combate)
        val currentAmmo = current.uiOutput?.ammoInfo?.currentMag ?: 30
        val previousAmmo = previous?.uiOutput?.ammoInfo?.currentMag ?: 30
        
        if (currentAmmo > previousAmmo && current.mergedEnemies.isEmpty()) {
            reward += REWARD_RELOAD_TIMELY
        }
        
        lastHP.set(currentHP)
        lastAmmo.set(currentAmmo)
        lastWeapon.set(currentWeapon)
        
        return reward
    }

    /**
     * Recompensas estratégicas (posicionamiento, zona).
     */
    private fun calculateStrategicRewards(
        previous: EnsembleResult?,
        current: EnsembleResult
    ): Float {
        var reward = 0f
        
        val situation = current.tacticalOutput?.situation
        val previousSituation = previous?.tacticalOutput?.situation
        
        // Buena posición (cobertura, high ground)
        if (situation?.hasHighGround == true && previousSituation?.hasHighGround != true) {
            reward += REWARD_HIGH_GROUND
        }
        
        // En zona segura
        if (situation?.inZone == true) {
            reward += REWARD_ZONE_ADVANCE
            
            // Bonus por tiempo en zona
            reward += REWARD_SURVIVAL_TIME_PER_SEC / 30f // por frame a 30fps
        }
        
        // Avance estratégico (cambio de fase)
        if (previous?.tacticalOutput?.situation?.type != situation?.type) {
            if (situation?.type?.name?.contains("ADVANTAGE") == true ||
                situation?.type?.name?.contains("SAFE") == true) {
                reward += REWARD_ROTATION_GOOD
            }
        }
        
        return reward
    }

    /**
     * Penalizaciones por comportamiento malo.
     */
    private fun calculatePenalties(
        previous: EnsembleResult?,
        current: EnsembleResult,
        action: com.ffai.assistant.action.ActionType
    ): Float {
        var penalty = 0f
        
        // Desperdicio de munición (disparo sin enemigos)
        if (action == com.ffai.assistant.action.ActionType.SHOOT && current.mergedEnemies.isEmpty()) {
            penalty += PENALTY_AMMO_WASTE
        }
        
        // Reload en combate cercano
        val hasCloseEnemies = current.mergedEnemies.any { 
            kotlin.math.hypot(it.x - 360.0, it.y - 800.0) < 100 
        }
        if (action == com.ffai.assistant.action.ActionType.RELOAD && hasCloseEnemies) {
            penalty += PENALTY_UNNECESSARY_RELOAD
        }
        
        // Exposición alto riesgo
        if (current.tacticalOutput?.situation?.dangerLevel?.let { it > 0.8f } == true &&
            action == com.ffai.assistant.action.ActionType.SHOOT) {
            penalty += PENALTY_EXPOSURE_HIGH_RISK
        }
        
        // Fuera de zona (daño del círculo)
        if (current.tacticalOutput?.situation?.inZone == false) {
            penalty += PENALTY_OUT_OF_ZONE
            
            // Si HP bajó estando fuera, es daño del círculo
            val currentHP = current.uiOutput?.hpInfo?.current ?: 100
            val previousHP = previous?.uiOutput?.hpInfo?.current ?: 100
            if (currentHP < previousHP) {
                penalty += PENALTY_CIRCLE_DAMAGE
            }
        }
        
        return penalty
    }

    /**
     * Shaping temporal (recompensas diferidas para secuencias).
     */
    private fun calculateTemporalShaping(
        action: com.ffai.assistant.action.ActionType,
        timestamp: Long
    ): Float {
        var shaping = 0f
        
        // Detectar secuencias positivas
        // Ejemplo: AIM -> SHOOT (con poco tiempo entre ellos)
        if (actionSequence.size >= 2) {
            val lastAction = actionSequence.lastOrNull()
            val secondLast = actionSequence.dropLast(1).lastOrNull()
            
            if (lastAction?.type == com.ffai.assistant.action.ActionType.SHOOT &&
                secondLast?.type == com.ffai.assistant.action.ActionType.AIM &&
                timestamp - secondLast.timestamp < 500) {
                // Buena secuencia aim->shoot rápido
                shaping += 2f
            }
            
            // Secuencia defensiva: MOVE + HEAL
            if (lastAction?.type == com.ffai.assistant.action.ActionType.HEAL &&
                secondLast?.type?.name?.contains("MOVE") == true) {
                shaping += 3f
            }
        }
        
        return shaping
    }

    /**
     * Bonus por secuencias exitosas.
     */
    private fun calculateSequenceBonus(timestamp: Long): Float {
        var bonus = 0f
        
        // Analizar últimas acciones
        val recentActions = actionSequence.filter { 
            timestamp - it.timestamp < SEQUENCE_REWARD_WINDOW_MS 
        }
        
        // Secuencia de combate exitoso: AIM -> SHOOT -> KILL detectado
        val hasAim = recentActions.any { it.type == com.ffai.assistant.action.ActionType.AIM }
        val hasShoot = recentActions.any { it.type == com.ffai.assistant.action.ActionType.SHOOT }
        
        if (hasAim && hasShoot) {
            // Verificar si hubo kill después
            val recentKills = killHistory.filter { 
                timestamp - it.timestamp < SEQUENCE_REWARD_WINDOW_MS && it.isEnemy
            }
            if (recentKills.isNotEmpty()) {
                bonus += 10f // Bonus por secuencia completa exitosa
            }
        }
        
        return bonus
    }

    // ============================================
    // MÉTODOS AUXILIARES
    // ============================================

    private fun isTeammate(victimName: String, state: EnsembleResult): Boolean {
        // Heurística: si el nombre está en lista de aliados conocidos
        // Por ahora, usar posición como proxy
        return false // Implementar con team info real
    }

    private fun isTeammateByPosition(x: Float, y: Float, state: EnsembleResult): Boolean {
        // Heurística: si hay marcador aliado en esa posición
        return false // Implementar con detección de aliados
    }

    private fun isWeaponUpgrade(oldWeapon: String, newWeapon: String): Boolean {
        val tierMap = mapOf(
            "PISTOL" to 1, "UZI" to 2, "SHOTGUN" to 3,
            "M416" to 4, "AKM" to 4, "SCAR" to 4,
            "M24" to 5, "AWM" to 5
        )
        return (tierMap[newWeapon] ?: 0) > (tierMap[oldWeapon] ?: 0)
    }

    private fun cleanupOldRecords(timestamp: Long) {
        killHistory.removeIf { timestamp - it.timestamp > KILL_CONFIRMATION_WINDOW_MS }
        damageDealtHistory.removeIf { timestamp - it.timestamp > DAMAGE_WINDOW_MS }
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    fun getStats(): ShaperRewardStats {
        return ShaperRewardStats(
            totalKills = totalKills.get(),
            totalAllyKills = totalAllyKills.get(),
            totalDeaths = totalDeaths.get(),
            totalFriendlyFire = totalFriendlyFire.get(),
            cumulativeReward = cumulativeReward.get(),
            recentActionCount = actionSequence.size
        )
    }

    fun reset() {
        lastHP.set(100)
        lastAmmo.set(30)
        lastWeapon.set("")
        lastArmor.set("")
        killHistory.clear()
        damageDealtHistory.clear()
        actionSequence.clear()
        totalKills.set(0)
        totalAllyKills.set(0)
        totalDeaths.set(0)
        totalFriendlyFire.set(0)
        cumulativeReward.set(0f)
        Logger.i(TAG, "RewardShaper reseteado")
    }
}

// ============================================
// DATA CLASSES
// ============================================

private data class KillRecord(
    val victim: String,
    val isOwnKill: Boolean,
    val isEnemy: Boolean,
    val timestamp: Long
)

private data class DamageRecord(
    val isEnemy: Boolean,
    val amount: Float,
    val timestamp: Long
)

private data class ActionRecord(
    val type: com.ffai.assistant.action.ActionType,
    val reward: Float,
    val timestamp: Long
)

data class ShaperRewardStats(
    val totalKills: Int,
    val totalAllyKills: Int,
    val totalDeaths: Int,
    val totalFriendlyFire: Int,
    val cumulativeReward: Float,
    val recentActionCount: Int
) {
    fun getKDRatio(): Float = if (totalDeaths > 0) totalKills.toFloat() / totalDeaths else totalKills.toFloat()
    fun getFriendlyFireRatio(): Float = if (totalKills > 0) totalFriendlyFire.toFloat() / (totalKills + totalFriendlyFire) else 0f
}
