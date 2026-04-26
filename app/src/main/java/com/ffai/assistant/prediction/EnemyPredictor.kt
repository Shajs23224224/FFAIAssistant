package com.ffai.assistant.prediction

import com.ffai.assistant.memory.HierarchicalMemorySystem
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * EnemyPredictor - Sistema de predicción de comportamiento enemigo
 * Predice movimientos, trayectorias, intenciones y rotaciones
 * Ventanas de predicción: 0.5s, 2s, 5s
 */
class EnemyPredictor(private val memory: HierarchicalMemorySystem) {

    companion object {
        const val TAG = "EnemyPredictor"
        
        // Ventanas de predicción en ms
        const val WINDOW_SHORT_MS = 500L      // Reacción inmediata
        const val WINDOW_MEDIUM_MS = 2000L  // Táctico
        const val WINDOW_LONG_MS = 5000L    // Estratégico
        
        // Factores de confianza
        const val CONFIDENCE_HIGH = 0.8f
        const val CONFIDENCE_MEDIUM = 0.5f
        const val CONFIDENCE_LOW = 0.2f
    }

    // ============================================
    // MODELOS DE PREDICCIÓN POR ENEMIGO
    // ============================================
    
    private val enemyModels = ConcurrentHashMap<EnemyId, EnemyPredictionModel>()
    
    // Histórico de predicciones para evaluar accuracy
    private val predictionHistory = mutableListOf<PredictionRecord>()

    // ============================================
    // PREDICCIÓN PRINCIPAL
    // ============================================
    
    /**
     * Predice estado futuro de un enemigo
     */
    fun predictEnemyState(
        enemyId: EnemyId,
        timeWindowMs: Long
    ): EnemyPrediction {
        val model = getOrCreateModel(enemyId)
        val currentState = model.getCurrentState()
        
        return when {
            // Ventana corta: extrapolación cinemática
            timeWindowMs <= WINDOW_SHORT_MS -> {
                predictShortTerm(currentState, timeWindowMs)
            }
            
            // Ventana media: modelo de comportamiento
            timeWindowMs <= WINDOW_MEDIUM_MS -> {
                predictMediumTerm(model, currentState, timeWindowMs)
            }
            
            // Ventana larga: patrones y estrategia
            else -> predictLongTerm(model, currentState, timeWindowMs)
        }
    }
    
    /**
     * Predice trayectoria completa para aim leading
     */
    fun predictTrajectory(
        enemyId: EnemyId,
        durationMs: Long,
        timeStepMs: Long = 50L
    ): TrajectoryPrediction {
        val model = getOrCreateModel(enemyId)
        val trajectory = mutableListOf<PredictedPosition>()
        
        var currentPos = model.getCurrentState().position
        var currentVel = model.getCurrentState().velocity
        var confidence = 1.0f
        
        val steps = (durationMs / timeStepMs).toInt()
        
        for (step in 0 until steps) {
            val timeMs = step * timeStepMs
            
            // Aplicar física básica + comportamiento
            val predictedPos = extrapolatePosition(
                currentPos, 
                currentVel, 
                timeStepMs,
                model.getBehaviorType()
            )
            
            // Decrecer confianza con el tiempo
            confidence *= 0.98f
            
            trajectory.add(PredictedPosition(
                position = predictedPos,
                timestamp = System.currentTimeMillis() + timeMs,
                confidence = confidence,
                velocity = currentVel
            ))
            
            currentPos = predictedPos
        }
        
        return TrajectoryPrediction(
            positions = trajectory,
            overallConfidence = trajectory.map { it.confidence }.average().toFloat()
        )
    }

    // ============================================
    // PREDICCIÓN CORTO PLAZO (0-500ms)
    // ============================================
    
    private fun predictShortTerm(
        currentState: EnemyState,
        timeWindowMs: Long
    ): EnemyPrediction {
        // Extrapolación lineal con inercia
        val timeSeconds = timeWindowMs / 1000f
        
        val predictedX = currentState.position.x + 
                        currentState.velocity.x * timeSeconds
        val predictedY = currentState.position.y + 
                        currentState.velocity.y * timeSeconds
        
        // Ajustar por aceleración si está disponible
        val predictedVelX = currentState.velocity.x + 
                           currentState.acceleration.x * timeSeconds
        val predictedVelY = currentState.velocity.y + 
                           currentState.acceleration.y * timeSeconds
        
        return EnemyPrediction(
            position = Position(predictedX, predictedY),
            velocity = Vector2f(predictedVelX, predictedVelY),
            action = PredictedAction.CONTINUE_MOVEMENT,
            confidence = CONFIDENCE_HIGH,
            timeWindow = timeWindowMs,
            reasoning = "Extrapolación cinemática"
        )
    }

