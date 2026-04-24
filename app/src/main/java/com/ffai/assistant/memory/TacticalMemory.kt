package com.ffai.assistant.memory

import com.ffai.assistant.perception.*
import com.ffai.assistant.utils.Logger

/**
 * FASE 5: TacticalMemory - Memoria táctica para IA.
 * 
 * Almacena y recupera:
 * - Rutas seguras aprendidas
 * - Patrones de rotación de enemigos
 * - Situaciones reconocidas
 * - Decisiones pasadas y sus resultados
 * 
 * Sistema de learning ligero: contadores de éxitos/fracasos (no backpropagation).
 */
class TacticalMemory {
    
    companion object {
        const val TAG = "TacticalMemory"
        const val MAX_ROUTES = 20
        const val MAX_PATTERNS = 15
        const val MAX_SITUATIONS = 30
        const val MAX_DECISIONS = 100
        const val SIMILARITY_THRESHOLD = 0.15f
    }
    
    // Rutas seguras aprendidas
    private val learnedRoutes: MutableList<SafeRoute> = mutableListOf()
    
    // Patrones de rotación de enemigos
    private val rotationPatterns: MutableMap<String, RotationPattern> = mutableMapOf()
    
    // Templates de situaciones
    private val situationTemplates: MutableList<SituationTemplate> = mutableListOf()
    
    // Historial de decisiones con resultados
    private val decisionHistory: MutableList<DecisionOutcome> = mutableListOf()
    
    // Rutas usadas recientemente (para evitar loops)
    private val recentRoutes: ArrayDeque<String> = ArrayDeque(5)
    
    // Contadores de éxito/fracaso por acción en situación
    private val actionSuccessCounters: MutableMap<String, SuccessCounter> = mutableMapOf()
    
    // Estadísticas globales
    private var totalEncounters = 0
    private var successfulEncounters = 0
    private var averageSurvivalTime = 0f
    
    /**
     * Aprende una nueva ruta segura.
     */
    fun learnRoute(from: Vector3D, to: Vector3D, success: Boolean, travelTimeMs: Long) {
        val routeId = generateRouteId(from, to)
        
        val existing = learnedRoutes.find { it.id == routeId }
        
        if (existing != null) {
            // Actualizar ruta existente
            existing.attempts++
            if (success) {
                existing.successes++
                existing.avgTravelTimeMs = (existing.avgTravelTimeMs * (existing.successes - 1) + travelTimeMs) / existing.successes
            }
            existing.lastUsed = System.currentTimeMillis()
            existing.safetyScore = existing.successes.toFloat() / existing.attempts
        } else {
            // Nueva ruta
            val newRoute = SafeRoute(
                id = routeId,
                from = from,
                to = to,
                attempts = 1,
                successes = if (success) 1 else 0,
                avgTravelTimeMs = if (success) travelTimeMs else 0L,
                lastUsed = System.currentTimeMillis(),
                safetyScore = if (success) 1.0f else 0.0f
            )
            
            learnedRoutes.add(newRoute)
            
            // Limitar tamaño
            if (learnedRoutes.size > MAX_ROUTES) {
                // Eliminar ruta menos usada
                learnedRoutes.minByOrNull { it.lastUsed }?.let { learnedRoutes.remove(it) }
            }
        }
        
        if (success) {
            Logger.d(TAG, "Ruta aprendida: ${routeId}, tiempo: ${travelTimeMs}ms")
        }
    }
    
    /**
     * Obtiene mejor ruta conocida entre dos puntos.
     */
    fun getBestRoute(from: Vector3D, to: Vector3D): SafeRoute? {
        val targetId = generateRouteId(from, to)
        
        return learnedRoutes
            .filter { it.id == targetId || isSimilarRoute(it.from, it.to, from, to) }
            .filter { it.safetyScore > 0.5f } // Solo rutas seguras
            .maxByOrNull { it.safetyScore * 100 - it.avgTravelTimeMs / 1000 }
            .also { route ->
                route?.let { recentRoutes.addLast(it.id) }
                if (recentRoutes.size > 5) recentRoutes.removeFirst()
            }
    }
    
