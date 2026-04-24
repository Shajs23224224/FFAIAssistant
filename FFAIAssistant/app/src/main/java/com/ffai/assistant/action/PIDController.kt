package com.ffai.assistant.action

import android.graphics.PointF
import com.ffai.assistant.utils.Logger

/**
 * PIDController - Control PID para aim suavizado.
 * 
 * Suaviza movimientos de aim para parecer humanos.
 * Evita snaps instantáneos (detectable como bot).
 * 
 * Parámetros tuneables:
 * - kp: Ganancia proporcional (respuesta directa al error)
 * - ki: Ganancia integral (acumula error para corregir offset)
 * - kd: Ganancia derivativa (suaviza cambios bruscos)
 * 
 * Uso típico:
 *   val output = pid.compute(targetX, targetY, currentX, currentY)
 *   // output = PointF con offset suavizado a aplicar
 */
class PIDController(
    var kp: Float = 0.6f,
    var ki: Float = 0.1f,
    var kd: Float = 0.3f,
    private val maxIntegral: Float = 50f,
    private val maxOutput: Float = 300f
) {
    // Estado del PID
    private var integralX: Float = 0f
    private var integralY: Float = 0f
    private var prevErrorX: Float = 0f
    private var prevErrorY: Float = 0f
    private var lastTimeMs: Long = 0L

    // Modo de operación
    enum class AimMode {
        AGGRESSIVE,   // kp=0.8, ki=0.05, kd=0.15 → rápido, menos suave
        BALANCED,     // kp=0.6, ki=0.1, kd=0.3 → balance velocidad/suavidad
        STEALTHY      // kp=0.3, ki=0.15, kd=0.55 → lento, muy suave
    }

    var mode: AimMode = AimMode.BALANCED
        set(value) {
            field = value
            when (value) {
                AimMode.AGGRESSIVE -> { kp = 0.8f; ki = 0.05f; kd = 0.15f }
                AimMode.BALANCED -> { kp = 0.6f; ki = 0.1f; kd = 0.3f }
                AimMode.STEALTHY -> { kp = 0.3f; ki = 0.15f; kd = 0.55f }
            }
        }

    /**
     * Calcula el offset suavizado para mover el aim.
     * 
     * @param targetX Coordenada X del objetivo (enemigo)
     * @param targetY Coordenada Y del objetivo (enemigo)
     * @param currentX Coordenada X actual del crosshair
     * @param currentY Coordenada Y actual del crosshair
     * @return PointF con el offset (dx, dy) a aplicar al aim
     */
    fun compute(targetX: Float, targetY: Float, currentX: Float, currentY: Float): PointF {
        val now = System.currentTimeMillis()
        if (lastTimeMs == 0L) {
            lastTimeMs = now
            prevErrorX = targetX - currentX
            prevErrorY = targetY - currentY
            // Primer frame: solo proporcional
            return PointF(
                (prevErrorX * kp).coerceIn(-maxOutput, maxOutput),
                (prevErrorY * kp).coerceIn(-maxOutput, maxOutput)
            )
        }

        val dt = ((now - lastTimeMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastTimeMs = now

        // Error
        val errorX = targetX - currentX
        val errorY = targetY - currentY

        // Integral (acumulada, con anti-windup)
        integralX = (integralX + errorX * dt).coerceIn(-maxIntegral, maxIntegral)
        integralY = (integralY + errorY * dt).coerceIn(-maxIntegral, maxIntegral)

        // Derivativa
        val derivativeX = (errorX - prevErrorX) / dt
        val derivativeY = (errorY - prevErrorY) / dt

        // Output PID
        val outputX = (kp * errorX + ki * integralX + kd * derivativeX).coerceIn(-maxOutput, maxOutput)
        val outputY = (kp * errorY + ki * integralY + kd * derivativeY).coerceIn(-maxOutput, maxOutput)

        // Guardar error para siguiente iteración
        prevErrorX = errorX
        prevErrorY = errorY

        return PointF(outputX, outputY)
    }

    /**
     * Calcula el offset para aim basado en distancia al centro.
     * Ajusta automáticamente el modo según la distancia:
     * - Lejos: AGGRESSIVE (acercar rápido)
     * - Medio: BALANCED
     * - Cerca: STEALTHY (fino, preciso)
     */
    fun computeAdaptive(targetX: Float, targetY: Float, currentX: Float, currentY: Float): PointF {
        val distance = kotlin.math.sqrt(
            ((targetX - currentX) * (targetX - currentX) +
             (targetY - currentY) * (targetY - currentY)).toDouble()
        ).toFloat()

        mode = when {
            distance > 200f -> AimMode.AGGRESSIVE
            distance > 50f -> AimMode.BALANCED
            else -> AimMode.STEALTHY
        }

        return compute(targetX, targetY, currentX, currentY)
    }

    /**
     * Resetea el estado del PID (al cambiar objetivo o iniciar nuevo aim).
     */
    fun reset() {
        integralX = 0f
        integralY = 0f
        prevErrorX = 0f
        prevErrorY = 0f
        lastTimeMs = 0L
    }

    /**
     * Obtiene métricas del PID para debugging.
     */
    fun getMetrics(): PIDMetrics {
        return PIDMetrics(
            mode = mode,
            kp = kp, ki = ki, kd = kd,
            integralX = integralX,
            integralY = integralY,
            prevErrorX = prevErrorX,
            prevErrorY = prevErrorY
        )
    }

    data class PIDMetrics(
        val mode: AimMode,
        val kp: Float, val ki: Float, val kd: Float,
        val integralX: Float, val integralY: Float,
        val prevErrorX: Float, val prevErrorY: Float
    )
}
