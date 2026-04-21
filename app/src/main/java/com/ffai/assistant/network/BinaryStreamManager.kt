package com.ffai.assistant.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ffai.assistant.utils.Logger
import com.github.luben.zstd.Zstd
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * BinaryStreamManager - Gestión avanzada de streaming binario con compresión
 * 
 * Features:
 * - Compresión Zstd para payloads grandes
 * - Chunking de frames grandes (>64KB)
 * - Adaptive JPEG quality basado en rendimiento
 * - Cola de transmisión con backpressure
 * - Estadísticas de throughput
 */
class BinaryStreamManager private constructor() {
    
    companion object {
        @Volatile
        private var instance: BinaryStreamManager? = null
        
        fun getInstance(): BinaryStreamManager {
            return instance ?: synchronized(this) {
                instance ?: BinaryStreamManager().also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Cola de transmisión
    private val transmitQueue = LinkedBlockingQueue<FramePacket>(ServerConfig.FRAME_BUFFER_SIZE)
    
    // Estado
    private val isProcessing = AtomicBoolean(false)
    private val droppedFrames = AtomicInteger(0)
    private val totalBytesSent = AtomicLong(0)
    
    // Configuración adaptativa
    private var adaptiveQuality = ServerConfig.JPEG_QUALITY_DEFAULT
    private var targetFrameInterval = ServerConfig.FRAME_INTERVAL_MS
    private var lastFrameTime = 0L
    private var consecutiveDrops = 0
    
    // Callback
    private var onFrameReady: ((ByteArray, Map<String, Any>) -> Unit)? = null
    
    data class FramePacket(
        val data: ByteArray,
        val metadata: Map<String, Any>,
        val timestamp: Long,
        val priority: Int = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FramePacket
            return timestamp == other.timestamp
        }
        
        override fun hashCode(): Int {
            return timestamp.hashCode()
        }
    }
    
    /**
     * Inicializa el manager
     */
    fun initialize(frameReadyCallback: (ByteArray, Map<String, Any>) -> Unit) {
        onFrameReady = frameReadyCallback
        startProcessingQueue()
        Logger.i("BinaryStreamManager: Inicializado")
    }
    
    /**
     * Procesa un bitmap para transmisión
     */
    fun processFrame(bitmap: Bitmap, gameState: Map<String, Any> = emptyMap()): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Control de frecuencia (backpressure)
        if (currentTime - lastFrameTime < targetFrameInterval) {
            // Frame muy pronto, dropear o encolar
            if (transmitQueue.remainingCapacity() == 0) {
                droppedFrames.incrementAndGet()
                consecutiveDrops++
                
                // Ajustar calidad si hay muchos drops consecutivos
                if (consecutiveDrops > 3) {
                    reduceQuality()
                    consecutiveDrops = 0
                }
                
                return false
            }
        }
        
        lastFrameTime = currentTime
        consecutiveDrops = 0
        
        // Procesar en background
        scope.launch {
            try {
                val packet = createFramePacket(bitmap, gameState)
                
                // Intentar encolar (no bloqueante)
                if (!transmitQueue.offer(packet)) {
                    droppedFrames.incrementAndGet()
                    Logger.w("BinaryStreamManager: Frame dropeado, cola llena")
                }
                
            } catch (e: Exception) {
                Logger.e("BinaryStreamManager: Error al procesar frame", e)
            }
        }
        
        return true
    }
    
    /**
     * Comprime un bitmap con calidad adaptativa
     */
    fun compressBitmapAdaptive(bitmap: Bitmap): ByteArray {
        return compressBitmap(bitmap, adaptiveQuality)
    }
    
    /**
     * Comprime con calidad específica
     */
    fun compressBitmap(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        
        // Redimensionar si es necesario
        val resizedBitmap = resizeIfNeeded(bitmap)
        
        // Comprimir a JPEG
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        
        var compressed = outputStream.toByteArray()
        
        // Comprimir con Zstd si está habilitado y payload es grande
        if (ServerConfig.COMPRESSION_ENABLED && compressed.size > ServerConfig.COMPRESSION_THRESHOLD_BYTES) {
            compressed = compressWithZstd(compressed)
        }
        
        // Liberar bitmap si fue redimensionado
        if (resizedBitmap != bitmap && !resizedBitmap.isRecycled) {
            resizedBitmap.recycle()
        }
        
        return compressed
    }
    
    /**
     * Chunking para frames grandes
     */
    fun createChunks(data: ByteArray, chunkSize: Int = ServerConfig.MAX_BINARY_CHUNK_SIZE): List<ByteArray> {
        if (data.size <= chunkSize) {
            return listOf(data)
        }
        
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            val remaining = data.size - offset
            val currentChunkSize = minOf(chunkSize, remaining)
            
            val chunk = data.copyOfRange(offset, offset + currentChunkSize)
            chunks.add(chunk)
            
            offset += currentChunkSize
        }
        
        return chunks
    }
    
