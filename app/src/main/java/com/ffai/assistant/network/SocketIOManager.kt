package com.ffai.assistant.network

import android.graphics.Bitmap
import android.util.Base64
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.utils.Logger
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SocketIOManager - Gestión profesional de conexiones SocketIO
 * 
 * Features:
 * - Conexión/desconexión automática
 * - Reconexión con backoff exponencial
 * - Eventos: frame, action, error, connect, disconnect
 * - Acknowledgments para confirmación de recepción
 * - Métricas de latencia y throughput
 */
class SocketIOManager private constructor() {
    
    companion object {
        @Volatile
        private var instance: SocketIOManager? = null
        
        fun getInstance(): SocketIOManager {
            return instance ?: synchronized(this) {
                instance ?: SocketIOManager().also { instance = it }
            }
        }
    }
    
    // Scope para corutinas
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // SocketIO client
    private var socket: Socket? = null
    
    // Estado de conexión
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    
    // Métricas
    private val framesSent = AtomicInteger(0)
    private val actionsReceived = AtomicInteger(0)
    private var lastPingTime = 0L
    private var currentLatency = 0L
    
    // Configuración de compresión adaptativa
    private var currentJpegQuality = ServerConfig.JPEG_QUALITY_DEFAULT
    
    // Callbacks
    private var onActionReceived: ((Action) -> Unit)? = null
    private var onConnectionChanged: ((Boolean, String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onMetricsUpdate: ((latency: Long, fps: Int, bytesSent: Long) -> Unit)? = null
    
    // Jobs
    private var metricsJob: Job? = null
    
    /**
     * Callback para recibir acciones del servidor
     */
    fun setOnActionReceived(callback: (Action) -> Unit) {
        onActionReceived = callback
    }
    
    /**
     * Callback para cambios de conexión
     */
    fun setOnConnectionChanged(callback: (Boolean, String) -> Unit) {
        onConnectionChanged = callback
    }
    
    /**
     * Callback para errores
     */
    fun setOnError(callback: (String) -> Unit) {
        onError = callback
    }
    
    /**
     * Callback para métricas de performance
     */
    fun setOnMetricsUpdate(callback: (latency: Long, fps: Int, bytesSent: Long) -> Unit) {
        onMetricsUpdate = callback
    }
    
    /**
     * Conecta al servidor SocketIO
     */
    fun connect(serverUrl: String = ServerConfig.SERVER_SOCKETIO_URL): Boolean {
        if (isConnecting.get() || isConnected.get()) {
            Logger.w("SocketIOManager: Ya conectado o conectando")
            return isConnected.get()
        }
        
        if (serverUrl.isEmpty()) {
            Logger.e("SocketIOManager: URL del servidor no configurada")
            onError?.invoke("URL del servidor no configurada. Ingresa la URL en la configuración.")
            return false
        }
        
        isConnecting.set(true)
        onConnectionChanged?.invoke(false, "Conectando...")
        
        try {
            // Opciones de SocketIO
            val options = IO.Options().apply {
                reconnection = ServerConfig.RECONNECTION_ENABLED
                reconnectionAttempts = ServerConfig.MAX_RECONNECTION_ATTEMPTS
                reconnectionDelay = ServerConfig.RECONNECTION_DELAY_MS
                reconnectionDelayMax = ServerConfig.RECONNECTION_DELAY_MAX_MS
                timeout = ServerConfig.CONNECTION_TIMEOUT_MS.toLong()
                forceNew = true
                transports = arrayOf("websocket", "polling")
            }
            
            // Crear socket con namespace
            val fullUrl = "$serverUrl${ServerConfig.SOCKETIO_NAMESPACE}"
            Logger.i("SocketIOManager: Conectando a $fullUrl")
            
            socket = IO.socket(fullUrl, options)
            
            // Registrar listeners
            setupSocketListeners()
            
            // Conectar
            socket?.connect()
            
            // Iniciar métricas
            startMetricsCollection()
            
            return true
            
        } catch (e: Exception) {
            Logger.e("SocketIOManager: Error al conectar", e)
            isConnecting.set(false)
            onError?.invoke("Error de conexión: ${e.message}")
            return false
        }
    }
    
    /**
     * Desconecta del servidor
     */
    fun disconnect() {
        Logger.i("SocketIOManager: Desconectando...")
        
        metricsJob?.cancel()
        metricsJob = null
        
        socket?.disconnect()
        socket?.off()  // Remover todos los listeners
        socket = null
        
        isConnected.set(false)
        isConnecting.set(false)
        reconnectAttempts.set(0)
        
        onConnectionChanged?.invoke(false, "Desconectado")
    }
    
    /**
     * Verifica si está conectado
     */
    fun isConnected(): Boolean = isConnected.get()
    
    /**
     * Envía un frame al servidor
     */
    fun emitFrame(bitmap: Bitmap, gameState: Map<String, Any> = emptyMap(), 
                  ack: ((Boolean) -> Unit)? = null) {
        if (!isConnected.get()) {
            Logger.w("SocketIOManager: No conectado, no se puede enviar frame")
            ack?.invoke(false)
            return
        }
        
        scope.launch {
            try {
                // Comprimir bitmap a JPEG
                val compressedData = compressBitmap(bitmap)
                val base64Image = Base64.encodeToString(compressedData, Base64.NO_WRAP)
                
                // Crear payload
                val payload = JSONObject().apply {
                    put("imageBase64", base64Image)
                    put("timestamp", System.currentTimeMillis())
                    put("quality", currentJpegQuality)
                    put("size", compressedData.size)
                    
                    // Agregar estado del juego si existe
                    gameState.forEach { (key, value) ->
                        put(key, value)
                    }
                }
                
                // Enviar con acknowledgment opcional
                if (ack != null) {
                    socket?.emit(ServerConfig.EVENT_FRAME, payload, object : io.socket.emitter.Emitter.Ack {
                        override fun call(vararg args: Any?) {
                            val success = args.isNotEmpty() && args[0] as? Boolean == true
                            ack.invoke(success)
                        }
                    })
                } else {
                    socket?.emit(ServerConfig.EVENT_FRAME, payload)
                }
                
                framesSent.incrementAndGet()
                
            } catch (e: Exception) {
                Logger.e("SocketIOManager: Error al enviar frame", e)
                ack?.invoke(false)
            }
        }
    }
    
    /**
     * Envía frame binario (más eficiente)
     */
    fun emitBinaryFrame(bitmap: Bitmap, gameState: Map<String, Any> = emptyMap()) {
        if (!isConnected.get() || !ServerConfig.BINARY_STREAM_ENABLED) {
            // Fallback a base64
            emitFrame(bitmap, gameState)
            return
        }
        
        scope.launch {
            try {
                val compressedData = compressBitmap(bitmap)
                val metadata = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("quality", currentJpegQuality)
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    gameState.forEach { (key, value) -> put(key, value) }
                }
                
                // Emitir como binario
                socket?.emit(ServerConfig.EVENT_BINARY_FRAME, compressedData, metadata)
                framesSent.incrementAndGet()
                
            } catch (e: Exception) {
                Logger.e("SocketIOManager: Error al enviar frame binario", e)
            }
        }
    }
    
    /**
     * Health check manual
     */
    fun emitHealthCheck() {
        if (!isConnected.get()) return
        
        lastPingTime = System.currentTimeMillis()
        socket?.emit(ServerConfig.EVENT_HEALTH_CHECK, object : io.socket.emitter.Emitter.Ack {
            override fun call(vararg args: Any?) {
                if (args.isNotEmpty()) {
                    currentLatency = System.currentTimeMillis() - lastPingTime
                    Logger.d("SocketIOManager: Latencia = ${currentLatency}ms")
                }
            }
        })
    }
    
    /**
     * Configuración dinámica
     */
    fun updateConfig(newConfig: Map<String, Any>) {
        if (!isConnected.get()) return
        
        val configJson = JSONObject()
        newConfig.forEach { (key, value) ->
            configJson.put(key, value)
        }
        
        socket?.emit(ServerConfig.EVENT_CONFIG_UPDATE, configJson)
        Logger.i("SocketIOManager: Configuración actualizada")
    }
    
    /**
     * Ajusta calidad JPEG basado en latencia
     */
    fun adjustQualityBasedOnLatency(latencyMs: Long) {
        currentJpegQuality = when {
            latencyMs > 300 -> (currentJpegQuality - 10).coerceAtLeast(ServerConfig.JPEG_QUALITY_MIN)
            latencyMs < 100 -> (currentJpegQuality + 5).coerceAtMost(ServerConfig.JPEG_QUALITY_MAX)
            else -> currentJpegQuality
        }
    }
    
    // ============================================
    // PRIVATE METHODS
    // ============================================
    
    private fun setupSocketListeners() {
        socket?.apply {
            // Connect
            on(Socket.EVENT_CONNECT, Emitter.Listener {
                Logger.i("SocketIOManager: Conectado al servidor")
                isConnected.set(true)
                isConnecting.set(false)
                reconnectAttempts.set(0)
                onConnectionChanged?.invoke(true, "Conectado")
            })
            
            // Disconnect
            on(Socket.EVENT_DISCONNECT, Emitter.Listener { args ->
                val reason = args.getOrNull(0)?.toString() ?: "Desconocido"
                Logger.i("SocketIOManager: Desconectado - $reason")
                isConnected.set(false)
                onConnectionChanged?.invoke(false, "Desconectado: $reason")
            })
            
            // Connect Error
            on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { args ->
                val error = args.getOrNull(0)?.toString() ?: "Error desconocido"
                Logger.e("SocketIOManager: Error de conexión - $error")
                isConnecting.set(false)
                reconnectAttempts.incrementAndGet()
                onConnectionChanged?.invoke(false, "Error: $error (intento ${reconnectAttempts.get()})")
            })
            
            // Reconnecting
            on("reconnecting", Emitter.Listener { args ->
                val attempt = args.getOrNull(0) as? Int ?: 0
                Logger.i("SocketIOManager: Reconectando... intento $attempt")
                reconnectAttempts.set(attempt)
                onConnectionChanged?.invoke(false, "Reconectando... ($attempt/${ServerConfig.MAX_RECONNECTION_ATTEMPTS})")
            })
            
            // Reconnect
            on("reconnect", Emitter.Listener { args ->
                val attempt = args.getOrNull(0) as? Int ?: 0
                Logger.i("SocketIOManager: Reconectado después de $attempt intentos")
                isConnected.set(true)
                reconnectAttempts.set(0)
                onConnectionChanged?.invoke(true, "Reconectado")
            })
            
            // Reconnect Failed
            on("reconnect_failed", Emitter.Listener {
                Logger.e("SocketIOManager: Reconexión fallida después de ${ServerConfig.MAX_RECONNECTION_ATTEMPTS} intentos")
                isConnecting.set(false)
                onError?.invoke("Reconexión fallida. Verifica tu conexión y URL del servidor.")
            })
            
            // Custom events
            on(ServerConfig.EVENT_ACTION, Emitter.Listener { args ->
                if (args.isNotEmpty()) {
                    handleAction(args[0] as? JSONObject)
                }
            })
            
            on(ServerConfig.EVENT_STATE_UPDATE, Emitter.Listener { args ->
                if (args.isNotEmpty()) {
                    Logger.d("SocketIOManager: State update recibido")
                    // Manejar actualizaciones de estado del servidor
                }
            })
            
            on(ServerConfig.EVENT_ERROR, Emitter.Listener { args ->
                val error = args.getOrNull(0)?.toString() ?: "Error del servidor"
                Logger.e("SocketIOManager: Error del servidor - $error")
                onError?.invoke("Servidor: $error")
            })
        }
    }
    
