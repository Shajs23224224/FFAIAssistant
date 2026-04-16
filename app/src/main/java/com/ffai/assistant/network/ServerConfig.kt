package com.ffai.assistant.network

/**
 * Configuración del servidor remoto para IA en la nube.
 */
object ServerConfig {
    
    // URL del servidor WebSocket (cambia esto por tu servidor)
    const val SERVER_WS_URL = "ws://192.168.1.100:8080/ws"
    
    // URL HTTP base para endpoints REST
    const val SERVER_HTTP_URL = "http://192.168.1.100:8080"
    
    // Tiempo de espera para conexión (ms)
    const val CONNECTION_TIMEOUT = 10000L
    
    // Tiempo entre pings de keepalive (ms)
    const val PING_INTERVAL = 30000L
    
    // Intervalo de envío de frames (ms) - 10 FPS para no saturar
    const val FRAME_INTERVAL = 100L
    
    // Calidad de compresión JPEG (0-100)
    const val JPEG_QUALITY = 60
    
    // Tamaño máximo de frame para enviar (reducir para menos latencia)
    const val MAX_FRAME_WIDTH = 320
    const val MAX_FRAME_HEIGHT = 180
}
