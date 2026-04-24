package com.ffai.assistant.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.utils.Logger
import kotlin.math.pow

/**
 * FASE 6: CameraController - Control avanzado de cámara con 3 velocidades.
 *
 * 3 Perfiles de rotación:
 * - SMOOTH (Suave): 30-60°/seg, ease-in-out, scouting, exploración
 * - MEDIUM (Media): 90-150°/seg, ease-out, combate estándar
 * - AGGRESSIVE (Agresiva): 200-360°/seg, linear, quick 180°, reacción instantánea
 *
 * Features:
 * - Perfiles de velocidad intercambiables en tiempo real
 * - Curvas de animación personalizadas
 * - Rotación a ángulos específicos
 * - Seguimiento suave de objetivos móviles
 * - Lead prediction para aim
 */
@RequiresApi(Build.VERSION_CODES.N)
class CameraController(
    private val service: AccessibilityService,
    private val gameConfig: GameConfig
) {

    companion object {
        const val TAG = "CameraController"
        
        // Velocidades en grados por segundo
        const val SPEED_SMOOTH_MIN = 30f
        const val SPEED_SMOOTH_MAX = 60f
        const val SPEED_MEDIUM_MIN = 90f
        const val SPEED_MEDIUM_MAX = 150f
        const val SPEED_AGGRESSIVE_MIN = 200f
        const val SPEED_AGGRESSIVE_MAX = 360f
        
        // Configuraciones de duración
        const val DURATION_SMOOTH_MS = 800L
        const val DURATION_MEDIUM_MS = 400L
        const val DURATION_AGGRESSIVE_MS = 150L
        
        // Umbral para considerar rotación completa
        const val FULL_ROTATION = 360f
    }

    // Perfil actual
    private var currentProfile: CameraProfile = CameraProfile.MEDIUM
    
    // Estado de aim
    private var currentAimX: Float = gameConfig.screenWidth / 2f
    private var currentAimY: Float = gameConfig.screenHeight / 2f
    private var targetAimX: Float = currentAimX
    private var targetAimY: Float = currentAimY
    
    // Para seguimiento suave
    private var lastTargetVelocityX = 0f
    private var lastTargetVelocityY = 0f
    private var isTracking = false
    
    // Thread de seguimiento
    private var trackingThread: Thread? = null
    private val shouldStopTracking = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Perfiles de velocidad de cámara.
     */
    enum class CameraProfile {
        SMOOTH,    // 30-60°/seg, scouting
        MEDIUM,    // 90-150°/seg, combate estándar
        AGGRESSIVE // 200-360°/seg, quick flick
    }

    /**
     * Establece el perfil de velocidad actual.
     */
    fun setProfile(profile: CameraProfile) {
        if (currentProfile != profile) {
            currentProfile = profile
            Logger.i(TAG, "Perfil de cámara cambiado: $profile")
        }
    }

    /**
     * Obtiene el perfil actual.
     */
    fun getCurrentProfile(): CameraProfile = currentProfile

    /**
     * Rota cámara por un delta (desplazamiento relativo).
     */
    fun rotate(deltaDegreesX: Float, deltaDegreesY: Float = 0f) {
        when (currentProfile) {
            CameraProfile.SMOOTH -> rotateSmooth(deltaDegreesX, deltaDegreesY)
            CameraProfile.MEDIUM -> rotateMedium(deltaDegreesX, deltaDegreesY)
            CameraProfile.AGGRESSIVE -> rotateAggressive(deltaDegreesX, deltaDegreesY)
        }
    }

    /**
     * Rota a un ángulo específico (absoluto).
     */
    fun rotateTo(targetDegreesX: Float, targetDegreesY: Float = 0f) {
        // Calcular delta desde posición actual (asumimos centrado)
        val currentDegreesX = 0f // Asumimos que siempre volvemos a referencia
        val deltaX = targetDegreesX - currentDegreesX
        val deltaY = targetDegreesY
        
        rotate(deltaX, deltaY)
    }

    /**
     * Quick 180° - Rotación instantánea hacia atrás.
     */
    fun quick180() {
        val oldProfile = currentProfile
        setProfile(CameraProfile.AGGRESSIVE)
        rotate(180f, 0f)
        setProfile(oldProfile)
    }

    /**
     * Quick 90° - Rotación rápida lateral.
     */
    fun quick90(direction: Direction) {
        val oldProfile = currentProfile
        setProfile(CameraProfile.AGGRESSIVE)
        rotate(if (direction == Direction.LEFT) -90f else 90f, 0f)
        setProfile(oldProfile)
    }

    /**
     * Inicia seguimiento suave de objetivo móvil.
     */
    fun startTracking(
        targetProvider: () -> Pair<Float, Float>?,
        onLost: () -> Unit = {}
    ) {
        stopTracking()
        isTracking = true
        shouldStopTracking.set(false)
        
        trackingThread = Thread {
            var consecutiveLost = 0
            
            while (!shouldStopTracking.get()) {
                val target = targetProvider()
                
                if (target != null) {
                    consecutiveLost = 0
                    smoothAimTo(target.first, target.second)
                } else {
                    consecutiveLost++
                    if (consecutiveLost > 10) { // ~1 segundo sin ver
                        onLost()
                        break
                    }
                }
                
                SystemClock.sleep(33) // ~30 FPS tracking
            }
            
            isTracking = false
        }.apply { start() }
    }

    /**
     * Detiene seguimiento.
     */
    fun stopTracking() {
        shouldStopTracking.set(true)
        trackingThread?.join(100)
        trackingThread = null
        isTracking = false
    }

    /**
     * Aim suave hacia punto específico con lead prediction.
     */
    fun aimWithLead(
        targetX: Float,
        targetY: Float,
        targetVelocityX: Float = 0f,
        targetVelocityY: Float = 0f,
        bulletTravelTimeMs: Float = 100f
    ) {
        // Calcular lead
        val leadX = targetVelocityX * bulletTravelTimeMs / 1000f
        val leadY = targetVelocityY * bulletTravelTimeMs / 1000f
        
        val aimX = targetX + leadX
        val aimY = targetY + leadY
        
        // Calcular delta para joystick
        val centerX = gameConfig.screenWidth / 2f
        val centerY = gameConfig.screenHeight / 2f
        
        val deltaX = ((aimX - centerX) / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
        val deltaY = ((aimY - centerY) / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)
        
        // Aplicar según perfil
        when (currentProfile) {
            CameraProfile.SMOOTH -> joystickMoveSmooth(deltaX, deltaY)
            CameraProfile.MEDIUM -> joystickMoveMedium(deltaX, deltaY)
            CameraProfile.AGGRESSIVE -> joystickMoveAggressive(deltaX, deltaY)
        }
        
        // Actualizar estado
        currentAimX = aimX
        currentAimY = aimY
        lastTargetVelocityX = targetVelocityX
        lastTargetVelocityY = targetVelocityY
    }

    /**
     * Flick shot - Movimiento ultra-rápido hacia objetivo y disparo.
     */
    fun flickShot(targetX: Float, targetY: Float, onComplete: () -> Unit = {}) {
        val oldProfile = currentProfile
        
        Thread {
            // Phase 1: Flick rápido
            setProfile(CameraProfile.AGGRESSIVE)
            val centerX = gameConfig.screenWidth / 2f
            val centerY = gameConfig.screenHeight / 2f
            val deltaX = ((targetX - centerX) / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
            val deltaY = ((targetY - centerY) / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)
            joystickMoveAggressive(deltaX, deltaY)
            
            SystemClock.sleep(50) // Esperar a que termine flick
            
            // Phase 2: Micro-ajuste suave
            setProfile(CameraProfile.MEDIUM)
            joystickMoveMedium(deltaX * 0.1f, deltaY * 0.1f)
            
            onComplete()
            
            // Restaurar perfil
            setProfile(oldProfile)
        }.start()
    }

    // ============================================
    // MÉTODOS PRIVADOS - ROTACIONES
    // ============================================

    private fun rotateSmooth(deltaX: Float, deltaY: Float) {
        val duration = calculateDuration(deltaX, SPEED_SMOOTH_MIN, SPEED_SMOOTH_MAX)
        val steps = (duration / 16).toInt().coerceAtLeast(5)
        
        for (i in 0 until steps) {
            val t = i / (steps - 1).toFloat()
            val easedT = easeInOutCubic(t)
            
            val currentDeltaX = deltaX * easedT / steps
            val currentDeltaY = deltaY * easedT / steps
            
            executeJoystickMove(currentDeltaX, currentDeltaY, 16)
            SystemClock.sleep(16)
        }
    }

    private fun rotateMedium(deltaX: Float, deltaY: Float) {
        val duration = calculateDuration(deltaX, SPEED_MEDIUM_MIN, SPEED_MEDIUM_MAX)
        val steps = (duration / 16).toInt().coerceAtLeast(3)
        
        for (i in 0 until steps) {
            val t = i / (steps - 1).toFloat()
            val easedT = easeOutQuad(t)
            
            val currentDeltaX = deltaX * easedT / steps
            val currentDeltaY = deltaY * easedT / steps
            
            executeJoystickMove(currentDeltaX, currentDeltaY, 16)
        }
    }

    private fun rotateAggressive(deltaX: Float, deltaY: Float) {
        // Movimiento inmediato, casi instantáneo
        val duration = calculateDuration(deltaX, SPEED_AGGRESSIVE_MIN, SPEED_AGGRESSIVE_MAX)
        executeJoystickMove(deltaX, deltaY, duration.toInt())
    }

    private fun calculateDuration(degrees: Float, speedMin: Float, speedMax: Float): Long {
        val absDegrees = kotlin.math.abs(degrees)
        val speed = speedMin + (speedMax - speedMin) * (absDegrees / 180f).coerceIn(0f, 1f)
        return (absDegrees / speed * 1000).toLong().coerceAtLeast(50)
    }

    // ============================================
    // MÉTODOS PRIVADOS - JOYSTICK
    // ============================================

    private fun joystickMoveSmooth(dx: Float, dy: Float) {
        val center = gameConfig.joystickRight
        val radius = gameConfig.joystickRadius
        
        val fromX = center.x
        val fromY = center.y
        val toX = center.x + dx * radius * 0.5f // Movimiento suave, mitad de rango
        val toY = center.y + dy * radius * 0.5f
        
        variableSwipe(fromX, fromY, toX, toY, DURATION_SMOOTH_MS, ::easeInOutCubic)
    }

    private fun joystickMoveMedium(dx: Float, dy: Float) {
        val center = gameConfig.joystickRight
        val radius = gameConfig.joystickRadius
        
        val fromX = center.x
        val fromY = center.y
        val toX = center.x + dx * radius * 0.8f
        val toY = center.y + dy * radius * 0.8f
        
        variableSwipe(fromX, fromY, toX, toY, DURATION_MEDIUM_MS, ::easeOutQuad)
    }

    private fun joystickMoveAggressive(dx: Float, dy: Float) {
        val center = gameConfig.joystickRight
        val radius = gameConfig.joystickRadius
        
        val fromX = center.x
        val fromY = center.y
        val toX = center.x + dx * radius // Rango completo
        val toY = center.y + dy * radius
        
        // Flick directo sin easing
        executeJoystickMove(dx, dy, DURATION_AGGRESSIVE_MS.toInt())
    }

    private fun executeJoystickMove(dx: Float, dy: Float, durationMs: Int) {
        val center = gameConfig.joystickRight
        val radius = gameConfig.joystickRadius
        
        val fromX = center.x + humanizedOffset(3f)
        val fromY = center.y + humanizedOffset(3f)
        val toX = center.x + dx * radius + humanizedOffset(3f)
        val toY = center.y + dy * radius + humanizedOffset(3f)
        
        val clampedFromX = fromX.coerceIn(0f, gameConfig.screenWidth.toFloat())
        val clampedFromY = fromY.coerceIn(0f, gameConfig.screenHeight.toFloat())
        val clampedToX = toX.coerceIn(0f, gameConfig.screenWidth.toFloat())
        val clampedToY = toY.coerceIn(0f, gameConfig.screenHeight.toFloat())
        
        try {
            val path = Path().apply {
                moveTo(clampedFromX, clampedFromY)
                lineTo(clampedToX, clampedToY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
                .build()
            
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Logger.e(TAG, "Error ejecutando movimiento joystick", e)
        }
    }

    private fun variableSwipe(
        fromX: Float, fromY: Float, toX: Float, toY: Float,
        durationMs: Long, easing: (Float) -> Float
    ) {
        val steps = (durationMs / 16).coerceAtLeast(3).toInt()
        
        for (i in 0 until steps) {
            val t = i / (steps - 1).toFloat()
            val easedT = easing(t)
            
            val currentX = fromX + (toX - fromX) * easedT
            val currentY = fromY + (toY - fromY) * easedT
            
            val nextT = (i + 1) / (steps - 1).toFloat()
            val nextEasedT = easing(nextT)
            val nextX = fromX + (toX - fromX) * nextEasedT
            val nextY = fromY + (toY - fromY) * nextEasedT
            
            microDrag(currentX, currentY, nextX, nextY, 16)
            SystemClock.sleep(16)
        }
    }

    private fun microDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Int) {
        try {
            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
                .build()
            
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Logger.e(TAG, "Error en microDrag", e)
        }
    }

    private fun smoothAimTo(x: Float, y: Float) {
        val centerX = gameConfig.screenWidth / 2f
        val centerY = gameConfig.screenHeight / 2f
        
        val dx = ((x - centerX) / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
        val dy = ((y - centerY) / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)
        
        // Limitar cambio máximo por frame (suavizado)
        val maxDelta = 0.3f
        val clampedDx = dx.coerceIn(-maxDelta, maxDelta)
        val clampedDy = dy.coerceIn(-maxDelta, maxDelta)
        
        joystickMoveSmooth(clampedDx, clampedDy)
    }

    // ============================================
    // CURVAS DE ANIMACIÓN
    // ============================================

    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4 * t * t * t
        } else {
            1 - (-2 * t + 2).pow(3) / 2
        }
    }

    private fun easeOutQuad(t: Float): Float {
        return 1 - (1 - t) * (1 - t)
    }

    private fun humanizedOffset(max: Float): Float {
        return (kotlin.random.Random.nextFloat() - 0.5f) * 2f * max
    }

    // ============================================
    // RESET
    // ============================================

    fun reset() {
        stopTracking()
        currentProfile = CameraProfile.MEDIUM
        currentAimX = gameConfig.screenWidth / 2f
        currentAimY = gameConfig.screenHeight / 2f
        targetAimX = currentAimX
        targetAimY = currentAimY
        Logger.i(TAG, "CameraController reseteado")
    }

    enum class Direction {
        LEFT, RIGHT
    }
}