    /**
     * Ajusta calidad basado en métricas de red
     */
    fun adaptToNetworkConditions(latencyMs: Long, throughputKbps: Double) {
        val oldQuality = adaptiveQuality
        
        adaptiveQuality = when {
            // Latencia alta -> bajar calidad
            latencyMs > 500 -> (adaptiveQuality - 15).coerceAtLeast(ServerConfig.JPEG_QUALITY_MIN)
            latencyMs > 300 -> (adaptiveQuality - 10).coerceAtLeast(ServerConfig.JPEG_QUALITY_MIN)
            latencyMs > 150 -> (adaptiveQuality - 5).coerceAtLeast(ServerConfig.JPEG_QUALITY_MIN)
            
            // Latencia baja y throughput alto -> subir calidad
            latencyMs < 50 && throughputKbps > 500 -> 
                (adaptiveQuality + 10).coerceAtMost(ServerConfig.JPEG_QUALITY_MAX)
            latencyMs < 100 && throughputKbps > 300 -> 
                (adaptiveQuality + 5).coerceAtMost(ServerConfig.JPEG_QUALITY_MAX)
            
            else -> adaptiveQuality
        }
        
        // Ajustar intervalo de frames
        targetFrameInterval = when {
            latencyMs > 400 -> 250L  // 4 FPS
            latencyMs > 200 -> 200L  // 5 FPS
            latencyMs > 100 -> 150L  // 6.6 FPS
            else -> 100L  // 10 FPS
        }
        
        if (oldQuality != adaptiveQuality) {
            Logger.i("BinaryStreamManager: Calidad ajustada $oldQuality -> $adaptiveQuality (latencia: ${latencyMs}ms)")
        }
    }
    
    /**
     * Obtiene estadísticas actuales
     */
    fun getStats(): StreamStats {
        return StreamStats(
            bytesSent = totalBytesSent.get(),
            framesDropped = droppedFrames.get(),
            currentQuality = adaptiveQuality,
            queueSize = transmitQueue.size,
            targetInterval = targetFrameInterval
        )
    }
    
    /**
     * Resetea estadísticas
     */
    fun resetStats() {
        totalBytesSent.set(0)
        droppedFrames.set(0)
        transmitQueue.clear()
    }
    
    /**
     * Limpieza de recursos
     */
    fun cleanup() {
        isProcessing.set(false)
        scope.cancel()
        transmitQueue.clear()
        instance = null
    }
    
    // ============================================
    // PRIVATE METHODS
    // ============================================
    
    private fun createFramePacket(bitmap: Bitmap, gameState: Map<String, Any>): FramePacket {
        val compressed = compressBitmapAdaptive(bitmap)
        
        val metadata = mutableMapOf<String, Any>().apply {
            put("timestamp", System.currentTimeMillis())
            put("quality", adaptiveQuality)
            put("originalSize", bitmap.byteCount)
            put("compressedSize", compressed.size)
            put("compressionRatio", bitmap.byteCount.toFloat() / compressed.size)
            putAll(gameState)
        }
        
        return FramePacket(compressed, metadata, System.currentTimeMillis())
    }
    
    private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
        val maxWidth = ServerConfig.MAX_FRAME_WIDTH
        val maxHeight = ServerConfig.MAX_FRAME_HEIGHT
        
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) {
            return bitmap
        }
        
        val ratio = minOf(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height
        )
        
        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    private fun compressWithZstd(data: ByteArray): ByteArray {
        return try {
            val compressed = Zstd.compress(data, 3)  // Level 3 = balance
            
            // Solo usar si hay ganancia significativa (>10%)
            if (compressed.size < data.size * 0.9) {
                Logger.d("BinaryStreamManager: Zstd ${data.size} -> ${compressed.size} bytes")
                compressed
            } else {
                data
            }
            
        } catch (e: Exception) {
            Logger.w("BinaryStreamManager: Zstd failed, usando sin compresión")
            data
        }
    }
    
    private fun reduceQuality() {
        adaptiveQuality = (adaptiveQuality - 10).coerceAtLeast(ServerConfig.JPEG_QUALITY_MIN)
        Logger.i("BinaryStreamManager: Calidad reducida a $adaptiveQuality (backpressure)")
    }
    
    private fun startProcessingQueue() {
        if (isProcessing.getAndSet(true)) return
        
        scope.launch {
            while (isActive && isProcessing.get()) {
                try {
                    val packet = transmitQueue.poll()
                    if (packet == null) {
                        delay(10)
                        continue
                    }
                    
                    // Procesar frame
                    onFrameReady?.invoke(packet.data, packet.metadata)
                    
                    totalBytesSent.addAndGet(packet.data.size.toLong())
                    
                } catch (e: Exception) {
                    Logger.e("BinaryStreamManager: Error en procesamiento", e)
                }
            }
        }
    }
    
    data class StreamStats(
        val bytesSent: Long,
        val framesDropped: Int,
        val currentQuality: Int,
        val queueSize: Int,
        val targetInterval: Long
    )
}
