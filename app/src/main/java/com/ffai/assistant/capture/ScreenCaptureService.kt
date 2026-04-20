package com.ffai.assistant.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ffai.assistant.MainActivity
import com.ffai.assistant.R
import com.ffai.assistant.config.Constants
import com.ffai.assistant.utils.Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service dedicado a la captura de pantalla.
 * Se requiere para Android 10+ (API 29+) al usar MediaProjection.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.ffai.assistant.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.ffai.assistant.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        
        const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001
        
        @Volatile
        var isRunning = false
            private set
            
        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    
    private val isCapturing = AtomicBoolean(false)
    
    // Performance tracking
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0

    override fun onCreate() {
        super.onCreate()
        Logger.i("ScreenCaptureService creado")
        instance = this
        
        // Crear thread de background para el ImageReader
        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                
                if (resultCode == -1 || data == null) {
                    Logger.e("ScreenCaptureService: resultCode o data inválidos")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                startForegroundService()
                startCapture(resultCode, data)
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()
        
        val notification = buildNotification()
        
        // Para Android 10+ (API 29+), especificar el tipo de foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        isRunning = true
        Logger.i("ScreenCaptureService iniciado en foreground")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Captura de Pantalla",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de captura de pantalla para FFAI Assistant"
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FFAI Assistant - Captura activa")
            .setContentText("Procesando pantalla...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (isCapturing.get()) {
            Logger.w("ScreenCaptureService: Ya está capturando")
            return
        }

        try {
            // Obtener MediaProjectionManager de forma segura
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
                ?: throw IllegalStateException("MediaProjectionManager no disponible")

            Logger.i("ScreenCaptureService: Solicitando MediaProjection...")
            
            // Crear MediaProjection
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            if (mediaProjection == null) {
                Logger.e("ScreenCaptureService: No se pudo obtener MediaProjection")
                stopSelf()
                return
            }
            
            Logger.i("ScreenCaptureService: MediaProjection obtenido correctamente")

            // Obtener dimensiones de pantalla
            val (screenWidth, screenHeight, density) = getScreenDimensions()
            Logger.i("ScreenCaptureService: Dimensiones ${screenWidth}x${screenHeight}, density=$density")

            // Crear ImageReader
            setupImageReader(screenWidth, screenHeight)
            
            // Crear VirtualDisplay
            createVirtualDisplay(screenWidth, screenHeight, density)

            isCapturing.set(true)
            lastFpsTime = System.currentTimeMillis()
            
            Logger.i("ScreenCaptureService: Captura iniciada correctamente")
            
            // Notificar a la UI que la captura está lista
            sendBroadcast(Intent("com.ffai.assistant.CAPTURE_STARTED"))

        } catch (e: SecurityException) {
            Logger.e("ScreenCaptureService: SecurityException", e)
            cleanup()
            stopSelf()
        } catch (e: Exception) {
            Logger.e("ScreenCaptureService: Error iniciando captura", e)
            cleanup()
            stopSelf()
        }
    }

    private fun setupImageReader(width: Int, height: Int) {
        val validWidth = if (width > 0) width else 1080
        val validHeight = if (height > 0) height else 1920
        
        Logger.i("ScreenCaptureService: Creando ImageReader ${validWidth}x${validHeight}")
        
        imageReader = ImageReader.newInstance(
            validWidth,
            validHeight,
            PixelFormat.RGBA_8888,
            2  // Máximo 2 buffers
        )
        
        if (imageReader == null) {
            throw IllegalStateException("No se pudo crear ImageReader")
        }
        
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isCapturing.get()) return@setOnImageAvailableListener
            
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
                    
                    // Enviar frame al servicio de accesibilidad para procesamiento
                    sendBroadcast(Intent("com.ffai.assistant.NEW_FRAME").apply {
                        putExtra("bitmap", bitmap)
                    })
                }
            } catch (e: Exception) {
                Logger.e("ScreenCaptureService: Error procesando imagen", e)
            } finally {
                image?.close()
            }
        }, backgroundHandler)
        
        Logger.i("ScreenCaptureService: ImageReader creado correctamente")
    }

    private fun createVirtualDisplay(width: Int, height: Int, density: Int) {
        val validWidth = if (width > 0) width else 1080
        val validHeight = if (height > 0) height else 1920
        val validDensity = if (density > 0) density else DisplayMetrics.DENSITY_DEFAULT
        
        Logger.i("ScreenCaptureService: Creando VirtualDisplay ${validWidth}x${validHeight}")
        
        val surface = imageReader?.surface
            ?: throw IllegalStateException("ImageReader surface no disponible")
            
        if (!surface.isValid) {
            throw IllegalStateException("ImageReader surface inválido")
        }

        // Flags para compatibilidad
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FFAIScreenCapture",
            validWidth,
            validHeight,
            validDensity,
            flags,
            surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    Logger.w("ScreenCaptureService: VirtualDisplay pausado")
                }
                override fun onResumed() {
                    Logger.i("ScreenCaptureService: VirtualDisplay resumido")
                }
                override fun onStopped() {
                    Logger.w("ScreenCaptureService: VirtualDisplay detenido")
                    isCapturing.set(false)
                }
            },
            backgroundHandler
        )

        if (virtualDisplay == null) {
            throw IllegalStateException("VirtualDisplay creation returned null")
        }

        Logger.i("ScreenCaptureService: VirtualDisplay creado correctamente")
    }

    private fun getScreenDimensions(): Triple<Int, Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager?
            ?: throw IllegalStateException("WindowManager no disponible")
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Triple(bounds.width(), bounds.height(), resources.configuration.densityDpi)
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Triple(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
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
        
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Escalar al tamaño de procesamiento
        val scaled = Bitmap.createScaledBitmap(bitmap, Constants.FRAME_WIDTH, Constants.FRAME_HEIGHT, true)
        
        if (bitmap !== scaled) {
            bitmap.recycle()
        }
        
        return scaled
    }

    private fun stopCapture() {
        if (!isCapturing.get()) return
        
        Logger.i("ScreenCaptureService: Deteniendo captura...")
        isCapturing.set(false)
        cleanup()
        isRunning = false
        
        sendBroadcast(Intent("com.ffai.assistant.CAPTURE_STOPPED"))
    }

    private fun cleanup() {
        try {
            Logger.i("ScreenCaptureService: Limpiando recursos...")
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Logger.w("ScreenCaptureService: Error durante cleanup", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i("ScreenCaptureService: onDestroy")
        stopCapture()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        instance = null
    }
}
