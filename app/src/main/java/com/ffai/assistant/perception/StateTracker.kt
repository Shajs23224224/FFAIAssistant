package com.ffai.assistant.perception

import com.ffai.assistant.utils.Logger
import kotlin.math.abs

/**
 * FASE 2: StateTracker - Tracking predictivo de entidades del juego.
 * 
 * Implementa:
 * - Kalman filtering simplificado para posiciones de enemigos
 * - Dead reckoning cuando no hay visión directa
 * - Tracking de velocidad y dirección de enemigos
 * - Predicción de movimiento futuro
 * 
 * Optimizado para Samsung A21S: operaciones simples, sin matrices complejas.
 */
class StateTracker(private val worldModel: TacticalWorldModel) {
    
    // Configuración de tracking
    companion object {
        const val POSITION_UNCERTAINTY_GAIN = 0.1f
        const val VELOCITY_DECAY = 0.95f
        const val MAX_PREDICTION_TIME_MS = 2000L
        const val POSITION_THRESHOLD = 0.05f
    }
    
    // Tracked entities por ID
    private val trackedEntities: MutableMap<String, TrackedEntity> = mutableMapOf()
    
    // Historial de movimiento del jugador
    private val playerHistory: ArrayDeque<PlayerSnapshot> = ArrayDeque(50)
    
    // Timestamp de última actualización
    private var lastUpdateTime: Long = 0L
    
    /**
     * Actualiza tracking basado en nueva detección de visión.
     */
    fun updateFromVision(detections: List<VisionDetection>) {
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastUpdateTime) / 1000f
        lastUpdateTime = currentTime
        
        // Procesar cada detección
        for (detection in detections) {
            val existing = trackedEntities[detection.id]
            
            if (existing != null) {
                // Actualizar entidad existente
                updateExistingEntity(existing, detection, deltaTime)
            } else {
                // Nueva entidad
                trackedEntities[detection.id] = createNewEntity(detection, currentTime)
            }
        }
        
        // Marcar no detectados como "ocultos"
        val detectedIds = detections.map { it.id }.toSet()
        for ((id, entity) in trackedEntities) {
            if (id !in detectedIds) {
                entity.isVisible = false
                // Incrementar incertidumbre
                entity.positionUncertainty += POSITION_UNCERTAINTY_GAIN * deltaTime
            }
        }
        
