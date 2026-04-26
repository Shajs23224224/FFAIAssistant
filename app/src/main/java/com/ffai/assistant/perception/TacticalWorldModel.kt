package com.ffai.assistant.perception

import com.ffai.assistant.utils.Logger

/**
 * FASE 2: TacticalWorldModel - Modelo interno completo del estado del juego.
 * 
 * Construye una representación rica del mundo que incluye:
 * - Posiciones propias y de enemigos
 * - Inventario y recursos
 * - Cobertura y terreno
 * - Riesgo y amenazas
 * - Estado de la partida
 * 
 * Optimizado para Samsung A21S (bajo uso de memoria, operaciones rápidas).
 */
class TacticalWorldModel {
    
    // ============================================
    // JUGADOR (Self State)
    // ============================================
    
    /** Posición actual en el mapa (coordenadas normalizadas 0-1) */
    var playerPosition: Vector3D = Vector3D(0f, 0f, 0f)
    
    /** Dirección de vista (ángulo en radianes) */
    var viewAngle: Float = 0f
    
    /** Salud actual (0-100) */
    var playerHealth: Float = 100f
    
    /** Armadura/escudo (0-100) */
    var playerShield: Float = 0f
    
    /** Estado de movimiento */
    var isMoving: Boolean = false
    var isCrouching: Boolean = false
    var isJumping: Boolean = false
    var isSprinting: Boolean = false
    var isAiming: Boolean = false
    
    /** Arma actual equipada */
    var currentWeapon: WeaponInfo = WeaponInfo.UNKNOWN
    
    /** Munición en cargador actual */
    var currentAmmo: Int = 0
    
    /** Munición total disponible */
    var totalAmmo: Int = 0
    
    /** Inventario completo */
    val inventory: Inventory = Inventory()
    
    // ============================================
    // ENEMIGOS (Enemy Tracking)
    // ============================================
    
    /** Lista de enemigos actualmente visibles */
    val visibleEnemies: MutableList<EnemyInfo> = mutableListOf()
    
    /** Enemigos vistos recientemente (últimos 10 segundos) */
    val recentEnemies: MutableMap<String, TrackedEnemy> = mutableMapOf()
    
    /** Último enemigo detectado (para aim assist) */
    var lastEnemyPosition: Vector3D? = null
    var lastEnemyTimestamp: Long = 0L
    
    /** Número de enemigos en combate */
    val activeThreatCount: Int get() = visibleEnemies.count { it.threatLevel >= ThreatLevel.MEDIUM }
    
    // ============================================
    // ZONA SEGURA (Safe Zone)
    // ============================================
    
    /** Centro de la zona segura (coordenadas normalizadas) */
    var safeZoneCenter: Vector2D = Vector2D(0.5f, 0.5f)
    
    /** Radio de la zona segura (normalizado) */
    var safeZoneRadius: Float = 1.0f
    
    /** La zona está encogiéndose */
    var isZoneShrinking: Boolean = false
    
    /** Tiempo hasta que la zona se encoja (segundos) */
    var timeToNextShrink: Float = 0f
    
    /** Distancia al borde de la zona segura */
    var distanceToZoneEdge: Float = 0f
    
    /** Estamos dentro de la zona segura */
    var isInSafeZone: Boolean = true
    
    /** Dirección al centro de la zona */
    var directionToZoneCenter: Vector2D = Vector2D(0f, 0f)
    
    // ============================================
    // COBERTURA (Cover Analysis)
    // ============================================
    
    /** Posiciones de cobertura identificadas */
    val coverPositions: MutableList<CoverInfo> = mutableListOf()
    
    /** Cobertura más cercana */
    var nearestCover: CoverInfo? = null
    
    /** Distancia a la cobertura más cercana */
    var distanceToNearestCover: Float = Float.MAX_VALUE
    
    /** Calidad de la cobertura actual (0-1) */
    var currentCoverQuality: Float = 0f
    
    /** Estamos en cobertura */
    var isInCover: Boolean = false
    
