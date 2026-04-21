package com.ffai.assistant.network

import com.ffai.assistant.action.Action
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * EventRouter - Sistema de enrutamiento de eventos SocketIO
 * 
 * Features:
 * - Registro dinámico de listeners por evento
 * - Despacho asíncrono de eventos
 * - Priorización de eventos críticos
 * - Métricas de procesamiento
 */
class EventRouter private constructor() {
    
    companion object {
        @Volatile
        private var instance: EventRouter? = null
        
        fun getInstance(): EventRouter {
            return instance ?: synchronized(this) {
                instance ?: EventRouter().also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Mapa de listeners por evento
    private val listeners = ConcurrentHashMap<String, MutableList<EventListener>>()
    
    // Prioridades de eventos
    private val eventPriorities = ConcurrentHashMap<String, EventPriority>()
    
    // Métricas
    private var eventsProcessed = 0L
    private var eventsDropped = 0L
    private var processingStartTime = 0L
    
    // Estado
    private val isActive = kotlinx.coroutines.atomic.AtomicBoolean(true)
    
    enum class EventPriority {
        CRITICAL,  // action, error
        HIGH,      // state_update
        NORMAL,    // otros
        LOW        // logging, debug
    }
    
    data class EventListener(
        val id: String,
        val priority: EventPriority,
        val handler: suspend (JSONObject) -> Unit
    )
    
    init {
        // Configurar prioridades por defecto
        eventPriorities[ServerConfig.EVENT_ACTION] = EventPriority.CRITICAL
        eventPriorities[ServerConfig.EVENT_ERROR] = EventPriority.CRITICAL
        eventPriorities[ServerConfig.EVENT_STATE_UPDATE] = EventPriority.HIGH
        eventPriorities[ServerConfig.EVENT_CONNECTED] = EventPriority.HIGH
        eventPriorities[ServerConfig.EVENT_DISCONNECTED] = EventPriority.HIGH
    }
    
    /**
     * Registra un listener para un evento
     */
    fun on(event: String, priority: EventPriority = EventPriority.NORMAL, 
           handler: suspend (JSONObject) -> Unit): String {
        val id = generateListenerId()
        val listener = EventListener(id, priority, handler)
        
        listeners.computeIfAbsent(event) { mutableListOf() }.add(listener)
        eventPriorities[event] = priority
        
        Logger.d("EventRouter: Listener '$id' registrado para evento '$event' (${priority.name})")
        return id
    }
    
    /**
     * Registra listener con priority CRITICAL (atajo)
     */
    fun onCritical(event: String, handler: suspend (JSONObject) -> Unit): String {
        return on(event, EventPriority.CRITICAL, handler)
    }
    
    /**
     * Registra listener para acciones del servidor
     */
    fun onAction(handler: suspend (Action) -> Unit): String {
        return on(ServerConfig.EVENT_ACTION, EventPriority.CRITICAL) { data ->
            val action = parseAction(data)
            if (action != null) {
                handler(action)
            }
        }
    }
    
    /**
     * Registra listener para errores
     */
    fun onError(handler: suspend (String) -> Unit): String {
        return on(ServerConfig.EVENT_ERROR, EventPriority.CRITICAL) { data ->
            val error = data.optString("message", "Error desconocido")
            handler(error)
        }
    }
    
    /**
     * Registra listener para conexión/desconexión
     */
    fun onConnectionChange(handler: suspend (Boolean, String) -> Unit): String {
        val connectId = on(ServerConfig.EVENT_CONNECTED, EventPriority.HIGH) { _ ->
            handler(true, "Conectado")
        }
        
        val disconnectId = on(ServerConfig.EVENT_DISCONNECTED, EventPriority.HIGH) { data ->
            val reason = data.optString("reason", "Desconectado")
            handler(false, reason)
        }
        
        return "connection_$connectId"  // Retornamos uno como referencia
    }
    
    /**
     * Remueve un listener por ID
     */
    fun off(listenerId: String) {
        listeners.forEach { (event, list) ->
            list.removeIf { it.id == listenerId }
        }
    }
    
    /**
     * Remueve todos los listeners de un evento
     */
    fun offAll(event: String) {
        listeners.remove(event)
    }
    
    /**
     * Despacha un evento a todos los listeners registrados
     */
    fun emit(event: String, data: JSONObject) {
        if (!isActive.get()) {
            eventsDropped++
            return
        }
        
        val eventListeners = listeners[event] ?: run {
            Logger.w("EventRouter: No hay listeners para evento '$event'")
            return
        }
        
        val priority = eventPriorities[event] ?: EventPriority.NORMAL
        
        scope.launch {
            processEvent(event, data, eventListeners, priority)
        }
    }
    
    /**
     * Despacho síncrono (para eventos críticos)
     */
    fun emitSync(event: String, data: JSONObject) {
        runBlocking {
            val eventListeners = listeners[event] ?: return@runBlocking
            val priority = eventPriorities[event] ?: EventPriority.CRITICAL
            processEvent(event, data, eventListeners, priority)
        }
    }
    
    /**
     * Obtiene métricas de procesamiento
     */
    fun getMetrics(): EventMetrics {
        return EventMetrics(
            eventsProcessed = eventsProcessed,
            eventsDropped = eventsDropped,
            activeListeners = listeners.values.sumOf { it.size },
            avgProcessingTime = if (eventsProcessed > 0) processingStartTime / eventsProcessed else 0
        )
    }
    
    /**
     * Limpia todos los listeners
     */
    fun clear() {
        listeners.clear()
        eventPriorities.clear()
        eventsProcessed = 0
        eventsDropped = 0
        processingStartTime = 0
    }
    
    /**
     * Activa/desactiva el router
     */
    fun setActive(active: Boolean) {
        isActive.set(active)
    }
    
    /**
     * Limpieza de recursos
     */
    fun cleanup() {
        isActive.set(false)
        scope.cancel()
        clear()
        instance = null
    }
    
    // ============================================
    // PRIVATE METHODS
    // ============================================
    
    private suspend fun processEvent(
        event: String, 
        data: JSONObject, 
        eventListeners: List<EventListener>,
        priority: EventPriority
    ) {
        val startTime = System.currentTimeMillis()
        
        // Ordenar por prioridad (CRITICAL primero)
        val sorted = eventListeners.sortedBy { it.priority.ordinal }
        
        sorted.forEach { listener ->
            try {
                withTimeout(getTimeoutForPriority(priority)) {
                    listener.handler(data)
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("EventRouter: Timeout procesando evento '$event' en listener '${listener.id}'")
            } catch (e: Exception) {
                Logger.e("EventRouter: Error en listener '${listener.id}' para evento '$event'", e)
            }
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        processingStartTime += processingTime
        eventsProcessed++
        
        Logger.d("EventRouter: Evento '$event' procesado en ${processingTime}ms (${eventListeners.size} listeners)")
    }
    
    private fun parseAction(data: JSONObject): Action? {
        return try {
            val actionType = data.optString("action", "HOLD")
            val x = data.optInt("x", 960)
            val y = data.optInt("y", 700)
            val duration = data.optInt("duration", 100)
            val confidence = data.optDouble("confidence", 1.0)
            
            com.ffai.assistant.action.Action(
                type = com.ffai.assistant.action.ActionType.valueOf(actionType),
                x = x,
                y = y,
                duration = duration,
                confidence = confidence.toFloat()
            )
        } catch (e: Exception) {
            Logger.e("EventRouter: Error parseando action", e)
            null
        }
    }
    
    private fun getTimeoutForPriority(priority: EventPriority): Long {
        return when (priority) {
            EventPriority.CRITICAL -> 5000L  // 5s para críticos
            EventPriority.HIGH -> 3000L
            EventPriority.NORMAL -> 1000L
            EventPriority.LOW -> 500L
        }
    }
    
    private fun generateListenerId(): String {
        return "listener_${System.currentTimeMillis()}_${(0..9999).random()}"
    }
    
    data class EventMetrics(
        val eventsProcessed: Long,
        val eventsDropped: Long,
        val activeListeners: Int,
        val avgProcessingTime: Long
    )
}