    // ============================================
    // PREDICCIÓN MEDIO PLAZO (500ms - 2s)
    // ============================================
    
    private fun predictMediumTerm(
        model: EnemyPredictionModel,
        currentState: EnemyState,
        timeWindowMs: Long
    ): EnemyPrediction {
        val behavior = model.getBehaviorType()
        val lastSeenCover = model.lastSeenCover
        
        return when (behavior) {
            BehaviorType.AGGRESSIVE_PUSH -> {
                // Predecir push hacia jugador
                val pushVector = calculatePushVector(currentState)
                EnemyPrediction(
                    position = extrapolateWithVector(currentState, pushVector, timeWindowMs),
                    velocity = pushVector,
                    action = PredictedAction.PUSH,
                    confidence = CONFIDENCE_MEDIUM,
                    timeWindow = timeWindowMs,
                    reasoning = "Patrón agresivo detectado"
                )
            }
            
            BehaviorType.DEFENSIVE_HOLD -> {
                // Predecir quedarse en cobertura
                val coverPos = lastSeenCover ?: currentState.position
                EnemyPrediction(
                    position = coverPos,
                    velocity = Vector2f(0f, 0f),
                    action = PredictedAction.HOLD_POSITION,
                    confidence = CONFIDENCE_MEDIUM + 0.1f,
                    timeWindow = timeWindowMs,
                    reasoning = "Comportamiento defensivo"
                )
            }
            
            BehaviorType.ROTATING -> {
                // Predecir rotación/flanqueo
                val rotationTarget = model.getRotationTarget()
                EnemyPrediction(
                    position = interpolateTowards(currentState.position, rotationTarget, 0.7f),
                    velocity = calculateRotationVelocity(currentState, rotationTarget),
                    action = PredictedAction.FLANK,
                    confidence = CONFIDENCE_MEDIUM - 0.1f,
                    timeWindow = timeWindowMs,
                    reasoning = "Patrón de rotación"
                )
            }
            
            else -> predictShortTerm(currentState, timeWindowMs) // Fallback
        }
    }

    // ============================================
    // PREDICCIÓN LARGO PLAZO (2s - 5s)
    // ============================================
    
    private fun predictLongTerm(
        model: EnemyPredictionModel,
        currentState: EnemyState,
        timeWindowMs: Long
    ): EnemyPrediction {
        // Usar patrones históricos y conocimiento del mapa
        val historicalPattern = findSimilarHistoricalPattern(model)
        
        return if (historicalPattern != null && historicalPattern.confidence > 0.6f) {
            EnemyPrediction(
                position = historicalPattern.predictedEndPosition,
                velocity = Vector2f(0f, 0f),
                action = historicalPattern.mostLikelyAction,
                confidence = CONFIDENCE_LOW + (historicalPattern.confidence * 0.2f),
                timeWindow = timeWindowMs,
                reasoning = "Basado en patrón histórico (${(historicalPattern.confidence * 100).toInt()}% match)"
            )
        } else {
            // Fallback a predicción física con decaimiento
            val shortPrediction = predictShortTerm(currentState, timeWindowMs)
            shortPrediction.copy(
                confidence = CONFIDENCE_LOW,
                reasoning = "Sin patrones históricos confiables"
            )
        }
    }

    // ============================================
    // PREDICCIÓN ESPECIALIZADA
    // ============================================
    
    /**
     * Predice punto de aim con compensación de velocidad
     */
    fun predictAimPoint(
        enemyId: EnemyId,
        bulletTravelTimeMs: Float,
        myPosition: Position
    ): AimPrediction {
        val enemyModel = getOrCreateModel(enemyId)
        val enemyState = enemyModel.getCurrentState()
        
        // Predicción básica
        val timeSeconds = bulletTravelTimeMs / 1000f
        val basePredictedPos = Position(
            enemyState.position.x + enemyState.velocity.x * timeSeconds,
            enemyState.position.y + enemyState.velocity.y * timeSeconds
        )
        
        // Ajuste por comportamiento
        val behaviorAdjustment = when (enemyModel.getBehaviorType()) {
            BehaviorType.STRAFING -> {
                // El enemigo zigzaguea, aumentar incertidumbre
                Vector2f(
                    (Math.random().toFloat() - 0.5f) * 20f,
                    (Math.random().toFloat() - 0.5f) * 20f
                )
            }
            BehaviorType.JUMPING -> {
                // Compensar altura (si fuera 3D)
                Vector2f(0f, -10f)  // Aim un poco más arriba
            }
            else -> Vector2f(0f, 0f)
        }
        
        val finalPredictedPos = Position(
            basePredictedPos.x + behaviorAdjustment.x,
            basePredictedPos.y + behaviorAdjustment.y
        )
        
        // Calcular confianza basada en consistencia de movimiento
        val consistency = calculateMovementConsistency(enemyModel)
        
        return AimPrediction(
            targetPosition = finalPredictedPos,
            confidence = consistency,
            leadAmount = Vector2f(
                enemyState.velocity.x * timeSeconds,
                enemyState.velocity.y * timeSeconds
            ),
            recommendedAimOffset = behaviorAdjustment
        )
    }
    
