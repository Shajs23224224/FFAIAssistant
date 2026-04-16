package com.ffai.assistant.network

/**
 * Configuración profesional para Oracle Cloud Infrastructure (OCI)
 * Optimizado para Always Free Tier con SSL/TLS
 */
object ServerConfig {
    
    // ============================================
    // CONFIGURACIÓN DE SERVIDOR OCI
    // ============================================
    
    // Reemplazar con tu IP pública de Oracle Cloud VM
    // Ejemplo: "ws://129.146.123.45/ws" (HTTP) o "wss://tudominio.com/ws" (HTTPS)
    const val SERVER_WS_URL = "ws://YOUR_OCI_IP/ws"
    
    // URL HTTP base para health checks y REST API
    const val SERVER_HTTP_URL = "http://YOUR_OCI_IP"
    
    // Para producción con SSL (recomendado):
    // const val SERVER_WS_URL = "wss://your-domain.com/ws"
    // const val SERVER_HTTP_URL = "https://your-domain.com"
    
    // ============================================
    // TIMING Y PERFORMANCE
    // ============================================
    
    // Tiempo de espera para conexión inicial (ms)
    const val CONNECTION_TIMEOUT = 15000L
    
    // Timeout para recibir respuesta del servidor (ms)
    const val RESPONSE_TIMEOUT = 5000L
    
    // Tiempo entre pings de keepalive para mantener conexión viva (ms)
    const val PING_INTERVAL = 30000L
    
    // Intervalo de envío de frames - 10 FPS (100ms)
    const val FRAME_INTERVAL = 100L
    
    // Backoff exponencial para reconexión: base * (2 ^ intentos)
    const val RECONNECT_BASE_DELAY = 1000L
    const val RECONNECT_MAX_DELAY = 30000L
    const val MAX_RECONNECT_ATTEMPTS = 10
    
    // ============================================
    // COMPRESIÓN Y CALIDAD
    // ============================================
    
    // Calidad de compresión JPEG (0-100). Menor = más compresión
    const val JPEG_QUALITY = 50
    
    // Usar compresión gzip adicional en el payload JSON
    const val USE_GZIP_COMPRESSION = true
    
    // Tamaño máximo de frame para enviar (reducir para menos latencia)
    // Resolución baja pero suficiente para detección de UI
    const val MAX_FRAME_WIDTH = 320
    const val MAX_FRAME_HEIGHT = 180
    
    // ============================================
    // BUFFER Y MEMORIA
    // ============================================
    
    // Tamaño del buffer circular para frames pendientes
    const val FRAME_BUFFER_SIZE = 3
    
    // Memoria máxima para caché de imágenes (MB)
    const val MAX_MEMORY_MB = 64
    
    // ============================================
    // FEATURE FLAGS
    // ============================================
    
    // Habilitar envío de métricas de rendimiento al servidor
    const val SEND_PERFORMANCE_METRICS = true
    
    // Habilitar logging detallado de red
    const val VERBOSE_NETWORK_LOGGING = false
    
    // Usar protocolo binario (más eficiente) vs JSON
    const val USE_BINARY_PROTOCOL = false
}
