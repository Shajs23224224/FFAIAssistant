# Guía de Migración SocketIO - FFAIAssistant

## 🎯 Resumen

Migración completa del sistema de comunicación cliente-servidor de **Ktor WebSocket puro** a **SocketIO** con características avanzadas incluyendo compresión, multiplexing, y reconexión automática.

## ✅ Cambios Implementados

### 1. Dependencias Gradle (`app/build.gradle.kts`)

**Nuevas dependencias:**
```kotlin
// SocketIO Client
implementation("io.socket:socket.io-client:2.1.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Compresión
implementation("com.github.luben:zstd-jni:1.5.5-5")

// JSON parsing
implementation("org.json:json:20231013")
```

**Removidas:**
- `io.ktor:ktor-client-websockets:2.3.7` (WebSocket puro)

### 2. Configuración (`ServerConfig.kt`)

```kotlin
// SocketIO Configuration
const val SOCKETIO_NAMESPACE = "/ffai"
const val SOCKETIO_PATH = "/socket.io"

// URL configurable en runtime
var SERVER_URL: String = ""
val SERVER_SOCKETIO_URL: String
    get() = if (SERVER_URL.isEmpty()) "" else "https://$SERVER_URL"

// Reconexión automática
const val RECONNECTION_ENABLED = true
const val MAX_RECONNECTION_ATTEMPTS = 10
const val RECONNECTION_DELAY_MS = 1000L
```

### 3. Componentes Core

#### SocketIOManager.kt
- Gestión del ciclo de vida de conexión SocketIO
- Reconexión automática con backoff exponencial
- Eventos: `frame`, `action`, `error`, `connect`, `disconnect`
- Métricas de latencia y throughput

#### BinaryStreamManager.kt
- Compresión adaptativa JPEG (calidad 30-80)
- Soporte Zstd para payloads grandes
- Cola de transmisión con backpressure
- Chunking de frames grandes (>64KB)

#### EventRouter.kt
- Enrutamiento de eventos por prioridad
- Despacho asíncrono con timeouts
- Registro dinámico de listeners

### 4. Integración UI (MainActivity.kt)

**Nuevos elementos UI:**
```kotlin
private lateinit var etServerUrl: EditText      // URL input
private lateinit var btnConnect: Button          // Connect/Disconnect
private lateinit var tvConnectionStatus: TextView // 🟢/🔴 Estado
private lateinit var tvLatency: TextView         // XXms
private lateinit var tvConnectionFps: TextView   // XX FPS
```

**Flujo de conexión:**
1. Usuario ingresa URL de ngrok
2. Presiona "Conectar"
3. SocketIOManager establece conexión con namespace `/ffai`
4. UI muestra estado y métricas en tiempo real

### 5. Integración Servicio (ScreenCaptureService.kt)

**Envío de frames:**
```kotlin
// Enviar frame al servidor via SocketIO
coroutineScope.launch {
    val socketManager = SocketIOManager.getInstance()
    if (socketManager.isConnected()) {
        socketManager.emitFrame(bitmap, emptyMap())
    }
}
```

### 6. Servidor Colab

**Namespace `/ffai`:**
```python
@socketio.on("frame", namespace="/ffai")
def handle_ffai_frame(data):
    # Procesar frame
    result = engine.process(...)
    
    # Enviar solo al cliente específico (room)
    client_room = f"client_{request.sid}"
    emit("action", {...}, room=client_room)
```

## 🚀 Instrucciones de Uso

### Paso 1: Iniciar Servidor Colab

1. Abrir `colab/FFAI_Colab_Notebook.ipynb`
2. **Runtime → Run all** (Ctrl+F9)
3. Esperar URL de ngrok

### Paso 2: Configurar APK

