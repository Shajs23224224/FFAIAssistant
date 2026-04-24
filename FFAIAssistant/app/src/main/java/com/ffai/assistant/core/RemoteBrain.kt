package com.ffai.assistant.core

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.network.SocketIOManager
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.perception.VisionProcessor
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*

/**
 * Brain remoto que delega decisiones al servidor vía SocketIO.
 * Usa SocketIOManager para compatibilidad con Flask-SocketIO del servidor.
 * 
 * Flujo:
 * 1. Captura frame de pantalla
 * 2. Extrae estado básico localmente (visión)
 * 3. Envía al servidor vía SocketIO
 * 4. Recibe acción y ejecuta
 */
class RemoteBrain(context: Context) {
    
    private val socketManager = SocketIOManager.getInstance()
    private val vision = VisionProcessor()
    
    private var currentState: GameState = GameState.DEFAULT
    private var lastAction: Action = Action.hold()
    private var episodeStats = EpisodeStats()
    
    private var pendingAction: Action? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callback para cuando se recibe una acción del servidor
    var onActionReady: ((Action) -> Unit)? = null
    
    init {
        Logger.i("RemoteBrain inicializado - Modo IA en la nube (SocketIO)")
        
        socketManager.setOnActionReceived { action ->
            pendingAction = action
            onActionReady?.invoke(action)
            episodeStats.totalActions++
        }
        
        socketManager.setOnConnectionChanged { connected, message ->
            if (connected) {
                Logger.i("✅ Conectado al servidor de IA")
            } else {
                Logger.w("❌ Desconectado del servidor: $message")
            }
        }
        
        socketManager.setOnError { error ->
            Logger.e("SocketIO Error: $error")
        }
        
        // Conectar al servidor
        scope.launch {
            connect()
        }
    }
    
    /**
     * Conecta al servidor remoto vía SocketIO.
     */
    private fun connect(): Boolean {
        return socketManager.connect()
    }
    
    /**
     * Procesa un frame y devuelve la acción recibida del servidor.
     * Si no hay conexión, usa fallback local.
     */
    fun processFrame(bitmap: Bitmap): Action {
        // 1. Extraer estado básico localmente (rápido)
        currentState = vision.analyze(bitmap)
        
        // 2. Enviar frame al servidor si está conectado (vía SocketIO)
        if (socketManager.isConnected()) {
            val gameState = mapOf(
                "health" to currentState.healthRatio,
                "ammo" to currentState.ammoRatio,
                "enemy" to currentState.enemyPresent
            )
            socketManager.emitFrame(bitmap, gameState)
        }
        
        // 3. Devolver acción pendiente o fallback
        return pendingAction?.also { 
            pendingAction = null
            lastAction = it
        } ?: getFallbackAction()
    }
    
    /**
     * Acción de fallback cuando no hay conexión con el servidor.
     */
    private fun getFallbackAction(): Action {
        // Lógica simple de supervivencia:
        // - Si vida baja, buscar curación
        // - Si munición baja, recargar
        // - Si enemigo detectado, disparar
        // - Si no, esperar
        
        return when {
            currentState.healthRatio < 0.3f -> Action(ActionType.HEAL)
            currentState.ammoRatio < 0.2f -> Action(ActionType.RELOAD)
            currentState.enemyPresent -> Action(ActionType.SHOOT)
            else -> Action.hold()
        }
    }
    
    /**
     * Finaliza un episodio.
     */
    fun endEpisode(finalPlacement: Int) {
        Logger.i("Episodio finalizado - Posición: $finalPlacement")
        episodeStats = EpisodeStats()
        pendingAction = null
    }
    
    /**
     * Obtiene el estado actual del juego.
     */
    fun getCurrentState(): GameState = currentState
    
    /**
     * Obtiene estadísticas del episodio actual.
     */
    fun getEpisodeStats(): EpisodeStats = episodeStats
    
    /**
     * Verifica si está conectado al servidor.
     */
    fun isConnectedToServer(): Boolean = socketManager.isConnected()
    
    /**
     * Fuerza reconexión al servidor.
     */
    fun reconnect() {
        socketManager.disconnect()
        scope.launch {
            delay(500)
            connect()
        }
    }
    
    /**
     * Libera recursos.
     */
    fun destroy() {
        scope.cancel()
        socketManager.disconnect()
    }
}
