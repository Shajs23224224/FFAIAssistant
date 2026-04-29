package com.ffai.assistant.perception

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.utils.Logger
import kotlin.math.abs

/**
 * Procesa imágenes capturadas para extraer estado del juego.
 * Implementación nativa sin OpenCV (más ligero).
 */
class VisionProcessor {
    
    private var lastFrameTime = 0L
    private var frameCount = 0
    
    // Histórico para detección de cambios
    private var prevHpPixels = 0
    private var prevAmmoPixels = 0
    
    /**
     * Analiza un bitmap y extrae el estado del juego.
     */
    fun analyze(bitmap: Bitmap): GameState {
        frameCount++
        val currentTime = System.currentTimeMillis()
        
        // 1. Detectar vida
        val healthRatio = detectHealth(bitmap)
        
        // 2. Detectar munición
        val ammoRatio = detectAmmo(bitmap)
        
        // 3. Detectar enemigos
        val enemyDetection = detectEnemies(bitmap)
        
        // 4. Detectar estado adicional
        val isInSafeZone = detectSafeZone(bitmap)
        val safeZonePressure = detectSafeZonePressure(bitmap)
        val isAiming = detectAiming(bitmap)
        val isCrouching = detectCrouching(bitmap)
        val hasArmor = detectArmor(bitmap)
        val hasHelmet = detectHelmet(bitmap)
        val lootNearby = detectLootNearby(bitmap)
        val currentWeapon = detectWeaponType(bitmap)
        val hasGoodWeapon = currentWeapon != WeaponType.PISTOL &&
            currentWeapon != WeaponType.MELEE &&
            currentWeapon != WeaponType.UNKNOWN
        
        // 5. Detectar si necesita curación
        val hasHealItems = detectHealItems(bitmap)
        
        lastFrameTime = currentTime
        
        return GameState(
            healthRatio = healthRatio,
            ammoRatio = ammoRatio,
            enemyPresent = enemyDetection.present,
            enemyX = enemyDetection.x,
            enemyY = enemyDetection.y,
            enemyDistance = enemyDetection.distance,
            enemyCount = enemyDetection.count,
            isInSafeZone = isInSafeZone,
            isCrouching = isCrouching,
            isAiming = isAiming,
            currentWeapon = currentWeapon,
            lootNearby = lootNearby,
            hasGoodWeapon = hasGoodWeapon,
            hasArmor = hasArmor,
            hasHelmet = hasHelmet,
            hasHealItems = hasHealItems,
            safeZoneShrinking = safeZonePressure > 0.55f,
            distanceToSafeZone = if (isInSafeZone) 0f else safeZonePressure.coerceIn(0.15f, 1f),
            timestamp = currentTime
        )
    }
    
    /**
     * Detecta la barra de vida analizando píxeles rojos/verdes.
     */
    private fun detectHealth(bitmap: Bitmap): Float {
        // Región típica de HP: esquina superior izquierda
        val samplePoints = listOf(
            Pair(100, 150), Pair(200, 150), Pair(300, 150),
            Pair(100, 200), Pair(200, 200), Pair(300, 200)
        )
        
        var greenPixels = 0
        var redPixels = 0
        
        for ((x, y) in samplePoints) {
            if (x < bitmap.width && y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Verde = vida alta, Rojo = vida baja
                if (g > 100 && r < 100) greenPixels++
                else if (r > 100 && g < 100) redPixels++
            }
        }
        
        val total = greenPixels + redPixels
        return if (total > 0) greenPixels.toFloat() / total else 0.8f
    }
    
    /**
     * Detecta munición analizando números/numeración en pantalla.
     */
    private fun detectAmmo(bitmap: Bitmap): Float {
        // Simplificación: detectar color amarillo/blanco en zona de munición
        val samplePoints = listOf(
            Pair(150, 350), Pair(250, 350), Pair(350, 350)
        )
        
        var brightPixels = 0
        
        for ((x, y) in samplePoints) {
            if (x < bitmap.width && y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                if (brightness > 200) brightPixels++
            }
        }
        
        return brightPixels.toFloat() / samplePoints.size
    }
    