    /** Cobertura está expuesta a enemigos */
    var isCoverCompromised: Boolean = false
    
    // ============================================
    // RIESGO Y EVALUACIÓN (Risk Assessment)
    // ============================================
    
    /** Nivel de amenaza inmediata */
    var immediateThreat: ThreatLevel = ThreatLevel.NONE
    
    /** Score de riesgo global (0-100) */
    var riskScore: Float = 0f
    
    /** Probabilidad de que ocurra pelea en los próximos 10s */
    var fightProbability: Float = 0f
    
    /** Riesgo de emboscada */
    var ambushRisk: Float = 0f
    
    /** Estamos en desventaja */
    var isAtDisadvantage: Boolean = false
    
    /** Estamos en ventaja */
    var isAtAdvantage: Boolean = false
    
    // ============================================
    // TIEMPO Y FASE (Match Phase)
    // ============================================
    
    /** Tiempo transcurrido en la partida (segundos) */
    var matchTimeSeconds: Int = 0
    
    /** Fase actual de la partida */
    var currentPhase: MatchPhase = MatchPhase.EARLY
    
    /** Jugadores vivos restantes */
    var playersAlive: Int = 100
    
    /** Número de kills propios */
    var ownKills: Int = 0
    
    /** Daño infligido en la partida */
    var damageDealt: Float = 0f
    
    /** Posición estimada (1 = ganador) */
    var estimatedPlacement: Int = 50
    
    // ============================================
    // DECISIONES RECOMENDADAS (AI Suggestions)
    // ============================================
    
    /** Postura recomendada actual */
    var recommendedStance: Stance = Stance.STANDING
    
    /** Dirección óptima de rotación */
    var optimalRotation: Float = 0f
    
    /** Acción prioritaria sugerida */
    var suggestedPriority: TacticalPriority = TacticalPriority.EXPLORE
    
    /** ¿Es seguro curarse ahora? */
    var isSafeToHeal: Boolean = false
    
    /** ¿Es seguro recargar ahora? */
    var isSafeToReload: Boolean = false
    
    /** ¿Es seguro saquear? */
    var isSafeToLoot: Boolean = false
    
    // ============================================
    // MÉTODOS DE ACTUALIZACIÓN
    // ============================================
    
    /**
     * Actualiza el estado completo basado en GameState (from Vision).
     */
    fun updateFromGameState(gameState: GameState) {
        // Actualizar salud y estado
        playerHealth = gameState.healthRatio * 100f
        playerShield = if (gameState.hasArmor) 100f else 0f
        
        // Actualizar armas y munición
        currentWeapon = detectWeaponType(gameState)
        currentAmmo = (gameState.ammoRatio * 30).toInt() // Aproximación
        totalAmmo = inventory.getTotalAmmoForWeapon(currentWeapon)
        
        // Actualizar enemigos
        if (gameState.enemyPresent) {
            updateEnemyFromGameState(gameState)
        } else {
            visibleEnemies.clear()
        }
        
        // Actualizar zona
        distanceToZoneEdge = gameState.distanceToSafeZone
        isInSafeZone = distanceToZoneEdge <= 0f
        
        // Recalcular evaluaciones
        recalculateRiskAssessment()
        recalculatePhase()
        updateRecommendations()
    }
    
