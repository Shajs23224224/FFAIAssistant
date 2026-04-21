package com.ffai.assistant.network

import com.ffai.assistant.utils.Logger

/**
 * Configuración SocketIO para Google Colab + ngrok
 * 
 * INSTRUCCIONES:
 * 1. Abre el notebook en Google Colab
 * 2. Ejecuta todas las celdas
 * 3. Copia la URL de ngrok que aparece
 * 4. Ingresa la URL en la UI del APK (MainActivity)
 * 5. La conexión SocketIO se establece automáticamente
 * 
 * NOTA: La URL de ngrok cambia cada vez que reinicias Colab.
 */
object ServerConfig {
    
    // ============================================
    // SOCKETIO CONFIGURATION
    // ============================================
    
    // Namespace para SocketIO (debe coincidir con servidor)
    const val SOCKETIO_NAMESPACE = "/ffai"
    
    // Path del endpoint SocketIO (default: /socket.io)
    const val SOCKETIO_PATH = "/socket.io"
    
    // ============================================
    // URL CONFIGURATION (Runtime)
    // ============================================
    
    // URL base del servidor (sin protocolo ni paths)
    var SERVER_URL: String = ""
        private set
    
    // URL completa para SocketIO
    val SERVER_SOCKETIO_URL: String
        get() = if (SERVER_URL.isEmpty()) "" else "https://$SERVER_URL"
    
    // URL HTTP para health checks
    val SERVER_HTTP_URL: String
        get() = if (SERVER_URL.isEmpty()) "" else "https://$SERVER_URL"
    
    /**
     * Configura la URL del servidor desde UI
     */
    fun configure(url: String) {
        SERVER_URL = url.replace("https://", "")
            .replace("http://", "")
            .replace("/socket.io", "")
            .replace("/ws", "")
            .trim('/')
        
        Logger.i("ServerConfig: URL configurada -> $SERVER_URL")
    }
    
    // ============================================
    // CONNECTION OPTIONS
    // ============================================
    
    // Timeouts
    const val CONNECTION_TIMEOUT_MS = 20000L
    const val RESPONSE_TIMEOUT_MS = 10000L
    
    // Reconexión automática
    const val RECONNECTION_ENABLED = true
    const val RECONNECTION_DELAY_MS = 1000L
    const val RECONNECTION_DELAY_MAX_MS = 5000L
    const val MAX_RECONNECTION_ATTEMPTS = 10
    
    // Heartbeat (ping/pong)
    const val PING_INTERVAL_MS = 25000L  // SocketIO default: 25s
    const val PING_TIMEOUT_MS = 60000L  // SocketIO default: 60s
    
    // ============================================
    // MULTIPLEXING & ADVANCED
    // ============================================
    
    const val ENABLE_MULTIPLEXING = true
    const val MULTIPLEX_CONNECTIONS = 2
    
    // ============================================
    // BINARY STREAMING
    // ============================================
    
    const val BINARY_STREAM_ENABLED = true
    const val MAX_BINARY_CHUNK_SIZE = 65536  // 64KB
    
    // ============================================
    // COMPRESSION
    // ============================================
    
    const val COMPRESSION_ENABLED = true
    const val JPEG_QUALITY_MIN = 30
    const val JPEG_QUALITY_MAX = 80
    const val JPEG_QUALITY_DEFAULT = 50
    const val COMPRESSION_THRESHOLD_BYTES = 1024
    
    // ============================================
    // TIMING & PERFORMANCE
    // ============================================
    
    const val FRAME_INTERVAL_MS = 150L  // ~6.6 FPS default
    const val FRAME_BUFFER_SIZE = 3
    const val MAX_MEMORY_MB = 32
    
    // Dimensiones máximas de frames
    const val MAX_FRAME_WIDTH = 640
    const val MAX_FRAME_HEIGHT = 360
    const val MAX_FRAME_RESOLUTION = 230400  // 640x360
    
    // ============================================
    // EVENTS
    // ============================================
    
    // Eventos enviados al servidor
    const val EVENT_FRAME = "frame"
    const val EVENT_BINARY_FRAME = "binary_frame"
    const val EVENT_HEALTH_CHECK = "health_check"
    const val EVENT_CONFIG_UPDATE = "config_update"
    
    // Eventos recibidos del servidor
    const val EVENT_ACTION = "action"
    const val EVENT_STATE_UPDATE = "state_update"
    const val EVENT_ERROR = "error"
    const val EVENT_CONNECTED = "connected"
    const val EVENT_DISCONNECTED = "disconnect"
    
    // ============================================
    // TIMING (Optimizado para Colab/latencia variable)
    // ============================================
    
    // Timeout extendido para Colab (puede tardar en responder)
    const val CONNECTION_TIMEOUT = 20000L
    const val RESPONSE_TIMEOUT = 10000L
    
    // Heartbeat más frecuente para mantener túnel ngrok vivo
    const val PING_INTERVAL = 20000L
    
    // Frames menos frecuentes (Colab gratuito tiene límites)
    const val FRAME_INTERVAL = 150L  // ~6.6 FPS
    
    // Reconexión rápida (ngrok puede reiniciar)
    const val RECONNECT_BASE_DELAY = 2000L
    const val RECONNECT_MAX_DELAY = 15000L
    const val MAX_RECONNECT_ATTEMPTS = 15
    
    // ============================================
    // COMPRESIÓN (Importante para ngrok gratuito)
    // ============================================
    
    // Calidad JPEG más baja = menos datos = menos lag
    const val JPEG_QUALITY = 40
    const val USE_GZIP_COMPRESSION = true
    
    // Resolución mínima viable
    const val MAX_FRAME_WIDTH = 240
    const val MAX_FRAME_HEIGHT = 135
}
