package com.ffai.assistant.core

import com.ffai.assistant.perception.*
import com.ffai.assistant.utils.Logger

/**
 * FASE 4: StrategicEngine - Decisiones estratégicas de alto nivel.
 * 
 * Este motor opera a baja frecuencia (cada 1-2 segundos) y decide:
 * - Selección de ruta segura
 - Gestión global de recursos
 - Control de posición
 - Juego de zona
 - Coordinación táctica
 - Adaptación al estilo del lobby
 * 
 * No reemplaza al FastTacticalEngine, sino que lo guía.
 */
class StrategicEngine(
    private val worldModel: TacticalWorldModel,
    private val stateTracker: StateTracker,
    private val riskAssessor: RiskAssessor
) {
    
    companion object {
        const val TAG = "StrategicEngine"
        const val STRATEGIC_UPDATE_INTERVAL_MS = 2000L // 2 segundos
        
        // Constantes de estrategia
        const val EARLY_GAME_THRESHOLD = 180 // 3 minutos
        const val MID_GAME_THRESHOLD = 300 // 5 minutos
        const val ENDGAME_THRESHOLD = 480 // 8 minutos
    }
    
    // Estado estratégico
    private var lastUpdateTime: Long = 0L
    private var currentStrategy: GameStrategy = GameStrategy.BALANCED
    private var targetDestination: Vector3D? = null
    private var plannedRoute: MutableList<Vector3D> = mutableListOf()
    
    // Historial de decisiones
    private val routeHistory: MutableList<RouteDecision> = mutableListOf()
    private val situationHistory: MutableList<SituationRecord> = mutableListOf()
    
    // Métricas de adaptación
    private var aggressionLevel: Float = 0.5f // 0-1
    private var survivalPriority: Float = 0.5f // 0-1
    private var estimatedLobbyAggression: Float = 0.5f
    
    // Contadores para adaptación
    private var encountersCount = 0
    private var successfulEngagements = 0
    private var deathsByAggression = 0
    
    /**
     * Actualiza estrategia (llamar cada 1-2 segundos, NO cada frame).
     */
    fun updateStrategy(): StrategicDecision? {
        val now = System.currentTimeMillis()
        
        // Solo actualizar cada intervalo
        if (now - lastUpdateTime < STRATEGIC_UPDATE_INTERVAL_MS) {
            return null
        }
        lastUpdateTime = now
        
        val startTime = System.currentTimeMillis()
        
        // Evaluar situación global
        val riskReport = riskAssessor.assessRisk()
        val matchPhase = determineMatchPhase()
        
        // Adaptar estrategia
        adaptStrategy(riskReport, matchPhase)
        
        // Tomar decisiones estratégicas
        val decision = makeStrategicDecision(riskReport, matchPhase)
        
        // Registrar para learning
        recordDecision(decision, riskReport, matchPhase)
        
        val latency = System.currentTimeMillis() - startTime
        if (latency > 50L) {
            Logger.w(TAG, "Strategic decision lenta: ${latency}ms")
        }
        
        return decision
    }
    
    /**
     * Determina la fase actual de la partida.
     */
    private fun determineMatchPhase(): MatchPhase {
        val time = worldModel.matchTimeSeconds
        val playersAlive = worldModel.playersAlive
        
        return when {
            time < EARLY_GAME_THRESHOLD -> MatchPhase.EARLY
            playersAlive > 30 -> MatchPhase.MID
            playersAlive > 10 -> MatchPhase.LATE
            else -> MatchPhase.ENDGAME
        }
    }
    
    /**
     * Adapta la estrategia según situación.
     */
    private fun adaptStrategy(risk: RiskReport, phase: MatchPhase) {
        // Ajustar agresión según fase y riesgo
        aggressionLevel = when {
            // Fase temprana: más agresivo para lootear
            phase == MatchPhase.EARLY && risk.totalScore < 40f -> 0.7f
            
            // Fase media: balanceado
            phase == MatchPhase.MID -> {
                if (worldModel.inventory.needsLoot()) 0.6f else 0.4f
            }
            
            // Fase tardía: conservador
            phase == MatchPhase.LATE -> 0.3f
            
            // Endgame: muy conservador
            phase == MatchPhase.ENDGAME -> 0.2f
            
            // Riesgo alto: defensivo
            risk.totalScore > 60f -> 0.2f
            
            // Default
            else -> 0.5f
        }
        
        // Ajustar prioridad de supervivencia
        survivalPriority = when {
            worldModel.playerHealth < 50f -> 0.9f
            risk.totalScore > 70f -> 0.8f
            worldModel.inventory.hasHeals() && worldModel.inventory.hasShields() -> 0.4f
            else -> 0.6f
        }
        
        // Seleccionar estrategia concreta
        currentStrategy = selectStrategy(phase, risk)
    }
    
    /**
     * Selecciona estrategia concreta.
     */
    private fun selectStrategy(phase: MatchPhase, risk: RiskReport): GameStrategy {
        return when {
            // Estrategias defensivas
            risk.totalScore > 75f -> GameStrategy.SURVIVAL
            worldModel.playerHealth < 30f -> GameStrategy.SURVIVAL
            phase == MatchPhase.ENDGAME && worldModel.ownKills < 2 -> GameStrategy.POSITIONING
            
            // Estrategias agresivas
            phase == MatchPhase.EARLY && worldModel.inventory.needsLoot() -> GameStrategy.AGGRESSIVE_LOOT
            aggressionLevel > 0.7f && worldModel.playerHealth > 70f -> GameStrategy.HUNTING
            
            // Estrategias balanceadas
            phase == MatchPhase.MID -> GameStrategy.BALANCED
            
            // Default
            else -> GameStrategy.POSITIONING
        }
    }
    
    /**
     * Toma decisión estratégica concreta.
     */
    private fun makeStrategicDecision(risk: RiskReport, phase: MatchPhase): StrategicDecision {
        return when (currentStrategy) {
            GameStrategy.SURVIVAL -> makeSurvivalDecision(risk)
            GameStrategy.POSITIONING -> makePositioningDecision(risk, phase)
            GameStrategy.HUNTING -> makeHuntingDecision()
            GameStrategy.AGGRESSIVE_LOOT -> makeLootingDecision()
            GameStrategy.BALANCED -> makeBalancedDecision(risk, phase)
        }
    }
    
    /**
     * Decisión de supervivencia: priorizar no morir.
     */
    private fun makeSurvivalDecision(risk: RiskReport): StrategicDecision {
        // Prioridad máxima: cobertura y zona
        val target = if (!worldModel.isInSafeZone) {
            calculateSafeZoneEntry()
        } else if (risk.exposure.isExposed) {
            findSafestPosition()
        } else {
            worldModel.nearestCover?.position
        }
        
        return StrategicDecision(
            type = StrategicDecisionType.MOVE_TO_SAFETY,
            targetPosition = target,
            priority = StrategicPriority.CRITICAL,
            estimatedRisk = 20f, // Confiamos en que es seguro
            timeHorizonSeconds = 10,
            description = "SURVIVAL MODE: Reach safety immediately"
        )
    }
    
    /**
     * Decisión de posicionamiento: controlar posición ventajosa.
     */
    private fun makePositioningDecision(risk: RiskReport, phase: MatchPhase): StrategicDecision {
        val target = when {
            // Fase final: buscar posición en zona con cobertura
            phase == MatchPhase.ENDGAME || phase == MatchPhase.LATE -> {
                findEndgamePosition()
            }
            // Fase media: controlar área con buen loot y cobertura
            else -> findControlPosition()
        }
        
        return StrategicDecision(
            type = StrategicDecisionType.IMPROVE_POSITION,
            targetPosition = target,
            priority = StrategicPriority.HIGH,
            estimatedRisk = riskAssessor.assessRouteViability(target ?: worldModel.playerPosition).riskScore,
            timeHorizonSeconds = 30,
            description = "POSITIONING: Control advantageous position"
        )
    }
    
    /**
     * Decisión de caza: buscar enemigos.
     */
    private fun makeHuntingDecision(): StrategicDecision {
        // Buscar enemigo más reciente
        val target = worldModel.recentEnemies.values
            .sortedBy { System.currentTimeMillis() - it.lastSeenTime }
            .firstOrNull()?.lastKnownPosition
        
        return StrategicDecision(
            type = StrategicDecisionType.HUNT_ENEMIES,
            targetPosition = target,
            priority = StrategicPriority.MEDIUM,
            estimatedRisk = 50f, // Moderado
            timeHorizonSeconds = 20,
            description = "HUNTING: Pursue recent enemy contact"
        )
    }
    
    /**
     * Decisión de saqueo agresivo.
     */
    private fun makeLootingDecision(): StrategicDecision {
        // Buscar área de loot cercana (simulado)
        val lootDirection = calculateLootDirection()
        
        return StrategicDecision(
            type = StrategicDecisionType.LOOT_AREA,
            targetPosition = null, // Explorar área
            targetDirection = lootDirection,
            priority = StrategicPriority.MEDIUM,
            estimatedRisk = 40f,
            timeHorizonSeconds = 45,
            description = "LOOTING: Fast aggressive loot"
        )
    }
    
    /**
     * Decisión balanceada.
     */
    private fun makeBalancedDecision(risk: RiskReport, phase: MatchPhase): StrategicDecision {
        return when {
            // Si necesitamos loot, ir a lootear
            worldModel.inventory.needsLoot() && risk.totalScore < 50f -> {
                makeLootingDecision()
            }
            // Si hay enemigos recientes, cazar
            worldModel.recentEnemies.isNotEmpty() && aggressionLevel > 0.5f -> {
                makeHuntingDecision()
            }
            // Si estamos expuestos, mejorar posición
            risk.exposure.isExposed -> {
                makePositioningDecision(risk, phase)
            }
            // Default: mantener posición
            else -> StrategicDecision(
                type = StrategicDecisionType.HOLD_POSITION,
                priority = StrategicPriority.LOW,
                estimatedRisk = risk.totalScore,
                timeHorizonSeconds = 15,
                description = "BALANCED: Hold current position"
            )
        }
    }
    
    /**
     * Calcula punto de entrada seguro a zona.
     */
    private fun calculateSafeZoneEntry(): Vector3D? {
        val zoneCenter = worldModel.safeZoneCenter
        val currentPos = worldModel.playerPosition.to2D()
        
        // Punto en el borde de zona más cercano a nosotros
        val directionToZone = Vector2D(
            zoneCenter.x - currentPos.x,
            zoneCenter.y - currentPos.y
        )
        
        val distanceToCenter = kotlin.math.hypot(directionToZone.x, directionToZone.y)
        val zoneRadius = worldModel.safeZoneRadius
        
        // Si estamos fuera, ir hacia centro hasta estar justo dentro
        if (distanceToCenter > zoneRadius) {
            val factor = (distanceToCenter - zoneRadius * 0.9f) / distanceToCenter
            return Vector3D(
                currentPos.x + directionToZone.x * factor,
                currentPos.y + directionToZone.y * factor,
                0f
            )
        }
        
        return null // Ya estamos en zona
    }
    
    /**
     * Encuentra posición más segura cercana.
     */
    private fun findSafestPosition(): Vector3D? {
        return worldModel.coverPositions
            .filter { it.quality > 0.6f }
            .filter { it.position.to2D().distance(worldModel.safeZoneCenter) < worldModel.safeZoneRadius }
            .minByOrNull { it.distance }
            ?.position
    }
    
    /**
     * Encuentra posición óptima para endgame.
     */
    private fun findEndgamePosition(): Vector3D? {
        // Buscar:
        // 1. Cerca del centro de zona
        // 2. Con buena cobertura
        // 3. No en zona de alto tráfico
        
        val zoneCenter = worldModel.safeZoneCenter
        
        return worldModel.coverPositions
            .filter { cover ->
                val distToCenter = cover.position.to2D().distance(zoneCenter)
                distToCenter < worldModel.safeZoneRadius * 0.5f && // Dentro de zona
                cover.quality > 0.7f // Buena cobertura
            }
            .minByOrNull { it.distance }
            ?.position
            ?: Vector3D(zoneCenter.x, zoneCenter.y, 0f)
    }
    
    /**
     * Encuentra posición de control (área con ventaja).
     */
    private fun findControlPosition(): Vector3D? {
        // Buscar cobertura con:
        // - Buena calidad
        // - Visión a áreas comunes
        // - Cerca de zona
        
        return worldModel.coverPositions
            .filter { it.quality > 0.5f }
            .filter { it.position.to2D().distance(worldModel.safeZoneCenter) < worldModel.safeZoneRadius * 0.8f }
            .maxByOrNull { cover ->
                // Score basado en calidad y distancia a zona
                val qualityScore = cover.quality * 100
                val zoneScore = (1f - cover.distance / worldModel.safeZoneRadius) * 50
                qualityScore + zoneScore
            }
            ?.position
    }
    
    /**
     * Calcula dirección para buscar loot.
     */
    private fun calculateLootDirection(): Vector2D {
        // Dirección aleatoria pero alejada de enemigos conocidos
        val baseDir = Vector2D(
            kotlin.math.cos(worldModel.matchTimeSeconds.toFloat()),
            kotlin.math.sin(worldModel.matchTimeSeconds.toFloat())
        )
        
        // Evitar direcciones hacia enemigos
        for (enemy in worldModel.visibleEnemies) {
            val enemyDir = Vector2D(
                enemy.position.x - worldModel.playerPosition.x,
                enemy.position.y - worldModel.playerPosition.y
            ).normalize()
            
            // Si vamos hacia enemigo, rotar
            val alignment = baseDir.x * enemyDir.x + baseDir.y * enemyDir.y
            if (alignment > 0.5f) {
                // Rotar 90°
                return Vector2D(-baseDir.y, baseDir.x)
            }
        }
        
        return baseDir
    }
    
    /**
     * Planea ruta hacia objetivo.
     */
    fun planRoute(destination: Vector3D): List<Vector3D> {
        val route = mutableListOf<Vector3D>()
        val current = worldModel.playerPosition
        
        // Ruta simple: waypoint intermedio si hay cobertura en el camino
        val coverOnRoute = worldModel.coverPositions
            .filter { it.position.isBetween(current, destination, 0.3f) }
            .minByOrNull { it.distance }
        
        if (coverOnRoute != null && coverOnRoute.distance > 0.1f) {
            route.add(coverOnRoute.position)
        }
        
        route.add(destination)
        plannedRoute = route
        
        return route
    }
    
    /**
     * Evalúa si un tercero (third party) podría intervenir.
     */
    fun predictThirdPartyRisk(position: Vector3D): Float {
        // Analizar posiciones de enemigos recientes
        val recentPositions = worldModel.recentEnemies.values
            .map { it.lastKnownPosition }
        
        if (recentPositions.isEmpty()) return 0.1f
        
        // Calcular si algún enemigo podría ver esa posición
        var risk = 0f
        for (enemyPos in recentPositions) {
            val dist = enemyPos.distanceTo(position)
            if (dist < 0.5f) {
                risk += (0.5f - dist) * 100
            }
        }
        
        return risk.coerceIn(0f, 100f)
    }
    
    /**
     * Adapta estilo según resultados.
     */
    fun adaptFromResult(
        placement: Int,
        kills: Int,
        damageDealt: Float,
        survivalTimeSeconds: Int
    ) {
        // Si morimos temprano por agresión, ser más conservador
        if (placement > 50 && survivalTimeSeconds < 300 && aggressionLevel > 0.6f) {
            aggressionLevel *= 0.8f
            Logger.i(TAG, "Adaptación: Reduciendo agresión a $aggressionLevel por muerte temprana")
        }
        
        // Si ganamos con pocas kills, podemos ser más agresivos
        if (placement <= 5 && kills < 3) {
            aggressionLevel = (aggressionLevel + 0.1f).coerceIn(0f, 1f)
            Logger.i(TAG, "Adaptación: Aumentando agresión a $aggressionLevel por win pasivo")
        }
        
        // Si hicimos mucho daño, estamos en buen nivel
        if (damageDealt > 1000f) {
            Logger.i(TAG, "Adaptación: Daño alto ($damageDealt), manteniendo estilo")
        }
    }
    
    /**
     * Registra decisión para análisis futuro.
     */
    private fun recordDecision(
        decision: StrategicDecision,
        risk: RiskReport,
        phase: MatchPhase
    ) {
        situationHistory.add(SituationRecord(
            timestamp = System.currentTimeMillis(),
            decisionType = decision.type,
            riskScore = risk.totalScore,
            phase = phase,
            playerHealth = worldModel.playerHealth,
            enemiesNearby = worldModel.visibleEnemies.size
        ))
        
        // Limitar historial
        if (situationHistory.size > 100) {
            situationHistory.removeFirst()
        }
    }
    
    /**
     * Obtiene resumen de estrategia actual.
     */
    fun getStrategySummary(): String {
        return buildString {
            append("[Strategic] ")
            append("Strategy: $currentStrategy | ")
            append("Aggression: ${(aggressionLevel * 100).toInt()}% | ")
            append("Survival: ${(survivalPriority * 100).toInt()}% | ")
            append("Target: ${targetDestination?.let { "(${it.x.toInt()},${it.y.toInt()})" } ?: "None"}")
        }
    }
    
    /**
     * Reset completo.
     */
    fun reset() {
        lastUpdateTime = 0L
        currentStrategy = GameStrategy.BALANCED
        targetDestination = null
        plannedRoute.clear()
        routeHistory.clear()
        situationHistory.clear()
        aggressionLevel = 0.5f
        survivalPriority = 0.5f
        encountersCount = 0
        successfulEngagements = 0
        Logger.i(TAG, "StrategicEngine reseteado")
    }
    
    // Getters
    fun getCurrentStrategy(): GameStrategy = currentStrategy
    fun getAggressionLevel(): Float = aggressionLevel
    fun getPlannedRoute(): List<Vector3D> = plannedRoute.toList()
}

