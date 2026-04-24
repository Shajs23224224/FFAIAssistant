package com.ffai.assistant.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CaptureManager - Gestiona captura de frames con buffer pool y frame skipping.
 * 
 * Optimizaciones:
 * - Buffer pool de Bitmaps reutilizables (evita GC pressure)
 * - Frame skipping configurable (1 de cada N frames → IA a 10-15Hz)
 * - Thread dedicado para captura
 * - Métricas de latencia en tiempo real
 */
class CaptureManager {

    // Configuración de frame skipping
    var frameSkipRatio: Int = 2  // Procesar 1 de cada N frames
        set(value) { field = value.coerceIn(1, 10) }

    // Buffer pool
    private val bitmapPool = ConcurrentLinkedQueue<Bitmap>()
    private var poolWidth: Int = 0
    private var poolHeight: Int = 0
    private val maxPoolSize = 6  // 2 en uso + 4 en pool

    // Frame counter para skipping
    private val frameCounter = AtomicLong(0)
    private var lastProcessedFrame = 0L

    // Callbacks
    var onFrameForInference: ((Bitmap, Long) -> Unit)? = null
    var onFrameSkipped: ((Bitmap) -> Unit)? = null

    // Stats
    private val totalCaptured = AtomicLong(0)
    private val totalProcessed = AtomicLong(0)
    private val totalSkipped = AtomicLong(0)
    private val lastStatsTime = AtomicLong(System.currentTimeMillis())

    // Capture thread
    private val captureThread = HandlerThread("CaptureThread", Thread.NORM_PRIORITY + 2)
    private var captureHandler: Handler? = null
    private val isRunning = AtomicBoolean(false)

    /**
     * Inicializa el buffer pool con dimensiones de pantalla.
     */
    fun initialize(screenWidth: Int, screenHeight: Int) {
        poolWidth = screenWidth
        poolHeight = screenHeight

        // Pre-alloc bitmaps en el pool
        for (i in 0 until maxPoolSize) {
            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmapPool.add(bitmap)
        }

        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        Logger.i("CaptureManager initialized: ${screenWidth}x${screenHeight}, pool=${maxPoolSize}, skip=$frameSkipRatio")
    }

    /**
     * Obtiene un Bitmap del pool. Si no hay disponibles, crea uno nuevo.
     */
    fun acquireBitmap(): Bitmap {
        val recycled = bitmapPool.poll()
        return if (recycled != null && !recycled.isRecycled) {
            recycled
        } else {
            Logger.w("CaptureManager: Pool empty, creating new bitmap")
            Bitmap.createBitmap(poolWidth, poolHeight, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Devuelve un Bitmap al pool para reutilización.
     */
    fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmapPool.size < maxPoolSize) {
            bitmapPool.add(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    /**
     * Procesa un frame capturado. Decide si debe ir a inferencia o ser skipped.
     * Debe ser llamado desde el callback de ImageReader.
     * 
     * @return true si el frame fue enviado a inferencia, false si fue skipped
     */
    fun onFrameCaptured(bitmap: Bitmap): Boolean {
        val frameNum = frameCounter.incrementAndGet()
        totalCaptured.incrementAndGet()

        val shouldProcess = frameNum % frameSkipRatio == 0L

        if (shouldProcess) {
            lastProcessedFrame = frameNum
            totalProcessed.incrementAndGet()
            onFrameForInference?.invoke(bitmap, frameNum)
            logStats()
            return true
        } else {
            totalSkipped.incrementAndGet()
            onFrameSkipped?.invoke(bitmap)
            // Reciclar bitmap skipped inmediatamente
            releaseBitmap(bitmap)
            return false
        }
    }

    /**
     * Fuerza el procesamiento del siguiente frame (ignora skip ratio).
     * Útil cuando se detecta un evento importante (disparo recibido, etc).
     */
    fun forceNextFrame() {
        frameCounter.incrementAndGet()  // Avanza counter para alinear con skip
    }

    /**
     * Ajusta dinámicamente el skip ratio basado en latencia.
     * Si latencia > target, aumenta skip. Si latencia < target, reduce skip.
     */
    fun adaptSkipRatio(currentLatencyMs: Long, targetLatencyMs: Long = 70L) {
        when {
            currentLatencyMs > targetLatencyMs * 1.5 -> {
                if (frameSkipRatio < 4) frameSkipRatio++
                Logger.d("CaptureManager: Increased skip ratio to $frameSkipRatio (latency=${currentLatencyMs}ms)")
            }
            currentLatencyMs < targetLatencyMs * 0.5 -> {
                if (frameSkipRatio > 1) frameSkipRatio--
                Logger.d("CaptureManager: Decreased skip ratio to $frameSkipRatio (latency=${currentLatencyMs}ms)")
            }
        }
    }

    private fun logStats() {
        val captured = totalCaptured.get()
        if (captured % 100 == 0L) {
            val processed = totalProcessed.get()
            val skipped = totalSkipped.get()
            val effectiveFps = if (captured > 0) processed * 100 / captured else 0
            Logger.d("CaptureManager stats: captured=$captured, processed=$processed, skipped=$skipped, effective=${effectiveFps}%")
        }
    }

    fun getStats(): CaptureStats {
        return CaptureStats(
            totalCaptured = totalCaptured.get(),
            totalProcessed = totalProcessed.get(),
            totalSkipped = totalSkipped.get(),
            currentSkipRatio = frameSkipRatio,
            poolSize = bitmapPool.size
        )
    }

    fun destroy() {
        isRunning.set(false)
        captureHandler?.looper?.quitSafely()
        captureThread.quitSafely()
        bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
        bitmapPool.clear()
        Logger.i("CaptureManager destroyed")
    }

    data class CaptureStats(
        val totalCaptured: Long,
        val totalProcessed: Long,
        val totalSkipped: Long,
        val currentSkipRatio: Int,
        val poolSize: Int
    )
}
