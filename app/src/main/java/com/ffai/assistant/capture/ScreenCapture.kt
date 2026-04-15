package com.ffai.assistant.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.ffai.assistant.config.Constants
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer

/**
 * Captura de pantalla usando MediaProjection API.
 * Optimizado para Android 12+ con alta performance.
 */
class ScreenCapture(private val context: Context) {
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handler: Handler? = null
    
    private var isCapturing = false
    private var onFrameCallback: ((Bitmap) -> Unit)? = null
    
    // Performance tracking
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0
    
    /**
     * Inicia la captura de pantalla.
     */
    fun start(resultCode: Int, data: Intent, onFrame: (Bitmap) -> Unit) {
        if (isCapturing) {
            Logger.w("ScreenCapture ya está iniciado")
            return
        }
        
        this.onFrameCallback = onFrame
        
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection == null) {
            Logger.e("No se pudo obtener MediaProjection")
            return
        }
        
        setupImageReader()
        createVirtualDisplay()
        
        isCapturing = true
        lastFpsTime = System.currentTimeMillis()
        Logger.i("ScreenCapture iniciado")
    }
    
    /**
     * Detiene la captura.
     */
    fun stop() {
        if (!isCapturing) return
        
        isCapturing = false
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        handler = null
        
        Logger.i("ScreenCapture detenido. FPS promedio: $currentFps")
    }
    
    fun isActive(): Boolean = isCapturing
    fun getCurrentFps(): Int = currentFps
    
    private fun setupImageReader() {
        handler = Handler(Looper.getMainLooper())
        
        imageReader = ImageReader.newInstance(
            Constants.FRAME_WIDTH,
            Constants.FRAME_HEIGHT,
            PixelFormat.RGBA_8888,
            3  // Máximo 3 buffers pendientes
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    
                    // Calcular FPS
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        currentFps = frameCount
                        frameCount = 0
                        lastFpsTime = now
                    }
                    
                    // Ejecutar callback en background
                    onFrameCallback?.invoke(bitmap)
                }
            } catch (e: Exception) {
                Logger.e("Error procesando imagen", e)
            } finally {
                image?.close()
            }
        }, handler)
    }
    
    private fun createVirtualDisplay() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) 
            as android.view.WindowManager
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        
        val width = bounds.width()
        val height = bounds.height()
        val density = context.resources.configuration.densityDpi
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FFAIScreenCapture",
            Constants.FRAME_WIDTH,
            Constants.FRAME_HEIGHT,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
        
        if (virtualDisplay == null) {
            Logger.e("No se pudo crear VirtualDisplay")
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * Constants.FRAME_WIDTH
        
        // Crear bitmap con padding incluido
        val bitmap = Bitmap.createBitmap(
            Constants.FRAME_WIDTH + rowPadding / pixelStride,
            Constants.FRAME_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Si hay padding, recortar al tamaño deseado
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, Constants.FRAME_WIDTH, Constants.FRAME_HEIGHT)
        } else {
            bitmap
        }
    }
}
