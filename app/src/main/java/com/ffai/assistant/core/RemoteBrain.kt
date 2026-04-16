package com.ffai.assistant.core

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.network.ServerConnection
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.perception.VisionProcessor
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*

/**
 * Brain remoto que delega todas las decisiones al servidor.
 * Captura frames, extrae estado básico, envía al servidor y recibe acciones.
 */
class RemoteBrain(context: Context) {
    
    private val serverConnection = ServerConnection()
    private val vision = VisionProcessor()
    
    private var currentState: GameState = GameState.DEFAULT
    private var lastAction: Action = Action.hold()
    private var episodeStats = EpisodeStats()
    
    private var pendingAction: Action? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callback para cuando se recibe una acción del servidor
    var onActionReady: ((Action) -> Unit)? = null
    
    init {
        Logger.i("RemoteBrain inicializado - Modo IA en la nube")
        
        serverConnection.setOnActionReceived { action ->
            pendingAction = action
            onActionReady?.invoke(action)
            episodeStats.totalActions++
        }
        
        serverConnection.setOnConnectionChanged { connected, message ->
            if (connected) {
                Logger.i("Conectado al servidor de IA")
            } else {
                Logger.w("Desconectado del servidor: $message")
            }
        }
        
        // Conectar al servidor
        scope.launch {
            connect()
        }
    }
    
    /**
     * Conecta al servidor remoto.
     */
    private suspend fun connect(): Boolean {
        return serverConnection.connect()
    }
    
    /**
     * Procesa un frame y devuelve la acción recibida del servidor.
     * Si no hay conexión, usa fallback local.
     */
    fun processFrame(bitmap: Bitmap): Action {
        // 1. Extraer estado básico localmente (rápido)
        currentState = vision.analyze(bitmap)
        
        // 2. Enviar frame al servidor si está conectado
        if (serverConnection.isConnected()) {
            scope.launch {
                serverConnection.sendFrame(
                    bitmap = bitmap,
                    healthRatio = currentState.healthRatio,
                    ammoRatio = currentState.ammoRatio
                )
            }
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
    fun isConnectedToServer(): Boolean = serverConnection.isConnected()
    
    /**
     * Libera recursos.
     */
    fun destroy() {
        scope.cancel()
        serverConnection.destroy()
    }
}
