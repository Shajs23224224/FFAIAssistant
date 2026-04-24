package com.ffai.assistant.navigation

import android.graphics.Bitmap
import android.graphics.Color
import com.ffai.assistant.model.MapOutput
import com.ffai.assistant.model.MarkedLocation
import com.ffai.assistant.model.MarkerType
import com.ffai.assistant.model.Zone
import com.ffai.assistant.model.ZonePrediction
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * FASE 7: MapInterpreter - Interpretación avanzada de mapa y mini-mapa.
 *
 * Funcionalidades:
 * - Detección de posición del jugador en mapa completo
 * - Identificación de zona segura actual y predicción de siguiente
 * - Detección de puntos de interés (POI)
 * - Análisis de terreno y rutas
 * - Interpretación de mini-mapa en tiempo real
 */
class MapInterpreter {

    companion object {
        const val TAG = "MapInterpreter"
        
        // Colores típicos del mapa de Free Fire
        const val COLOR_SAFE_ZONE = 0xFF4CAF50.toInt() // Verde
        const val COLOR_NEXT_ZONE = 0xFFFF9800.toInt() // Naranja
        const val COLOR_WATER = 0xFF2196F3.toInt() // Azul
        const val COLOR_LAND = 0xFF8BC34A.toInt() // Verde claro
        const val COLOR_BUILDING = 0xFF795548.toInt() // Marrón
        const val COLOR_PLAYER_MARKER = 0xFFE91E63.toInt() // Rosa
        
        // Umbrales de detección
        const val ZONE_DETECTION_THRESHOLD = 50
        const val PLAYER_MARKER_SIZE = 8
    }

    // Cache de análisis
    private val mapCache = ConcurrentHashMap<String, MapAnalysis>()
    private var lastMapBitmap: Bitmap? = null
    private var lastMiniMapBitmap: Bitmap? = null
    
    // Estado
    private var currentPosition: Pair<Float, Float>? = null
    private var currentZone: Zone? = null
    private var nextZonePrediction: ZonePrediction? = null
    private var poiList = listOf<POI>()

