package com.ffai.assistant.network

import android.graphics.Bitmap
import android.util.Base64
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.utils.Logger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Conexión WebSocket profesional para Oracle Cloud Infrastructure.
 * 
 * Features:
 * - Reconexión automática con backoff exponencial
 * - Heartbeat/ping para mantener conexión viva
 * - Compresión de frames por JPEG
 * - Manejo robusto de errores y estados
 */
class ServerConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Lazy initialization to avoid creating HttpClient on main thread
    private val client by lazy {
        HttpClient(OkHttp) {
            install(WebSockets)
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = ServerConfig.CONNECTION_TIMEOUT
                requestTimeoutMillis = ServerConfig.RESPONSE_TIMEOUT
            }
        }
    }
    
    private var session: DefaultClientWebSocketSession? = null
    private var isConnected = false
    private var isConnecting = false
    private var lastFrameTime = 0L
    private var reconnectAttempts = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var receiveJob: Job? = null
    
    // Callbacks
    private var onActionReceived: ((Action) -> Unit)? = null
    private var onConnectionChanged: ((Boolean, String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    
    /**
     * Callback para recibir acciones del servidor.
     */
    fun setOnActionReceived(callback: (Action) -> Unit) {
        onActionReceived = callback
    }
    
    /**
     * Callback para cambios de conexión con mensaje de estado.
     */
    fun setOnConnectionChanged(callback: (Boolean, String) -> Unit) {
        onConnectionChanged = callback
    }
    
    /**
     * Callback para errores.
     */
    fun setOnError(callback: (String) -> Unit) {
        onError = callback
    }
    
    /**
     * Conecta al servidor WebSocket con manejo de reconexión.
     */
    suspend fun connect(): Boolean {
        if (isConnecting || isConnected) {
            return isConnected
        }
        
        isConnecting = true
        
        return try {
            Logger.i("Conectando a OCI: ${ServerConfig.SERVER_WS_URL}")
            onConnectionChanged?.invoke(false, "Conectando...")
            
            session = client.webSocketSession(ServerConfig.SERVER_WS_URL)
            isConnected = true
            isConnecting = false
            reconnectAttempts.set(0)
            
            onConnectionChanged?.invoke(true, "Conectado a Oracle Cloud")
            Logger.i("✓ Conectado al servidor OCI")
            
            // Cancelar cualquier job de reconexión pendiente
            reconnectJob?.cancel()
            reconnectJob = null
            
            // Iniciar heartbeat
            startHeartbeat()
            
            // Iniciar loop de recepción
            receiveJob?.cancel()
            receiveJob = scope.launch {
                receiveMessages()
            }
            
            true
            
        } catch (e: Exception) {
            Logger.e("Error de conexión", e)
            isConnected = false
            isConnecting = false
            onConnectionChanged?.invoke(false, "Error: ${e.message}")
            onError?.invoke("No se pudo conectar: ${e.message}")
            
            // Iniciar reconexión automática
            scheduleReconnect()
            
            false
        }
    }
    
    /**
     * Programar reconexión con backoff exponencial.
     */
    private fun scheduleReconnect() {
        val attempts = reconnectAttempts.incrementAndGet()
        
        if (attempts > ServerConfig.MAX_RECONNECT_ATTEMPTS) {
            Logger.e("Máximo de intentos de reconexión alcanzado")
            onError?.invoke("Máximo de reconexiones alcanzado. Reinicia la app.")
            return
        }
        
        // Backoff exponencial: base * 2^attempts, capped at max
        val delay = minOf(
            ServerConfig.RECONNECT_BASE_DELAY * (1 shl (attempts - 1)),
            ServerConfig.RECONNECT_MAX_DELAY
        )
        
        Logger.i("Reconexión en ${delay}ms (intento $attempts/${ServerConfig.MAX_RECONNECT_ATTEMPTS})")
        onConnectionChanged?.invoke(false, "Reconectando en ${delay/1000}s...")
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            connect()
        }
    }
    
    /**
     * Iniciar heartbeat para mantener conexión viva.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isConnected && isActive) {
                delay(ServerConfig.PING_INTERVAL)
                try {
                    session?.send(Frame.Ping(ByteArray(0)))
                    if (ServerConfig.VERBOSE_NETWORK_LOGGING) {
                        Logger.d("Heartbeat enviado")
                    }
                } catch (e: Exception) {
                    Logger.w("Heartbeat falló, conexión probablemente muerta")
                    handleDisconnection()
                    break
                }
            }
        }
    }
    
    /**
     * Manejar desconexión detectada.
     */
    private suspend fun handleDisconnection() {
        if (!isConnected) return
        
        Logger.w("Desconexión detectada")
        isConnected = false
        onConnectionChanged?.invoke(false, "Desconectado")
        
        try {
            session?.close()
        } catch (e: Exception) {
            // Ignorar errores al cerrar
        }
        
        session = null
        scheduleReconnect()
    }
    
    /**
     * Desconecta del servidor manualmente.
     */
    suspend fun disconnect() {
        Logger.i("Desconexión manual solicitada")
        
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        receiveJob?.cancel()
        receiveJob = null
        
        reconnectAttempts.set(ServerConfig.MAX_RECONNECT_ATTEMPTS + 1) // Prevenir auto-reconexión
        
        try {
            session?.close()
            session = null
            isConnected = false
            onConnectionChanged?.invoke(false, "Desconectado manualmente")
            Logger.i("Desconectado del servidor OCI")
        } catch (e: Exception) {
            Logger.e("Error desconectando", e)
        }
    }
    
    /**
     * Envía un frame de pantalla al servidor.
     * Solo envía si ha pasado suficiente tiempo desde el último frame.
     */
    suspend fun sendFrame(bitmap: Bitmap, healthRatio: Float, ammoRatio: Float) {
        if (!isConnected || session == null) return
        
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < ServerConfig.FRAME_INTERVAL) return
        lastFrameTime = now
        
        try {
            // Redimensionar y comprimir frame
            val scaledBitmap = scaleBitmap(bitmap)
            val base64Image = bitmapToBase64(scaledBitmap)
            
            val message = FrameMessage(
                type = "frame",
                timestamp = now,
                health = healthRatio,
                ammo = ammoRatio,
                imageBase64 = base64Image
            )
            
            val json = Json.encodeToString(FrameMessage.serializer(), message)
            session?.send(json)
            
            if (scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            
        } catch (e: Exception) {
            Logger.e("Error enviando frame", e)
            if (e is ClosedReceiveChannelException) {
                isConnected = false
                onConnectionChanged?.invoke(false, "Desconectado por error")
            }
        }
    }
    
    /**
     * Recibe mensajes del servidor con manejo robusto de errores.
     */
    private suspend fun receiveMessages() {
        while (isConnected && currentCoroutineContext().isActive) {
            try {
                val frame = session?.incoming?.receive()
                
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        handleServerMessage(text)
                    }
                    is Frame.Binary -> {
                        // Manejar datos binarios si es necesario
                        if (ServerConfig.VERBOSE_NETWORK_LOGGING) {
                            Logger.d("Frame binario recibido: ${frame.readBytes().size} bytes")
                        }
                    }
                    is Frame.Ping -> {
                        session?.send(Frame.Pong(ByteArray(0)))
                    }
                    is Frame.Close -> {
                        Logger.w("Servidor solicitó cierre")
                        handleDisconnection()
                        return
                    }
                    else -> {
                        if (ServerConfig.VERBOSE_NETWORK_LOGGING) {
                            Logger.d("Frame tipo: ${frame?.frameType}")
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                Logger.w("Canal cerrado")
                handleDisconnection()
                return
            } catch (e: CancellationException) {
                Logger.d("Recepción cancelada")
                return
            } catch (e: Exception) {
                Logger.e("Error recibiendo mensaje", e)
                delay(1000)
            }
        }
    }
    
    /**
     * Procesa mensajes recibidos del servidor.
     */
    private fun handleServerMessage(json: String) {
        try {
            val response = Json.decodeFromString(ServerResponse.serializer(), json)
            
            if (response.type == "action") {
                val action = parseAction(response)
                onActionReceived?.invoke(action)
            }
        } catch (e: Exception) {
            Logger.e("Error parseando mensaje del servidor", e)
        }
    }
    
    /**
     * Convierte la respuesta del servidor a una Action.
     */
    private fun parseAction(response: ServerResponse): Action {
        val actionType = ActionType.fromName(response.action ?: "HOLD")
        return Action(
            type = actionType,
            targetX = response.coordinates?.x ?: 0f,
            targetY = response.coordinates?.y ?: 0f,
            intensity = 1f
        )
    }
    
    /**
     * Escala el bitmap para reducir tamaño de transmisión.
     */
    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= ServerConfig.MAX_FRAME_WIDTH && height <= ServerConfig.MAX_FRAME_HEIGHT) {
            return bitmap
        }
        
        val scale = minOf(
            ServerConfig.MAX_FRAME_WIDTH.toFloat() / width,
            ServerConfig.MAX_FRAME_HEIGHT.toFloat() / height
        )
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Convierte bitmap a Base64 JPEG.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, ServerConfig.JPEG_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    fun isConnected(): Boolean = isConnected
    
    /**
     * Libera recursos.
     */
    /**
     * Fuerza reconexión inmediata.
     */
    fun forceReconnect() {
        reconnectAttempts.set(0)
        reconnectJob?.cancel()
        
        scope.launch {
            disconnect()
            delay(500)
            connect()
        }
    }
    
    fun destroy() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        receiveJob?.cancel()
        
        scope.launch {
            try {
                disconnect()
                client.close()
            } catch (e: Exception) {
                Logger.e("Error en destroy", e)
            } finally {
                scope.cancel()
            }
        }
    }
}

/**
 * Mensaje enviado al servidor con frame de pantalla.
 */
@Serializable
data class FrameMessage(
    val type: String,
    val timestamp: Long,
    val health: Float,
    val ammo: Float,
    val imageBase64: String
)

/**
 * Respuesta recibida del servidor con acción a ejecutar.
 */
@Serializable
data class ServerResponse(
    val type: String,
    val action: String? = null,
    val confidence: Float? = null,
    val coordinates: Coordinates? = null,
    val duration: Int? = null
)

@Serializable
data class Coordinates(
    val x: Float,
    val y: Float
)
