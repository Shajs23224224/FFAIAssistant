package com.ffai.assistant.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer

/**
 * Preprocessor - Preprocesamiento eficiente de frames para inferencia TFLite.
 * 
 * Optimizaciones:
 * - Resize a 160x96 (tamaño óptimo para micro-CNN)
 * - Conversión RGBA→RGB directa a ByteBuffer (no pixel-by-pixel lento)
 * - ROI dinámico: solo procesa región de interés
 * - Buffer reuse: ByteBuffer pre-allocado, nunca GC
 * - Zero-copy cuando sea posible
 */
class Preprocessor(
    private val targetWidth: Int = TARGET_WIDTH,
    private val targetHeight: Int = TARGET_HEIGHT
) {
    // ByteBuffer reutilizable para output (RGB, listo para TFLite)
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(
        targetWidth * targetHeight * 3  // RGB = 3 bytes por pixel
    )

    // Bitmap temporal para resize (reutilizado)
    private var resizedBitmap: Bitmap? = null

    // Buffer para pixels intermedio
    private val pixelBuffer: IntArray = IntArray(targetWidth * targetHeight)

    companion object {
        const val TARGET_WIDTH = 160
        const val TARGET_HEIGHT = 96
        const val CHANNELS = 3  // RGB
        const val INPUT_SIZE = TARGET_WIDTH * TARGET_HEIGHT * CHANNELS
    }

    /**
     * Preprocesa un frame completo para inferencia.
     * 
     * Pipeline: ROI crop → Resize 160x96 → RGBA→RGB → ByteBuffer
     * 
     * @param bitmap Frame capturado (puede ser reciclado después)
     * @param roi Región de interés (null = pantalla completa)
     * @return ByteBuffer listo para TFLite (160x96x3, RGB, normalizado 0-1)
     */
    fun preprocess(bitmap: Bitmap, roi: Rect? = null): ByteBuffer {
        val startTime = System.currentTimeMillis()

        // 1. Aplicar ROI si está activo
        val sourceBitmap = if (roi != null && roi.width() > 0 && roi.height() > 0) {
            cropROI(bitmap, roi)
        } else {
            bitmap
        }

        // 2. Resize a 160x96
        val resized = resizeToTarget(sourceBitmap)

        // 3. Convertir RGBA→RGB ByteBuffer normalizado
        convertToRGBBuffer(resized)

        // 4. Preparar buffer para lectura
        outputBuffer.rewind()

        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed > 15) {
            Logger.w("Preprocessor: slow preprocess ${elapsed}ms")
        }

        // Reciclar bitmap de ROI si fue creado
        if (sourceBitmap !== bitmap && !sourceBitmap.isRecycled) {
            sourceBitmap.recycle()
        }

        return outputBuffer
    }

    /**
     * Obtiene el ByteBuffer de output sin re-procesar (para reusar último frame).
     */
    fun getLastOutput(): ByteBuffer {
        outputBuffer.rewind()
        return outputBuffer
    }

    /**
     * Extrae features visuales rápidas sin modelo (para ReflexEngine).
     * Analiza regiones clave del frame en lugar de toda la imagen.
     * 
     * @return QuickVisualFeatures con datos del HUD y detección básica
     */
    fun extractQuickFeatures(bitmap: Bitmap): QuickVisualFeatures {
        val w = bitmap.width
        val h = bitmap.height

        // Región HP: esquina superior izquierda
        val healthRatio = sampleRegionAvg(bitmap, 
            (w * 0.02).toInt(), (h * 0.88).toInt(),
            (w * 0.22).toInt(), (h * 0.96).toInt(),
            ColorChannel.GREEN
        )

        // Región ammo: esquina inferior derecha
        val ammoRatio = sampleRegionAvg(bitmap,
            (w * 0.78).toInt(), (h * 0.88).toInt(),
            (w * 0.98).toInt(), (h * 0.96).toInt(),
            ColorChannel.BRIGHTNESS
        )

        // Detección enemigo: región central (sampling sparse)
        val enemyResult = detectEnemySparse(bitmap)

        return QuickVisualFeatures(
            healthRatio = healthRatio,
            ammoRatio = ammoRatio,
            enemyPresent = enemyResult.present,
            enemyScreenX = enemyResult.screenX,
            enemyScreenY = enemyResult.screenY,
            enemyConfidence = enemyResult.confidence
        )
    }

    private fun cropROI(bitmap: Bitmap, roi: Rect): Bitmap {
        // Asegurar que ROI está dentro de bounds
        val safeLeft = roi.left.coerceIn(0, bitmap.width - 1)
        val safeTop = roi.top.coerceIn(0, bitmap.height - 1)
        val safeRight = roi.right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = roi.bottom.coerceIn(safeTop + 1, bitmap.height)

        return Bitmap.createBitmap(bitmap, safeLeft, safeTop,
            safeRight - safeLeft, safeBottom - safeTop)
    }

    private fun resizeToTarget(source: Bitmap): Bitmap {
        // Reutilizar bitmap temporal si las dimensiones coinciden
        if (resizedBitmap == null || resizedBitmap!!.width != targetWidth || resizedBitmap!!.height != targetHeight) {
            resizedBitmap?.recycle()
            resizedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }

        // Resize usando Android built-in (hardware accelerated cuando posible)
        val canvas = android.graphics.Canvas(resizedBitmap!!)
        val srcRect = android.graphics.Rect(0, 0, source.width, source.height)
        val dstRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
        canvas.drawBitmap(source, srcRect, dstRect, null)

        return resizedBitmap!!
    }

    private fun convertToRGBBuffer(bitmap: Bitmap) {
        // Obtener pixels en batch (mucho más rápido que getPixel individual)
        bitmap.getPixels(pixelBuffer, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        outputBuffer.rewind()

        // Convertir RGBA int[] → RGB byte[] normalizado [0,1]
        for (i in pixelBuffer.indices) {
            val pixel = pixelBuffer[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            outputBuffer.put(floatToByte(r))
            outputBuffer.put(floatToByte(g))
            outputBuffer.put(floatToByte(b))
        }
    }

    private fun floatToByte(value: Float): Byte {
        return (value.coerceIn(0f, 1f) * 255f).toInt().toByte()
    }

    private enum class ColorChannel { GREEN, RED, BRIGHTNESS }

    private fun sampleRegionAvg(
        bitmap: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int,
        channel: ColorChannel
    ): Float {
        var total = 0f
        var count = 0
        val step = 8  // Sample cada 8 pixels (rápido)

        for (y in top until bottom step step) {
            for (x in left until right step step) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    total += when (channel) {
                        ColorChannel.GREEN -> Color.green(pixel) / 255f
                        ColorChannel.RED -> Color.red(pixel) / 255f
                        ColorChannel.BRIGHTNESS -> {
                            (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / (3f * 255f)
                        }
                    }
                    count++
                }
            }
        }
        return if (count > 0) total / count else 0.5f
    }

    private data class EnemySparseResult(
        val present: Boolean,
        val screenX: Int,
        val screenY: Int,
        val confidence: Float
    )

    /**
     * Detección sparse de enemigos (ultra-rápida, para reflejos).
     * Grid search cada 20 pixels en región central.
     */
    private fun detectEnemySparse(bitmap: Bitmap): EnemySparseResult {
        val w = bitmap.width
        val h = bitmap.height
        val centerX = w / 2
        val centerY = h / 2
        val searchW = w / 3
        val searchH = h / 3

        var enemyX = 0f
        var enemyY = 0f
        var totalScore = 0f
        var hitCount = 0

        for (y in (centerY - searchH)..(centerY + searchH) step 20) {
            for (x in (centerX - searchW)..(centerX + searchW) step 20) {
                if (x in 0 until w && y in 0 until h) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    // Detectar colores de nametag enemigo (rojo/naranja vivo)
                    val isReddish = r > 150 && g > 50 && b < 100
                    val saturation = (maxOf(r, g, b) - minOf(r, g, b)).toFloat() / maxOf(1, maxOf(r, g, b))

                    val score = when {
                        isReddish && saturation > 0.5f -> 0.8f + saturation * 0.2f
                        r > 200 && saturation > 0.7f -> 0.6f
                        else -> 0f
                    }

                    if (score > 0.5f) {
                        enemyX += x * score
                        enemyY += y * score
                        totalScore += score
                        hitCount++
                    }
                }
            }
        }

        val present = hitCount > 3 && totalScore > 2f
        return if (present) {
            EnemySparseResult(
                present = true,
                screenX = (enemyX / totalScore).toInt(),
                screenY = (enemyY / totalScore).toInt(),
                confidence = (totalScore / hitCount).coerceIn(0f, 1f)
            )
        } else {
            EnemySparseResult(false, centerX, centerY, 0f)
        }
    }

    fun destroy() {
        resizedBitmap?.recycle()
        resizedBitmap = null
    }
}

/**
 * Features visuales rápidos extraídos sin modelo (para ReflexEngine).
 */
data class QuickVisualFeatures(
    val healthRatio: Float = 1f,
    val ammoRatio: Float = 1f,
    val enemyPresent: Boolean = false,
    val enemyScreenX: Int = 0,
    val enemyScreenY: Int = 0,
    val enemyConfidence: Float = 0f
)
