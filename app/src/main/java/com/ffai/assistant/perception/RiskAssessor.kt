package com.ffai.assistant.perception

import com.ffai.assistant.utils.Logger

/**
 * FASE 2: RiskAssessor - Evaluación de riesgo táctico.
 * 
 * Calcula:
 * - Exposición del jugador
 * - Amenazas inmediatas
 * - Riesgo de posición
 * - Viabilidad de acciones
 * 
 * Optimizado para Samsung A21S: cálculos simples, no allocations.
 */
class RiskAssessor(private val worldModel: TacticalWorldModel) {
    
    companion object {
        const val CRITICAL_HEALTH_THRESHOLD = 25f
        const val LOW_HEALTH_THRESHOLD = 50f
        const val MEDIUM_HEALTH_THRESHOLD = 75f
        const val CRITICAL_AMMO_THRESHOLD = 5
        const val LOW_AMMO_THRESHOLD = 10
    }
    
    /**
     * Evaluación completa de riesgo actual.
     */
    fun assessRisk(): RiskReport {
        val exposure = assessExposure()
        val threatRisk = assessThreatRisk()
        val positionRisk = assessPositionRisk()
        val resourceRisk = assessResourceRisk()
        
        val totalRisk = (exposure.score + threatRisk.score + positionRisk.score + resourceRisk.score) / 4f
        
        return RiskReport(
            totalScore = totalRisk,
            exposure = exposure,
            threatRisk = threatRisk,
            positionRisk = positionRisk,
            resourceRisk = resourceRisk,
            isCritical = totalRisk > 75f,
            isHigh = totalRisk > 50f,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Evalúa exposición del jugador (sin cobertura).
     */
    private fun assessExposure(): ExposureAssessment {
        var score = 0f
        val factors = mutableListOf<String>()
        
        // Sin cobertura cercana
        if (worldModel.distanceToNearestCover > 0.3f) {
            score += 20f
            factors.add("No cover nearby")
        } else if (worldModel.distanceToNearestCover > 0.15f) {
            score += 10f
            factors.add("Cover far")
        }
        
        // Cobertura comprometida
        if (worldModel.isCoverCompromised) {
            score += 30f
            factors.add("Cover compromised")
        }
        
        // En campo abierto (no en cover)
        if (!worldModel.isInCover) {
            score += 15f
            factors.add("Not in cover")
        }
        
        // Visible a enemigos
        val visibleEnemies = worldModel.visibleEnemies.size
        if (visibleEnemies > 0) {
            score += visibleEnemies * 10f
            factors.add("Visible to $visibleEnemies enemies")
        }
        
        // Postura expuesta
        if (!worldModel.isCrouching && visibleEnemies > 0) {
            score += 10f
            factors.add("Standing in combat")
        }
        
        return ExposureAssessment(
            score = score.coerceIn(0f, 100f),
            isExposed = score > 40f,
            factors = factors
        )
    }
    
    /**
     * Evalúa amenazas inmediatas.
     */
    private fun assessThreatRisk(): ThreatRiskAssessment {
        var score = 0f
        val threats = mutableListOf<String>()
        val immediateActions = mutableListOf<String>()
        
        // Enemigos cercanos
        val closeEnemies = worldModel.visibleEnemies.count { 
            it.distanceEstimate < 0.2f 
        }
        if (closeEnemies > 0) {
            score += closeEnemies * 25f
            threats.add("$closeEnemies enemies very close")
            immediateActions.add("CREATE DISTANCE")
        }
        
        // Enemigos con ángulo favorable
        val threateningEnemies = worldModel.visibleEnemies.count {
            it.threatLevel >= ThreatLevel.HIGH
        }
        if (threateningEnemies > 0) {
            score += threateningEnemies * 20f
            threats.add("$threateningEnemies high-threat enemies")
        }
        
        // Enemigos desconocidos (recientes pero no visibles)
        val hiddenEnemies = worldModel.recentEnemies.size - worldModel.visibleEnemies.size
        if (hiddenEnemies > 0) {
            score += hiddenEnemies * 5f
            threats.add("$hiddenEnemies enemies nearby but hidden")
        }
        
        // Ratio desfavorable
        if (worldModel.visibleEnemies.size > 1) {
            score += (worldModel.visibleEnemies.size - 1) * 15f
            threats.add("Outnumbered ${worldModel.visibleEnemies.size}:1")
        }
        
        // Fuego inminente
        if (worldModel.immediateThreat == ThreatLevel.CRITICAL) {
            score += 40f
            threats.add("CRITICAL THREAT")
            immediateActions.add("FIGHT OR FLIGHT NOW")
        }
        
        return ThreatRiskAssessment(
            score = score.coerceIn(0f, 100f),
            isThreatened = score > 30f,
            isCritical = score > 70f,
            threats = threats,
            recommendedActions = immediateActions
        )
    }
    
    /**
     * Evalúa riesgo de posición (zona, terreno).
     */
    private fun assessPositionRisk(): PositionRiskAssessment {
        var score = 0f
        val issues = mutableListOf<String>()
        
        // Fuera de zona segura
        if (!worldModel.isInSafeZone) {
            val distance = worldModel.distanceToZoneEdge
            score += 20f + distance * 20f
            issues.add("Outside safe zone (dist: ${(distance * 100).toInt()}%)")
            
            if (worldModel.isZoneShrinking) {
                score += 15f
                issues.add("Zone is shrinking")
            }
        }
        
        // Mal ángulo respecto a zona
        // Si tenemos que correr mucho para llegar a zona = riesgo
        
        // En zona de riesgo (ej: puente, campo abierto)
        if (worldModel.distanceToNearestCover > 0.4f) {
            score += 10f
            issues.add("Exposed terrain")
        }
        
        // Riesgo de ser pinched (entre zona y enemigos)
        if (!worldModel.isInSafeZone && worldModel.visibleEnemies.isNotEmpty()) {
            score += 25f
            issues.add("Pinched between zone and enemies")
        }
        
        return PositionRiskAssessment(
            score = score.coerceIn(0f, 100f),
            isInDangerousPosition = score > 40f,
            needsRepositioning = score > 30f,
            issues = issues
        )
    }
    
    /**
     * Evalúa riesgo de recursos (salud, munición).
     */
    private fun assessResourceRisk(): ResourceRiskAssessment {
        var score = 0f
        val concerns = mutableListOf<String>()
        val needs = mutableListOf<ResourceNeed>()
        
        // Salud baja
        when {
            worldModel.playerHealth < CRITICAL_HEALTH_THRESHOLD -> {
                score += 40f
                concerns.add("CRITICAL HEALTH (${worldModel.playerHealth.toInt()}%)")
                needs.add(ResourceNeed.HEAL_URGENT)
            }
            worldModel.playerHealth < LOW_HEALTH_THRESHOLD -> {
                score += 25f
                concerns.add("Low health (${worldModel.playerHealth.toInt()}%)")
                needs.add(ResourceNeed.HEAL_SOON)
            }
            worldModel.playerHealth < MEDIUM_HEALTH_THRESHOLD -> {
                score += 10f
                concerns.add("Below full health")
            }
        }
        
        // Sin heals disponibles
        if (worldModel.playerHealth < LOW_HEALTH_THRESHOLD && !worldModel.inventory.hasHeals()) {
            score += 15f
            concerns.add("No heals available")
            needs.add(ResourceNeed.FIND_HEALS)
        }
        
        // Munición baja
        when {
            worldModel.currentAmmo == 0 && worldModel.totalAmmo == 0 -> {
                score += 35f
                concerns.add("NO AMMO")
                needs.add(ResourceNeed.FIND_AMMO)
            }
            worldModel.currentAmmo == 0 -> {
                score += 25f
                concerns.add("Magazine empty")
                needs.add(ResourceNeed.RELOAD)
            }
            worldModel.currentAmmo < CRITICAL_AMMO_THRESHOLD -> {
                score += 15f
                concerns.add("Low magazine (${worldModel.currentAmmo})")
                needs.add(ResourceNeed.RELOAD)
            }
        }
        
        // Escudo bajo (si el juego tiene shields)
        if (worldModel.playerShield < 25f) {
            score += 5f
            concerns.add("Low shield")
        }
        
        return ResourceRiskAssessment(
            score = score.coerceIn(0f, 100f),
            isResourceCritical = score > 30f,
            concerns = concerns,
            resourceNeeds = needs
        )
    }
    
    /**
     * Evalúa si es seguro realizar una acción específica.
     */
    fun isSafeToPerform(action: SafeAction): Boolean {
        return when (action) {
            SafeAction.HEAL -> {
                worldModel.visibleEnemies.isEmpty() || 
                (worldModel.isInCover && worldModel.playerHealth < 30f)
            }
            SafeAction.RELOAD -> {
                worldModel.visibleEnemies.isEmpty() || 
                worldModel.currentAmmo > 5 ||
                worldModel.distanceToNearestCover < 0.1f
            }
            SafeAction.LOOT -> {
                worldModel.visibleEnemies.isEmpty() &&
                worldModel.recentEnemies.isEmpty() &&
                worldModel.isInSafeZone &&
                worldModel.immediateThreat < ThreatLevel.MEDIUM
            }
            SafeAction.REVIVE_TEAMMATE -> {
                worldModel.visibleEnemies.isEmpty() &&
                worldModel.isInCover &&
                worldModel.immediateThreat < ThreatLevel.MEDIUM
            }
            SafeAction.ROTATE_OPENLY -> {
                worldModel.visibleEnemies.isEmpty() &&
                worldModel.immediateThreat == ThreatLevel.NONE
            }
        }
    }
    
    /**
     * Calcula viabilidad de ir a un punto específico.
     */
    fun assessRouteViability(destination: Vector3D): RouteViability {
        val currentPos = worldModel.playerPosition
        val distance = currentPos.distanceTo(destination)
        
        var riskScore = 0f
        val hazards = mutableListOf<String>()
        
        // Verificar enemigos en el camino
        for (enemy in worldModel.visibleEnemies) {
            val enemyPos = enemy.position
            // Distancia a la línea de ruta (simplified)
            val distToRoute = approximateDistanceToRoute(enemyPos, currentPos, destination)
            
            if (distToRoute < 0.2f) {
                riskScore += 30f
                hazards.add("Enemy near route")
            }
        }
        
        // Verificar si pasa por fuera de zona
        // (simplified: asumir que destino está en zona segura)
        
        // Distancia total
        if (distance > 0.5f) {
            riskScore += distance * 10f
            hazards.add("Long route")
        }
        
        // Cobertura en destino
        val hasCoverAtDestination = worldModel.coverPositions.any {
            it.position.distanceTo(destination) < 0.1f
        }
        if (!hasCoverAtDestination) {
            riskScore += 15f
            hazards.add("No cover at destination")
        }
        
        val viability = when {
            riskScore < 20f -> RouteViability.SAFE
            riskScore < 40f -> RouteViability.VIABLE
            riskScore < 60f -> RouteViability.RISKY
            else -> RouteViability.DANGEROUS
        }
        
        return RouteViabilityAssessment(
            viability = viability,
            riskScore = riskScore,
            estimatedTimeMs = (distance * 2000).toLong(), // Aprox 2s por unidad de distancia
            hazards = hazards
        )
    }
    
    /**
     * Distancia aproximada de punto a línea (ruta).
     */
    private fun approximateDistanceToRoute(
        point: Vector3D,
        lineStart: Vector3D,
        lineEnd: Vector3D
    ): Float {
        val lineVectorX = lineEnd.x - lineStart.x
        val lineVectorY = lineEnd.y - lineStart.y
        val lineLength = kotlin.math.hypot(lineVectorX, lineVectorY)
        
        if (lineLength == 0f) return point.distanceTo(lineStart)
        
        // Proyección escalar
        val t = ((point.x - lineStart.x) * lineVectorX + 
                 (point.y - lineStart.y) * lineVectorY) / (lineLength * lineLength)
        
        val clampedT = t.coerceIn(0f, 1f)
        
        val closestX = lineStart.x + clampedT * lineVectorX
        val closestY = lineStart.y + clampedT * lineVectorY
        
        return kotlin.math.hypot(point.x - closestX, point.y - closestY)
    }
    
    /**
     * Obtiene resumen rápido de riesgo.
     */
    fun getQuickSummary(): String {
        val report = assessRisk()
        return "Risk: ${report.totalScore.toInt()}% [E:${report.exposure.score.toInt()} T:${report.threatRisk.score.toInt()} P:${report.positionRisk.score.toInt()} R:${report.resourceRisk.score.toInt()}]"
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class RiskReport(
    val totalScore: Float,
    val exposure: ExposureAssessment,
    val threatRisk: ThreatRiskAssessment,
    val positionRisk: PositionRiskAssessment,
    val resourceRisk: ResourceRiskAssessment,
    val isCritical: Boolean,
    val isHigh: Boolean,
    val timestamp: Long
)

data class ExposureAssessment(
    val score: Float,
    val isExposed: Boolean,
    val factors: List<String>
)

data class ThreatRiskAssessment(
    val score: Float,
    val isThreatened: Boolean,
    val isCritical: Boolean,
    val threats: List<String>,
    val recommendedActions: List<String>
)

data class PositionRiskAssessment(
    val score: Float,
    val isInDangerousPosition: Boolean,
    val needsRepositioning: Boolean,
    val issues: List<String>
)

data class ResourceRiskAssessment(
    val score: Float,
    val isResourceCritical: Boolean,
    val concerns: List<String>,
    val resourceNeeds: List<ResourceNeed>
)

data class RouteViabilityAssessment(
    val viability: RouteViability,
    val riskScore: Float,
    val estimatedTimeMs: Long,
    val hazards: List<String>
)

enum class SafeAction {
    HEAL,
    RELOAD,
    LOOT,
    REVIVE_TEAMMATE,
    ROTATE_OPENLY
}

enum class ResourceNeed {
    HEAL_URGENT,
    HEAL_SOON,
    RELOAD,
    FIND_HEALS,
    FIND_AMMO,
    FIND_SHIELD
}

enum class RouteViability {
    SAFE,       // Ruta clara, poco riesgo
    VIABLE,     // Algo de riesgo pero manejable
    RISKY,      // Riesgo significativo
    DANGEROUS   // Probablemente mortal
}
