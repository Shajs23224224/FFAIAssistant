package com.ffai.assistant.gesture

import com.ffai.assistant.vision.FusedEnemy
import com.ffai.assistant.utils.Logger

/**
 * FASE 4: MovementController - Control de movimiento táctico.
 * 
 * Features:
 * - A* pathfinding simplificado
 * - Strafe patterns (ADAD)
 * - Bunny-hop en descensos
 * - Crouch-peek en corners
 * - Cover usage (peek-shoot-retreat)
 */
class MovementController(private val gestureEngine: GestureEngine) {
    
    companion object {
        const val TAG = "MovementController"
        
        const val JOYSTICK_X = 200f
        const val JOYSTICK_Y = 700f
        const val MAX_JOYSTICK_DISTANCE = 150f
    }
    
    // Estado
    private var isMoving = false
    private var isCrouching = false
    private var isJumping = false
    private var lastStrafeDirection = StrafeDirection.LEFT
    
    // Posiciones de cobertura conocidas
    private val knownCovers = mutableListOf<CoverPosition>()
    
    /**
     * Mueve a coordenada objetivo.
     */
    fun moveTo(
        targetX: Float,
        targetY: Float,
        playerX: Float = 540f,
        playerY: Float = 960f
    ): Boolean {
        val deltaX = targetX - playerX
        val deltaY = targetY - playerY
        
        // Normalizar a rango joystick
        val magnitude = kotlin.math.hypot(deltaX, deltaY)
        val scale = if (magnitude > MAX_JOYSTICK_DISTANCE) {
            MAX_JOYSTICK_DISTANCE / magnitude
        } else 1f
        
        return dragJoystick(deltaX * scale, deltaY * scale, 500)
    }
    
    /**
     * Strafe alternado (ADAD) para evadir.
     */
    fun strafeEvasive(durationMs: Long = 1000): Boolean {
        val startTime = System.currentTimeMillis()
        var success = true
        
        while (System.currentTimeMillis() - startTime < durationMs) {
            // Alternar dirección
            lastStrafeDirection = when (lastStrafeDirection) {
                StrafeDirection.LEFT -> StrafeDirection.RIGHT
                StrafeDirection.RIGHT -> StrafeDirection.LEFT
            }
            
            success = success && gestureEngine.strafe(lastStrafeDirection, 150)
            
            if (!success) break
            
            // Pequeña pausa
            Thread.sleep(50)
        }
        
        return success
    }
    
    /**
     * Bunny-hop (saltar mientras se mueve).
     */
    fun bunnyHop(directionX: Float, directionY: Float): Boolean {
        var success = true
        
        // Jump
        success = success && gestureEngine.jump()
        
        // Iniciar movimiento mientras en aire
        Thread.sleep(100)
        success = success && dragJoystick(directionX, directionY, 300)
        
        return success
    }
    
    /**
     * Crouch-peek: agacharse, peek, levantarse.
     */
    fun crouchPeek(direction: PeekDirection): Boolean {
        var success = true
        
        // 1. Crouch
        success = success && gestureEngine.crouch()
        Thread.sleep(200)
        
        // 2. Peek (mover levemente)
        val peekDelta = when (direction) {
            PeekDirection.LEFT -> -30f
            PeekDirection.RIGHT -> 30f
        }
        success = success && dragJoystick(peekDelta, 0f, 200)
        
        // 3. Un-crouch
        success = success && gestureEngine.crouch()
        
        return success
    }
    
    /**
     * Busca cobertura cercana y se mueve hacia ella.
     */
    fun moveToCover(
        playerX: Float,
        playerY: Float,
        enemies: List<FusedEnemy>
    ): Boolean {
        // Encontrar cobertura más cercana no expuesta
        val safeCover = findSafestCover(playerX, playerY, enemies)
            ?: return false
        
        Logger.i(TAG, "Moving to cover at (${safeCover.x}, ${safeCover.y})")
        
        // Mover hacia cobertura
        val success = moveTo(safeCover.x, safeCover.y, playerX, playerY)
        
        if (success) {
            // Crouch al llegar
            Thread.sleep(300)
            gestureEngine.crouch()
        }
        
        return success
    }
    
    /**
     * Peek-shoot-retreat desde cobertura.
     */
    fun peekShootRetreat(
        cover: CoverPosition,
        enemy: FusedEnemy,
        weaponController: WeaponController
    ): Boolean {
        // 1. Peek (salir de cobertura)
        val peekSuccess = peekFromCover(cover, enemy)
        if (!peekSuccess) return false
        
        // 2. Shoot
        Thread.sleep(100)
        val shootSuccess = weaponController.shoot(
            targetX = enemy.centerX(),
            targetY = enemy.centerY(),
            distance = calculateDistance(cover, enemy)
        )
        
        // 3. Retreat (volver a cobertura inmediatamente)
        Thread.sleep(100)
        val retreatSuccess = retreatToCover(cover)
        
        return shootSuccess && retreatSuccess
    }
    
    /**
     * Relocate después de disparos (evitar prefire).
     */
    fun relocateAfterShots(shotsFired: Int): Boolean {
        if (shotsFired < 3) return true  // No necesario si pocos disparos
        
        Logger.d(TAG, "Relocating after $shotsFired shots")
        
        // Pequeño movimiento lateral aleatorio
        val direction = if (kotlin.random.Random.nextBoolean()) 50f else -50f
        
        return dragJoystick(direction, 0f, 200) ||
               gestureEngine.strafe(
                   if (direction > 0) StrafeDirection.RIGHT else StrafeDirection.LEFT,
                   200
               )
    }
    
