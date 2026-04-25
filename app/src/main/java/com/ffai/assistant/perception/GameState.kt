package com.ffai.assistant.perception

import com.ffai.assistant.config.Constants

/**
 * Representa el estado completo del juego detectado por la visión.
 * Este es el input para la red neuronal.
 */
data class GameState(
    // Características básicas (normalizadas 0-1)
    val healthRatio: Float = 1.0f,
    val ammoRatio: Float = 1.0f,
    
    // Información de enemigo
    val enemyPresent: Boolean = false,
    val enemyX: Float = 0f,      // -1 (izq) a 1 (der)
    val enemyY: Float = 0f,      // -1 (arriba) a 1 (abajo)
    val enemyDistance: Float = 1f,  // 0 (cerca) a 1 (lejos)
    val enemyCount: Int = 0,
    
    // Cooldowns de acciones (0 = listo, 1 = en cooldown)
    val shootCooldown: Float = 0f,
    val healCooldown: Float = 0f,
    val reloadCooldown: Float = 0f,
    
    // Estado del jugador
    val isInSafeZone: Boolean = true,
    val isCrouching: Boolean = false,
    val isAiming: Boolean = false,
    val currentWeapon: WeaponType = WeaponType.ASSAULT_RIFLE,
    
    // Información de combate
    val kills: Int = 0,
    val damageDealt: Float = 0f,
    val damageTaken: Float = 0f,
    val isDead: Boolean = false,
    val placement: Int = 50,  // Posición en la partida
    
    // Compañeros
    val teammatesAlive: Int = 3,
    val teammateNeedsRevive: Boolean = false,
    
    // Loot cercano
    val lootNearby: Boolean = false,
    val hasGoodWeapon: Boolean = true,
    val hasArmor: Boolean = true,
    val hasHelmet: Boolean = true,
    val hasHealItems: Boolean = true,
    
    // Zona segura
    val safeZoneShrinking: Boolean = false,
    val distanceToSafeZone: Float = 0f,  // 0 = dentro, 1 = muy lejos
    
    // Timestamp para tracking
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Convierte a vector de features para la red neuronal (8 valores)
     */
    fun toFeatureVector(): FloatArray {
        return floatArrayOf(
            healthRatio.coerceIn(0f, 1f),
            ammoRatio.coerceIn(0f, 1f),
            if (enemyPresent) 1f else 0f,
            enemyX.coerceIn(-1f, 1f),
            enemyY.coerceIn(-1f, 1f),
            enemyDistance.coerceIn(0f, 1f),
            shootCooldown.coerceIn(0f, 1f),
            healCooldown.coerceIn(0f, 1f)
        )
    }
    
    /**
     * Estado de emergencia - necesita curación urgente
     */
    fun needsUrgentHeal(): Boolean = healthRatio < 0.3f && healCooldown < 0.1f
    
    /**
     * Debe recargar
     */
    fun needsReload(): Boolean = ammoRatio < 0.2f && reloadCooldown < 0.1f
    
    /**
     * Puede atacar
     */
    fun canAttack(): Boolean = enemyPresent && ammoRatio > 0.1f && shootCooldown < 0.1f
    
    /**
     * Está en peligro
     */
    fun isInDanger(): Boolean = healthRatio < 0.5f && enemyPresent
    
    /**
     * Debe moverse a zona segura
     */
    fun needsToMoveToSafeZone(): Boolean = !isInSafeZone && distanceToSafeZone > 0.3f
    
    companion object {
        val DEFAULT = GameState()
        val EMPTY = GameState(
            healthRatio = 0f,
            ammoRatio = 0f,
            enemyPresent = false,
            isDead = true
        )
    }
}

enum class WeaponType {
    PISTOL,
    SMG,
    ASSAULT_RIFLE,
    LMG,
    SNIPER,
    SHOTGUN,
    MELEE,
    UNKNOWN
}