    /**
     * Predice si el enemigo está preparando emboscada
     */
    fun predictAmbushRisk(enemyId: EnemyId, myPlannedPath: List<Position>): Float {
        val model = getOrCreateModel(enemyId)
        
        // Factores de emboscada:
        // 1. Enemigo en cobertura cercana al camino
        // 2. Ha dejado de moverse recientemente
        // 3. Orientación hacia el camino
        
        var riskScore = 0f
        
        // Verificar proximidad a puntos de emboscada comunes
        myPlannedPath.forEach { point ->
            val distance = calculateDistance(model.getCurrentState().position, point)
            if (distance < 50f) {  // Dentro de rango de emboscada
                riskScore += 0.3f
            }
        }
        
        // Ajustar por comportamiento pasivo reciente
        if (model.getBehaviorType() == BehaviorType.DEFENSIVE_HOLD) {
            riskScore += 0.2f
        }
        
        return riskScore.coerceIn(0f, 1f)
    }
    
    /**
     * Predice intención del enemigo
     */
    fun predictIntention(enemyId: EnemyId): EnemyIntention {
        val model = getOrCreateModel(enemyId)
        val behavior = model.getBehaviorType()
        val recentActions = model.recentActions
        
        return when {
            // Analizar secuencia de acciones
            recentActions.takeLast(3).all { it == EnemyAction.MOVING_CLOSER } ->
                EnemyIntention.PUSHING
            
            recentActions.takeLast(2).all { it == EnemyAction.SHOOTING } &&
            behavior == BehaviorType.AGGRESSIVE_PUSH ->
                EnemyIntention.COMMITTING_FIGHT
            
            recentActions.contains(EnemyAction.USING_COVER) &&
            recentActions.last() == EnemyAction.HEALING ->
                EnemyIntention.RECOVERING
            
            behavior == BehaviorType.RETREATING ->
                EnemyIntention.DISENGAGING
            
            else -> EnemyIntention.UNKNOWN
        }
    }

    // ============================================
    // ACTUALIZACIÓN DE MODELOS
    // ============================================
    
    fun updateEnemyState(
        enemyId: EnemyId,
        position: Position,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val model = getOrCreateModel(enemyId)
        model.updatePosition(position, timestamp)
        
        // Actualizar memoria - usando tipos locales (desacoplado de memory por ahora)
        // TODO: Implementar adaptador si se necesita integración con HierarchicalMemorySystem
        // memory.shortTerm.updateEnemyPosition(...)
    }
    
    fun recordEnemyAction(enemyId: EnemyId, action: EnemyAction) {
        val model = getOrCreateModel(enemyId)
        model.recordAction(action)
    }
    
    fun onEnemyEliminated(enemyId: EnemyId) {
        val model = enemyModels.remove(enemyId)
        model?.let {
            // Guardar patrón en memoria a largo plazo para futuras predicciones
            savePatternToMemory(it)
        }
    }

    // ============================================
    // MÉTODOS PRIVADOS
    // ============================================
    
    private fun getOrCreateModel(enemyId: EnemyId): EnemyPredictionModel {
        return enemyModels.getOrPut(enemyId) { EnemyPredictionModel(enemyId) }
    }
    
    private fun extrapolatePosition(
        position: Position,
        velocity: Vector2f,
        timeMs: Long,
        behaviorType: BehaviorType
    ): Position {
        val timeSeconds = timeMs / 1000f
        
        // Aplicar decaimiento de velocidad según comportamiento
        val velocityDecay = when (behaviorType) {
            BehaviorType.AGGRESSIVE_PUSH -> 1.0f  // Sin decaimiento
            BehaviorType.DEFENSIVE_HOLD -> 0.5f    // Decaimiento rápido
            else -> 0.8f
        }
        
        return Position(
            position.x + velocity.x * timeSeconds * velocityDecay,
            position.y + velocity.y * timeSeconds * velocityDecay
        )
    }
    