    /**
     * Movimiento errático cuando bajo fuego.
     */
    fun evasiveManeuver(): Boolean {
        return when (kotlin.random.Random.nextInt(3)) {
            0 -> gestureEngine.jump()
            1 -> strafeEvasive(500)
            2 -> {
                gestureEngine.crouch()
                Thread.sleep(200)
                gestureEngine.crouch()
                true
            }
            else -> true
        }
    }
    
    /**
     * Rota hacia zona segura.
     */
    fun rotateToZone(
        currentPos: Pair<Float, Float>,
        zoneCenter: Pair<Float, Float>
    ): Boolean {
        val (x1, y1) = currentPos
        val (x2, y2) = zoneCenter
        
        val deltaX = x2 - x1
        val deltaY = y2 - y1
        
        // Normalizar
        val distance = kotlin.math.hypot(deltaX, deltaY)
        if (distance == 0f) return true
        
        val scale = MAX_JOYSTICK_DISTANCE / distance
        
        return dragJoystick(deltaX * scale, deltaY * scale, 1000)
    }
    
    /**
     * Drag en joystick.
     */
    private fun dragJoystick(deltaX: Float, deltaY: Float, durationMs: Long): Boolean {
        isMoving = true
        
        val success = gestureEngine.drag(
            centerX = JOYSTICK_X,
            centerY = JOYSTICK_Y,
            deltaX = deltaX,
            deltaY = deltaY,
            holdDurationMs = durationMs
        )
        
        isMoving = false
        return success
    }
    
    /**
     * Encuentra cobertura más segura.
     */
    private fun findSafestCover(
        playerX: Float,
        playerY: Float,
        enemies: List<FusedEnemy>
    ): CoverPosition? {
        return knownCovers
            .filter { !isExposed(it, enemies) }
            .minByOrNull { kotlin.math.hypot(it.x - playerX, it.y - playerY) }
    }
    
    /**
     * Verifica si posición está expuesta a enemigos.
     */
    private fun isExposed(position: CoverPosition, enemies: List<FusedEnemy>): Boolean {
        return enemies.any { enemy ->
            val angleToEnemy = kotlin.math.atan2(
                enemy.centerY() - position.y,
                enemy.centerX() - position.x
            )
            
            // Simplificado: considerar expuesta si enemigo está dentro de ±90 grados del cover
            kotlin.math.abs(angleToEnemy - position.facingAngle) < Math.PI / 2
        }
    }
    
    /**
     * Calcula distancia entre cobertura y enemigo.
     */
    private fun calculateDistance(cover: CoverPosition, enemy: FusedEnemy): Float {
        return kotlin.math.hypot(
            enemy.centerX() - cover.x,
            enemy.centerY() - cover.y
        )
    }
    
    /**
     * Peek desde cobertura.
     */
    private fun peekFromCover(cover: CoverPosition, enemy: FusedEnemy): Boolean {
        // Determinar dirección de peek
        val angleToEnemy = kotlin.math.atan2(
            enemy.centerY() - cover.y,
            enemy.centerX() - cover.x
        )
        
        val direction = when {
            angleToEnemy > cover.facingAngle -> PeekDirection.RIGHT
            else -> PeekDirection.LEFT
        }
        
        return crouchPeek(direction)
    }
    
    /**
     * Retirada a cobertura.
     */
    private fun retreatToCover(cover: CoverPosition): Boolean {
        // Moverse hacia el cover
        return moveTo(cover.x, cover.y)
    }
    
    /**
     * Registra posición de cobertura.
     */
    fun registerCover(x: Float, y: Float, facingAngle: Float) {
        val cover = CoverPosition(x, y, facingAngle)
        if (!knownCovers.any { it.isNear(cover) }) {
            knownCovers.add(cover)
            Logger.d(TAG, "Registered cover at ($x, $y)")
        }
    }
    
    /**
     * Obtiene coberturas conocidas.
     */
    fun getKnownCovers(): List<CoverPosition> = knownCovers.toList()
    
    /**
     * Limpia coberturas.
     */
    fun clearCovers() {
        knownCovers.clear()
        Logger.i(TAG, "Known covers cleared")
    }
    
    /**
     * Reset.
     */
    fun reset() {
        isMoving = false
        isCrouching = false
        isJumping = false
        knownCovers.clear()
        Logger.i(TAG, "MovementController reset")
    }
    
    fun isMoving(): Boolean = isMoving
    fun isCrouching(): Boolean = isCrouching
}

/**
 * Posición de cobertura.
 */
data class CoverPosition(
    val x: Float,
    val y: Float,
    val facingAngle: Float,
    val confidence: Float = 1.0f
) {
    fun isNear(other: CoverPosition, threshold: Float = 50f): Boolean {
        return kotlin.math.hypot(x - other.x, y - other.y) < threshold
    }
}

/**
 * Direcciones de strafe.
 */
enum class StrafeDirectionCore { LEFT, RIGHT }

/**
 * Direcciones de peek.
 */
enum class PeekDirection { LEFT, RIGHT }
