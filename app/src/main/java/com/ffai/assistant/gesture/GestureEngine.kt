package com.ffai.assistant.gesture

import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.ffai.assistant.utils.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * FASE 4: GestureEngine - Motor de gestos táctiles avanzado.
 * 
 * Tipos de gestos:
 * - Tap: toque simple (disparo, loot, interactuar)
 * - Swipe: deslizar (aim con velocidad variable)
 * - Drag: arrastre (joystick virtual)
 * - Pinch: pellizco (zoom)
 * - LongPress: presión larga (ADS - aim down sights)
 * 
 * Features:
 * - Humanización: variación aleatoria
 * - Curvas Bezier para naturalidad
 * - Presión variable según contexto
 */
class GestureEngine(private val accessibilityService: android.accessibilityservice.AccessibilityService) {
    
    companion object {
        const val TAG = "GestureEngine"
        
        // Timing (con variación humana ±10ms)
        const val DEFAULT_TAP_DURATION = 50L
        const val LONG_PRESS_DURATION = 500L
        const val SWIPE_DURATION = 150L
        const val DRAG_DURATION = 300L
        
        // Precisión
        const val POSITION_VARIATION = 2f  // pixels
        const val TIMING_VARIATION = 10L     // ms
        
        // Zonas de UI típicas
        const val FIRE_BUTTON_X = 1200f
        const val FIRE_BUTTON_Y = 700f
        const val JOYSTICK_X = 200f
        const val JOYSTICK_Y = 700f
        const val JUMP_BUTTON_X = 1100f
        const val JUMP_BUTTON_Y = 550f
    }
    
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val gestureHistory = ArrayDeque<GestureRecord>(100)
    
    // Stats
    private var gestureCount = 0
    private var failedGestures = 0
    
    /**
     * Ejecuta tap simple.
     */
    fun tap(x: Float, y: Float, durationMs: Long = DEFAULT_TAP_DURATION): Boolean {
        val (humanX, humanY) = humanizePosition(x, y)
        val humanDuration = humanizeTiming(durationMs)
        
        return executeGesture(Gesture.Tap(humanX, humanY, humanDuration))
    }
    
    /**
     * Ejecuta doble tap.
     */
    fun doubleTap(x: Float, y: Float): Boolean {
        val (humanX, humanY) = humanizePosition(x, y)
        
        return executeGesture(Gesture.DoubleTap(humanX, humanY))
    }
    
    /**
     * Ejecuta swipe para aim.
     * @param deltaX Movimiento horizontal (positivo = derecha)
     * @param deltaY Movimiento vertical (positivo = abajo)
     * @param speedFactor Multiplicador de velocidad (1.0 = normal)
     */
    fun swipe(
        startX: Float,
        startY: Float,
        deltaX: Float,
        deltaY: Float,
        speedFactor: Float = 1.0f
    ): Boolean {
        val (humanStartX, humanStartY) = humanizePosition(startX, startY)
        val endX = humanStartX + deltaX
        val endY = humanStartY + deltaY
        
        // Duración inversamente proporcional a velocidad
        val baseDuration = (SWIPE_DURATION / speedFactor).toLong()
        val duration = humanizeTiming(baseDuration.coerceIn(50, 500))
        
        // Curva Bezier para naturalidad
        val path = createBezierPath(humanStartX, humanStartY, endX, endY)
        
        return executeGesture(Gesture.Swipe(path, duration))
    }
    
    /**
     * Arrastra (joystick).
     */
    fun drag(
        centerX: Float,
        centerY: Float,
        deltaX: Float,
        deltaY: Float,
        holdDurationMs: Long = DRAG_DURATION
    ): Boolean {
        val (humanCenterX, humanCenterY) = humanizePosition(centerX, centerY)
        val endX = humanCenterX + deltaX
        val endY = humanCenterY + deltaY
        
        val duration = humanizeTiming(holdDurationMs)
        
        return executeGesture(Gesture.Drag(humanCenterX, humanCenterY, endX, endY, duration))
    }
    
    /**
     * Presión larga (ADS).
     */
    fun longPress(x: Float, y: Float, durationMs: Long = LONG_PRESS_DURATION): Boolean {
        val (humanX, humanY) = humanizePosition(x, y)
        val humanDuration = humanizeTiming(durationMs)
        
        return executeGesture(Gesture.LongPress(humanX, humanY, humanDuration))
    }
    
