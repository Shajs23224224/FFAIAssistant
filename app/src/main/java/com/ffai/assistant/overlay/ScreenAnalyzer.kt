package com.ffai.assistant.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FASE 1: ScreenAnalyzer - Captura y análisis de frames de pantalla.
 * 
 * Responsabilidades:
 * - Capturar frames a 30 FPS desde pantalla
 - Dividir en zonas de interés (Combat, Periférico, HUD)
 * - Cola de frames para procesamiento asíncrono
 * - Sistema de atención visual (prioridad dinámica)
 * - ROI dinámico ajustable
 */
class ScreenAnalyzer(
    private val width: Int = 720,
    private val height: Int = 1600,
    private val density: Int = 280
) : ImageReader.OnImageAvailableListener {

    companion object {
        const val TAG = "ScreenAnalyzer"
        const val MAX_FPS = 30
        const val FRAME_INTERVAL_MS = 33L // 1000 / 30
        const val MAX_QUEUE_SIZE = 3 // Evitar acumulación
    }

    // ImageReader para captura
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    
    // Thread de procesamiento
    private var processingThread: HandlerThread? = null
    private var processingHandler: Handler? = null
    
    // Estado
    private val isRunning = AtomicBoolean(false)
    private val lastFrameTime = AtomicInteger(0)
    private val frameCount = AtomicInteger(0)
    private val droppedFrames = AtomicInteger(0)
    
    // Cola de frames
    private val frameQueue = ConcurrentLinkedQueue<FrameData>()
    
    // Zonas de análisis (dinámicas)
    private var combatZone = Rect() // Centro - combate cercano
    private var peripheralZone = Rect() // Periférico - enemigos lejanos
    private var hudZone = Rect() // HUD - HP, munición, minimapa
    private var miniMapZone = Rect() // Mini-mapa específico
    
    // ROI actual
    private var currentROICenterX = width / 2f
    private var currentROICenterY = height / 2f
    private var currentROIScale = 1.0f
    
    // Callbacks
    private var onFrameAvailable: ((FrameData) -> Unit)? = null
    private var onAnalysisZone: ((AnalysisZones) -> Unit)? = null

    /**
     * Inicializa el ImageReader y thread de procesamiento.
     */
    fun initialize() {
        try {
            // Crear ImageReader con formato RGBA
            imageReader = ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                2 // Número de buffers
            )
            
            imageReader?.setOnImageAvailableListener(this, null)
            surface = imageReader?.surface
            
            // Thread de procesamiento
            processingThread = HandlerThread("ScreenAnalyzer").apply { start() }
            processingHandler = Handler(processingThread!!.looper)
            
            // Calcular zonas iniciales
            updateZones()
            
            Logger.i(TAG, "ScreenAnalyzer inicializado: ${width}x${height}")
        } catch (e: Exception) {
            Logger.e(TAG, "Error inicializando ScreenAnalyzer", e)
        }
    }

    /**
     * Retorna la Surface para captura.
     */
    fun getSurface(): Surface? = surface

    /**
     * Callback cuando hay imagen disponible.
     */
    override fun onImageAvailable(reader: ImageReader) {
        if (!isRunning.get()) return
        
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFrameTime.get()
        
        // Limitar a 30 FPS
        if (elapsed < FRAME_INTERVAL_MS) {
            // Dropear frame para mantener FPS
            reader.acquireLatestImage()?.close()
            droppedFrames.incrementAndGet()
            return
        }
        
        val image = reader.acquireLatestImage() ?: return
        
        try {
            lastFrameTime.set(currentTime.toInt())
            frameCount.incrementAndGet()
            
            // Convertir a Bitmap
            val bitmap = imageToBitmap(image)
            
            // Crear FrameData
            val frameData = FrameData(
                bitmap = bitmap,
                timestamp = currentTime,
                frameNumber = frameCount.get(),
                zones = getCurrentZones()
            )
            
            // Gestionar cola (evitar overflow)
            if (frameQueue.size >= MAX_QUEUE_SIZE) {
                frameQueue.poll()?.bitmap?.recycle() // Liberar memoria
                droppedFrames.incrementAndGet()
            }
            
            frameQueue.offer(frameData)
            
            // Notificar callback
            processingHandler?.post {
                onFrameAvailable?.invoke(frameData)
            }
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error procesando frame", e)
        } finally {
            image.close()
        }
    }

    /**
     * Inicia el análisis.
     */
    fun start() {
        isRunning.set(true)
        Logger.i(TAG, "ScreenAnalyzer iniciado")
    }

    /**
     * Detiene el análisis.
     */
    fun stop() {
        isRunning.set(false)
        
        // Limpiar cola
        while (frameQueue.isNotEmpty()) {
            frameQueue.poll()?.bitmap?.recycle()
        }
        
        Logger.i(TAG, "ScreenAnalyzer detenido. Frames: ${frameCount.get()}, Dropped: ${droppedFrames.get()}")
    }

    /**
     * Libera recursos.
     */
    fun release() {
        stop()
        
        imageReader?.close()
        surface?.release()
        processingThread?.quitSafely()
        
        imageReader = null
        surface = null
        processingThread = null
        processingHandler = null
        
        Logger.i(TAG, "ScreenAnalyzer liberado")
    }

    /**
     * Actualiza el ROI dinámico.
     */
    fun updateROI(centerX: Float, centerY: Float, scale: Float) {
        currentROICenterX = centerX
        currentROICenterY = centerY
        currentROIScale = scale.coerceIn(0.25f, 1.0f)
        
        updateZones()
    }

    /**
     * Actualiza las zonas de análisis basado en ROI actual.
     */
    private fun updateZones() {
        val roiWidth = (width * currentROIScale).toInt()
        val roiHeight = (height * currentROIScale).toInt()
        val roiLeft = (currentROICenterX - roiWidth / 2).toInt().coerceIn(0, width - roiWidth)
        val roiTop = (currentROICenterY - roiHeight / 2).toInt().coerceIn(0, height - roiHeight)
        val roiRect = Rect(roiLeft, roiTop, roiLeft + roiWidth, roiTop + roiHeight)
        
        // Zona de combate: Centro del ROI
        val combatSize = (kotlin.math.min(roiWidth, roiHeight) * 0.6).toInt()
        val combatLeft = roiLeft + (roiWidth - combatSize) / 2
        val combatTop = roiTop + (roiHeight - combatSize) / 2
        combatZone.set(combatLeft, combatTop, combatLeft + combatSize, combatTop + combatSize)
        
        // Zona periférica: Todo el ROI menos el centro
        peripheralZone.set(roiRect)
        
        // Zona HUD: Esquina superior derecha
        val hudWidth = (width * 0.3).toInt()
        val hudHeight = (height * 0.2).toInt()
        hudZone.set(width - hudWidth, 0, width, hudHeight)
        
        // Mini-mapa: Dentro de HUD, esquina superior derecha
        val mapSize = (kotlin.math.min(hudWidth, hudHeight) * 0.8).toInt()
        miniMapZone.set(width - mapSize - 20, 20, width - 20, 20 + mapSize)
        
        onAnalysisZone?.invoke(getCurrentZones())
    }

    /**
     * Obtiene las zonas actuales de análisis.
     */
    fun getCurrentZones(): AnalysisZones {
        return AnalysisZones(
            combat = combatZone,
            peripheral = peripheralZone,
            hud = hudZone,
            miniMap = miniMapZone,
            full = Rect(0, 0, width, height),
            roi = Rect(
                (currentROICenterX - width * currentROIScale / 2).toInt(),
                (currentROICenterY - height * currentROIScale / 2).toInt(),
                (currentROICenterX + width * currentROIScale / 2).toInt(),
                (currentROICenterY + height * currentROIScale / 2).toInt()
            )
        )
    }

    /**
     * Obtiene el siguiente frame de la cola (no bloqueante).
     */
    fun pollFrame(): FrameData? = frameQueue.poll()

    /**
     * Verifica si hay frames disponibles.
     */
    fun hasFrames(): Boolean = frameQueue.isNotEmpty()

    /**
     * Obtiene estadísticas.
     */
    fun getStats(): AnalyzerStats {
        val total = frameCount.get()
        val dropped = droppedFrames.get()
        return AnalyzerStats(
            totalFrames = total,
            droppedFrames = dropped,
            effectiveFps = if (total > 0) total.toFloat() / ((System.currentTimeMillis() % 100000) / 1000f).coerceAtLeast(1f) else 0f,
            queueSize = frameQueue.size,
            currentROI = currentROIScale
        )
    }

    // ============================================
    // CONVERSIÓN IMAGEN
    // ============================================

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        
        // Crear bitmap
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Si hay padding, recortar
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    // ============================================
    // SETTERS
    // ============================================

    fun setOnFrameAvailableListener(listener: (FrameData) -> Unit) {
        onFrameAvailable = listener
    }

    fun setOnAnalysisZoneListener(listener: (AnalysisZones) -> Unit) {
        onAnalysisZone = listener
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class FrameData(
    val bitmap: Bitmap,
    val timestamp: Long,
    val frameNumber: Int,
    val zones: AnalysisZones
) {
    /**
     * Extrae sub-bitmap de zona específica.
     */
    fun extractZone(zone: Rect): Bitmap {
        return Bitmap.createBitmap(
            bitmap,
            zone.left.coerceIn(0, bitmap.width - 1),
            zone.top.coerceIn(0, bitmap.height - 1),
            zone.width().coerceAtMost(bitmap.width - zone.left),
            zone.height().coerceAtMost(bitmap.height - zone.top)
        )
    }

    /**
     * Libera memoria del bitmap.
     */
    fun recycle() {
        bitmap.recycle()
    }
}

data class AnalysisZones(
    val combat: Rect,
    val peripheral: Rect,
    val hud: Rect,
    val miniMap: Rect,
    val full: Rect,
    val roi: Rect
)

data class AnalyzerStats(
    val totalFrames: Int,
    val droppedFrames: Int,
    val effectiveFps: Float,
    val queueSize: Int,
    val currentROI: Float
)