    private fun calculatePushVector(currentState: EnemyState): Vector2f {
        // Vector hacia el jugador (asumiendo que conocemos nuestra posición)
        return Vector2f(
            currentState.velocity.x * 1.5f,
            currentState.velocity.y * 1.5f
        )
    }
    
    private fun interpolateTowards(from: Position, to: Position, t: Float): Position {
        return Position(
            from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t
        )
    }
    
    private fun calculateRotationVelocity(currentState: EnemyState, target: Position): Vector2f {
        val dx = target.x - currentState.position.x
        val dy = target.y - currentState.position.y
        val distance = sqrt(dx * dx + dy * dy)
        
        return if (distance > 0) {
            Vector2f(
                (dx / distance) * 100f,  // Velocidad normalizada a 100 unidades/s
                (dy / distance) * 100f
            )
        } else {
            Vector2f(0f, 0f)
        }
    }
    
    private fun calculateDistance(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
    
    private fun calculateMovementConsistency(model: EnemyPredictionModel): Float {
        val velocities = model.velocityHistory
        if (velocities.size < 2) return 0.5f
        
        // Calcular varianza de velocidades
        val meanX = velocities.map { it.x }.average()
        val meanY = velocities.map { it.y }.average()
        
        val varianceX = velocities.map { (it.x - meanX) * (it.x - meanX) }.average()
        val varianceY = velocities.map { (it.y - meanY) * (it.y - meanY) }.average()
        
        // Menor varianza = mayor consistencia
        val totalVariance = (varianceX + varianceY).toFloat()
        return (1f / (1f + totalVariance / 1000f)).coerceIn(0.3f, 0.95f)
    }
    
    private fun extrapolateWithVector(
        state: EnemyState,
        vector: Vector2f,
        timeMs: Long
    ): Position {
        val timeSeconds = timeMs / 1000f
        return Position(
            state.position.x + vector.x * timeSeconds,
            state.position.y + vector.y * timeSeconds
        )
    }
    
    private fun findSimilarHistoricalPattern(model: EnemyPredictionModel): HistoricalPattern? {
        // Buscar en memoria a largo plazo patrones similares
        // Placeholder - implementación real usaría la memoria jerárquica
        return null
    }
    
    private fun savePatternToMemory(model: EnemyPredictionModel) {
        // Guardar patrón de comportamiento en memoria para futuras partidas
        Logger.d(TAG, "Guardando patrón de enemigo ${model.enemyId}")
    }

    // ============================================
    // RESET
    // ============================================
    
    fun reset() {
        enemyModels.clear()
        predictionHistory.clear()
        Logger.i(TAG, "EnemyPredictor reseteado")
    }
}

// ============================================
// MODELO DE PREDICCIÓN INDIVIDUAL
// ============================================

class EnemyPredictionModel(val enemyId: EnemyId) {
    
    private val positionHistory = mutableListOf<TimestampedPosition>()
    val velocityHistory = mutableListOf<Vector2f>()
    val recentActions = mutableListOf<EnemyAction>()
    
    var lastSeenCover: Position? = null
    private var behaviorType: BehaviorType = BehaviorType.UNKNOWN
    private var lastUpdateTime = 0L
    
    fun updatePosition(position: Position, timestamp: Long) {
        // Calcular velocidad desde última posición
        if (positionHistory.isNotEmpty()) {
            val lastPos = positionHistory.last()
            val timeDelta = (timestamp - lastPos.timestamp) / 1000f
            
            if (timeDelta > 0) {
                val velocity = Vector2f(
                    (position.x - lastPos.position.x) / timeDelta,
                    (position.y - lastPos.position.y) / timeDelta
                )
                velocityHistory.add(velocity)
                if (velocityHistory.size > 20) velocityHistory.removeAt(0)
            }
        }
        
        positionHistory.add(TimestampedPosition(position, timestamp))
        if (positionHistory.size > 50) positionHistory.removeAt(0)
        
        lastUpdateTime = timestamp
        
        // Actualizar tipo de comportamiento
        updateBehaviorType()
    }
    
    fun recordAction(action: EnemyAction) {
        recentActions.add(action)
        if (recentActions.size > 10) recentActions.removeAt(0)
    }
    