    /**
     * Registra patrón de rotación de enemigo.
     */
    fun recordRotationPattern(enemyId: String, positions: List<Vector3D>, timestamps: List<Long>) {
        if (positions.size < 3) return // Necesitamos mínimo datos
        
        // Calcular patrón
        val pattern = analyzeMovementPattern(positions, timestamps)
        
        rotationPatterns[enemyId] = RotationPattern(
            enemyId = enemyId,
            patternType = pattern,
            lastPositions = positions.takeLast(5),
            lastTimestamps = timestamps.takeLast(5),
            confidence = calculatePatternConfidence(positions),
            recordedAt = System.currentTimeMillis()
        )
        
        // Limitar
        if (rotationPatterns.size > MAX_PATTERNS) {
            rotationPatterns.entries.minByOrNull { it.value.recordedAt }?.let {
                rotationPatterns.remove(it.key)
            }
        }
    }
    
    /**
     * Predice siguiente posición de enemigo basado en patrón.
     */
    fun predictEnemyPosition(enemyId: String, timeMs: Long): Vector3D? {
        val pattern = rotationPatterns[enemyId] ?: return null
        
        if (pattern.confidence < 0.4f) return null
        
        return when (pattern.patternType) {
            MovementPatternType.CIRCULAR -> predictCircular(pattern, timeMs)
            MovementPatternType.LINEAR -> predictLinear(pattern, timeMs)
            MovementPatternType.ZIGZAG -> predictZigzag(pattern, timeMs)
            else -> predictLastDirection(pattern, timeMs)
        }
    }
    
    /**
     * Reconoce situación actual basada en templates.
     */
    fun recognizeSituation(worldModel: TacticalWorldModel): SituationMatch? {
        val current = extractSituationFeatures(worldModel)
        
        // Buscar template más similar
        var bestMatch: SituationTemplate? = null
        var bestSimilarity = 0f
        
        for (template in situationTemplates) {
            val similarity = calculateSimilarity(current, template.features)
            if (similarity > bestSimilarity && similarity > SIMILARITY_THRESHOLD) {
                bestSimilarity = similarity
                bestMatch = template
            }
        }
        
        return bestMatch?.let {
            SituationMatch(
                template = it,
                similarity = bestSimilarity,
                recommendedAction = it.bestAction
            )
        }
    }
    
    /**
     * Crea nuevo template de situación.
     */
    fun learnSituation(worldModel: TacticalWorldModel, actionTaken: String, outcome: Outcome) {
        val features = extractSituationFeatures(worldModel)
        
        // Verificar si similar ya existe
        val existing = situationTemplates.find { 
            calculateSimilarity(features, it.features) > 0.85f 
        }
        
        if (existing != null) {
            // Actualizar template existente
            existing.occurrences++
            updateActionSuccess(existing, actionTaken, outcome)
        } else {
            // Crear nuevo template
            val newTemplate = SituationTemplate(
                id = "sit_${System.currentTimeMillis()}",
                features = features,
                occurrences = 1,
                actionOutcomes = mutableMapOf(actionTaken to ActionStats(1, if (outcome == Outcome.SUCCESS) 1 else 0)),
                bestAction = actionTaken,
                createdAt = System.currentTimeMillis()
            )
            
            situationTemplates.add(newTemplate)
            
            // Limitar
            if (situationTemplates.size > MAX_SITUATIONS) {
                situationTemplates.minByOrNull { it.occurrences }?.let { situationTemplates.remove(it) }
            }
        }
        
        // Actualizar contador global
        val key = "${features.healthRange}_${features.enemyCount}_${features.threatLevel}_$actionTaken"
        val counter = actionSuccessCounters.getOrPut(key) { SuccessCounter() }
        counter.total++
        if (outcome == Outcome.SUCCESS) counter.successes++
    }
    
    /**
     * Registra resultado de decisión.
     */
    fun recordDecision(decision: String, situation: SituationFeatures, result: DecisionResult) {
        decisionHistory.add(DecisionOutcome(
            decision = decision,
            situation = situation,
            result = result,
            timestamp = System.currentTimeMillis()
        ))
        
        // Actualizar estadísticas
        totalEncounters++
        if (result.survived) successfulEncounters++
        averageSurvivalTime = (averageSurvivalTime * (totalEncounters - 1) + result.survivalTimeSeconds) / totalEncounters
        
        // Limitar
        if (decisionHistory.size > MAX_DECISIONS) {
            decisionHistory.removeFirst()
        }
    }
    
    /**
     * Obtiene tasa de éxito de una acción en situación similar.
     */
    fun getActionSuccessRate(action: String, worldModel: TacticalWorldModel): Float {
        val features = extractSituationFeatures(worldModel)
        val key = "${features.healthRange}_${features.enemyCount}_${features.threatLevel}_$action"
        
        val counter = actionSuccessCounters[key]
        return if (counter != null && counter.total > 0) {
            counter.successes.toFloat() / counter.total
        } else 0.5f // Default: 50% desconocido
    }
    