// ============================================
// DATA CLASSES
// ============================================

data class StrategicDecision(
    val type: StrategicDecisionType,
    val targetPosition: Vector3D? = null,
    val targetDirection: Vector2D? = null,
    val priority: StrategicPriority,
    val estimatedRisk: Float,
    val timeHorizonSeconds: Int,
    val description: String
)

data class RouteDecision(
    val from: Vector3D,
    val to: Vector3D,
    val viability: com.ffai.assistant.perception.RouteViability,
    val riskScore: Float,
    val timestamp: Long
)

data class SituationRecord(
    val timestamp: Long,
    val decisionType: StrategicDecisionType,
    val riskScore: Float,
    val phase: MatchPhase,
    val playerHealth: Float,
    val enemiesNearby: Int
)

enum class GameStrategy {
    SURVIVAL,           // Priorizar no morir
    POSITIONING,        // Controlar posición
    HUNTING,            // Buscar enemigos
    AGGRESSIVE_LOOT,    // Lootear rápido
    BALANCED            // Balanceado
}

enum class StrategicDecisionType {
    MOVE_TO_SAFETY,
    IMPROVE_POSITION,
    HUNT_ENEMIES,
    LOOT_AREA,
    HOLD_POSITION,
    ROTATE_POSITION,
    RETREAT
}

enum class StrategicPriority {
    CRITICAL,   // Hacer ahora
    HIGH,       // Hacer pronto
    MEDIUM,     // Planificar
    LOW         // Opcional
}

// Helpers
private fun Vector3D.isBetween(start: Vector3D, end: Vector3D, threshold: Float): Boolean {
    val distToStart = this.distanceTo(start)
    val distToEnd = this.distanceTo(end)
    val startToEnd = start.distanceTo(end)
    return kotlin.math.abs(distToStart + distToEnd - startToEnd) < threshold
}

private fun Vector3D.to2D(): Vector2D = Vector2D(x, y)