    /**
     * Pellizco (zoom mapa).
     */
    fun pinch(
        centerX: Float,
        centerY: Float,
        scaleFactor: Float  // >1 = zoom in, <1 = zoom out
    ): Boolean {
        val (humanX, humanY) = humanizePosition(centerX, centerY)
        
        return executeGesture(Gesture.Pinch(humanX, humanY, scaleFactor))
    }
    
    /**
     * Acción completa de disparo.
     * Incluye: aim + fire + recoil compensation.
     */
    fun shoot(
        targetX: Float,
        targetY: Float,
        recoilCompensationY: Float = 0f
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        // 1. Aim: swipe hacia target
        val aimSuccess = swipe(
            startX = 540f,  // Centro pantalla
            startY = 960f,
            deltaX = targetX - 540f,
            deltaY = targetY - 960f,
            speedFactor = 1.5f  // Aim rápido
        )
        
        if (!aimSuccess) return false
        
        // 2. Fire: tap en botón de disparo
        val fireSuccess = tap(FIRE_BUTTON_X, FIRE_BUTTON_Y, 30)
        
        if (!fireSuccess) return false
        
        // 3. Recoil compensation: micro-ajuste hacia abajo
        if (recoilCompensationY > 0) {
            scheduler.schedule({
                swipe(
                    startX = targetX,
                    startY = targetY,
                    deltaX = 0f,
                    deltaY = recoilCompensationY,
                    speedFactor = 2.0f
                )
            }, 50, TimeUnit.MILLISECONDS)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Logger.d(TAG, "Shoot action completed in ${elapsed}ms")
        
        return true
    }
    
    /**
     * Movimiento táctico (strafe).
     */
    fun strafe(
        direction: StrafeDirection,
        durationMs: Long = 200
    ): Boolean {
        val deltaX = when (direction) {
            StrafeDirection.LEFT -> -100f
            StrafeDirection.RIGHT -> 100f
        }
        
        return drag(
            centerX = JOYSTICK_X,
            centerY = JOYSTICK_Y,
            deltaX = deltaX,
            deltaY = 0f,
            holdDurationMs = durationMs
        )
    }
    
    /**
     * Salto (jump).
     */
    fun jump(): Boolean {
        return tap(JUMP_BUTTON_X, JUMP_BUTTON_Y)
    }
    
    /**
     * Agacharse (crouch).
     */
    fun crouch(): Boolean {
        // Asumiendo botón de crouch cerca del joystick
        return tap(JOYSTICK_X + 150, JOYSTICK_Y - 100)
    }
    
    /**
     * Loot rápido.
     */
    fun loot(itemX: Float, itemY: Float): Boolean {
        // Tap en item
        val tapSuccess = tap(itemX, itemY)
        
        // Esperar y confirmar (segundo tap)
        if (tapSuccess) {
            scheduler.schedule({
                tap(itemX, itemY + 50)  // Botón de loot
            }, 100, TimeUnit.MILLISECONDS)
        }
        
        return tapSuccess
    }
    
    /**
     * Recarga.
     */
    fun reload(): Boolean {
        // Asumiendo botón de reload
        return tap(1000f, 400f)
    }
    
    /**
     * Curación.
     */
    fun heal(): Boolean {
        // Asumiendo botón de heal o acceso a inventario
        return longPress(900f, 400f, 300)
    }
    
    /**
     * Ejecuta gesto genérico.
     */
    private fun executeGesture(gesture: Gesture): Boolean {
        return try {
            // Crear GestureDescription
            val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
            
            when (gesture) {
                is Gesture.Tap -> {
                    val path = Path().apply { moveTo(gesture.x, gesture.y) }
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, gesture.durationMs
                        )
                    )
                }
                is Gesture.DoubleTap -> {
                    // Dos taps consecutivos
                    val path1 = Path().apply { moveTo(gesture.x, gesture.y) }
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            path1, 0, 50
                        )
                    )
                }
                is Gesture.Swipe -> {
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            gesture.path, 0, gesture.durationMs
                        )
                    )
                }
                is Gesture.Drag -> {
                    val path = Path().apply {
                        moveTo(gesture.startX, gesture.startY)
                        lineTo(gesture.endX, gesture.endY)
                    }
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, gesture.durationMs
                        )
                    )
                }
                is Gesture.LongPress -> {
                    val path = Path().apply { moveTo(gesture.x, gesture.y) }
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, gesture.durationMs, true  // willContinue
                        )
                    )
                }
                is Gesture.Pinch -> {
                    // Simplificado: dos swipes opuestos
                    val path1 = Path().apply {
                        moveTo(gesture.centerX - 50, gesture.centerY)
                        lineTo(gesture.centerX - 50 * gesture.scaleFactor, gesture.centerY)
                    }
                    val path2 = Path().apply {
                        moveTo(gesture.centerX + 50, gesture.centerY)
                        lineTo(gesture.centerX + 50 * gesture.scaleFactor, gesture.centerY)
                    }
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(path1, 0, 100)
                    )
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(path2, 0, 100)
                    )
                }
            }
            
            // Ejecutar
            val result = accessibilityService.dispatchGesture(
                gestureBuilder.build(),
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        Logger.d(TAG, "Gesture completed: ${gesture.type}")
                    }
                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        Logger.w(TAG, "Gesture cancelled: ${gesture.type}")
                    }
                },
                null
            )
            
            if (result) {
                gestureCount++
                gestureHistory.add(GestureRecord(gesture, System.currentTimeMillis()))
                if (gestureHistory.size > 100) gestureHistory.removeFirst()
            } else {
                failedGestures++
            }
            
            result
        } catch (e: Exception) {
            Logger.e(TAG, "Error executing gesture", e)
            failedGestures++
            false
        }
    }
    
    /**
     * Crea path Bezier para movimiento natural.
     */
    private fun createBezierPath(
        startX: Float, startY: Float,
        endX: Float, endY: Float
    ): Path {
        val path = Path()
        path.moveTo(startX, startY)
        
        // Punto de control para curva
        val controlX = (startX + endX) / 2 + (kotlin.random.Random.nextFloat() - 0.5f) * 20
        val controlY = (startY + endY) / 2 + (kotlin.random.Random.nextFloat() - 0.5f) * 20
        
        path.quadTo(controlX, controlY, endX, endY)
        return path
    }
    
    /**
     * Humaniza posición (variación aleatoria).
     */
    private fun humanizePosition(x: Float, y: Float): Pair<Float, Float> {
        val offsetX = (kotlin.random.Random.nextFloat() - 0.5f) * POSITION_VARIATION * 2
        val offsetY = (kotlin.random.Random.nextFloat() - 0.5f) * POSITION_VARIATION * 2
        return Pair(x + offsetX, y + offsetY)
    }
    
    /**
     * Humaniza timing.
     */
    private fun humanizeTiming(durationMs: Long): Long {
        val variation = (kotlin.random.Random.nextFloat() - 0.5f) * TIMING_VARIATION * 2
        return (durationMs + variation.toLong()).coerceAtLeast(10)
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): GestureStats {
        val successRate = if (gestureCount + failedGestures > 0) {
            gestureCount.toFloat() / (gestureCount + failedGestures)
        } else 0f
        
        return GestureStats(
            totalGestures = gestureCount,
            failedGestures = failedGestures,
            successRate = successRate,
            recentGestures = gestureHistory.takeLast(10).map { it.gesture.type }
        )
    }
    
    /**
     * Libera recursos.
     */
    fun shutdown() {
        scheduler.shutdown()
        Logger.i(TAG, "GestureEngine shutdown")
    }
}

/**
 * Tipos de gestos.
 */
sealed class Gesture(val type: String) {
    data class Tap(val x: Float, val y: Float, val durationMs: Long) : Gesture("TAP")
    data class DoubleTap(val x: Float, val y: Float) : Gesture("DOUBLE_TAP")
    data class Swipe(val path: Path, val durationMs: Long) : Gesture("SWIPE")
    data class Drag(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val durationMs: Long
    ) : Gesture("DRAG")
    data class LongPress(val x: Float, val y: Float, val durationMs: Long) : Gesture("LONG_PRESS")
    data class Pinch(val centerX: Float, val centerY: Float, val scaleFactor: Float) : Gesture("PINCH")
}

/**
 * Dirección de strafe.
 */
enum class StrafeDirection { LEFT, RIGHT }

/**
 * Registro de gesto.
 */
private data class GestureRecord(
    val gesture: Gesture,
    val timestamp: Long
)

/**
 * Estadísticas de gestos.
 */
data class GestureStats(
    val totalGestures: Int,
    val failedGestures: Int,
    val successRate: Float,
    val recentGestures: List<String>
)
