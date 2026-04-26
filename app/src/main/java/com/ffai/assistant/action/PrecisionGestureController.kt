package com.ffai.assistant.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.perception.WeaponType
import com.ffai.assistant.utils.Logger

/**
 * FASE 6: PrecisionGestureController - Control fino del personaje.
 * 
 * Extensiones de GestureController para:
 * - Movimiento suave sin jerkiness
 * - Tap preciso para headshots
 * - Drag con aceleración variable (ease in/out)
 * - Gestos compuestos (jump + aim, crouch + shoot)
 * - Recoil compensation
 * - Lead tracking (apuntar donde enemigo va a estar)
 * 
 * Target: < 16ms por gesto (60 FPS).
 */
@RequiresApi(Build.VERSION_CODES.N)
class PrecisionGestureController(
    private val service: AccessibilityService,
    private val gameConfig: GameConfig,
    private val baseController: GestureController? = null
) {
    
    companion object {
        const val TAG = "PrecisionGestureController"
        
        // Constantes de precisión
        const val SMOOTH_AIM_MIN_STEP = 2f // Mínimo movimiento (evitar micro-tremors)
        const val SMOOTH_AIM_MAX_STEP = 50f // Máximo movimiento por frame
        const val HEADSHOT_OFFSET_Y = -15f // Offset Y para headshots (arriba del centro)
        const val RECOIL_COMPENSATION_INTERVAL = 50L // ms
        
        // Perfiles de velocidad
        val EASE_OUT_QUAD: (Float) -> Float = { t -> 1 - (1 - t) * (1 - t) }
        val EASE_IN_OUT_CUBIC: (Float) -> Float = { t ->
            if (t < 0.5f) 4 * t * t * t else 1 - kotlin.math.pow((-2 * t + 2).toDouble(), 3.0).toFloat() / 2
        }
        val LINEAR: (Float) -> Float = { t -> t }
    }
    
    // Estado de aim suave
    private var currentAimX: Float = gameConfig.screenWidth / 2f
    private var currentAimY: Float = gameConfig.screenHeight / 2f
    private var targetAimX: Float = currentAimX
    private var targetAimY: Float = currentAimY
    private var lastAimUpdateTime: Long = 0L
    
    // Estado de recoil
    private var isCompensatingRecoil: Boolean = false
    private var lastShotTime: Long = 0L
    private var shotsInBurst: Int = 0
    private var recoilPattern: RecoilPattern = RecoilPattern.UNKNOWN
    
    // Cola de gestos para composición
    private val gestureQueue: ArrayDeque<GestureAction> = ArrayDeque()
    
    /**
     * Aim suave hacia objetivo (sin teleport).
     */
    fun smoothAim(targetX: Float, targetY: Float, durationMs: Int = 100) {
        targetAimX = targetX
        targetAimY = targetY
        
        val now = System.currentTimeMillis()
        val elapsed = now - lastAimUpdateTime
        
        if (elapsed < 16) return // Limitar a ~60 FPS
        
        // Calcular delta necesario
        val dx = targetAimX - currentAimX
        val dy = targetAimY - currentAimY
        
        // Limitar paso máximo (evitar snap brusco)
        val distance = kotlin.math.hypot(dx, dy)
        val stepSize = distance.coerceIn(SMOOTH_AIM_MIN_STEP, SMOOTH_AIM_MAX_STEP)
        
        val ratio = if (distance > 0) stepSize / distance else 0f
        val moveX = dx * ratio
        val moveY = dy * ratio
        
        // Aplicar movimiento
        currentAimX += moveX
        currentAimY += moveY
        
        // Ejecutar via joystick derecho (aim)
        val centerX = gameConfig.screenWidth / 2f
        val centerY = gameConfig.screenHeight / 2f
        val joystickDx = ((currentAimX - centerX) / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
        val joystickDy = ((currentAimY - centerY) / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)
        
        joystickMove(joystickDx, joystickDy, durationMs)
        
        lastAimUpdateTime = now
    }
    
    /**
     * Tap preciso con offset para headshot.
     */
    fun precisionHeadshotTap(
        baseX: Float,
        baseY: Float,
        enemyHeight: Float = 60f,
        holdDurationMs: Long = 50
    ) {
        // Head está aproximadamente 25% desde arriba del bounding box
        val headOffsetY = -enemyHeight * 0.25f + HEADSHOT_OFFSET_Y
        
        val targetX = baseX + humanizedOffset(2f)
        val targetY = baseY + headOffsetY + humanizedOffset(2f)
        
        precisionTap(targetX, targetY, holdDurationMs)
    }
    
    /**
     * Tap preciso en coordenadas exactas.
     */
    fun precisionTap(x: Float, y: Float, holdDurationMs: Long = 50) {
        val clampedX = x.coerceIn(0f, gameConfig.screenWidth.toFloat())
        val clampedY = y.coerceIn(0f, gameConfig.screenHeight.toFloat())
        
        try {
            val path = Path().apply { moveTo(clampedX, clampedY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, holdDurationMs))
                .build()
            
            dispatchGestureSafe(gesture, "precision_tap")
        } catch (e: Exception) {
            Logger.e(TAG, "precisionTap error", e)
        }
    }
    
    /**
     * Drag con aceleración variable (ease in/out).
     */
    fun variableDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 200,
        speedProfile: (Float) -> Float = EASE_OUT_QUAD
    ) {
        val steps = (durationMs / 16).coerceAtLeast(5) // ~60 FPS
        
        for (i in 0 until steps) {
            val t = i / (steps - 1).toFloat()
            val easedT = speedProfile(t)
            
            val currentX = startX + (endX - startX) * easedT
            val currentY = startY + (endY - startY) * easedT
            
            val nextT = (i + 1) / (steps - 1).toFloat()
            val nextEasedT = speedProfile(nextT)
            val nextX = startX + (endX - startX) * nextEasedT
            val nextY = startY + (endY - startY) * nextEasedT
            
            // Micro-drag de 16ms
            microDrag(currentX, currentY, nextX, nextY, 16)
            
            SystemClock.sleep(16)
        }
    }
    
    /**
     * Gesto compuesto: múltiples acciones secuenciales.
     */
    fun comboGesture(
        actions: List<GestureAction>,
        delayBetweenMs: Int = 50
    ) {
        gestureQueue.addAll(actions)
        
        Thread {
            while (gestureQueue.isNotEmpty()) {
                val action = gestureQueue.removeFirst()
                executeGestureAction(action)
                
                if (gestureQueue.isNotEmpty()) {
                    SystemClock.sleep(delayBetweenMs.toLong())
                }
            }
        }.start()
    }
    
    /**
     * Jump + Aim: Salta mientras apunta.
     */
    fun jumpAndAim(targetX: Float, targetY: Float) {
        val combo = listOf(
            GestureAction.Tap(gameConfig.buttonJump.x, gameConfig.buttonJump.y, 100),
            GestureAction.Aim(targetX, targetY, 200)
        )
        comboGesture(combo, 50)
    }
    
    /**
     * Crouch + Shoot: Agacharse y disparar.
     */
    fun crouchAndShoot() {
        val combo = listOf(
            GestureAction.Hold(gameConfig.buttonCrouch.x, gameConfig.buttonCrouch.y, 100),
            GestureAction.Shoot(100)
        )
        comboGesture(combo, 0)
    }
    
    /**
     * Slide + Shoot (si el juego lo soporta).
     */
    fun slideAndShoot(directionX: Float, directionY: Float) {
        val startX = gameConfig.joystickLeft.x
        val startY = gameConfig.joystickLeft.y
        val endX = startX + directionX * gameConfig.joystickRadius
        val endY = startY + directionY * gameConfig.joystickRadius
        
        val combo = listOf(
            GestureAction.Swipe(startX, startY, endX, endY, 150),
            GestureAction.Shoot(100)
        )
        comboGesture(combo, 0)
    }
    
    /**
     * Inicia compensación de recoil.
     */
    fun startRecoilCompensation(weaponType: WeaponType) {
        recoilPattern = when (weaponType) {
            WeaponType.ASSAULT_RIFLE -> RecoilPattern.AR_PATTERN
            WeaponType.SMG -> RecoilPattern.SMG_PATTERN
            WeaponType.LMG -> RecoilPattern.LMG_PATTERN
            else -> RecoilPattern.UNKNOWN
        }
        
        isCompensatingRecoil = true
        shotsInBurst = 0
        lastShotTime = System.currentTimeMillis()
        
        // Thread de compensación
        Thread {
            while (isCompensatingRecoil) {
                compensateRecoilStep()
                SystemClock.sleep(RECOIL_COMPENSATION_INTERVAL)
            }
        }.start()
    }
    
    /**
     * Detiene compensación de recoil.
     */
    fun stopRecoilCompensation() {
        isCompensatingRecoil = false
        shotsInBurst = 0
    }
    
    /**
     * Step de compensación de recoil.
     */
    private fun compensateRecoilStep() {
        val now = System.currentTimeMillis()
        if (now - lastShotTime > 200) {
            // No ha disparado recientemente, resetear
            shotsInBurst = 0
            return
        }
        
        shotsInBurst++
        
        // Calcular compensación basada en patrón
        val (compensateX, compensateY) = when (recoilPattern) {
            RecoilPattern.AR_PATTERN -> calculateARCompensation(shotsInBurst)
            RecoilPattern.SMG_PATTERN -> calculateSMGCompensation(shotsInBurst)
            RecoilPattern.LMG_PATTERN -> calculateLMGCompensation(shotsInBurst)
            else -> Pair(0f, 0f)
        }
        
        // Aplicar compensación via micro-aim
        val centerX = gameConfig.screenWidth / 2f
        val centerY = gameConfig.screenHeight / 2f
        joystickMove(compensateX, compensateY, 50)
    }
    
    /**
     * Calcula compensación para AR.
     */
    private fun calculateARCompensation(shotNumber: Int): Pair<Float, Float> {
        // AR: Suben primero 5-10 shots, luego estabilizan
        val verticalRecoil = when {
            shotNumber <= 3 -> 0.15f
            shotNumber <= 8 -> 0.10f
            else -> 0.05f
        }
        
        // Horizontal drift aleatorio
        val horizontalRecoil = if (shotNumber % 3 == 0) 0.05f else 0f
        
        // Compensación es inversa al recoil
        return Pair(-horizontalRecoil, verticalRecoil)
    }
    
    /**
     * Calcula compensación para SMG.
     */
    private fun calculateSMGCompensation(shotNumber: Int): Pair<Float, Float> {
        // SMG: Más recoil horizontal, menos vertical
        val verticalRecoil = if (shotNumber <= 5) 0.08f else 0.04f
        val horizontalRecoil = if (shotNumber % 2 == 0) 0.08f else -0.08f
        
        return Pair(-horizontalRecoil, verticalRecoil)
    }
    
    /**
     * Calcula compensación para LMG.
     */
    private fun calculateLMGCompensation(shotNumber: Int): Pair<Float, Float> {
        // LMG: Mucho recoil vertical
        val verticalRecoil = if (shotNumber <= 10) 0.20f else 0.15f
        val horizontalRecoil = kotlin.math.sin(shotNumber.toFloat()) * 0.05f
        
        return Pair(-horizontalRecoil, verticalRecoil)
    }
    
    /**
     * Lead tracking: Apuntar donde enemigo va a estar.
     */
    fun leadAim(
        currentEnemyX: Float,
        currentEnemyY: Float,
        enemyVelocityX: Float,
        enemyVelocityY: Float,
        bulletTravelTimeMs: Float
    ): Pair<Float, Float> {
        // Predecir posición futura
        val predictedX = currentEnemyX + enemyVelocityX * bulletTravelTimeMs / 1000f
        val predictedY = currentEnemyY + enemyVelocityY * bulletTravelTimeMs / 1000f
        
        return Pair(predictedX, predictedY)
    }
    
    /**
     * Burst fire: Disparar en ráfagas controladas.
     */
    fun burstFire(burstSize: Int = 3, burstDelayMs: Long = 100) {
        Thread {
            startRecoilCompensation(WeaponType.ASSAULT_RIFLE)
            
            for (i in 0 until burstSize) {
                executeGestureAction(GestureAction.Shoot(50))
                if (i < burstSize - 1) {
                    SystemClock.sleep(burstDelayMs)
                }
            }
            
            stopRecoilCompensation()
        }.start()
    }
    
    /**
     * Reset de estado.
     */
    fun reset() {
        currentAimX = gameConfig.screenWidth / 2f
        currentAimY = gameConfig.screenHeight / 2f
        targetAimX = currentAimX
        targetAimY = currentAimY
        stopRecoilCompensation()
        gestureQueue.clear()
        Logger.i(TAG, "PrecisionGestureController reseteado")
    }
    
    // ============================================
    // MÉTODOS PRIVADOS
    // ============================================
    
    private fun joystickMove(dx: Float, dy: Float, durationMs: Int) {
        val center = gameConfig.joystickRight
        val radius = gameConfig.joystickRadius
        
        val fromX = center.x
        val fromY = center.y
        val toX = center.x + dx * radius
        val toY = center.y + dy * radius
        
        microDrag(fromX, fromY, toX, toY, durationMs)
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
            
            dispatchGestureSafe(gesture, "micro_drag")
        } catch (e: Exception) {
            Logger.e(TAG, "microDrag error", e)
        }
    }
    
    private fun executeGestureAction(action: GestureAction) {
        when (action) {
            is GestureAction.Tap -> precisionTap(action.x, action.y, action.durationMs)
            is GestureAction.Hold -> {
                val path = Path().apply { moveTo(action.x, action.y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
                    .build()
                dispatchGestureSafe(gesture, "hold")
            }
            is GestureAction.Shoot -> {
                precisionTap(gameConfig.buttonFire.x, gameConfig.buttonFire.y, action.durationMs)
            }
            is GestureAction.Aim -> smoothAim(action.x, action.y, action.durationMs.toInt())
            is GestureAction.Swipe -> {
                val path = Path().apply {
                    moveTo(action.startX, action.startY)
                    lineTo(action.endX, action.endY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
                    .build()
                dispatchGestureSafe(gesture, "swipe")
            }
        }
    }
    
    private fun dispatchGestureSafe(gesture: GestureDescription, name: String) {
        try {
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Logger.e(TAG, "dispatchGesture error: $name", e)
        }
    }
    
    private fun humanizedOffset(maxOffset: Float = 5f): Float {
        return (kotlin.random.Random.nextFloat() - 0.5f) * 2f * maxOffset
    }
}

// ============================================
// DATA CLASSES Y ENUMS
// ============================================

sealed class GestureAction {
    data class Tap(val x: Float, val y: Float, val durationMs: Long) : GestureAction()
    data class Hold(val x: Float, val y: Float, val durationMs: Long) : GestureAction()
    data class Shoot(val durationMs: Long) : GestureAction()
    data class Aim(val x: Float, val y: Float, val durationMs: Long) : GestureAction()
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMs: Long) : GestureAction()
}

enum class RecoilPattern {
    AR_PATTERN,
    SMG_PATTERN,
    LMG_PATTERN,
    SHOTGUN_PATTERN,
    UNKNOWN
}

typealias SpeedProfile = (Float) -> Float
