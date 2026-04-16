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

/**
 * Conexión WebSocket con el servidor de IA remoto.
 * Envía frames de pantalla y recibe acciones a ejecutar.
 */
class ServerConnection {
    
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = ServerConfig.CONNECTION_TIMEOUT
            requestTimeoutMillis = ServerConfig.CONNECTION_TIMEOUT
        }
    }
    
    private var session: DefaultClientWebSocketSession? = null
    private var isConnected = false
    private var lastFrameTime = 0L
    
    private var onActionReceived: ((Action) -> Unit)? = null
    private var onConnectionChanged: ((Boolean) -> Unit)? = null
    
    /**
     * Callback para recibir acciones del servidor.
     */
    fun setOnActionReceived(callback: (Action) -> Unit) {
        onActionReceived = callback
    }
    
    /**
     * Callback para cambios de conexión.
     */
    fun setOnConnectionChanged(callback: (Boolean) -> Unit) {
        onConnectionChanged = callback
    }
    
    /**
     * Conecta al servidor WebSocket.
     */
    suspend fun connect(): Boolean {
        return try {
            Logger.i("Conectando al servidor: ${ServerConfig.SERVER_WS_URL}")
            
            session = client.webSocketSession(ServerConfig.SERVER_WS_URL)
            isConnected = true
            onConnectionChanged?.invoke(true)
            
            Logger.i("Conexión establecida con servidor")
            
            // Iniciar loop de recepción de mensajes
            CoroutineScope(Dispatchers.IO).launch {
                receiveMessages()
            }
            
            true
        } catch (e: Exception) {
            Logger.e("Error conectando al servidor", e)
            isConnected = false
            onConnectionChanged?.invoke(false)
            false
        }
    }
    
    /**
     * Desconecta del servidor.
     */
    suspend fun disconnect() {
        try {
            session?.close()
            session = null
            isConnected = false
            onConnectionChanged?.invoke(false)
            Logger.i("Desconectado del servidor")
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
            
            scaledBitmap.recycle()
            
        } catch (e: Exception) {
            Logger.e("Error enviando frame", e)
            if (e is ClosedReceiveChannelException) {
                isConnected = false
                onConnectionChanged?.invoke(false)
            }
        }
    }
    
    /**
     * Recibe mensajes del servidor.
     */
    private suspend fun receiveMessages() {
        while (isConnected) {
            try {
                val frame = session?.incoming?.receive()
                
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleServerMessage(text)
                }
            } catch (e: ClosedReceiveChannelException) {
                Logger.w("Conexión cerrada por el servidor")
                isConnected = false
                onConnectionChanged?.invoke(false)
                break
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
        val actionType = ActionType.valueOf(response.action ?: "HOLD")
        return Action(
            type = actionType,
            x = response.coordinates?.x ?: 0f,
            y = response.coordinates?.y ?: 0f,
            duration = response.duration ?: 100
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
    fun destroy() {
        CoroutineScope(Dispatchers.IO).launch {
            disconnect()
            client.close()
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