        // Limpiar entidades muy antiguas
        cleanupOldEntities()
    }
    
    /**
     * Actualiza entidad existente con nueva detección (simplified Kalman).
     */
    private fun updateExistingEntity(
        entity: TrackedEntity,
        detection: VisionDetection,
        deltaTime: Float
    ) {
        // Calcular velocidad observada
        val observedVelocityX = (detection.x - entity.position.x) / deltaTime
        val observedVelocityY = (detection.y - entity.position.y) / deltaTime
        
        // Actualizar velocidad con decaimiento (simplified Kalman gain)
        val alpha = 0.3f // Factor de confianza en medición
        entity.velocityX = entity.velocityX * (1 - alpha) + observedVelocityX * alpha
        entity.velocityY = entity.velocityY * (1 - alpha) + observedVelocityY * alpha
        
        // Aplicar decaimiento a velocidad
        entity.velocityX *= VELOCITY_DECAY
        entity.velocityY *= VELOCITY_DECAY
        
        // Actualizar posición
        entity.position = Vector3D(detection.x, detection.y, detection.z)
        entity.lastSeenTime = System.currentTimeMillis()
        entity.isVisible = true
        
        // Reducir incertidumbre por observación
        entity.positionUncertainty *= 0.7f
        entity.positionUncertainty = entity.positionUncertainty.coerceIn(0.01f, 1.0f)
        
        // Actualizar tipo si cambió
        if (detection.type != entity.type) {
            entity.type = detection.type
        }
    }
    
    /**
     * Crea nueva entidad trackeada.
     */
    private fun createNewEntity(detection: VisionDetection, currentTime: Long): TrackedEntity {
        return TrackedEntity(
            id = detection.id,
            position = Vector3D(detection.x, detection.y, detection.z),
            velocityX = 0f,
            velocityY = 0f,
            type = detection.type,
            lastSeenTime = currentTime,
            isVisible = true,
            positionUncertainty = 0.1f
        )
    }
    
    /**
     * Predice posición futura de entidad (dead reckoning).
     */
    fun predictPosition(entityId: String, timeMs: Long): Vector3D? {
        val entity = trackedEntities[entityId] ?: return null
        
        // Si no ha sido visto recientemente, incertidumbre es alta
        if (System.currentTimeMillis() - entity.lastSeenTime > MAX_PREDICTION_TIME_MS) {
            return null // Demasiado incierto
        }
        
        val deltaTime = timeMs / 1000f
        
        // Dead reckoning: posición + velocidad * tiempo
        return Vector3D(
            x = entity.position.x + entity.velocityX * deltaTime,
            y = entity.position.y + entity.velocityY * deltaTime,
            z = entity.position.z
        )
    }
    
    /**
     * Predice donde apuntar para hit (lead tracking).
     */
    fun predictAimPosition(
        entityId: String,
        bulletTravelTimeMs: Float,
        ownPosition: Vector3D
    ): Vector3D? {
        val entity = trackedEntities[entityId] ?: return null
        
        // Si entidad está quieto, no compensar
        if (kotlin.math.abs(entity.velocityX) < 0.01f && 
            kotlin.math.abs(entity.velocityY) < 0.01f) {
            return entity.position
        }
        
        // Predecir posición cuando la bala llegue
        return predictPosition(entityId, bulletTravelTimeMs.toLong())
    }
    
    /**
     * Actualiza tracking del jugador propio.
     */
    fun updatePlayerPosition(position: Vector3D, viewAngle: Float) {
        val snapshot = PlayerSnapshot(
            position = position,
            viewAngle = viewAngle,
            timestamp = System.currentTimeMillis()
        )
        
        playerHistory.add(snapshot)
        
        // Mantener solo últimos 50 snapshots
        if (playerHistory.size > 50) {
            playerHistory.removeFirst()
        }
        
        // Actualizar world model
        worldModel.playerPosition = position
        worldModel.viewAngle = viewAngle
    }
    
    /**
     * Estima velocidad de entidad.
     */
    fun estimateVelocity(entityId: String): Pair<Float, Float> {
        val entity = trackedEntities[entityId] ?: return Pair(0f, 0f)
        return Pair(entity.velocityX, entity.velocityY)
    }
    
    /**
     * Determina si entidad se está moviendo.
     */
    fun isMoving(entityId: String): Boolean {
        val entity = trackedEntities[entityId] ?: return false
        return kotlin.math.abs(entity.velocityX) > 0.01f || 
               kotlin.math.abs(entity.velocityY) > 0.01f
    }
    
    /**
     * Predice dirección de movimiento futuro.
     */
    fun predictMovementDirection(entityId: String): Vector2D? {
        val entity = trackedEntities[entityId] ?: return null
        
        if (!isMoving(entityId)) return null
        
        return Vector2D(entity.velocityX, entity.velocityY).normalize()
    }
    
    /**
     * Detecta patrón de movimiento (ej: zigzag, línea recta, quieto).
     */
    fun detectMovementPattern(entityId: String): MovementPattern {
        val entity = trackedEntities[entityId] ?: return MovementPattern.UNKNOWN
        
        val velocityMag = kotlin.math.hypot(entity.velocityX, entity.velocityY)
        
        return when {
            velocityMag < 0.01f -> MovementPattern.STATIONARY
            velocityMag > 0.3f -> MovementPattern.SPRINTING
            kotlin.math.abs(entity.velocityX) > kotlin.math.abs(entity.velocityY) * 2 -> 
                MovementPattern.STRAFING
            else -> MovementPattern.MOVING
        }
    }
    
    /**
     * Obtiene todas las entidades trackeadas.
     */
    fun getAllTracked(): List<TrackedEntity> = trackedEntities.values.toList()
    
    /**
     * Obtiene solo entidades actualmente visibles.
     */
    fun getVisibleEntities(): List<TrackedEntity> = 
        trackedEntities.values.filter { it.isVisible }
    
    /**
     * Obtiene entidades más peligrosas (cerca + visible).
     */
    fun getMostThreatening(maxCount: Int = 3): List<TrackedEntity> {
        val playerPos = worldModel.playerPosition
        
        return trackedEntities.values
            .filter { it.isVisible || it.positionUncertainty < 0.3f }
            .sortedBy { it.position.distanceTo(playerPos) }
            .take(maxCount)
    }
    
    /**
     * Limpia entidades antiguas o muy inciertas.
     */
    private fun cleanupOldEntities() {
        val now = System.currentTimeMillis()
        val iterator = trackedEntities.entries.iterator()
        
        while (iterator.hasNext()) {
            val (_, entity) = iterator.next()
            val age = now - entity.lastSeenTime
            
            // Eliminar si:
            // - No visto en > 10 segundos
            // - Incertidumbre muy alta por > 5 segundos
            if (age > 10000L || (age > 5000L && entity.positionUncertainty > 0.8f)) {
                iterator.remove()
            }
        }
        
        // Limitar número total de entidades
        if (trackedEntities.size > 20) {
            // Ordenar por tiempo de última vista, eliminar los más antiguos
            val sorted = trackedEntities.entries.sortedBy { it.value.lastSeenTime }
            val toRemove = sorted.take(trackedEntities.size - 20)
            toRemove.forEach { trackedEntities.remove(it.key) }
        }
    }
    
    /**
     * Reset completo del tracker.
     */
    fun reset() {
        trackedEntities.clear()
        playerHistory.clear()
        lastUpdateTime = 0L
        Logger.i("StateTracker reseteado")
    }
    
    /**
     * Obtiene resumen del tracking.
     */
    fun getSummary(): String {
        val visible = trackedEntities.values.count { it.isVisible }
        val hidden = trackedEntities.size - visible
        return "[StateTracker] Total: ${trackedEntities.size} (Visible: $visible, Hidden: $hidden)"
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class TrackedEntity(
    val id: String,
    var position: Vector3D,
    var velocityX: Float,
    var velocityY: Float,
    var type: EntityType,
    var lastSeenTime: Long,
    var isVisible: Boolean,
    var positionUncertainty: Float
)

data class VisionDetection(
    val id: String,
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val confidence: Float,
    val type: EntityType,
    val screenX: Float,
    val screenY: Float
)

data class PlayerSnapshot(
    val position: Vector3D,
    val viewAngle: Float,
    val timestamp: Long
)

enum class EntityType {
    ENEMY,
    TEAMMATE,
    NPC,
    LOOT,
    VEHICLE,
    UNKNOWN
}

enum class MovementPattern {
    STATIONARY,     // Quieto
    MOVING,         // Movimiento normal
    SPRINTING,      // Corriendo
    STRAFING,       // Movimiento lateral
    ZIGZAG,         // Patrón evasivo
    UNKNOWN
}

// Extensiones
private fun Vector2D.normalize(): Vector2D {
    val mag = kotlin.math.hypot(x, y)
    return if (mag > 0) Vector2D(x / mag, y / mag) else Vector2D(0f, 0f)
}

private fun Vector2D.magnitude(): Float = kotlin.math.hypot(x, y)