    /**
     * Detecta enemigos analizando colores característicos.
     */
    private fun detectEnemies(bitmap: Bitmap): EnemyDetection {
        val centerX = bitmap.width / 2
        val centerY = bitmap.height / 2
        val searchRadius = minOf(bitmap.width, bitmap.height) / 3
        
        var enemyX = 0f
        var enemyY = 0f
        var enemyPixels = 0
        var totalScore = 0f
        
        // Grid search eficiente (cada 10 píxeles)
        for (y in (centerY - searchRadius)..(centerY + searchRadius) step 10) {
            for (x in (centerX - searchRadius)..(centerX + searchRadius) step 10) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    val score = scoreEnemyPixel(pixel)
                    
                    if (score > 0.7f) {
                        enemyX += x * score
                        enemyY += y * score
                        totalScore += score
                        enemyPixels++
                    }
                }
            }
        }
        
        val present = enemyPixels > 5 && totalScore > 3f
        val countEstimate = estimateEnemyClusters(enemyPixels, totalScore)
        
        return if (present) {
            val avgX = enemyX / totalScore
            val avgY = enemyY / totalScore
            
            // Normalizar a -1 a 1
            val normalizedX = ((avgX - centerX) / searchRadius).coerceIn(-1f, 1f)
            val normalizedY = ((avgY - centerY) / searchRadius).coerceIn(-1f, 1f)
            
            // Distancia inversamente proporcional al tamaño del objeto detectado
            val distance = (1f - (enemyPixels / 50f).coerceIn(0f, 1f))
            
            EnemyDetection(true, normalizedX, normalizedY, distance, countEstimate)
        } else {
            EnemyDetection(false, 0f, 0f, 1f, 0)
        }
    }

    private fun estimateEnemyClusters(enemyPixels: Int, totalScore: Float): Int {
        return when {
            enemyPixels > 26 || totalScore > 13f -> 3
            enemyPixels > 14 || totalScore > 7f -> 2
            else -> 1
        }
    }
    
    /**
     * Score de probabilidad de que un píxel sea parte de un enemigo.
     */
    private fun scoreEnemyPixel(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        
        // Enemigos típicamente tienen colores vivos (altos valores RGB)
        val brightness = (r + g + b) / 3
        
        // Detectar rojo/naranja predominante
        val isReddish = r > 150 && g > 50 && b < 100
        
        // Detectar alta saturación
        val saturation = if (brightness > 0) {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            (max - min).toFloat() / max
        } else 0f
        
        return when {
            isReddish && brightness > 100 -> 0.8f + saturation * 0.2f
            brightness > 200 && saturation > 0.7f -> 0.6f
            else -> 0f
        }
    }
    
    /**
     * Detecta si está en zona segura (azul) o fuera (rojo).
     */
    private fun detectSafeZone(bitmap: Bitmap): Boolean {
        // Muestra zona del minimapa
        val sampleX = bitmap.width - 100
        val sampleY = 100
        
        if (sampleX < bitmap.width && sampleY < bitmap.height) {
            val pixel = bitmap.getPixel(sampleX, sampleY)
            val b = Color.blue(pixel)
            val r = Color.red(pixel)
            
            // Zona segura = predominio azul
            return b > r
        }
        
        return true  // Asumir seguro por defecto
    }
    
    /**
     * Detecta si hay items de curación disponibles.
     */
    private fun detectHealItems(bitmap: Bitmap): Boolean {
        // Detectar icono de botiquín (blanco con cruz o similar)
        val samplePoints = listOf(
            Pair(bitmap.width - 200, 400),
            Pair(bitmap.width - 150, 400)
        )
        
        for ((x, y) in samplePoints) {
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                if (brightness > 200) return true
            }
        }
        
        return false
    }

    private fun detectSafeZonePressure(bitmap: Bitmap): Float {
        val edgeBlue = sampleBlue(bitmap, 0.86f, 0.10f, 0.98f, 0.22f)
        val edgeRed = sampleRed(bitmap, 0.02f, 0.10f, 0.16f, 0.22f)
        return ((edgeBlue + edgeRed) * 0.5f).coerceIn(0f, 1f)
    }

    private fun detectAiming(bitmap: Bitmap): Boolean {
        return sampleBrightness(bitmap, 0.46f, 0.42f, 0.54f, 0.58f) > 0.68f
    }

    private fun detectCrouching(bitmap: Bitmap): Boolean {
        return sampleBrightness(bitmap, 0.72f, 0.78f, 0.84f, 0.90f) > 0.70f
    }

    private fun detectArmor(bitmap: Bitmap): Boolean {
        return sampleBlue(bitmap, 0.03f, 0.83f, 0.12f, 0.90f) > 0.45f
    }

    private fun detectHelmet(bitmap: Bitmap): Boolean {
        return sampleBlue(bitmap, 0.12f, 0.83f, 0.21f, 0.90f) > 0.40f
    }

    private fun detectLootNearby(bitmap: Bitmap): Boolean {
        val lootBrightness = sampleBrightness(bitmap, 0.35f, 0.60f, 0.65f, 0.82f)
        val lootRed = sampleRed(bitmap, 0.35f, 0.60f, 0.65f, 0.82f)
        return lootBrightness > 0.62f || lootRed > 0.56f
    }

    private fun detectWeaponType(bitmap: Bitmap): WeaponType {
        val ammoPanelBrightness = sampleBrightness(bitmap, 0.73f, 0.84f, 0.98f, 0.96f)
        val centralContrast = sampleContrast(bitmap, 0.70f, 0.78f, 0.98f, 0.96f)
        return when {
            ammoPanelBrightness > 0.82f && centralContrast < 0.18f -> WeaponType.SNIPER
            ammoPanelBrightness < 0.48f -> WeaponType.SHOTGUN
            centralContrast > 0.32f -> WeaponType.SMG
            ammoPanelBrightness > 0.68f -> WeaponType.ASSAULT_RIFLE
            else -> WeaponType.UNKNOWN
        }
    }

    private fun sampleBrightness(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Float {
        return sampleRegion(bitmap, left, top, right, bottom) { r, g, b ->
            (r + g + b) / (3f * 255f)
        }
    }

    private fun sampleRed(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Float {
        return sampleRegion(bitmap, left, top, right, bottom) { r, _, _ -> r / 255f }
    }

    private fun sampleBlue(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Float {
        return sampleRegion(bitmap, left, top, right, bottom) { _, _, b -> b / 255f }
    }

    private fun sampleContrast(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Float {
        var minValue = 1f
        var maxValue = 0f
        sampleRegion(bitmap, left, top, right, bottom) { r, g, b ->
            val value = (r + g + b) / (3f * 255f)
            if (value < minValue) minValue = value
            if (value > maxValue) maxValue = value
            value
        }
        return (maxValue - minValue).coerceIn(0f, 1f)
    }

    private fun sampleRegion(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        extractor: (Float, Float, Float) -> Float
    ): Float {
        val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (bitmap.width * right).toInt().coerceIn(x0 + 1, bitmap.width)
        val y1 = (bitmap.height * bottom).toInt().coerceIn(y0 + 1, bitmap.height)

        var total = 0f
        var count = 0
        for (y in y0 until y1 step 10) {
            for (x in x0 until x1 step 10) {
                val pixel = bitmap.getPixel(x, y)
                total += extractor(
                    Color.red(pixel).toFloat(),
                    Color.green(pixel).toFloat(),
                    Color.blue(pixel).toFloat()
                )
                count++
            }
        }
        return if (count > 0) total / count else 0f
    }
    
    private data class EnemyDetection(
        val present: Boolean,
        val x: Float,
        val y: Float,
        val distance: Float,
        val count: Int
    )
}