    private fun handleAction(data: JSONObject?) {
        if (data == null) return
        
        try {
            actionsReceived.incrementAndGet()
            
            val actionType = data.optString("action", "HOLD")
            val x = data.optInt("x", 960)
            val y = data.optInt("y", 700)
            val duration = data.optInt("duration", 100)
            val confidence = data.optDouble("confidence", 1.0)
            
            // Mapear coordenadas de pantalla a rango -1..1 para targetX/targetY
            val normalizedX = (x / 1080f * 2f) - 1f
            val normalizedY = (y / 1920f * 2f) - 1f
            val intensity = (duration / 1000f).coerceIn(0f, 1f)
            
            val action = Action(
                type = ActionType.valueOf(actionType),
                targetX = normalizedX,
                targetY = normalizedY,
                intensity = intensity
            )
            
            // Ejecutar en hilo principal para UI
            CoroutineScope(Dispatchers.Main).launch {
                onActionReceived?.invoke(action)
            }
            
        } catch (e: Exception) {
            Logger.e("SocketIOManager: Error al procesar action", e)
        }
    }
    
    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, currentJpegQuality, outputStream)
        return outputStream.toByteArray()
    }
    
    private fun startMetricsCollection() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (isActive) {
                delay(1000)  // Cada segundo
                
                val fps = framesSent.getAndSet(0)
                val actions = actionsReceived.getAndSet(0)
                
                // Calcular bytes enviados (estimado)
                val avgFrameSize = 15 * 1024  // 15KB promedio
                val bytesSent = fps * avgFrameSize.toLong()
                
                withContext(Dispatchers.Main) {
                    onMetricsUpdate?.invoke(currentLatency, fps, bytesSent)
                }
                
                // Ajustar calidad basado en latencia
                adjustQualityBasedOnLatency(currentLatency)
            }
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
        instance = null
    }
}
