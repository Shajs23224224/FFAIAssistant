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
 * GestureController - Controlador avanzado de gestos multi-touch.
 * 
 * Soporta: TAP, HOLD, DRAG, HOLD_DRAG, SWIPE, MULTI_TOUCH, CONTINUOUS_DRAG
 * PID aim suavizado integrado. Humanización: offsets, delays, velocidad variable.
 */
@RequiresApi(Build.VERSION_CODES.N)
class GestureController(
    private val service: AccessibilityService,
    private val gameConfig: GameConfig
) {
    private val random = Random(System.currentTimeMillis())
    private var lastActionTime = 0L
    private val pidController = PIDController()
    private var continuousStrokeId: Int = 0

    /**
     * Ejecuta una acción del sistema de decisión.
     */
    fun execute(action: Action) {
        try {
            val now = System.currentTimeMillis()
            if (!isAccessibilityServiceEnabled()) {
                Logger.w("GestureController: AccessibilityService no utilizable para ${action.type}")
                return
            }
            if (now - lastActionTime < Constants.ACTION_COOLDOWN_MS) return
            lastActionTime = now

            when (action.type) {
                ActionType.AIM -> executeAimWithPID(action.x, action.y)
                ActionType.SHOOT -> executeShoot()
                ActionType.MOVE_FORWARD -> executeMove(0f, -action.confidence)
                ActionType.MOVE_BACKWARD -> executeMove(0f, action.confidence)
                ActionType.MOVE_LEFT -> executeMove(-action.confidence, 0f)
                ActionType.MOVE_RIGHT -> executeMove(action.confidence, 0f)
                ActionType.HEAL -> executeHoldTap(gameConfig.buttonHeal, 300L)
                ActionType.RELOAD -> executeHoldTap(gameConfig.buttonReload, 500L)
                ActionType.CROUCH -> executeHoldTap(gameConfig.buttonCrouch, 200L)
                ActionType.JUMP -> executeTap(gameConfig.buttonJump)
                ActionType.LOOT -> executeHoldTap(gameConfig.buttonLoot, 400L)
                ActionType.REVIVE -> executeHoldTap(gameConfig.buttonRevive, 2000L)
                ActionType.ROTATE_LEFT -> executeRotate(-0.5f)
                ActionType.ROTATE_RIGHT -> executeRotate(0.5f)
                ActionType.HOLD -> { /* No hacer nada */ }
            }
        } catch (e: Exception) {
            Logger.e("GestureController: Error ejecutando acción ${action.type}", e)
        }
    }

    /**
     * Ejecuta un GestureCommand avanzado (multi-touch, hold+drag, etc).
     */
    fun executeCommand(command: GestureCommand) {
        try {
            if (!isAccessibilityServiceEnabled()) {
                Logger.w("GestureController: AccessibilityService no utilizable para comando ${command.type}")
                return
            }
            when (command.type) {
                GestureType.TAP, GestureType.SINGLE_TAP -> { val p = command.pointers.firstOrNull() ?: return; tap(p.currentX, p.currentY, command.durationMs) }
                GestureType.HOLD -> { val p = command.pointers.firstOrNull() ?: return; hold(p.currentX, p.currentY, command.durationMs) }
                GestureType.DRAG -> { val p = command.pointers.firstOrNull() ?: return; drag(p.startX, p.startY, p.currentX, p.currentY, command.durationMs) }
                GestureType.HOLD_DRAG, GestureType.DRAG_HOLD -> { val p = command.pointers.firstOrNull() ?: return; holdAndDrag(p.startX, p.startY, p.currentX, p.currentY, command.durationMs) }
                GestureType.SWIPE, GestureType.CAMERA_SWIPE -> { val p = command.pointers.firstOrNull() ?: return; swipe(p.startX, p.startY, p.currentX, p.currentY, command.durationMs) }
                GestureType.MULTI_TOUCH -> executeMultiTouch(command.pointers, command.durationMs)
                GestureType.CONTINUOUS_DRAG -> { val p = command.pointers.firstOrNull() ?: return; continuousDrag(p.currentX, p.currentY, command.durationMs) }
                GestureType.DOUBLE_TAP, GestureType.TRIPLE_TAP, GestureType.COMBO -> { /* TODO: Implementar */ }
            }
        } catch (e: Exception) {
            Logger.e("GestureController: Error ejecutando comando ${command.type}", e)
        }
    }
    
    // === AIM CON PID ===

    private fun executeAimWithPID(targetX: Int, targetY: Int) {
        val cx = gameConfig.screenWidth / 2f
        val cy = gameConfig.screenHeight / 2f
        val offset = pidController.computeAdaptive(targetX.toFloat(), targetY.toFloat(), cx, cy)
        val dx = (offset.x / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
        val dy = (offset.y / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)
        joystickMove(gameConfig.joystickRight, dx, dy, 100)
    }

    fun resetAimPID() = pidController.reset()

    // === ACCIONES BÁSICAS ===

    private fun executeShoot() {
        humanizedDelay(30)
        tap(gameConfig.buttonFire.x, gameConfig.buttonFire.y, 80)
    }

    private fun executeMove(dx: Float, dy: Float) {
        joystickMove(gameConfig.joystickLeft, dx, dy, 200)
    }

    private fun executeRotate(direction: Float) {
        joystickMove(gameConfig.joystickRight, direction, 0f, 300)
    }

    private fun executeHoldTap(point: PointF, holdMs: Long) {
        hold(point.x, point.y, holdMs)
    }

    private fun executeTap(point: PointF) {
        tap(point.x, point.y)
    }
    
    // === JOYSTICK ===

    private fun joystickMove(center: PointF, dx: Float, dy: Float, durationMs: Long) {
        val radius = gameConfig.joystickRadius
        val fromX = center.x + humanizedOffset()
        val fromY = center.y + humanizedOffset()
        val toX = center.x + (dx * radius) + humanizedOffset()
        val toY = center.y + (dy * radius) + humanizedOffset()
        holdAndDrag(fromX, fromY, toX, toY, durationMs)
    }

    // === GESTOS PRIMITIVOS ===

    private fun tap(x: Float, y: Float, durationMs: Long = 80) {
        try {
            val fX = (x + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
            val fY = (y + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())
            val path = Path().apply { moveTo(fX, fY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGestureSafe(gesture, "tap")
        } catch (e: Exception) {
            Logger.e("GestureController: tap error", e)
        }
    }

    private fun hold(x: Float, y: Float, durationMs: Long = 500L) {
        try {
            val fX = (x + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
            val fY = (y + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())
            val path = Path().apply { moveTo(fX, fY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGestureSafe(gesture, "hold(${durationMs}ms)")
        } catch (e: Exception) {
            Logger.e("GestureController: hold error", e)
        }
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        try {
            val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGestureSafe(gesture, "drag")
        } catch (e: Exception) {
            Logger.e("GestureController: drag error", e)
        }
    }

    private fun holdAndDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        try {
            val hOff = humanizedOffset()
            val fX = (fromX + hOff).coerceIn(0f, gameConfig.screenWidth.toFloat())
            val fY = (fromY + hOff).coerceIn(0f, gameConfig.screenHeight.toFloat())
            val tX = (toX + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
            val tY = (toY + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())
            val path = Path().apply {
                moveTo(fX, fY)
                val midX = (fX + tX) / 2f + humanizedOffset() * 0.5f
                val midY = (fY + tY) / 2f + humanizedOffset() * 0.5f
                quadTo(midX, midY, tX, tY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGestureSafe(gesture, "hold+drag")
        } catch (e: Exception) {
            Logger.e("GestureController: holdAndDrag error", e)
        }
    }

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 150L) {
        drag(fromX, fromY, toX, toY, durationMs)
    }

    private fun continuousDrag(targetX: Float, targetY: Float, durationMs: Long = 100L) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val tX = (targetX + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
                val tY = (targetY + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())
                val path = Path().apply { moveTo(tX, tY) }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                    .continueStroke(path, durationMs, durationMs, false)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGestureSafe(gesture, "continuous-drag")
            } catch (e: Exception) {
                drag(targetX, targetY, targetX + humanizedOffset(), targetY + humanizedOffset(), durationMs)
            }
        } else {
            drag(targetX, targetY, targetX + humanizedOffset(), targetY + humanizedOffset(), durationMs)
        }
    }

    // === MULTI-TOUCH ===

    /**
     * Ejecuta gesto multi-touch: múltiples strokes simultáneos.
     * Cada TouchPointer → un stroke independiente.
     * Ej: mover joystick izq + apuntar joystick der + disparar al mismo tiempo.
     */
    private fun executeMultiTouch(pointers: List<TouchPointer>, durationMs: Long) {
        try {
            val builder = GestureDescription.Builder()
            for (pointer in pointers) {
                val path = Path().apply {
                    val sX = (pointer.startX + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
                    val sY = (pointer.startY + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())
                    moveTo(sX, sY)
                    if (pointer.currentX != pointer.startX || pointer.currentY != pointer.startY) {
                        val cX = (pointer.currentX + humanizedOffset()).coerceIn(0f, gameConfig.screenWidth.toFloat())
                        val cY = (pointer.currentY + humanizedOffset()).coerceIn(0f, gameConfig.screenHeight.toFloat())
                        lineTo(cX, cY)
                    }
                }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            }
            dispatchGestureSafe(builder.build(), "multi-touch(${pointers.size})")
        } catch (e: Exception) {
            Logger.e("GestureController: multi-touch error", e)
        }
    }

    /**
     * Ejecuta gesto compuesto: mover + apuntar + disparar simultáneo.
     * Combina joystick izquierdo (mover) + joystick derecho (apuntar) + botón fuego.
     */
    fun executeMoveAimShoot(moveDx: Float, moveDy: Float, aimDx: Float, aimDy: Float) {
        val radius = gameConfig.joystickRadius
        val moveCenter = gameConfig.joystickLeft
        val aimCenter = gameConfig.joystickRight
        val fireBtn = gameConfig.buttonFire

        val movePointer = TouchPointer(
            id = 0, startX = moveCenter.x, startY = moveCenter.y,
            currentX = moveCenter.x + moveDx * radius,
            currentY = moveCenter.y + moveDy * radius
        )
        val aimPointer = TouchPointer(
            id = 1, startX = aimCenter.x, startY = aimCenter.y,
            currentX = aimCenter.x + aimDx * radius,
            currentY = aimCenter.y + aimDy * radius
        )
        val firePointer = TouchPointer(
            id = 2, startX = fireBtn.x, startY = fireBtn.y,
            currentX = fireBtn.x, currentY = fireBtn.y
        )
        executeMultiTouch(listOf(movePointer, aimPointer, firePointer), 150L)
    }

    // === UTILIDADES ===

    private fun dispatchGestureSafe(gesture: GestureDescription, label: String) {
        try {
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Logger.d("GestureController: gesture completed for $label")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Logger.w("GestureController: gesture cancelled for $label")
                    }
                },
                null
            )
            if (!dispatched) Logger.w("GestureController: dispatch failed for $label")
        } catch (e: Exception) {
            Logger.w("GestureController: dispatch error for $label", e)
        }
    }

    private fun humanizedOffset(): Float {
        return (random.nextInt(Constants.HUMAN_OFFSET_PX * 2 + 1) - Constants.HUMAN_OFFSET_PX).toFloat()
    }

    private fun humanizedDelay(baseMs: Long) {
        val variance = random.nextInt(Constants.HUMAN_DELAY_VARIANCE_MS * 2 + 1) - Constants.HUMAN_DELAY_VARIANCE_MS
        SystemClock.sleep((baseMs + variance).coerceAtLeast(0))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                false
            } else {
                val root = service.rootInActiveWindow
                if (root == null) {
                    // En juegos/fullscreen rootInActiveWindow puede ser null y dispatchGesture seguir funcionando.
                    Logger.d("GestureController: rootInActiveWindow es null; continuando con dispatchGesture")
                }
                true
            }
        } catch (e: Exception) {
            Logger.w("GestureController: no se pudo validar AccessibilityService", e)
            false
        }
    }
}
