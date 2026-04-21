package com.ffai.assistant.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.ffai.assistant.config.Constants
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.utils.Logger
import kotlin.random.Random

/**
 * Controlador de gestos que inyecta toques en pantalla.
 * Usa AccessibilityService.dispatchGesture() para simular interacciones humanas.
 */
@RequiresApi(Build.VERSION_CODES.N)
class GestureController(
    private val service: AccessibilityService,
    private val gameConfig: GameConfig
) {
    
    private val random = Random(System.currentTimeMillis())
    private var lastActionTime = 0L
    
    /**
     * Ejecuta una acción completa.
     */
    fun execute(action: Action) {
        try {
            val now = System.currentTimeMillis()

            // Verificar que el servicio esté conectado
            if (!isAccessibilityServiceEnabled()) {
                Logger.w("GestureController: Servicio no habilitado")
                return
            }

            // Verificar cooldown entre acciones
            if (now - lastActionTime < Constants.ACTION_COOLDOWN_MS) {
                return
            }

            lastActionTime = now

            when (action.type) {
            ActionType.AIM -> {
                // Normalizar coordenadas de pantalla a rango -1..1
                val normX = (action.x / 1080f * 2f) - 1f
                val normY = (action.y / 1920f * 2f) - 1f
                executeAim(normX, normY)
            }
            ActionType.SHOOT -> executeShoot()
            ActionType.MOVE_FORWARD -> executeMove(0f, -action.confidence)
            ActionType.MOVE_BACKWARD -> executeMove(0f, action.confidence)
            ActionType.MOVE_LEFT -> executeMove(-action.confidence, 0f)
            ActionType.MOVE_RIGHT -> executeMove(action.confidence, 0f)
            ActionType.HEAL -> executeTap(gameConfig.buttonHeal, action.cooldownMs)
            ActionType.RELOAD -> executeTap(gameConfig.buttonReload, action.cooldownMs)
            ActionType.CROUCH -> executeTap(gameConfig.buttonCrouch, action.cooldownMs)
            ActionType.JUMP -> executeTap(gameConfig.buttonJump, action.cooldownMs)
            ActionType.LOOT -> executeTap(gameConfig.buttonLoot, action.cooldownMs)
            ActionType.REVIVE -> executeTap(gameConfig.buttonRevive, action.cooldownMs)
            ActionType.ROTATE_LEFT -> executeRotate(-0.5f)
            ActionType.ROTATE_RIGHT -> executeRotate(0.5f)
            ActionType.HOLD -> { /* No hacer nada */ }
            }
        } catch (e: Exception) {
            Logger.e("GestureController: Error ejecutando acción ${action.type}", e)
        }
    }
    
    /**
     * Apunta hacia una dirección específica usando el joystick derecho.
     */
    private fun executeAim(dx: Float, dy: Float) {
        val center = gameConfig.joystickRight
        joystickMove(center, dx, dy, durationMs = 100)
    }
    
    /**
     * Dispara tocando el botón de fuego.
     */
    private fun executeShoot() {
        // Primero apuntar si es necesario (simulado)
        humanizedDelay(50)
        
        val button = gameConfig.buttonFire
        tap(button.x, button.y, durationMs = 80)
    }
    
    /**
     * Mueve el personaje usando joystick izquierdo.
     */
    private fun executeMove(dx: Float, dy: Float) {
        val center = gameConfig.joystickLeft
        joystickMove(center, dx, dy, durationMs = 200)
    }
    
    /**
     * Rota la cámara.
     */
    private fun executeRotate(direction: Float) {
        val center = gameConfig.joystickRight
        joystickMove(center, direction, 0f, durationMs = 300)
    }
    
    /**
     * Simula movimiento de joystick.
     */
    private fun joystickMove(center: PointF, dx: Float, dy: Float, durationMs: Long) {
        val radius = gameConfig.joystickRadius
        
        val fromX = center.x + humanizedOffset()
        val fromY = center.y + humanizedOffset()
        val toX = center.x + (dx * radius) + humanizedOffset()
        val toY = center.y + (dy * radius) + humanizedOffset()
        
        drag(fromX, fromY, toX, toY, durationMs)
    }
    
    /**
     * Ejecuta tap simple con humanización.
     */
    private fun executeTap(point: PointF, waitAfterMs: Long = 0) {
        tap(point.x, point.y)
        
        if (waitAfterMs > 0) {
            humanizedDelay(waitAfterMs)
        }
    }
    
    /**
     * Tap simple en coordenadas.
     */
    private fun tap(x: Float, y: Float, durationMs: Long = 80) {
        try {
            val finalX = (x + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
            val finalY = (y + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())

            val path = Path().apply {
                moveTo(finalX, finalY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val dispatched = try {
                service.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                Logger.w("GestureController: Error en dispatchGesture (tap)", e)
                false
            }

            Logger.d("Tap en ($finalX, $finalY): success=$dispatched")
        } catch (e: Exception) {
            Logger.e("GestureController: Error en tap($x, $y)", e)
        }
    }
    
    /**
     * Drag de un punto a otro.
     */
    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        try {
            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val dispatched = try {
                service.dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                Logger.w("GestureController: Error en dispatchGesture (drag)", e)
                false
            }

            Logger.d("Drag: ($fromX, $fromY) -> ($toX, $toY): success=$dispatched")
        } catch (e: Exception) {
            Logger.e("GestureController: Error en drag", e)
        }
    }
    
    /**
     * Offset aleatorio para humanización.
     */
    private fun humanizedOffset(): Float {
        return (random.nextInt(Constants.HUMAN_OFFSET_PX * 2 + 1) - 
                Constants.HUMAN_OFFSET_PX).toFloat()
    }
    
    /**
     * Delay con variación aleatoria.
     */
    private fun humanizedDelay(baseMs: Long) {
        val variance = random.nextInt(Constants.HUMAN_DELAY_VARIANCE_MS * 2 + 1) - 
                       Constants.HUMAN_DELAY_VARIANCE_MS
        SystemClock.sleep((baseMs + variance).coerceAtLeast(0))
    }

    /**
     * Verifica si el servicio de accesibilidad está habilitado.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            service.rootInActiveWindow != null
        } catch (e: Exception) {
            false
        }
    }
}
