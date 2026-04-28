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
import com.ffai.assistant.core.*
import com.ffai.assistant.core.AdvancedAICore
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.learning.MemoryManager
import com.ffai.assistant.perception.PerceptionEngine
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

    // === NUEVA ARQUITECTURA HÍBRIDA ===
    // Core pipeline
    private var captureManager: CaptureManager? = null
    private var preprocessor: Preprocessor? = null
    private var roiTracker: ROITracker? = null
    private var gameLoop: GameLoop? = null

    // Perception
    private var perceptionEngine: PerceptionEngine? = null

    // Decision
    private var reflexEngine: ReflexEngine? = null
    private var decisionEngine: DecisionEngine? = null

    // Action
    private var gestureController: GestureController? = null
    private var aimController: AimController? = null

    // Learning & Memory
    private var memoryManager: MemoryManager? = null

    // Config
    private var gameConfig: GameConfig? = null

    // Legacy compatibility (mantener para no romper API)
    private var brain: Brain? = null
    private val useHybridAI = true  // Usar nueva arquitectura
    
    // === IA AVANZADA ENSEMBLE (FASES 1-9) ===
    private var advancedAICore: AdvancedAICore? = null
    private val useAdvancedAI = true  // Usar ensemble de 8 modelos +120MB
    private val useEnhancedPipeline = true  // NUEVO: Usar pipeline YOLO+EnsembleRL+Gesture

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

        // Nueva arquitectura
        gameLoop?.destroy()
        captureManager?.destroy()
        preprocessor?.destroy()
        perceptionEngine?.destroy()
        memoryManager?.destroy()
        
        // IA Avanzada Ensemble
        advancedAICore?.release()

        // Legacy
        brain?.destroy()

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

        registerCaptureReceiver()

        // Inicializar nueva arquitectura híbrida
        serviceScope.launch(Dispatchers.Default) {
            try {
                initHybridArchitecture()
            } catch (e: Exception) {
                Logger.e("Error inicializando arquitectura híbrida", e)
                updateStatus("Error init: ${e.message}")
            }
        }

        updateStatus("Servicio conectado. Abre la app para activar captura.")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun initHybridArchitecture() {
        Logger.i("Inicializando arquitectura híbrida...")

        // 1. Config
        val config = GameConfig(this@FFAccessibilityService)
        gameConfig = config

        // 2. Core Pipeline (v3.0 - Build fix applied)
        val screenWidth = config.screenWidth
        val screenHeight = config.screenHeight
        captureManager = CaptureManager()
        captureManager?.initialize(screenWidth, screenHeight)
        captureManager?.onFrameForInference = { bitmap: android.graphics.Bitmap, _: Long ->
            gameLoop?.onFrameAvailable(bitmap)
        }
        preprocessor = Preprocessor()
        roiTracker = ROITracker(screenWidth, screenHeight)

        // 3. Perception
        perceptionEngine = PerceptionEngine(roiTracker!!).apply {
            initializeModel(this@FFAccessibilityService)
        }

        // 4. Action
        gestureController = GestureController(this@FFAccessibilityService, config)
        aimController = AimController(config, gestureController!!)

        // 5. Decision (Reflexes → Tactical)
        reflexEngine = ReflexEngine()
        val policyModel = PolicyModel(this@FFAccessibilityService)  // Puede fallar si no existe
        val tacticalEngine = TacticalEngine(policyModel)
        decisionEngine = DecisionEngine(reflexEngine!!, tacticalEngine, aimController!!)

        // 6. Memory
        memoryManager = MemoryManager(this@FFAccessibilityService, serviceScope)

        // 7. GameLoop (orquestador principal)
        gameLoop = GameLoop().apply {
            initialize(
                captureManager = captureManager!!,
                preprocessor = preprocessor!!,
                roiTracker = roiTracker!!,
                reflexEngine = reflexEngine!!,
                decisionEngine = decisionEngine!!,
                gestureController = gestureController!!,
                gameConfig = config
            )
        }

        // Legacy (mantener compatibilidad)
        brain = Brain(this@FFAccessibilityService)

        Logger.i("Arquitectura híbrida lista: Reflexes+Tactical+Learning")
        updateStatus("IA Híbrida lista (Reflexes+Tactical+Learning)")
        
        // 8. IA Avanzada Ensemble (inicialización async)
        if (useAdvancedAI) {
            serviceScope.launch {
                try {
                    advancedAICore = AdvancedAICore(
                        context = this@FFAccessibilityService,
                        service = this@FFAccessibilityService,
                        gameConfig = config
                    )
                    advancedAICore?.initialize()
                    Logger.i("IA Avanzada Ensemble inicializada (8 modelos, +120MB)")
                    updateStatus("IA Ensemble lista (8 modelos, 3 modos razonamiento)")
                } catch (e: Exception) {
                    Logger.e("Error inicializando IA Avanzada", e)
                }
            }
        }
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

        // Iniciar nueva arquitectura
        if (useHybridAI) {
            gameLoop?.start()
        }
        
        // Iniciar IA Avanzada Ensemble
        if (useAdvancedAI) {
            serviceScope.launch {
                advancedAICore?.start()
            }
        }

        lastFpsTime = System.currentTimeMillis()
        frameCount = 0
    }

    private fun stopGameLoop() {
        isRunning = false
        gameLoop?.stop()
        advancedAICore?.stop()
        brain?.endEpisode(50)
    }
    
    @RequiresApi(Build.VERSION_CODES.N)
    private fun onFrameCaptured(bitmap: android.graphics.Bitmap) {
        if (!isRunning) return

        if (!isFullyInitialized || gestureController == null) {
            Logger.w("onFrameCaptured: Servicio no inicializado completamente")
            bitmap.recycle()
            return
        }

        // NUEVO PIPELINE: YOLO + Ensemble RL + GestureEngine (Fases 1-6)
        if (useEnhancedPipeline && useAdvancedAI) {
            serviceScope.launch(Dispatchers.Default) {
                try {
                    // Usar nuevo pipeline del AdvancedAICore
                    // Nota: processFrameEnhanced es suspend, así que usamos launch
                    advancedAICore?.processFrameEnhanced(bitmap)
                    
                    // Track FPS
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        currentFps = frameCount
                        frameCount = 0
                        lastFpsTime = now
                        Logger.d("FPS (Enhanced): $currentFps")
                    }
                } catch (e: Exception) {
                    Logger.e("Error en pipeline mejorado", e)
                    bitmap.recycle()
                }
            }
            return
        }

        // En modo híbrido legacy, el GameLoop maneja todo internamente
        if (useHybridAI) {
            gameLoop?.onFrameAvailable(bitmap)
            return
        }

        // Legacy path (fallback)
        val frameStart = System.currentTimeMillis()

        serviceScope.launch(Dispatchers.Default) {
            try {
                val action = brain?.processFrame(bitmap)

                if (action != null && action.type != ActionType.HOLD) {
                    withContext(Dispatchers.Main) {
                        gestureController?.execute(action)
                    }
                }

                frameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    currentFps = frameCount
                    frameCount = 0
                    lastFpsTime = now
                    Logger.d("FPS: $currentFps")
                }

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
    
    /**
     * Obtiene estadísticas del nuevo pipeline mejorado.
     */
    fun getEnhancedStats(): AdvancedAIStats? = advancedAICore?.getEnhancedStats()
    
    /**
     * Fuerza el uso del pipeline mejorado (YOLO + Ensemble RL).
     */
    fun setUseEnhancedPipeline(use: Boolean) {
        // No se puede cambiar en caliente, requiere reinicio
        Logger.i("Enhanced pipeline setting: $use (requires restart)")
    }
}