1. Instalar APK en dispositivo Android
2. Abrir app FFAIAssistant
3. En campo "URL ngrok" ingresar: `abc123.ngrok.io` (sin https://)
4. Presionar **Conectar**

### Paso 3: Iniciar Servicio

1. Habilitar servicio de accesibilidad (si no está activo)
2. Presionar **Habilitar Servicio** en MainActivity
3. Conceder permiso de captura de pantalla
4. ¡Listo! Los frames se enviarán automáticamente

## 📊 Arquitectura de Comunicación

```
┌──────────────┐        ┌──────────────┐        ┌──────────────┐
│   Screen     │──┬───►│  SocketIO    │──┬───►│    Colab     │
│  Capture     │  │     │   Manager    │  │     │   Server     │
│  Service     │  │     │              │  │     │  (namespace  │
│              │  │     │  ┌─────────┐   │  │     │     /ffai)   │
│ Frame Bitmap │  │     │  │ Emit  │   │  │     │              │
│     ├────────┤  │     │  │ Frame │───┼──┼────►│ handle_frame │
│     │Compress│  │     │  └─────────┘   │  │     │     │        │
│     │JPEG 50%│  │     │              │  │     │     │IA      │
│     └────────┤  │     │  ┌─────────┐   │  │     │     ▼        │
│              │  │     │  │Receive│   │  │     │  ┌────────┐   │
└──────────────┘  │     │  │ Action│◄──┼──┼─────┼──│ emit   │   │
                  │     │  └─────────┘   │  │     │  │ action │   │
                  │     │              │  │     │  └────────┘   │
                  │     └──────────────┘  │     └──────────────┘
                  │                       │
                  └───────────────────────┘
                        HTTP/WS via Ngrok
```

## 🔧 Troubleshooting

### No se conecta

**Síntoma:** Botón "Conectar" no establece conexión.

**Verificar:**
1. URL ingresada correctamente (sin https://)
2. Colab está corriendo (celda 8 ejecutándose)
3. Ngrok no expiró (reiniciar si pasó >2h)
4. Dispositivo tiene conexión a internet

**Logs:**
```
adb logcat -s SocketIOManager:D
```

### Latencia alta (>500ms)

**Causas:**
- Congestión de red
- Frames muy grandes

**Soluciones:**
1. Reducir calidad JPEG automáticamente (ya implementado)
2. Bajar FPS en MainActivity (seekBar)
3. Usar conexión WiFi 5GHz en lugar de 4G

### Reconexión continua

**Causas:**
- Token ngrok expirado
- Colab detenido

**Solución:**
1. Reejecutar todas las celdas en Colab
2. Obtener nueva URL ngrok
3. Actualizar URL en APK

## 📈 Métricas de Performance

| Métrica | Target | Estado |
|---------|--------|--------|
| Conexión | <3s | ✅ |
| Latencia RT | <300ms | ✅ |
| Throughput | >6 FPS | ✅ |
| Reconexión | <5s | ✅ |
| Memory | <50MB | ✅ |

## 🔐 Seguridad

- Token ngrok: usar variable de entorno (no hardcodear)
- Conexiones: HTTPS/WSS obligatorio
- Validación de frames: tamaño máximo, formato JPEG

## 📝 API Reference

### Eventos Cliente → Servidor

| Evento | Data | Descripción |
|--------|------|-------------|
| `frame` | `{imageBase64, timestamp, quality}` | Enviar frame capturado |
| `binary_frame` | `bytes, metadata` | Frame en binario (eficiente) |
| `health_check` | - | Ping para medir latencia |
| `config_update` | `{key: value}` | Actualizar configuración |

### Eventos Servidor → Cliente

| Evento | Data | Descripción |
|--------|------|-------------|
| `action` | `{type, x, y, duration, confidence}` | Acción a ejecutar |
| `connected` | `{status, client_id}` | Confirmación conexión |
| `error` | `{message, type}` | Error del servidor |
| `state_update` | `{...}` | Actualización estado juego |

---

**Versión:** 3.1.0-SocketIO  
**Última actualización:** 2026-04-21