    /**
     * Predice si habrá push de enemigos.
     */
    fun predictEnemyPush(worldModel: TacticalWorldModel): PushPrediction {
        val recentEnemies = worldModel.recentEnemies.size
        val visibleEnemies = worldModel.visibleEnemies.size
        val patterns = rotationPatterns.values
        
        // Heurísticas de push
        val likelihood = when {
            visibleEnemies == 0 && recentEnemies >= 2 -> 0.6f // Desaparecieron, posible flanqueo
            patterns.any { it.patternType == MovementPatternType.AGGRESSIVE } -> 0.7f
            worldModel.playerHealth < 30f -> 0.5f // Jugadores suelen pushar contra débiles
            worldModel.distanceToNearestCover < 0.1f -> 0.3f // Estamos en cover, menos likely
            else -> 0.2f
        }
        
        val estimatedTime = if (likelihood > 0.5f) 5000L else 15000L // ms
        val likelyDirection = predictPushDirection(worldModel)
        
        return PushPrediction(likelihood, estimatedTime, likelyDirection)
    }
    
    /**
     * Adapta estilo al lobby basado en resultados.
     */
    fun adaptToLobby(placement: Int, kills: Int, damage: Float): AdaptationSuggestion {
        val aggressionChange = when {
            kills > 5 && placement <= 10 -> 0.1f // Agresivo funciona
            kills > 3 && placement > 50 -> -0.1f // Agresión no funcionó
            kills < 2 && placement <= 5 -> 0.05f // Pasivo funciona, podemos ser más agresivos
            else -> 0f
        }
        
        val survivalChange = when {
            placement > 70 -> 0.15f // Morimos temprano, más survival
            placement <= 10 -> -0.05f // Good placement, less survival priority needed
            else -> 0f
        }
        
        return AdaptationSuggestion(
            aggressionDelta = aggressionChange,
            survivalDelta = survivalChange,
            recommendedStyle = if (aggressionChange > 0) PlayStyle.AGGRESSIVE 
                              else if (survivalChange > 0) PlayStyle.DEFENSIVE 
                              else PlayStyle.BALANCED
        )
    }
    
    /**
     * Obtiene resumen de memoria.
     */
    fun getSummary(): String {
        return buildString {
            append("[TacticalMemory] ")
            append("Routes: ${learnedRoutes.size} | ")
            append("Patterns: ${rotationPatterns.size} | ")
            append("Situations: ${situationTemplates.size} | ")
            append("Decisions: ${decisionHistory.size} | ")
            append("Win Rate: ${if (totalEncounters > 0) (successfulEncounters * 100 / totalEncounters) else 0}%")
        }
    }
    
    /**
     * Limpia memoria antigua.
     */
    fun cleanup(maxAgeMs: Long = 3600000L) { // 1 hora
        val now = System.currentTimeMillis()
        
        // Limpiar patrones antiguos
        rotationPatterns.entries.removeAll { 
            now - it.value.recordedAt > maxAgeMs 
        }
        
        // Limpiar decisiones antiguas
        decisionHistory.removeAll { 
            now - it.timestamp > maxAgeMs 
        }
    }
    
    /**
     * Reset completo.
     */
    fun reset() {
        learnedRoutes.clear()
        rotationPatterns.clear()
        situationTemplates.clear()
        decisionHistory.clear()
        recentRoutes.clear()
        actionSuccessCounters.clear()
        totalEncounters = 0
        successfulEncounters = 0
        averageSurvivalTime = 0f
        Logger.i(TAG, "TacticalMemory reseteado")
    }
    
    // ============================================
    // MÉTODOS PRIVADOS
    // ============================================
    
    private fun generateRouteId(from: Vector3D, to: Vector3D): String {
        val fx = (from.x * 10).toInt()
        val fy = (from.y * 10).toInt()
        val tx = (to.x * 10).toInt()
        val ty = (to.y * 10).toInt()
        return "route_${fx}_${fy}_${tx}_${ty}"
    }
    
    private fun isSimilarRoute(from1: Vector3D, to1: Vector3D, from2: Vector3D, to2: Vector3D): Boolean {
        val fromDist = from1.distanceTo(from2)
        val toDist = to1.distanceTo(to2)
        return fromDist < 0.1f && toDist < 0.1f
    }
    
