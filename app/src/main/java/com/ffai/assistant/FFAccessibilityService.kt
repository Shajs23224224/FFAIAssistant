package com.ffai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.ffai.assistant.action.GestureController
import com.ffai.assistant.capture.ScreenCapture
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
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameLoopJob: Job? = null
    
    private var screenCapture: ScreenCapture? = null
    private var brain: Brain? = null
    private var remoteBrain: RemoteBrain? = null
    private var gestureController: GestureController? = null
    private var gameConfig: GameConfig? = null
    
    // Modo de operación: true = IA en servidor, false = IA local
    private val useRemoteBrain = true
    
    private var isRunning = false
    private var mediaProjectionReady = false
    private var freeFireActive = false
    
    // Performance tracking
    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0
    
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
        
        // Inicializar componentes
        gameConfig = GameConfig(this)
        
        if (useRemoteBrain) {
            Logger.i("Usando IA remota (cloud)")
            remoteBrain = RemoteBrain(this)
        } else {
            Logger.i("Usando IA local")
            brain = Brain(this)
        }
        
        gestureController = GestureController(this, gameConfig!!)
        screenCapture = ScreenCapture(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopAI()
        brain?.destroy()
        remoteBrain?.destroy()
        serviceScope.cancel()
        instance = null
        isServiceRunning = false
        Logger.i("FFAccessibilityService destruido")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i("Servicio de accesibilidad conectado")
        isServiceRunning = true
        
        // Notificar a la UI que el servicio está activo
        updateStatus("Servicio conectado. Abre Free Fire para iniciar.")
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
     * Inicia MediaProjection con el resultado de la actividad de permiso.
     */
    fun startMediaProjection(resultCode: Int, data: Intent) {
        if (mediaProjectionReady) return
        
        screenCapture?.start(resultCode, data) { bitmap ->
            // Callback llamado en cada frame capturado
            onFrameCaptured(bitmap)
        }
        
        mediaProjectionReady = true
        Logger.i("MediaProjection iniciado")
        
        // Si Free Fire ya está activo, iniciar el loop de IA
        if (freeFireActive) {
            startGameLoop()
        }
    }
    
    /**
     * Detiene completamente la IA.
     */
    fun stopAI() {
        isRunning = false
        gameLoopJob?.cancel()
        gameLoopJob = null
        
        screenCapture?.stop()
        mediaProjectionReady = false
        
        updateStatus("Servicio detenido")
        Logger.i("IA detenida")
    }
    
    private fun onFreeFireStarted() {
        updateStatus("Free Fire detectado. Esperando permiso de captura...")
        
        // Si ya tenemos MediaProjection, iniciar directamente
        if (mediaProjectionReady) {
            startGameLoop()
        }
        // Si no, la MainActivity debe solicitar el permiso
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
    
    private fun updateDebugInfo() {
        val state = if (useRemoteBrain) {
            remoteBrain?.getCurrentState()
        } else {
            brain?.getCurrentState()
        } ?: return
        
        val lastAction = if (useRemoteBrain) {
            remoteBrain?.getEpisodeStats()?.totalActions
        } else {
            brain?.getEpisodeStats()?.totalActions
        } ?: 0
        
        val intent = Intent("com.ffai.assistant.DEBUG_UPDATE").apply {
            putExtra("fps", currentFps)
            putExtra("action", "Action #${lastAction}")
            putExtra("health", state.healthRatio)
            putExtra("ammo", state.ammoRatio)
            putExtra("enemy", state.enemyPresent)
        }
        sendBroadcast(intent)
    }
    
    fun isAIActive(): Boolean = isRunning
    fun getCurrentFps(): Int = currentFps
    fun getBrain(): Brain? = brain
}