    fun getCurrentState(): EnemyState {
        val currentPos = positionHistory.lastOrNull()?.position ?: Position(0f, 0f)
        val currentVel = velocityHistory.lastOrNull() ?: Vector2f(0f, 0f)
        
        // Calcular aceleración
        val acceleration = if (velocityHistory.size >= 2) {
            val lastVel = velocityHistory.last()
            val prevVel = velocityHistory[velocityHistory.size - 2]
            Vector2f(
                lastVel.x - prevVel.x,
                lastVel.y - prevVel.y
            )
        } else Vector2f(0f, 0f)
        
        return EnemyState(
            position = currentPos,
            velocity = currentVel,
            acceleration = acceleration,
            lastUpdate = lastUpdateTime
        )
    }
    
    fun getBehaviorType(): BehaviorType = behaviorType
    
    fun getRotationTarget(): Position {
        // Placeholder: en implementación real, usaría conocimiento del mapa
        return Position(0f, 0f)
    }
    
    private fun updateBehaviorType() {
        if (velocityHistory.size < 3) return
        
        val avgSpeed = velocityHistory.map { sqrt(it.x * it.x + it.y * it.y) }.average()
        val speedVariance = velocityHistory.map { 
            val speed = sqrt(it.x * it.x + it.y * it.y)
            (speed - avgSpeed) * (speed - avgSpeed)
        }.average()
        
        behaviorType = when {
            avgSpeed > 150 && speedVariance < 500 -> BehaviorType.AGGRESSIVE_PUSH
            avgSpeed < 20 -> BehaviorType.DEFENSIVE_HOLD
            speedVariance > 2000 -> BehaviorType.STRAFING
            avgSpeed > 100 && positionHistory.size > 5 && 
                isCircularMovement() -> BehaviorType.ROTATING
            avgSpeed > 100 -> BehaviorType.PATROLLING
            else -> BehaviorType.UNKNOWN
        }
    }
    
    private fun isCircularMovement(): Boolean {
        // Simplificación: detectar si hay cambios de dirección frecuentes
        if (velocityHistory.size < 5) return false
        
        var directionChanges = 0
        for (i in 1 until velocityHistory.size) {
            val dot = velocityHistory[i-1].x * velocityHistory[i].x + 
                     velocityHistory[i-1].y * velocityHistory[i].y
            if (dot < 0) directionChanges++  // Producto punto negativo = cambio de dirección
        }
        
        return directionChanges >= 3
    }
}

// ============================================
// DATA CLASSES Y ENUMS
// ============================================

@JvmInline
value class EnemyId(val id: String)

data class Position(val x: Float, val y: Float, val z: Float = 0f)
data class Vector2f(val x: Float, val y: Float)
data class TimestampedPosition(val position: Position, val timestamp: Long)

data class EnemyState(
    val position: Position,
    val velocity: Vector2f,
    val acceleration: Vector2f,
    val lastUpdate: Long
)

data class EnemyPrediction(
    val position: Position,
    val velocity: Vector2f,
    val action: PredictedAction,
    val confidence: Float,
    val timeWindow: Long,
    val reasoning: String
)

data class TrajectoryPrediction(
    val positions: List<PredictedPosition>,
    val overallConfidence: Float
)

data class PredictedPosition(
    val position: Position,
    val timestamp: Long,
    val confidence: Float,
    val velocity: Vector2f
)

data class AimPrediction(
    val targetPosition: Position,
    val confidence: Float,
    val leadAmount: Vector2f,
    val recommendedAimOffset: Vector2f
)

enum class PredictedAction {
    CONTINUE_MOVEMENT,
    PUSH,
    HOLD_POSITION,
    FLANK,
    RETREAT,
    TAKE_COVER,
    UNKNOWN
}

enum class BehaviorType {
    UNKNOWN,
    AGGRESSIVE_PUSH,
    DEFENSIVE_HOLD,
    STRAFING,
    ROTATING,
    PATROLLING,
    JUMPING,
    RETREATING
}

enum class EnemyAction {
    MOVING_CLOSER,
    MOVING_AWAY,
    SHOOTING,
    USING_COVER,
    HEALING,
    RELOADING,
    IDLE
}

enum class EnemyIntention {
    PUSHING,
    COMMITTING_FIGHT,
    RECOVERING,
    DISENGAGING,
    UNKNOWN
}

data class HistoricalPattern(
    val predictedEndPosition: Position,
    val mostLikelyAction: PredictedAction,
    val confidence: Float,
    val matchCount: Int
)

data class PredictionRecord(
    val enemyId: EnemyId,
    val prediction: EnemyPrediction,
    val actualResult: Position?,  // null si no se pudo verificar
    val timestamp: Long
)
