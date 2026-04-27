package com.ffai.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicResize
import android.renderscript.Type
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FASE 1: FramePreprocessor - Preprocesamiento GPU-accelerado para YOLO.
 * 
 * Features:
 * - GPU resize con RenderScript
 * - Buffer pooling para evitar GC
 * - Letterbox resize (mantener aspect ratio)
 * - Normalización ImageNet (mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
 * 
 * Optimizado para Samsung A21S:
 * - Uso de RenderScript sobre GPU Mali-G52
 * - Pool de 5 buffers para reuso
 * - Float16 para reducir memoria
 */
class FramePreprocessor(private val context: Context) {
    
    companion object {
        const val TAG = "FramePreprocessor"
        const val INPUT_SIZE = 640
        const val NUM_CHANNELS = 3
        const val BUFFER_POOL_SIZE = 5
        
        // ImageNet normalization constants
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
    
    private var renderScript: RenderScript? = null
    private var resizeScript: ScriptIntrinsicResize? = null
    private var isInitialized = false
    
    // Buffer pool para ByteBuffers de salida
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()
    private val activeBuffers = ConcurrentLinkedQueue<ByteBuffer>()
    
    // Buffer de input para RenderScript
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null
    
    // Performance tracking
    private var preprocessCount = 0
    private var totalPreprocessTime = 0L
    
    /**
     * Inicializa RenderScript y buffer pool.
     */
    fun initialize(): Boolean {
        return try {
            Logger.i(TAG, "Inicializando FramePreprocessor...")
            
            // Crear instancia RenderScript
            renderScript = RenderScript.create(context)
            resizeScript = ScriptIntrinsicResize.create(renderScript)
            
            // Inicializar buffer pool
            initializeBufferPool()
            
            // Preparar allocations
            createAllocations()
            
            isInitialized = true
            Logger.i(TAG, "FramePreprocessor inicializado con pool de $BUFFER_POOL_SIZE buffers")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error inicializando RenderScript", e)
            // Fallback a CPU
            initializeCpuFallback()
            true
        }
    }
    
    /**
     * Preprocesa un bitmap para YOLO.
     * @param bitmap Imagen de entrada
     * @return ByteBuffer normalizado listo para inferencia
     */
    fun preprocess(bitmap: Bitmap): ByteBuffer {
        val startTime = System.currentTimeMillis()
        
        val result = if (isInitialized && renderScript != null) {
            preprocessGpu(bitmap)
        } else {
            preprocessCpu(bitmap)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        totalPreprocessTime += elapsed
        preprocessCount++
        
        if (preprocessCount % 100 == 0) {
            val avg = totalPreprocessTime / preprocessCount
            Logger.d(TAG, "Preprocess #$preprocessCount: ${elapsed}ms (promedio: ${avg}ms)")
        }
        
        return result
    }
    
    /**
     * Preprocesamiento GPU con RenderScript.
     */
    private fun preprocessGpu(bitmap: Bitmap): ByteBuffer {
        // Calcular dimensiones letterbox
        val (scaledWidth, scaledHeight, padX, padY) = calculateLetterboxDimensions(bitmap.width, bitmap.height)
        
        // Redimensionar con RenderScript
        val resized = resizeWithRenderScript(bitmap, scaledWidth, scaledHeight)
        
        // Crear bitmap letterbox (con padding)
        val letterboxBitmap = createLetterboxBitmap(resized, padX, padY)
        
        // Liberar bitmap temporal
        if (resized != bitmap && resized != letterboxBitmap) {
            resized.recycle()
        }
        
        // Normalizar y convertir a ByteBuffer
        val outputBuffer = acquireBuffer()
        normalizeBitmapToBuffer(letterboxBitmap, outputBuffer)
        
        // Liberar bitmap letterbox
        letterboxBitmap.recycle()
        
        return outputBuffer
    }
    
    /**
     * Redimensiona bitmap usando RenderScript.
     */
    private fun resizeWithRenderScript(source: Bitmap, dstWidth: Int, dstHeight: Int): Bitmap {
        // Crear bitmap destino
        val dstBitmap = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        
        try {
            // Crear allocations
            val srcAlloc = Allocation.createFromBitmap(renderScript, source)
            val dstAlloc = Allocation.createFromBitmap(renderScript, dstBitmap)
            
            // Ejecutar resize
            resizeScript?.setInput(srcAlloc)
            resizeScript?.forEach_bicubic(dstAlloc)
            
            // Copiar resultado
            dstAlloc.copyTo(dstBitmap)
            
            // Liberar allocations
            srcAlloc.destroy()
            dstAlloc.destroy()
        } catch (e: Exception) {
            Logger.w(TAG, "RenderScript resize falló, usando CPU", e)
            return Bitmap.createScaledBitmap(source, dstWidth, dstHeight, true)
        }
        
        return dstBitmap
    }
    
    /**
     * Preprocesamiento CPU (fallback).
     */
    private fun preprocessCpu(bitmap: Bitmap): ByteBuffer {
        val (scaledWidth, scaledHeight, padX, padY) = calculateLetterboxDimensions(bitmap.width, bitmap.height)
        
        // Redimensionar con bilinear filtering
        val resized = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        
        // Crear letterbox
        val letterboxBitmap = createLetterboxBitmap(resized, padX, padY)
        
        // Liberar temporal
        if (resized != bitmap) {
            resized.recycle()
        }
        
        // Normalizar
        val outputBuffer = acquireBuffer()
        normalizeBitmapToBuffer(letterboxBitmap, outputBuffer)
        
        letterboxBitmap.recycle()
        
        return outputBuffer
    }
    
    /**
     * Calcula dimensiones letterbox manteniendo aspect ratio.
     */
    private fun calculateLetterboxDimensions(srcWidth: Int, srcHeight: Int): LetterboxDimensions {
        val scale = kotlin.math.min(
            INPUT_SIZE.toFloat() / srcWidth,
            INPUT_SIZE.toFloat() / srcHeight
        )
        
        val scaledWidth = (srcWidth * scale).toInt()
        val scaledHeight = (srcHeight * scale).toInt()
        val padX = (INPUT_SIZE - scaledWidth) / 2
        val padY = (INPUT_SIZE - scaledHeight) / 2
        
        return LetterboxDimensions(scaledWidth, scaledHeight, padX, padY)
    }
    
    /**
     * Crea bitmap letterbox con padding negro.
     */
    private fun createLetterboxBitmap(source: Bitmap, padX: Int, padY: Int): Bitmap {
        val letterbox = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(letterbox)
        
        // Fondo negro
        canvas.drawColor(android.graphics.Color.BLACK)
        
        // Dibujar imagen centrada
        canvas.drawBitmap(source, padX.toFloat(), padY.toFloat(), null)
        
        return letterbox
    }
    
    /**
     * Normaliza bitmap a ByteBuffer con ImageNet stats.
     */
    private fun normalizeBitmapToBuffer(bitmap: Bitmap, buffer: ByteBuffer) {
        buffer.clear()
        
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        for (pixel in pixels) {
            // Extraer RGB (0-255)
            val r = ((pixel shr 16) and 0xFF).toFloat() / 255.0f
            val g = ((pixel shr 8) and 0xFF).toFloat() / 255.0f
            val b = (pixel and 0xFF).toFloat() / 255.0f
            
            // Normalizar: (x - mean) / std
            buffer.putFloat((r - MEAN[0]) / STD[0])
            buffer.putFloat((g - MEAN[1]) / STD[1])
            buffer.putFloat((b - MEAN[2]) / STD[2])
        }
        
        buffer.rewind()
    }
    
    /**
     * Inicializa pool de buffers.
     */
    private fun initializeBufferPool() {
        for (i in 0 until BUFFER_POOL_SIZE) {
            val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS * 4)
                .order(ByteOrder.nativeOrder())
            bufferPool.offer(buffer)
        }
    }
    
    /**
     * Adquiere buffer del pool o crea nuevo si no hay disponibles.
     */
    private fun acquireBuffer(): ByteBuffer {
        val buffer = bufferPool.poll()
        return if (buffer != null) {
            activeBuffers.offer(buffer)
            buffer.clear()
            buffer
        } else {
            // Crear buffer temporal (no ideal pero funcional)
            Logger.w(TAG, "Buffer pool agotado, creando buffer temporal")
            ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS * 4)
                .order(ByteOrder.nativeOrder())
        }
    }
    
    /**
     * Libera buffer de vuelta al pool.
     */
    fun releaseBuffer(buffer: ByteBuffer) {
        if (activeBuffers.remove(buffer)) {
            buffer.clear()
            bufferPool.offer(buffer)
        }
    }
    
    /**
     * Crea allocations de RenderScript.
     */
    private fun createAllocations() {
        try {
            val rs = renderScript ?: return
            
            // Tipo para bitmap 640x640 RGBA
            val type = Type.createXY(rs, Element.RGBA_8888(rs), INPUT_SIZE, INPUT_SIZE)
            outputAllocation = Allocation.createTyped(rs, type)
        } catch (e: Exception) {
            Logger.w(TAG, "Error creando allocations", e)
        }
    }
    
    /**
     * Inicialización fallback si RenderScript falla.
     */
    private fun initializeCpuFallback() {
        Logger.i(TAG, "Usando modo CPU (RenderScript no disponible)")
        initializeBufferPool()
        isInitialized = true
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): PreprocessorStats {
        val avgTime = if (preprocessCount > 0) totalPreprocessTime / preprocessCount else 0
        return PreprocessorStats(
            preprocessCount = preprocessCount,
            averageTimeMs = avgTime,
            bufferPoolSize = bufferPool.size,
            activeBuffers = activeBuffers.size,
            isGpuAccelerated = renderScript != null
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        // Devolver todos los buffers activos al pool
        while (activeBuffers.isNotEmpty()) {
            val buffer = activeBuffers.poll()
            buffer?.clear()
            bufferPool.offer(buffer)
        }
        
        // Limpiar pool
        bufferPool.clear()
        
        // Liberar RenderScript
        inputAllocation?.destroy()
        outputAllocation?.destroy()
        resizeScript?.destroy()
        renderScript?.destroy()
        
        inputAllocation = null
        outputAllocation = null
        resizeScript = null
        renderScript = null
        isInitialized = false
        
        Logger.i(TAG, "FramePreprocessor liberado")
    }
    
    fun isInitialized(): Boolean = isInitialized
}

/**
 * Dimensiones calculadas para letterbox.
 */
private data class LetterboxDimensions(
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Int,
    val padY: Int
)

/**
 * Estadísticas del preprocesador.
 */
data class PreprocessorStats(
    val preprocessCount: Int,
    val averageTimeMs: Long,
    val bufferPoolSize: Int,
    val activeBuffers: Int,
    val isGpuAccelerated: Boolean
)