    /**
     * Actualiza información de enemigo desde GameState.
     */
    private fun updateEnemyFromGameState(gameState: GameState) {
        // Convertir coordenadas normalizadas (-1 a 1) a coordenadas de pantalla (0-1080, 0-2400)
        val screenX = ((gameState.enemyX + 1f) / 2f * 1080f).coerceIn(0f, 1080f)
        val screenY = ((gameState.enemyY + 1f) / 2f * 2400f).coerceIn(0f, 2400f)
        
        val enemy = EnemyInfo(
            id = "current_target",
            position = Vector3D(gameState.enemyX, gameState.enemyY, 0f),
            screenX = screenX,
            screenY = screenY,
            healthEstimate = 100f, // Desconocido
            distanceEstimate = gameState.enemyDistance,
            isVisible = true,
            threatLevel = calculateThreatLevel(gameState),
            lastSeen = System.currentTimeMillis()
        )
        
        // Actualizar o agregar
        val existingIndex = visibleEnemies.indexOfFirst { it.id == enemy.id }
        if (existingIndex >= 0) {
            visibleEnemies[existingIndex] = enemy
        } else {
            visibleEnemies.add(enemy)
        }
        
        // Limitar lista
        if (visibleEnemies.size > 5) {
            visibleEnemies.sortBy { it.threatLevel.ordinal }
            visibleEnemies.removeAt(0)
        }
        
        // Actualizar tracker
        lastEnemyPosition = enemy.position
        lastEnemyTimestamp = System.currentTimeMillis()
        
        // Agregar a recientes
        recentEnemies[enemy.id] = TrackedEnemy(
            id = enemy.id,
            lastKnownPosition = enemy.position,
            lastSeenTime = System.currentTimeMillis(),
            confidence = if (enemy.isVisible) 1.0f else 0.7f
        )
    }
    
    /**
     * Recalcula la evaluación de riesgo.
     */
    private fun recalculateRiskAssessment() {
        var risk = 0f
        
        // Salud baja = riesgo alto
        if (playerHealth < 25f) risk += 40f
        else if (playerHealth < 50f) risk += 25f
        else if (playerHealth < 75f) risk += 10f
        
        // Enemigos presentes
        risk += visibleEnemies.size * 15f
        risk += activeThreatCount * 20f
        
        // Sin munición
        if (currentAmmo == 0 && totalAmmo == 0) risk += 30f
        else if (currentAmmo == 0) risk += 15f
        
        // Fuera de zona
        if (!isInSafeZone) {
            risk += 20f
            if (isZoneShrinking) risk += 15f
        }
        
        // Sin cobertura
        if (distanceToNearestCover > 0.3f) risk += 15f
        if (isCoverCompromised) risk += 25f
        
        // Evaluar nivel de amenaza
        immediateThreat = when {
            risk >= 70f -> ThreatLevel.CRITICAL
            risk >= 50f -> ThreatLevel.HIGH
            risk >= 30f -> ThreatLevel.MEDIUM
            risk >= 15f -> ThreatLevel.LOW
            else -> ThreatLevel.NONE
        }
        
        // Probabilidad de pelea
        fightProbability = when {
            visibleEnemies.isNotEmpty() -> 0.8f
            recentEnemies.isNotEmpty() -> 0.4f
            playersAlive < 20 -> 0.3f
            playersAlive < 50 -> 0.15f
            else -> 0.05f
        }
        
        // Riesgo de emboscada
        ambushRisk = if (recentEnemies.isNotEmpty() && visibleEnemies.isEmpty()) 0.4f else 0.1f
        
        // Ventaja/Desventaja
        isAtDisadvantage = risk > 50f || (visibleEnemies.isNotEmpty() && distanceToNearestCover > 0.2f)
        isAtAdvantage = risk < 20f && coverPositions.isNotEmpty() && visibleEnemies.size <= 1
        
        riskScore = risk.coerceIn(0f, 100f)
    }
    
    /**
     * Recalcula la fase de la partida.
     */
    private fun recalculatePhase() {
        currentPhase = when {
            matchTimeSeconds < 180 -> MatchPhase.EARLY // Primeros 3 min
            playersAlive > 30 -> MatchPhase.MID
            playersAlive > 10 -> MatchPhase.LATE
            else -> MatchPhase.ENDGAME
        }
    }
    
