package com.ffai.assistant.core

import com.ffai.assistant.action.GestureController
import com.ffai.assistant.action.PIDController
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.utils.Logger

/**
 * AimController - Orquesta aim con PID + detección de enemigo.
 * 
 * Recibe centroid del heatmap de enemigo, calcula error respecto al 
 * centro de pantalla, aplica PID para generar movimiento suave,
 * y ajusta velocidad según distancia.
 */
class AimController(
    private val gameConfig: GameConfig,
    private val gestureController: GestureController
) {
    private val pid = PIDController()

    // Estado del aim
    private var isAiming: Boolean = false
    private var currentTargetX: Float = 0f
    private var currentTargetY: Float = 0f
    private var targetLostFrames: Int = 0
    private val maxTargetLostFrames: Int = 10

    // Centro de pantalla (crosshair)
    private val crosshairX: Float get() = gameConfig.screenWidth / 2f
    private val crosshairY: Float get() = gameConfig.screenHeight / 2f

    /**
     * Actualiza el aim basado en detección de enemigo del heatmap.
     * 
     * @param enemyScreenX Coordenada X del enemigo en pantalla (-1 si no detectado)
     * @param enemyScreenY Coordenada Y del enemigo en pantalla (-1 si no detectado)
     * @param confidence Confianza de la detección (0-1)
     * @return true si se ejecutó un ajuste de aim
     */
    fun updateAim(enemyScreenX: Float, enemyScreenY: Float, confidence: Float): Boolean {
        if (enemyScreenX < 0 || enemyScreenY < 0 || confidence < 0.4f) {
            // Enemigo perdido
            targetLostFrames++
            if (targetLostFrames > maxTargetLostFrames && isAiming) {
                stopAiming()
            }
            return false
        }

        targetLostFrames = 0

        // Solo apuntar si el enemigo no está ya centrado
        val dx = enemyScreenX - crosshairX
        val dy = enemyScreenY - crosshairY
        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        // Si está muy cerca del centro, no mover (evitar jitter)
        if (distance < 15f) {
            return false
        }

        // Calcular offset PID
        val offset = pid.computeAdaptive(
            enemyScreenX, enemyScreenY,
            crosshairX, crosshairY
        )

        // Convertir offset a dirección del joystick derecho
        val aimDx = (offset.x / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
        val aimDy = (offset.y / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)

        // Ejecutar aim via joystick derecho
        currentTargetX = enemyScreenX
        currentTargetY = enemyScreenY
        isAiming = true

        // Mover joystick derecho para apuntar
        val center = gameConfig.joystickRight
        val radius = gameConfig.joystickRadius
        gestureController.executeCommand(
            com.ffai.assistant.action.GestureCommand(
                type = com.ffai.assistant.action.GestureType.HOLD_DRAG,
                pointers = listOf(
                    com.ffai.assistant.action.TouchPointer(
                        id = 1,
                        startX = center.x,
                        startY = center.y,
                        currentX = center.x + aimDx * radius,
                        currentY = center.y + aimDy * radius
                    )
                ),
                durationMs = 80L,
                tag = "aim-pid"
            )
        )

        return true
    }

    /**
     * Ejecuta aim + shoot simultáneo (combate).
     * Mueve joystick derecho + toca botón fuego al mismo tiempo.
     */
    fun aimAndShoot(enemyScreenX: Float, enemyScreenY: Float, confidence: Float): Boolean {
        if (confidence < 0.5f) return false

        val aimDx = ((enemyScreenX - crosshairX) / gameConfig.screenWidth * 2f).coerceIn(-1f, 1f)
        val aimDy = ((enemyScreenY - crosshairY) / gameConfig.screenHeight * 2f).coerceIn(-1f, 1f)

        // Multi-touch: apuntar + disparar simultáneo
        gestureController.executeMoveAimShoot(
            moveDx = 0f, moveDy = 0f,  // Sin movimiento
            aimDx = aimDx, aimDy = aimDy
        )

        isAiming = true
        return true
    }

    /**
     * Detiene el aim (resetea PID y estado).
     */
    fun stopAiming() {
        pid.reset()
        isAiming = false
        currentTargetX = 0f
        currentTargetY = 0f
        targetLostFrames = 0
    }

    fun isCurrentlyAiming(): Boolean = isAiming

    fun getTarget(): Pair<Float, Float> = Pair(currentTargetX, currentTargetY)
}