    private fun analyzeMovementPattern(positions: List<Vector3D>, timestamps: List<Long>): MovementPatternType {
        if (positions.size < 3) return MovementPatternType.UNKNOWN
        
        // Calcular velocidades
        val velocities = mutableListOf<Float>()
        for (i in 1 until positions.size) {
            val dist = positions[i].distanceTo(positions[i-1])
            val time = (timestamps[i] - timestamps[i-1]) / 1000f
            if (time > 0) velocities.add(dist / time)
        }
        
        if (velocities.isEmpty()) return MovementPatternType.UNKNOWN
        
        val avgVelocity = velocities.average().toFloat()
        val velocityVariance = velocities.map { (it - avgVelocity) * (it - avgVelocity) }.average().toFloat()
        
        return when {
            velocityVariance < 0.01f && avgVelocity > 0.05f -> MovementPatternType.LINEAR
            velocityVariance > 0.05f -> MovementPatternType.ZIGZAG
            positions.size >= 5 && isCircularPattern(positions) -> MovementPatternType.CIRCULAR
            avgVelocity > 0.1f -> MovementPatternType.AGGRESSIVE
            else -> MovementPatternType.RANDOM
        }
    }
    
    private fun isCircularPattern(positions: List<Vector3D>): Boolean {
        // Simplified: check if positions form a loop
        if (positions.size < 5) return false
        
        val center = Vector3D(
            positions.map { it.x }.average().toFloat(),
            positions.map { it.y }.average().toFloat(),
            0f
        )
        
        val distances = positions.map { it.distanceTo(center) }
        val avgDist = distances.average()
        val variance = distances.map { (it - avgDist) * (it - avgDist) }.average()
        
        return variance < 0.02 // Low variance = circular
    }
    
    private fun calculatePatternConfidence(positions: List<Vector3D>): Float {
        return (positions.size / 10f).coerceIn(0.3f, 1.0f)
    }
    
    private fun predictCircular(pattern: RotationPattern, timeMs: Long): Vector3D? {
        val lastPos = pattern.lastPositions.lastOrNull() ?: return null
        return lastPos // Simplified
    }
    
    private fun predictLinear(pattern: RotationPattern, timeMs: Long): Vector3D? {
        if (pattern.lastPositions.size < 2) return null
        
        val last = pattern.lastPositions.last()
        val prev = pattern.lastPositions[pattern.lastPositions.size - 2]
        
        val dx = last.x - prev.x
        val dy = last.y - prev.y
        val dt = (pattern.lastTimestamps.last() - pattern.lastTimestamps[pattern.lastTimestamps.size - 2]) / 1000f
        
        if (dt <= 0) return last
        
        val predictedTime = timeMs / 1000f
        return Vector3D(
            last.x + dx / dt * predictedTime,
            last.y + dy / dt * predictedTime,
            last.z
        )
    }
    
    private fun predictZigzag(pattern: RotationPattern, timeMs: Long): Vector3D? {
        return predictLinear(pattern, timeMs) // Fallback
    }
    
    private fun predictLastDirection(pattern: RotationPattern, timeMs: Long): Vector3D? {
        return pattern.lastPositions.lastOrNull()
    }
    
    private fun extractSituationFeatures(worldModel: TacticalWorldModel): SituationFeatures {
        return SituationFeatures(
            healthRange = (worldModel.playerHealth / 25).toInt().coerceIn(0, 3),
            shieldRange = (worldModel.playerShield / 25).toInt().coerceIn(0, 3),
            enemyCount = worldModel.visibleEnemies.size.coerceIn(0, 5),
            threatLevel = worldModel.immediateThreat.ordinal,
            hasCover = if (worldModel.isInCover) 1 else 0,
            inSafeZone = if (worldModel.isInSafeZone) 1 else 0,
            weaponType = worldModel.currentWeapon.type.ordinal,
            ammoStatus = when {
                worldModel.currentAmmo == 0 -> 0
                worldModel.currentAmmo < 10 -> 1
                worldModel.currentAmmo < 20 -> 2
                else -> 3
            },
            hasHeals = if (worldModel.inventory.hasHeals()) 1 else 0
        )
    }
    
