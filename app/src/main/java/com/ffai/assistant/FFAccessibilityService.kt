package com.ffai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.ffai.assistant.action.GestureController
import com.ffai.assistant.capture.ScreenCaptureService
import com.ffai.assistant.config.Constants
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.core.Brain
import com.ffai.assistant.core.RemoteBrain
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*

/**
 * Servicio de accesibilidad principal.
 * Coordina captura de pantalla, procesamiento de IA y ejecución de acciones.
 */
class FFAccessibilityService : AccessibilityService() {
    
    // Exception handler para coroutines
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e("Coroutine error", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private var gameLoopJob: Job? = null
    
    private var brain: Brain? = null
    private var remoteBrain: RemoteBrain? = null
    private var gestureController: GestureController? = null
    private var gameConfig: GameConfig? = null
    
    // Modo de operación: true = IA en servidor, false = IA local
    private val useRemoteBrain = true

    private var isRunning = false
    private var captureServiceReady = false
    private var freeFireActive = false
    private var isFullyInitialized = false
    private var activityReady = false
    private var receiverRegistered = false
    
    // Performance tracking
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0
    
    // Receiver para escuchar frames del ScreenCaptureService
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.ffai.assistant.CAPTURE_STARTED" -> {
                    Logger.i("FFAccessibilityService: Captura iniciada")
                    captureServiceReady = true
                    isFullyInitialized = true
                    updateStatus("Captura lista. Esperando Free Fire...")
                    if (freeFireActive) {
                        startGameLoop()
                    }
                }
                "com.ffai.assistant.CAPTURE_STOPPED" -> {
                    Logger.i("FFAccessibilityService: Captura detenida")
                    captureServiceReady = false
                    stopGameLoop()
                    updateStatus("Captura detenida")
                }
                "com.ffai.assistant.CAPTURE_ERROR" -> {
                    val errorMessage = intent.getStringExtra("error_message") ?: "Error desconocido en captura"
                    Logger.e("FFAccessibilityService: Error de captura - $errorMessage")
                    captureServiceReady = false
                    updateStatus("Error: $errorMessage")
                    // Notificar a la MainActivity del error
                    sendBroadcast(Intent("com.ffai.assistant.CAPTURE_ERROR").apply {
                        putExtra("error_message", errorMessage)
                    })
                }
                "com.ffai.assistant.NEW_FRAME" -> {
                    // Recibir frame como parcelable
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("bitmap", Bitmap::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("bitmap")
                    }
                    if (bitmap != null) {
                        onFrameCaptured(bitmap)
                    }
                }
            }
        }
    }
    
    companion object {
        var instance: FFAccessibilityService? = null
            private set
        
        var isServiceRunning = false
            private set
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.i("FFAccessibilityService v${Constants.VERSION} creado")

        // No inicializar componentes críticos aquí - esperar a onServiceConnected()
        // y a la confirmación de la Activity para evitar crashes por ciclo de vida
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopAI()
        brain?.destroy()
        remoteBrain?.destroy()
        
        // Desregistrar receiver
        if (receiverRegistered) {
            unregisterReceiver(captureReceiver)
            receiverRegistered = false
        }
        
        serviceScope.cancel()
        instance = null
        isServiceRunning = false
        Logger.i("FFAccessibilityService destruido")
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i("Servicio de accesibilidad conectado")
        isServiceRunning = true
        
        // Registrar receiver para escuchar eventos del ScreenCaptureService
        registerCaptureReceiver()

        // Inicializar componentes básicos (no dependen de permisos de captura)
        serviceScope.launch(Dispatchers.Default) {
            try {
                val config = GameConfig(this@FFAccessibilityService)
                gameConfig = config
                gestureController = GestureController(this@FFAccessibilityService, config)
                Logger.i("Componentes de UI inicializados")
            } catch (e: Exception) {
                Logger.e("Error inicializando componentes UI", e)
            }
        }

        // Inicializar AI en hilo de fondo (no bloquea el servicio)
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (useRemoteBrain) {
                    Logger.i("Usando IA remota (cloud)")
                    remoteBrain = RemoteBrain(this@FFAccessibilityService)
                } else {
                    Logger.i("Usando IA local")
                    brain = Brain(this@FFAccessibilityService)
                }
                updateStatus("IA lista. Usa la app para iniciar captura.")
            } catch (e: Exception) {
                Logger.e("Error inicializando AI", e)
                updateStatus("Error IA: ${e.message}")
            }
        }

        updateStatus("Servicio conectado. Abre la app para activar captura.")
    }
    
    private fun registerCaptureReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.ffai.assistant.CAPTURE_STARTED")
            addAction("com.ffai.assistant.CAPTURE_STOPPED")
            addAction("com.ffai.assistant.CAPTURE_ERROR")
            addAction("com.ffai.assistant.NEW_FRAME")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(captureReceiver, filter)
        }
        receiverRegistered = true
        Logger.i("FFAccessibilityService: CaptureReceiver registrado")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                
                if (isFreeFirePackage(packageName)) {
                    if (!freeFireActive) {
                        Logger.i("Free Fire detectado: $packageName")
                        freeFireActive = true
                        onFreeFireStarted()
                    }
                } else {
                    if (freeFireActive) {
                        Logger.i("Free Fire ya no está en primer plano")
                        freeFireActive = false
                        onFreeFireStopped()
                    }
                }
            }
        }
    }
    
    override fun onInterrupt() {
        Logger.w("Servicio interrumpido")
    }
    
    /**
     * Notifica que la MainActivity está lista.
     * @deprecated Ya no es necesario con la nueva arquitectura de servicio
     */
    fun setActivityReady(ready: Boolean) {
        activityReady = ready
        Logger.i("MainActivity ready state: $ready")
    }
    
    /**
     * Verifica si el servicio de captura está listo
     */
    fun isCaptureReady(): Boolean = captureServiceReady
    
    /**
     * Detiene completamente la IA.
     */
    fun stopAI() {
        isRunning = false
        gameLoopJob?.cancel()
        gameLoopJob = null
        
        captureServiceReady = false
        
        updateStatus("Servicio detenido")
        Logger.i("IA detenida")
    }
    
    private fun onFreeFireStarted() {
        updateStatus("Free Fire detectado.")
        
        // Si ya tenemos el servicio de captura listo, iniciar directamente
        if (captureServiceReady) {
            startGameLoop()
        } else {
            updateStatus("Free Fire detectado. Inicia captura desde la app.")
        }
    }
    
    private fun onFreeFireStopped() {
        stopGameLoop()
        updateStatus("Free Fire cerrado. Servicio en espera.")
    }
    
    private fun startGameLoop() {
        if (isRunning) return
        
        isRunning = true
        updateStatus("IA Activa - Procesando...")
        Logger.i("Game loop iniciado")
        
        lastFpsTime = System.currentTimeMillis()
        frameCount = 0
    }
    
    private fun stopGameLoop() {
        isRunning = false
        // Notificar fin de episodio (asumimos posición 50 por defecto)
        brain?.endEpisode(50)
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    private fun onFrameCaptured(bitmap: android.graphics.Bitmap) {
        if (!isRunning) return

        // Validar que el servicio esté completamente inicializado
        if (!isFullyInitialized || gestureController == null) {
            Logger.w("onFrameCaptured: Servicio no inicializado completamente")
            bitmap.recycle()
            return
        }

        val frameStart = System.currentTimeMillis()
        
        serviceScope.launch(Dispatchers.Default) {
            try {
                // 1. Brain procesa frame y decide acción
                val action = if (useRemoteBrain) {
                    remoteBrain?.processFrame(bitmap)
                } else {
                    brain?.processFrame(bitmap)
                }
                
                // 2. Ejecutar acción en UI thread
                if (action != null && action.type != com.ffai.assistant.action.ActionType.HOLD) {
                    withContext(Dispatchers.Main) {
                        gestureController?.execute(action)
                    }
                }
                
                // 3. Stats y debug
                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    currentFps = frameCount
                    frameCount = 0
                    lastFpsTime = now
                    Logger.d("FPS: $currentFps")
                }
                
                // 4. Log performance
                val frameTime = System.currentTimeMillis() - frameStart
                if (frameTime > 100) {
                    Logger.w("Frame lento: ${frameTime}ms")
                }
                
            } catch (e: Exception) {
                Logger.e("Error procesando frame", e)
            } finally {
                bitmap.recycle()
            }
        }
    }
    
    fun endEpisode(finalPlacement: Int) {
        brain?.endEpisode(finalPlacement)
        remoteBrain?.endEpisode(finalPlacement)
    }
    
    private fun isFreeFirePackage(packageName: String): Boolean {
        return packageName.contains("freefire") || 
               packageName.contains("garena") ||
               packageName == "com.dts.freefireth" ||
               packageName == "com.dts.freefiremax" ||
               packageName == "com.dts.freefireth1" ||
               packageName == "com.dts.freefiremax1"
    }
    
    private fun updateStatus(message: String) {
        // Enviar broadcast a MainActivity
        val intent = Intent("com.ffai.assistant.STATUS_UPDATE").apply {
            putExtra("status", message)
        }
        sendBroadcast(intent)
    }
    
    fun isAIActive(): Boolean = isRunning
    fun getCurrentFps(): Int = currentFps
    fun getBrain(): Brain? = brain
}