    /**
     * Actualiza recomendaciones tácticas.
     */
    private fun updateRecommendations() {
        // Determinar postura
        recommendedStance = when {
            immediateThreat == ThreatLevel.CRITICAL -> Stance.CROUCHING
            isInCover -> Stance.CROUCHING
            visibleEnemies.isNotEmpty() && distanceToNearestCover < 0.2f -> Stance.CROUCHING
            isSafeToLoot -> Stance.STANDING
            else -> Stance.STANDING
        }
        
        // Determinar prioridad
        suggestedPriority = when {
            immediateThreat == ThreatLevel.CRITICAL -> TacticalPriority.SURVIVE
            playerHealth < 50f && inventory.hasHeals -> TacticalPriority.HEAL
            currentAmmo == 0 && totalAmmo > 0 -> TacticalPriority.RELOAD
            !isInSafeZone -> TacticalPriority.MOVE_TO_ZONE
            isAtDisadvantage -> TacticalPriority.RETREAT
            visibleEnemies.isNotEmpty() -> TacticalPriority.ENGAGE
            inventory.needsLoot() -> TacticalPriority.LOOT
            recentEnemies.isNotEmpty() -> TacticalPriority.HUNT
            else -> TacticalPriority.EXPLORE
        }
        
        // ¿Es seguro hacer X?
        isSafeToHeal = visibleEnemies.isEmpty() || (isInCover && playerHealth < 30f)
        isSafeToReload = visibleEnemies.isEmpty() || currentAmmo > 5
        isSafeToLoot = visibleEnemies.isEmpty() && recentEnemies.isEmpty() && isInSafeZone
    }
    
