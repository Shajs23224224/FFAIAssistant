package com.ffai.assistant.perception

import android.graphics.Rect

/**
 * PerceptionOutput - Datos extraídos del frame por el modelo CNN.
 *
 * Contiene:
 * - EnemyHeatmap: Grid 20x12 con probabilidades de enemigos
 * - HUDState: Vida, munición, monedas, armadura
 * - ButtonDetection: Hasta 8 botones detectados con coordenadas
 * - ProcessingTimeMs: Latencia de inferencia
 */
data class PerceptionOutput(
    val enemies: EnemyHeatmap,
    val hud: HUDState,
    val buttons: List<ButtonDetection>,
    val processingTimeMs: Long = 0L
) {
    /**
     * True si hay al menos un enemigo detectado con confianza > 0.5
     */
    val hasEnemy: Boolean get() = enemies.centroids.isNotEmpty()

    /**
     * Enemigo más cercano al centro de la pantalla
     */
    val primaryEnemy: EnemyCentroid?
        get() = enemies.centroids.maxByOrNull { it.confidence }

    companion object {
        val EMPTY = PerceptionOutput(
            enemies = EnemyHeatmap.EMPTY,
            hud = HUDState(),
            buttons = emptyList()
        )
    }
}

/**
 * Heatmap de enemigos en grid 20x12.
 * Cada celda contiene probabilidad [0,1] de que haya un enemigo.
 */
data class EnemyHeatmap(
    val grid: FloatArray,  // 20x12 = 240 valores
    val centroids: List<EnemyCentroid>,  // Centros de enemigos detectados
    val width: Int = 20,
    val height: Int = 12
) {
    /**
     * Obtiene probabilidad de una celda específica.
     */
    fun getProbability(cellX: Int, cellY: Int): Float {
        val idx = cellY * width + cellX
        return if (idx in grid.indices) grid[idx] else 0f
    }

    companion object {
        val EMPTY = EnemyHeatmap(
            grid = FloatArray(240) { 0f },
            centroids = emptyList()
        )
    }
}

/**
 * Centro de un enemigo detectado con metadatos.
 */
data class EnemyCentroid(
    val cellX: Int,        // Posición X en grid (0-19)
    val cellY: Int,        // Posición Y en grid (0-11)
    val screenX: Float,    // Coordenada X en pantalla
    val screenY: Float,    // Coordenada Y en pantalla
    val confidence: Float, // Confianza de la detección [0,1]
    val size: Float        // Tamaño aproximado (estimación de distancia inversa)
)

/**
 * Estado del HUD detectado del frame.
 */
data class HUDState(
    val health: Float = 1.0f,      // Vida normalizada 0-1
    val ammo: Float = 1.0f,        // Munición normalizada 0-1
    val coins: Int = 0,            // Monedas disponibles
    val armor: Float = 1.0f,       // Armadura normalizada 0-1
    val hasHelmet: Boolean = true,
    val hasArmor: Boolean = true
) {
    val healthCritical: Boolean get() = health < 0.25f
    val healthLow: Boolean get() = health < 0.5f
    val ammoCritical: Boolean get() = ammo < 0.1f
    val ammoLow: Boolean get() = ammo < 0.3f
}

/**
 * Botón detectado en pantalla.
 */
data class ButtonDetection(
    val type: ButtonType,
    val screenX: Float,
    val screenY: Float,
    val width: Float = 80f,
    val height: Float = 80f,
    val confidence: Float,
    val boundingBox: Rect = Rect(
        (screenX - width/2).toInt(),
        (screenY - height/2).toInt(),
        (screenX + width/2).toInt(),
        (screenY + height/2).toInt()
    )
)

enum class ButtonType {
    FIRE,      // Botón de disparar
    JUMP,      // Botón de saltar
    CROUCH,    // Botón de agacharse
    RELOAD,    // Botón de recargar
    HEAL,      // Botón de curar
    LOOT,      // Botón de saquear
    REVIVE,    // Botón de revivir
    INVENTORY, // Inventario
    MAP,       // Mapa
    UNKNOWN    // Otro tipo no identificado
}
