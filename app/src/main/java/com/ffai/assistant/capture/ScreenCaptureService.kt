package com.ffai.assistant.capture

import android.app.Activity
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
import kotlinx.coroutines.*
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
        const val ACTION_CAPTURE_ERROR = "com.ffai.assistant.CAPTURE_ERROR"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        
        const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001
        
        const val MAX_RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1000L
        
        @Volatile
        var isRunning = false
            private set
            
        @Volatile
        var instance: ScreenCaptureService? = null
            private set
            
        @Volatile
        var currentFps: Int = 0
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
    
    // SocketIO integration
    private lateinit var binaryStreamManager: BinaryStreamManager
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                
                // Validación estricta según las 10 reglas de arquitectura
                if (resultCode != Activity.RESULT_OK) {
                    Logger.e("ScreenCaptureService: resultCode inválido ($resultCode), se requiere RESULT_OK")
                    reportError("Permiso de captura denegado por el usuario")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                if (data == null) {
                    Logger.e("ScreenCaptureService: Intent data es null")
                    reportError("Datos de autorización no disponibles")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                try {
                    startForegroundService()
                    startCaptureWithRetry(resultCode, data, 0)
                } catch (e: Exception) {
                    Logger.e("ScreenCaptureService: Error fatal iniciando captura", e)
                    reportError("Error iniciando servicio: ${e.message}")
                    cleanup()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    private fun reportError(message: String) {
        Logger.e("ScreenCaptureService Error: $message")
        sendBroadcast(Intent(ACTION_CAPTURE_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
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

    private fun startCaptureWithRetry(resultCode: Int, data: Intent, attempt: Int) {
        if (attempt >= MAX_RETRY_ATTEMPTS) {
            Logger.e("ScreenCaptureService: Máximo de reintentos ($MAX_RETRY_ATTEMPTS) alcanzado")
            reportError("No se pudo iniciar captura después de $MAX_RETRY_ATTEMPTS intentos")
            stopSelf()
            return
        }
        
        try {
            startCapture(resultCode, data)
        } catch (e: SecurityException) {
            Logger.e("ScreenCaptureService: SecurityException en intento ${attempt + 1}", e)
            if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                Logger.i("ScreenCaptureService: Reintentando en ${RETRY_DELAY_MS}ms...")
                backgroundHandler?.postDelayed({
                    cleanup()
                    startCaptureWithRetry(resultCode, data, attempt + 1)
                }, RETRY_DELAY_MS)
            } else {
                reportError("Permiso de seguridad denegado: ${e.message}")
                stopSelf()
            }
        } catch (e: Exception) {
            Logger.e("ScreenCaptureService: Error en intento ${attempt + 1}", e)
            if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                backgroundHandler?.postDelayed({
                    cleanup()
                    startCaptureWithRetry(resultCode, data, attempt + 1)
                }, RETRY_DELAY_MS)
            } else {
                reportError("Error de captura: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (isCapturing.get()) {
            Logger.w("ScreenCaptureService: Ya está capturando")
            return
        }

        try {
            // Obtener MediaProjectionManager de forma segura - sin cast peligroso
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            if (projectionManager == null) {
                throw IllegalStateException("MediaProjectionManager no disponible")
            }

            Logger.i("ScreenCaptureService: Solicitando MediaProjection...")
            
            // PASO 5: Crear MediaProjection con datos exactos - nunca reconstruir el Intent
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            
            // PASO 6: Validar proyección antes de continuar
            if (mediaProjection == null) {
                Logger.e("ScreenCaptureService: MediaProjection es null")
                throw IllegalStateException("No se pudo obtener MediaProjection - resultado inválido")
            }
            
            Logger.i("ScreenCaptureService: MediaProjection obtenido correctamente")
            
            // PASO 7: Configurar callback para saber cuándo se detiene la proyección
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Logger.w("ScreenCaptureService: MediaProjection detenida por el sistema")
                        isCapturing.set(false)
                        stopCapture()
                        stopSelf()
                    }
                }, backgroundHandler)
            }

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
                    
                    // Enviar frame al servidor via SocketIO (si está conectado)
                    coroutineScope.launch {
                        try {
                            val socketManager = SocketIOManager.getInstance()
                            if (socketManager.isConnected()) {
                                socketManager.emitFrame(bitmap, emptyMap())
                            }
                        } catch (e: Exception) {
                            Logger.e("ScreenCaptureService: Error enviando frame por SocketIO", e)
                        }
                    }
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
            
            // Cancelar corutinas de SocketIO
            coroutineScope.cancel()
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
