package com.ffai.assistant.perception

import android.graphics.Bitmap
import com.ffai.assistant.core.ROITracker
import com.ffai.assistant.utils.Logger

/**
 * PerceptionEngine - Orquestador del sistema de percepción.
 *
 * Flujo:
 *   1. Intenta usar PerceptionModel (CNN TFLite) si está disponible
 *   2. Si falla o no está disponible → VisionProcessor legacy (pixel sampling)
 *   3. Aplica cache: si frame similar al anterior, reutiliza resultado
 *   4. Actualiza ROITracker basado en detecciones
 *
 * El engine decide cuál backend usar basado en disponibilidad y velocidad.
 */
class PerceptionEngine(
    private val roiTracker: ROITracker
) {
    // Modelo CNN (opcional, se carga si existe)
    private var perceptionModel: PerceptionModel? = null

    // Fallback legacy
    private val legacyVision = VisionProcessor()

    // Cache de último resultado
    private var lastOutput: PerceptionOutput = PerceptionOutput.EMPTY
    private var lastFrameHash: Int = 0
    private val cacheThreshold = 1000  // ms

    // Stats
    private var modelCalls: Long = 0
    private var fallbackCalls: Long = 0
    private var cacheHits: Long = 0

    /**
     * Inicializa el PerceptionModel si existe el archivo.
     */
    fun initializeModel(context: android.content.Context) {
        try {
            perceptionModel = PerceptionModel(context)
            if (perceptionModel?.isAvailable() == true) {
                Logger.i("PerceptionEngine: CNN model loaded successfully")
            } else {
                Logger.w("PerceptionEngine: CNN model not available, using fallback")
            }
        } catch (e: Exception) {
            Logger.e("PerceptionEngine: Failed to load CNN model", e)
            perceptionModel = null
        }
    }

    /**
     * Analiza un frame y extrae percepción del juego.
     *
     * @param bitmap Frame capturado
     * @param useCache Si true, intenta usar resultado cacheado
     * @return PerceptionOutput con detecciones
     */
    fun analyze(bitmap: Bitmap, useCache: Boolean = true): PerceptionOutput {
        val startTime = System.currentTimeMillis()

        // Check cache: si el frame es muy similar al anterior, reusar
        val frameHash = computeFrameHash(bitmap)
        if (useCache && frameHash == lastFrameHash) {
            cacheHits++
            return lastOutput
        }

        val output = try {
            if (perceptionModel?.isAvailable() == true) {
                // Usar CNN model
                modelCalls++
                perceptionModel?.analyze(bitmap) ?: run {
                    fallbackCalls++
                    analyzeWithFallback(bitmap)
                }
            } else {
                // Usar legacy
                fallbackCalls++
                analyzeWithFallback(bitmap)
            }
        } catch (e: Exception) {
            Logger.e("PerceptionEngine: Analysis failed, using fallback", e)
            fallbackCalls++
            analyzeWithFallback(bitmap)
        }

        // Actualizar ROI tracker si se detectaron enemigos
        if (output.hasEnemy) {
            val primary = output.primaryEnemy
            if (primary != null) {
                roiTracker.onEnemyDetected(primary.screenX.toInt(), primary.screenY.toInt())
            }
        } else {
            roiTracker.onNothingDetected()
        }

        // Cache
        lastFrameHash = frameHash
        lastOutput = output.copy(processingTimeMs = System.currentTimeMillis() - startTime)

        return lastOutput
    }

    /**
     * Análisis rápido usando VisionProcessor legacy.
     * Útil para reflejos cuando el CNN es lento o no disponible.
     */
    fun analyzeQuick(bitmap: Bitmap): PerceptionOutput {
        return analyzeWithFallback(bitmap)
    }

    private fun analyzeWithFallback(bitmap: Bitmap): PerceptionOutput {
        // Usar VisionProcessor legacy y convertir a PerceptionOutput
        val gameState = legacyVision.analyze(bitmap)

        // Convertir GameState a PerceptionOutput
        val hud = HUDState(
            health = gameState.healthRatio,
            ammo = gameState.ammoRatio,
            coins = 0,  // No detectado por legacy
            armor = if (gameState.hasArmor) 1f else 0f
        )

        val centroids = mutableListOf<EnemyCentroid>()
        if (gameState.enemyPresent) {
            // Convertir coordenadas normalizadas a pantalla
            val screenX = ((gameState.enemyX + 1f) / 2f * bitmap.width).toFloat()
            val screenY = ((gameState.enemyY + 1f) / 2f * bitmap.height).toFloat()

            centroids.add(EnemyCentroid(
                cellX = ((gameState.enemyX + 1f) / 2f * 20).toInt().coerceIn(0, 19),
                cellY = ((gameState.enemyY + 1f) / 2f * 12).toInt().coerceIn(0, 11),
                screenX = screenX,
                screenY = screenY,
                confidence = if (gameState.enemyPresent) 0.7f else 0f,
                size = (1f - gameState.enemyDistance).coerceIn(0f, 1f)
            ))
        }

        return PerceptionOutput(
            enemies = EnemyHeatmap(
                grid = generateHeatmapFromCentroids(centroids),
                centroids = centroids
            ),
            hud = hud,
            buttons = emptyList()  // Legacy no detecta botones
        )
    }

    /**
     * Genera heatmap grid 20x12 a partir de centroides.
     */
    private fun generateHeatmapFromCentroids(centroids: List<EnemyCentroid>): FloatArray {
        val grid = FloatArray(240) { 0f }

        for (centroid in centroids) {
            val idx = centroid.cellY * 20 + centroid.cellX
            if (idx in grid.indices) {
                grid[idx] = centroid.confidence
            }
            // Expandir influencia a celdas vecinas
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val ny = (centroid.cellY + dy).coerceIn(0, 11)
                    val nx = (centroid.cellX + dx).coerceIn(0, 19)
                    val nIdx = ny * 20 + nx
                    val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val influence = centroid.confidence * (1f - dist * 0.3f)
                    if (nIdx in grid.indices && grid[nIdx] < influence) {
                        grid[nIdx] = influence.coerceIn(0f, 1f)
                    }
                }
            }
        }

        return grid
    }

    /**
     * Hash simple del frame para cache (SSIM rápido aproximado).
     */
    private fun computeFrameHash(bitmap: Bitmap): Int {
        // Muestrear algunos píxeles clave
        var hash = 0
        val samplePoints = listOf(
            Pair(bitmap.width / 4, bitmap.height / 4),
            Pair(bitmap.width / 2, bitmap.height / 2),
            Pair(bitmap.width * 3 / 4, bitmap.height * 3 / 4)
        )

        for ((x, y) in samplePoints) {
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                hash = hash * 31 + bitmap.getPixel(x, y)
            }
        }

        return hash
    }

    fun getStats(): PerceptionStats {
        return PerceptionStats(
            modelCalls = modelCalls,
            fallbackCalls = fallbackCalls,
            cacheHits = cacheHits,
            isModelAvailable = perceptionModel?.isAvailable() ?: false
        )
    }

    fun destroy() {
        perceptionModel?.close()
    }

    data class PerceptionStats(
        val modelCalls: Long,
        val fallbackCalls: Long,
        val cacheHits: Long,
        val isModelAvailable: Boolean
    )
}