    /**
     * Calcula nivel de amenaza de un enemigo.
     */
    private fun calculateThreatLevel(gameState: GameState): ThreatLevel {
        return when {
            gameState.enemyDistance < 0.15f -> ThreatLevel.CRITICAL // Muy cerca
            gameState.enemyDistance < 0.3f -> ThreatLevel.HIGH
            gameState.enemyDistance < 0.5f -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
    }
    
    /**
     * Detecta tipo de arma desde GameState (heurístico).
     */
    private fun detectWeaponType(gameState: GameState): WeaponInfo {
        // Heurísticas basadas en comportamiento
        return when {
            gameState.isSniperScopeActive -> WeaponInfo.SNIPER
            currentAmmo <= 25 && currentWeapon == WeaponInfo.SHOTGUN -> WeaponInfo.SHOTGUN
            currentAmmo <= 35 -> WeaponInfo.SMG
            else -> WeaponInfo.AR
        }
    }
    
    /**
     * Agrega una posición de cobertura.
     */
    fun addCoverPosition(position: Vector3D, quality: Float, isSolid: Boolean = true) {
        coverPositions.add(CoverInfo(
            position = position,
            quality = quality,
            isSolid = isSolid,
            distance = playerPosition.distanceTo(position)
        ))
        
        // Actualizar más cercana
        if (coverPositions.last().distance < distanceToNearestCover) {
            nearestCover = coverPositions.last()
            distanceToNearestCover = coverPositions.last().distance
        }
    }
    
    /**
     * Limpia información de enemigos antiguos.
     */
    fun cleanupOldEnemies(maxAgeMs: Long = 10000L) {
        val now = System.currentTimeMillis()
        val iterator = recentEnemies.entries.iterator()
        while (iterator.hasNext()) {
            val (_, tracked) = iterator.next()
            if (now - tracked.lastSeenTime > maxAgeMs) {
                iterator.remove()
            }
        }
    }
    
    /**
     * Obtiene resumen del estado actual.
     */
    fun getSummary(): String {
        return buildString {
            append("[WorldModel] ")
            append("HP:${playerHealth.toInt()} ")
            append("Ammo:$currentAmmo/$totalAmmo ")
            append("Enemies:${visibleEnemies.size} ")
            append("Threat:$immediateThreat ")
            append("Risk:${riskScore.toInt()}% ")
            append("Zone:${if (isInSafeZone) "IN" else "OUT"} ")
            append("Phase:$currentPhase ")
            append("Priority:$suggestedPriority")
        }
    }
    
    /**
     * Reset para nueva partida.
     */
    fun reset() {
        playerPosition = Vector3D(0f, 0f, 0f)
        playerHealth = 100f
        playerShield = 0f
        visibleEnemies.clear()
        recentEnemies.clear()
        coverPositions.clear()
        nearestCover = null
        distanceToNearestCover = Float.MAX_VALUE
        matchTimeSeconds = 0
        ownKills = 0
        damageDealt = 0f
        Logger.i("TacticalWorldModel reseteado")
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class Vector2D(val x: Float, val y: Float) {
    fun distanceTo(other: Vector2D): Float {
        return kotlin.math.hypot(x - other.x, y - other.y)
    }
}

data class Vector3D(val x: Float, val y: Float, val z: Float) {
    fun distanceTo(other: Vector3D): Float {
        return kotlin.math.sqrt(
            (x - other.x) * (x - other.x) +
            (y - other.y) * (y - other.y) +
            (z - other.z) * (z - other.z)
        )
    }
    
    fun to2D(): Vector2D = Vector2D(x, y)
}

data class EnemyInfo(
    val id: String,
    val position: Vector3D,
    val screenX: Float,
    val screenY: Float,
    val healthEstimate: Float,
    val distanceEstimate: Float,
    val isVisible: Boolean,
    val threatLevel: ThreatLevel,
    val lastSeen: Long
)

data class TrackedEnemy(
    val id: String,
    val lastKnownPosition: Vector3D,
    val lastSeenTime: Long,
    val confidence: Float
)

data class CoverInfo(
    val position: Vector3D,
    val quality: Float, // 0-1
    val isSolid: Boolean,
    val distance: Float
)

data class WeaponInfo(
    val type: WeaponType,
    val name: String,
    val optimalRange: Float, // 0-1 normalized
    val damagePerShot: Float,
    val fireRate: Float // shots per second
) {
    companion object {
        val UNKNOWN = WeaponInfo(WeaponType.UNKNOWN, "Unknown", 0.5f, 20f, 5f)
        val AR = WeaponInfo(WeaponType.ASSAULT_RIFLE, "Assault Rifle", 0.4f, 35f, 10f)
        val SMG = WeaponInfo(WeaponType.SMG, "SMG", 0.25f, 25f, 15f)
        val SNIPER = WeaponInfo(WeaponType.SNIPER, "Sniper", 0.8f, 100f, 1f)
        val SHOTGUN = WeaponInfo(WeaponType.SHOTGUN, "Shotgun", 0.15f, 150f, 2f)
    }
}

class Inventory {
    var healItems: Int = 0
    var shieldItems: Int = 0
    var grenades: Int = 0
    var ammoAR: Int = 0
    var ammoSMG: Int = 0
    var ammoSniper: Int = 0
    var ammoShotgun: Int = 0
    
    fun hasHeals(): Boolean = healItems > 0
    fun hasShields(): Boolean = shieldItems > 0
    fun needsLoot(): Boolean {
        return healItems < 3 || healItems < 2 || totalAmmo() < 100
    }
    
    fun getTotalAmmoForWeapon(weapon: WeaponInfo): Int {
        return when (weapon.type) {
            WeaponType.ASSAULT_RIFLE -> ammoAR
            WeaponType.SMG -> ammoSMG
            WeaponType.SNIPER -> ammoSniper
            WeaponType.SHOTGUN -> ammoShotgun
            else -> 0
        }
    }
    
    private fun totalAmmo(): Int = ammoAR + ammoSMG + ammoSniper + ammoShotgun
}

// ============================================
// ENUMS
// ============================================

enum class ThreatLevel {
    NONE, LOW, MEDIUM, HIGH, CRITICAL
}

enum class MatchPhase {
    EARLY, MID, LATE, ENDGAME
}

enum class Stance {
    STANDING, CROUCHING, PRONE, JUMPING
}

enum class TacticalPriority {
    SURVIVE,      // Primera prioridad: no morir
    HEAL,         // Curarse
    RELOAD,       // Recargar
    MOVE_TO_ZONE, // Ir a zona segura
    RETREAT,      // Retirarse
    ENGAGE,       // Atacar enemigo
    HUNT,         // Buscar enemigos
    LOOT,         // Saquear
    EXPLORE       // Explorar/moverse
}