    private fun calculateSimilarity(features1: SituationFeatures, features2: SituationFeatures): Float {
        val weights = floatArrayOf(1.5f, 0.5f, 2.0f, 2.0f, 1.0f, 1.0f, 0.5f, 1.0f, 0.5f)
        val values1 = features1.toFloatArray()
        val values2 = features2.toFloatArray()
        
        var weightedDiff = 0f
        var totalWeight = 0f
        
        for (i in values1.indices) {
            val diff = kotlin.math.abs(values1[i] - values2[i])
            weightedDiff += diff * weights[i]
            totalWeight += weights[i]
        }
        
        return 1f - (weightedDiff / (totalWeight * 3f)) // Normalizar
    }
    
    private fun updateActionSuccess(template: SituationTemplate, action: String, outcome: Outcome) {
        val stats = template.actionOutcomes.getOrPut(action) { ActionStats(0, 0) }
        stats.totalUses++
        if (outcome == Outcome.SUCCESS) stats.successes++
        
        // Recalcular mejor acción
        template.bestAction = template.actionOutcomes.maxByOrNull { 
            it.value.successes.toFloat() / it.value.totalUses 
        }?.key ?: action
    }
    
    private fun predictPushDirection(worldModel: TacticalWorldModel): Vector2D {
        val recent = worldModel.recentEnemies.values
        if (recent.isEmpty()) return Vector2D(0f, 0f)
        
        val avgX = recent.map { it.lastKnownPosition.x }.average().toFloat()
        val avgY = recent.map { it.lastKnownPosition.y }.average().toFloat()
        
        return Vector2D(
            avgX - worldModel.playerPosition.x,
            avgY - worldModel.playerPosition.y
        )
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class SafeRoute(
    val id: String,
    val from: Vector3D,
    val to: Vector3D,
    var attempts: Int,
    var successes: Int,
    var avgTravelTimeMs: Long,
    var lastUsed: Long,
    var safetyScore: Float
)

data class RotationPattern(
    val enemyId: String,
    val patternType: MovementPatternType,
    val lastPositions: List<Vector3D>,
    val lastTimestamps: List<Long>,
    val confidence: Float,
    val recordedAt: Long
)

data class SituationTemplate(
    val id: String,
    val features: SituationFeatures,
    var occurrences: Int,
    val actionOutcomes: MutableMap<String, ActionStats>,
    var bestAction: String,
    val createdAt: Long
)

data class SituationFeatures(
    val healthRange: Int,      // 0-3 (0-25, 25-50, 50-75, 75-100)
    val shieldRange: Int,      // 0-3
    val enemyCount: Int,       // 0-5
    val threatLevel: Int,      // ThreatLevel ordinal
    val hasCover: Int,         // 0-1
    val inSafeZone: Int,       // 0-1
    val weaponType: Int,       // WeaponType ordinal
    val ammoStatus: Int,       // 0-3
    val hasHeals: Int          // 0-1
) {
    fun toFloatArray(): FloatArray {
        return floatArrayOf(
            healthRange.toFloat(), shieldRange.toFloat(), enemyCount.toFloat(),
            threatLevel.toFloat(), hasCover.toFloat(), inSafeZone.toFloat(),
            weaponType.toFloat(), ammoStatus.toFloat(), hasHeals.toFloat()
        )
    }
}

data class DecisionOutcome(
    val decision: String,
    val situation: SituationFeatures,
    val result: DecisionResult,
    val timestamp: Long
)

data class DecisionResult(
    val survived: Boolean,
    val gotKill: Boolean,
    val survivalTimeSeconds: Float,
    val placement: Int
)

data class SituationMatch(
    val template: SituationTemplate,
    val similarity: Float,
    val recommendedAction: String
)

data class PushPrediction(
    val likelihood: Float,
    val estimatedTimeMs: Long,
    val likelyDirection: Vector2D
)

data class AdaptationSuggestion(
    val aggressionDelta: Float,
    val survivalDelta: Float,
    val recommendedStyle: PlayStyle
)

data class ActionStats(
    var totalUses: Int,
    var successes: Int
)

data class SuccessCounter(
    var total: Int = 0,
    var successes: Int = 0
)

enum class MovementPatternType {
    LINEAR,
    CIRCULAR,
    ZIGZAG,
    RANDOM,
    AGGRESSIVE,
    UNKNOWN
}

enum class Outcome {
    SUCCESS,
    FAILURE,
    PARTIAL
}

enum class PlayStyle {
    AGGRESSIVE,
    BALANCED,
    DEFENSIVE
}

private fun Vector2D(x: Float, y: Float) = com.ffai.assistant.perception.Vector2D(x, y)