    /**
     * Analiza mapa completo y extrae información.
     */
    fun analyzeMap(mapBitmap: Bitmap): MapOutput {
        val startTime = System.currentTimeMillis()
        
        // Detectar posición del jugador
        val playerPos = detectPlayerPosition(mapBitmap)
        currentPosition = playerPos
        
        // Detectar zona segura actual
        val safeZone = detectSafeZone(mapBitmap)
        currentZone = safeZone
        
        // Predecir siguiente zona
        val nextZone = predictNextZone(mapBitmap, safeZone, playerPos)
        nextZonePrediction = nextZone
        
        // Detectar POIs
        val pois = detectPOIs(mapBitmap)
        poiList = pois
        
        // Detectar marcadores del equipo
        val teamMarkers = detectTeamMarkers(mapBitmap)
        
        lastMapBitmap = mapBitmap
        
        Logger.d(TAG, "Mapa analizado en ${System.currentTimeMillis() - startTime}ms - " +
                "Pos: $playerPos, Zona: ${safeZone != null}, POIs: ${pois.size}")
        
        return MapOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            playerPosition = playerPos,
            currentSafeZone = safeZone,
            nextSafeZone = nextZone,
            markedLocations = teamMarkers,
            teammatePositions = emptyList(), // Detectar de mini-mapa
            confidence = if (playerPos != null) 0.8f else 0.4f
        )
    }

    /**
     * Analiza mini-mapa en tiempo real.
     */
    fun analyzeMiniMap(miniMapBitmap: Bitmap): MiniMapInfo {
        val startTime = System.currentTimeMillis()
        
        // Detectar posición relativa en mini-mapa
        val playerPos = detectPlayerInMiniMap(miniMapBitmap)
        
        // Detectar enemigos en mini-mapa (puntos rojos)
        val enemies = detectEnemiesInMiniMap(miniMapBitmap)
        
        // Detectar aliados (puntos azules/verdes)
        val allies = detectAlliesInMiniMap(miniMapBitmap)
        
        // Detectar zona en mini-mapa
        val zoneInfo = detectZoneInMiniMap(miniMapBitmap)
        
        lastMiniMapBitmap = miniMapBitmap
        
        return MiniMapInfo(
            playerPosition = playerPos,
            enemies = enemies,
            allies = allies,
            zoneVisible = zoneInfo.visible,
            zoneCenter = zoneInfo.center,
            zoneRadius = zoneInfo.radius,
            orientation = 0f // Detectar de brújula
        )
    }

    /**
     * Detecta posición del jugador en mapa completo.
     */
    private fun detectPlayerPosition(bitmap: Bitmap): Pair<Float, Float>? {
        // Buscar marcador de jugador (usualmente un punto/icono distintivo)
        var bestX = 0f
        var bestY = 0f
        var bestConfidence = 0f
        
        val width = bitmap.width
        val height = bitmap.height
        
        // Samplear puntos en cuadrícula
        val stepX = width / 20
        val stepY = height / 20
        
        for (x in 0 until width step stepX) {
            for (y in 0 until height step stepY) {
                val pixel = bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
                val confidence = matchPlayerMarkerColor(pixel)
                
                if (confidence > bestConfidence && confidence > 0.7f) {
                    bestConfidence = confidence
                    bestX = x.toFloat() / width
                    bestY = y.toFloat() / height
                }
            }
        }
        
        return if (bestConfidence > 0.5f) Pair(bestX, bestY) else null
    }

    /**
     * Detecta zona segura en mapa.
     */
    private fun detectSafeZone(bitmap: Bitmap): Zone? {
        val width = bitmap.width
        val height = bitmap.height
        
        // Detectar círculo blanco/azul claro (zona segura)
        var zonePixels = 0
        var sumX = 0f
        var sumY = 0f
        var minX = width.toFloat()
        var maxX = 0f
        var minY = height.toFloat()
        var maxY = 0f
        
        val step = 4 // Sampleo para velocidad
        
        for (x in 0 until width step step) {
            for (y in 0 until height step step) {
                val pixel = bitmap.getPixel(x, y)
                if (isSafeZoneColor(pixel)) {
                    zonePixels++
                    sumX += x
                    sumY += y
                    minX = kotlin.math.min(minX, x.toFloat())
                    maxX = kotlin.math.max(maxX, x.toFloat())
                    minY = kotlin.math.min(minY, y.toFloat())
                    maxY = kotlin.math.max(maxY, y.toFloat())
                }
            }
        }
        
        if (zonePixels < ZONE_DETECTION_THRESHOLD) return null
        
        val centerX = (sumX / zonePixels) / width
        val centerY = (sumY / zonePixels) / height
        
        // Estimar radio
        val radiusX = (maxX - minX) / 2 / width
        val radiusY = (maxY - minY) / 2 / height
        val radius = (radiusX + radiusY) / 2
        
        return Zone(
            centerX = centerX.coerceIn(0f, 1f),
            centerY = centerY.coerceIn(0f, 1f),
            radius = radius.coerceIn(0.01f, 0.5f),
            timeRemaining = null // Calcular de UI
        )
    }

    /**
     * Predice siguiente zona basado en patrón y posición actual.
     */
    private fun predictNextZone(
        bitmap: Bitmap,
        currentZone: Zone?,
        playerPos: Pair<Float, Float>?
    ): ZonePrediction? {
        currentZone ?: return null
        
        // Buscar indicador de siguiente zona (círculo naranja/punteado)
        var nextZoneCenterX = currentZone.centerX
        var nextZoneCenterY = currentZone.centerY
        var foundIndicator = false
        
        // Si hay siguiente zona visible
        val width = bitmap.width
        val height = bitmap.height
        
        for (x in 0 until width step 8) {
            for (y in 0 until height step 8) {
                val pixel = bitmap.getPixel(x, y)
                if (isNextZoneIndicator(pixel)) {
                    nextZoneCenterX = x.toFloat() / width
                    nextZoneCenterY = y.toFloat() / height
                    foundIndicator = true
                    break
                }
            }
            if (foundIndicator) break
        }
        
        // Si no encontramos indicador, predecir basado en patrón
        if (!foundIndicator && playerPos != null) {
            // Típicamente la siguiente zona está entre centro actual y jugador
            nextZoneCenterX = (currentZone.centerX + playerPos.first) / 2
            nextZoneCenterY = (currentZone.centerY + playerPos.second) / 2
        }
        
        return ZonePrediction(
            centerX = nextZoneCenterX.coerceIn(0f, 1f),
            centerY = nextZoneCenterY.coerceIn(0f, 1f),
            radius = currentZone.radius * 0.7f, // Típicamente 70% del anterior
            probability = if (foundIndicator) 0.9f else 0.6f
        )
    }

    /**
     * Detecta puntos de interés (POIs) en mapa.
     */
    private fun detectPOIs(bitmap: Bitmap): List<POI> {
        val pois = mutableListOf<POI>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Detectar diferentes tipos de terreno/ubicaciones
        val step = 10
        
        for (x in 0 until width step step) {
            for (y in 0 until height step step) {
                val pixel = bitmap.getPixel(x, y)
                val poiType = classifyPOI(pixel)
                
                if (poiType != POIType.UNKNOWN) {
                    pois.add(POI(
                        x = x.toFloat() / width,
                        y = y.toFloat() / height,
                        type = poiType,
                        confidence = 0.6f
                    ))
                }
            }
        }
        
        // Agrupar POIs cercanos
        return clusterPOIs(pois)
    }

    /**
     * Detecta marcadores del equipo en mapa.
     */
    private fun detectTeamMarkers(bitmap: Bitmap): List<MarkedLocation> {
        val markers = mutableListOf<MarkedLocation>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Buscar iconos de marcadores (triángulos, pines, etc.)
        val step = 6
        
        for (x in 0 until width step step) {
            for (y in 0 until height step step) {
                val pixel = bitmap.getPixel(x, y)
                
                when {
                    isEnemyMarker(pixel) -> {
                        markers.add(MarkedLocation(
                            x = x.toFloat() / width,
                            y = y.toFloat() / height,
                            type = MarkerType.ENEMY,
                            label = "Enemy spotted"
                        ))
                    }
                    isLootMarker(pixel) -> {
                        markers.add(MarkedLocation(
                            x = x.toFloat() / width,
                            y = y.toFloat() / height,
                            type = MarkerType.LOOT,
                            label = "Loot here"
                        ))
                    }
                    isVehicleMarker(pixel) -> {
                        markers.add(MarkedLocation(
                            x = x.toFloat() / width,
                            y = y.toFloat() / height,
                            type = MarkerType.VEHICLE,
                            label = "Vehicle"
                        ))
                    }
                }
            }
        }
        
        return markers
    }

    // ============================================
    // MINI-MAP DETECTION
    // ============================================

    private fun detectPlayerInMiniMap(bitmap: Bitmap): Pair<Float, Float> {
        // Jugador usualmente en centro del mini-mapa o marcado con flecha
        return Pair(0.5f, 0.5f) // Simplificado
    }

    private fun detectEnemiesInMiniMap(bitmap: Bitmap): List<Pair<Float, Float>> {
        val enemies = mutableListOf<Pair<Float, Float>>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Enemigos = puntos rojos
        for (x in 0 until width step 2) {
            for (y in 0 until height step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (isRedColor(pixel)) {
                    enemies.add(Pair(x.toFloat() / width, y.toFloat() / height))
                }
            }
        }
        
        return clusterPoints(enemies, 0.1f)
    }

    private fun detectAlliesInMiniMap(bitmap: Bitmap): List<Pair<Float, Float>> {
        val allies = mutableListOf<Pair<Float, Float>>()
        val width = bitmap.width
        val height = bitmap.height
        
        // Aliados = puntos azules/verdes
        for (x in 0 until width step 2) {
            for (y in 0 until height step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (isBlueColor(pixel) || isGreenColor(pixel)) {
                    allies.add(Pair(x.toFloat() / width, y.toFloat() / height))
                }
            }
        }
        
        return clusterPoints(allies, 0.1f)
    }

    private fun detectZoneInMiniMap(bitmap: Bitmap): ZoneInfo {
        // Detectar círculo blanco en mini-mapa
        return ZoneInfo(
            visible = true,
            center = Pair(0.5f, 0.5f),
            radius = 0.4f
        )
    }

    // ============================================
    // UTILIDADES DE COLOR
    // ============================================

    private fun matchPlayerMarkerColor(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        // Rosa brillante típico de marcador de jugador
        return if (r > 200 && g < 100 && b > 100) {
            (r - 200) / 55f
        } else 0f
    }

    private fun isSafeZoneColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        // Blanco/azul muy claro
        return r > 240 && g > 240 && b > 240
    }

    private fun isNextZoneIndicator(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        // Naranja/punteado
        return r > 200 && g > 100 && g < 180 && b < 100
    }

    private fun classifyPOI(pixel: Int): POIType {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        return when {
            r < 100 && g < 150 && b > 150 -> POIType.WATER
            r > 200 && g > 200 && b < 150 -> POIType.BEACH
            r > 100 && g > 150 && b < 100 -> POIType.FOREST
            r > 150 && g > 100 && b < 100 -> POIType.DESERT
            r > 100 && g > 100 && b > 100 && r < 200 -> POIType.MOUNTAIN
            else -> POIType.UNKNOWN
        }
    }

    private fun isEnemyMarker(pixel: Int): Boolean {
        val r = Color.red(pixel)
        return r > 200 && Color.green(pixel) < 100 && Color.blue(pixel) < 100
    }

    private fun isLootMarker(pixel: Int): Boolean {
        val g = Color.green(pixel)
        return g > 200 && Color.red(pixel) < 150 && Color.blue(pixel) < 150
    }

    private fun isVehicleMarker(pixel: Int): Boolean {
        val b = Color.blue(pixel)
        return b > 200 && Color.red(pixel) < 150 && Color.green(pixel) < 150
    }

    private fun isRedColor(pixel: Int): Boolean {
        return Color.red(pixel) > 180 && Color.green(pixel) < 100 && Color.blue(pixel) < 100
    }

    private fun isBlueColor(pixel: Int): Boolean {
        return Color.blue(pixel) > 180 && Color.red(pixel) < 100 && Color.green(pixel) < 100
    }

    private fun isGreenColor(pixel: Int): Boolean {
        return Color.green(pixel) > 180 && Color.red(pixel) < 100 && Color.blue(pixel) < 100
    }

    // ============================================
    // CLUSTERING
    // ============================================

    private fun clusterPOIs(pois: List<POI>): List<POI> {
        if (pois.isEmpty()) return emptyList()
        
        val clusters = mutableListOf<MutableList<POI>>()
        
        pois.forEach { poi ->
            val matchingCluster = clusters.find { cluster ->
                cluster.any { existing ->
                    kotlin.math.hypot(existing.x - poi.x, existing.y - poi.y) < 0.05f
                }
            }
            
            if (matchingCluster != null) {
                matchingCluster.add(poi)
            } else {
                clusters.add(mutableListOf(poi))
            }
        }
        
        return clusters.map { cluster ->
            val avgX = cluster.map { it.x }.average().toFloat()
            val avgY = cluster.map { it.y }.average().toFloat()
            val dominantType = cluster.groupingBy { it.type }.eachCount().maxByOrNull { it.value }?.key ?: POIType.UNKNOWN
            
            POI(
                x = avgX,
                y = avgY,
                type = dominantType,
                confidence = 0.7f
            )
        }
    }

    private fun clusterPoints(points: List<Pair<Float, Float>>, threshold: Float): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        
        val clusters = mutableListOf<MutableList<Pair<Float, Float>>>()
        
        points.forEach { point ->
            val matchingCluster = clusters.find { cluster ->
                cluster.any { existing ->
                    kotlin.math.hypot(existing.first - point.first, existing.second - point.second) < threshold
                }
            }
            
            if (matchingCluster != null) {
                matchingCluster.add(point)
            } else {
                clusters.add(mutableListOf(point))
            }
        }
        
        return clusters.map { cluster ->
            val avgX = cluster.map { it.first }.average().toFloat()
            val avgY = cluster.map { it.second }.average().toFloat()
            Pair(avgX, avgY)
        }
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    fun getCurrentPosition(): Pair<Float, Float>? = currentPosition
    fun getCurrentZone(): Zone? = currentZone
    fun getNextZonePrediction(): ZonePrediction? = nextZonePrediction
    fun getPOIList(): List<POI> = poiList

    fun isPositionInSafeZone(): Boolean {
        val pos = currentPosition ?: return false
        val zone = currentZone ?: return false
        
        val distance = kotlin.math.hypot(
            pos.first - zone.centerX.toDouble(),
            pos.second - zone.centerY.toDouble()
        )
        
        return distance < zone.radius
    }

    fun getDistanceToZoneEdge(): Float {
        val pos = currentPosition ?: return Float.MAX_VALUE
        val zone = currentZone ?: return Float.MAX_VALUE
        
        val distanceToCenter = kotlin.math.hypot(
            pos.first - zone.centerX.toDouble(),
            pos.second - zone.centerY.toDouble()
        ).toFloat()
        
        return (zone.radius - distanceToCenter).coerceAtLeast(0f)
    }

    fun reset() {
        currentPosition = null
        currentZone = null
        nextZonePrediction = null
        poiList = emptyList()
        lastMapBitmap = null
        lastMiniMapBitmap = null
        Logger.i(TAG, "MapInterpreter reseteado")
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class MiniMapInfo(
    val playerPosition: Pair<Float, Float>,
    val enemies: List<Pair<Float, Float>>,
    val allies: List<Pair<Float, Float>>,
    val zoneVisible: Boolean,
    val zoneCenter: Pair<Float, Float>?,
    val zoneRadius: Float,
    val orientation: Float
)

data class POI(
    val x: Float,
    val y: Float,
    val type: POIType,
    val confidence: Float
)

data class ZoneInfo(
    val visible: Boolean,
    val center: Pair<Float, Float>?,
    val radius: Float
)

enum class POIType {
    UNKNOWN, WATER, BEACH, FOREST, DESERT, MOUNTAIN, URBAN, MILITARY_BASE, DOCK
}

data class MapAnalysis(
    val timestamp: Long,
    val position: Pair<Float, Float>?,
    val safeZone: Zone?,
    val nextZone: ZonePrediction?,
    val pois: List<POI>,
    val confidence: Float
)
