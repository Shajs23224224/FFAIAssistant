package com.ffai.assistant.core

/**
 * QuickVisualFeatures - Features visuales extraídos rápidamente del frame.
 *
 * Usado por ReflexEngine para decisiones ultra-rápidas sin pasar
 * por el modelo CNN completo.
 *
 * Extraído en Preprocessor.extractQuickFeatures():
 * - Muestreo de píxeles estratégicos (HUD + centro pantalla)
 * - Detección simple de color para enemigos (rojo/azul típico de Free Fire)
 * - Lectura de barras de vida/munición por color
 */
data class QuickVisualFeatures(
    // Estado del jugador (del HUD)
    val healthRatio: Float = 1.0f,
    val ammoRatio: Float = 1.0f,

    // Detección de enemigo
    val enemyPresent: Boolean = false,
    val enemyScreenX: Int = 0,
    val enemyScreenY: Int = 0,
    val enemyConfidence: Float = 0f,
    val enemyPersistence: Float = 0f,
    val enemyVelocityX: Float = 0f,
    val enemyVelocityY: Float = 0f,
    val centerThreat: Float = 0f,
    val recentDamageLikely: Boolean = false,

    // Zona segura
    val safeZoneIndicator: Float = 1.0f,  // 1.0 = seguro, <1.0 = peligro

    // Estado de acción (si está disparando, curando, etc)
    val isFiring: Boolean = false,
    val isHealing: Boolean = false,
    val combatIntensity: Float = 0f
) {
    companion object {
        val DEFAULT = QuickVisualFeatures()

        fun fromEnemyDetection(x: Int, y: Int, confidence: Float): QuickVisualFeatures {
            return QuickVisualFeatures(
                enemyPresent = confidence > 0.5f,
                enemyScreenX = x,
                enemyScreenY = y,
                enemyConfidence = confidence
            )
        }
    }
}
