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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
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
     * @return true si se inició correctamente, false si falló
     */
    fun start(resultCode: Int, data: Intent?, onFrame: (Bitmap) -> Unit): Boolean {
        if (isCapturing) {
            Logger.w("ScreenCapture ya está iniciado")
            return true
        }

        // Validar parámetros de entrada
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Logger.e("ScreenCapture: resultCode inválido ($resultCode) o data nulo")
            return false
        }

        this.onFrameCallback = onFrame

        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager?

            if (projectionManager == null) {
                Logger.e("ScreenCapture: MediaProjectionManager no disponible")
                return false
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Logger.e("ScreenCapture: No se pudo obtener MediaProjection")
                return false
            }

            // Obtener dimensiones de pantalla primero
            val (screenWidth, screenHeight, density) = getScreenDimensions()
            
            // Validar que la proyección esté activa
            try {
                setupImageReader(screenWidth, screenHeight)
                createVirtualDisplay(screenWidth, screenHeight, density)
            } catch (e: Exception) {
                Logger.e("ScreenCapture: Error creando ImageReader/VirtualDisplay", e)
                cleanup()
                return false
            }

            isCapturing = true
            lastFpsTime = System.currentTimeMillis()
            Logger.i("ScreenCapture iniciado correctamente")
            return true

        } catch (e: SecurityException) {
            Logger.e("ScreenCapture: SecurityException al iniciar captura", e)
            cleanup()
            return false
        } catch (e: IllegalStateException) {
            Logger.e("ScreenCapture: IllegalStateException - estado inválido", e)
            cleanup()
            return false
        } catch (e: NullPointerException) {
            Logger.e("ScreenCapture: NullPointerException", e)
            cleanup()
            return false
        } catch (e: Exception) {
            Logger.e("ScreenCapture: Error inesperado al iniciar", e)
            cleanup()
            return false
        }
    }

    /**
     * Limpia recursos parciales en caso de error.
     */
    private fun cleanup() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            handler = null
        } catch (e: Exception) {
            Logger.w("ScreenCapture: Error durante cleanup", e)
        }
    }
    
    /**
     * Detiene la captura.
     */
    fun stop() {
        if (!isCapturing) return

        isCapturing = false
        cleanup()

        Logger.i("ScreenCapture detenido. FPS promedio: $currentFps")
    }
    
    fun isActive(): Boolean = isCapturing
    fun getCurrentFps(): Int = currentFps
    
    private fun setupImageReader(width: Int, height: Int) {
        handler = Handler(Looper.getMainLooper())
        
        // Usar dimensiones de pantalla reales para evitar problemas de escalado
        imageReader = ImageReader.newInstance(
            width,
            height,
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
    
    private fun getScreenDimensions(): Triple<Int, Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            ?: throw IllegalStateException("WindowManager no disponible")
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ (Android 11+)
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Triple(bounds.width(), bounds.height(), context.resources.configuration.densityDpi)
        } else {
            // API 21-29 (Android 5.0 - 10)
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Triple(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
        }
    }

    private fun createVirtualDisplay(width: Int, height: Int, density: Int) {
        try {
            // Validar dimensiones antes de crear
            if (width <= 0 || height <= 0 || density <= 0) {
                Logger.e("ScreenCapture: Dimensiones inválidas (${width}x${height}, density=$density)")
                throw IllegalStateException("Dimensiones de pantalla inválidas")
            }

            Logger.i("ScreenCapture: Creando VirtualDisplay ${width}x${height} (density=$density)")

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FFAIScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            if (virtualDisplay == null) {
                Logger.e("ScreenCapture: No se pudo crear VirtualDisplay")
                throw IllegalStateException("VirtualDisplay creation returned null")
            }

            Logger.i("ScreenCapture: VirtualDisplay creado correctamente")

        } catch (e: SecurityException) {
            Logger.e("ScreenCapture: SecurityException creando VirtualDisplay", e)
            throw e
        } catch (e: IllegalStateException) {
            Logger.e("ScreenCapture: IllegalStateException creando VirtualDisplay", e)
            throw e
        } catch (e: Exception) {
            Logger.e("ScreenCapture: Error inesperado creando VirtualDisplay", e)
            throw IllegalStateException("Error creando VirtualDisplay: ${e.message}", e)
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        
        // Crear bitmap con dimensiones reales de la imagen
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Redimensionar al tamaño deseado para procesamiento
        val scaled = Bitmap.createScaledBitmap(bitmap, Constants.FRAME_WIDTH, Constants.FRAME_HEIGHT, true)
        
        // Liberar bitmap original si es diferente
        if (bitmap !== scaled) {
            bitmap.recycle()
        }
        
        return scaled
    }
}
