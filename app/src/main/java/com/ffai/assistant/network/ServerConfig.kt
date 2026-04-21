package com.ffai.assistant.network

/**
 * Configuración para Google Colab + ngrok
 * 
 * INSTRUCCIONES:
 * 1. Abre el notebook en Google Colab
 * 2. Ejecuta todas las celdas
 * 3. Copia la URL de ngrok que aparece (ej: https://abc123.ngrok.io)
 * 4. Pega esa URL aquí en SERVER_WS_URL
 * 5. Rebuild y reinstala el APK
 * 
 * NOTA: La URL de ngrok cambia cada vez que reinicias Colab.
 * Para URL permanente: obtén token gratuito en ngrok.com
 */
object ServerConfig {
    
    // ============================================
    // CONFIGURACIÓN NGROK/COLAB (Runtime Configurable)
    // ============================================
    
    // URL configurable en runtime - actualizar desde UI
    var SERVER_URL: String = ""
        private set
    
    // WebSocket URL completo (se construye desde SERVER_URL)
    val SERVER_WS_URL: String
        get() = if (SERVER_URL.isEmpty()) "" else "wss://${SERVER_URL.replace("https://", "").replace("http://", "")}"
    
    // HTTP URL base
    val SERVER_HTTP_URL: String
        get() = if (SERVER_URL.isEmpty()) "" else "https://${SERVER_URL.replace("https://", "").replace("http://", "")}"
    
    /**
     * Configura la URL del servidor (llamar desde MainActivity/UI)
     */
    fun configure(url: String) {
        // Limpiar URL (quitar protocolo y paths)
        SERVER_URL = url.replace("https://", "")
            .replace("http://", "")
            .replace("/ws", "")
            .replace("/", "")
        
        Logger.i("ServerConfig: URL configurada -> $SERVER_URL")
    }
    
    // Token de ngrok - solo referencia, se usa en Colab notebook
    // NOTA: El token real debe configurarse en Colab, NO aquí
    const val NGROK_TOKEN_REFERENCE = "OBTENER_EN_NGROK_DOT_COM"
    
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
    
    // ============================================
    // FEATURES
    // ============================================
    
    const val FRAME_BUFFER_SIZE = 2
    const val MAX_MEMORY_MB = 32
    const val SEND_PERFORMANCE_METRICS = true
    const val VERBOSE_NETWORK_LOGGING = false  // Desactivado para producción
    const val USE_BINARY_PROTOCOL = false
}
