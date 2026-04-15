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
            hasHealItems = hasHealItems
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
        
        return if (present) {
            val avgX = enemyX / totalScore
            val avgY = enemyY / totalScore
            
            // Normalizar a -1 a 1
            val normalizedX = ((avgX - centerX) / searchRadius).coerceIn(-1f, 1f)
            val normalizedY = ((avgY - centerY) / searchRadius).coerceIn(-1f, 1f)
            
            // Distancia inversamente proporcional al tamaño del objeto detectado
            val distance = (1f - (enemyPixels / 50f).coerceIn(0f, 1f))
            
            EnemyDetection(true, normalizedX, normalizedY, distance, 1)
        } else {
            EnemyDetection(false, 0f, 0f, 1f, 0)
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
    
    private data class EnemyDetection(
        val present: Boolean,
        val x: Float,
        val y: Float,
        val distance: Float,
        val count: Int
    )
}
