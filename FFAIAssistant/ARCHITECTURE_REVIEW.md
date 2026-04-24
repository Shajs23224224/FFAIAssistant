# Revisión de Arquitectura Colab ↔ APK

## 🚨 Problemas Críticos Identificados

### 1. **Incompatibilidad de Protocolos**

| Componente | Protocolo | Librería |
|------------|-----------|----------|
| **Servidor Colab** | SocketIO | `flask-socketio` |
| **APK Android** | WebSocket puro | `ktor-client-websockets` |

**Problema**: SocketIO añade capa de protocolo propia sobre WebSocket. No son compatibles directamente.

### 2. **URL Hardcodeada**

`ServerConfig.kt` línea 24-27:
```kotlin
const val SERVER_WS_URL = "wss://poem-tipping-glitter.ngrok-free.dev/ws"
const val SERVER_HTTP_URL = "https://poem-tipping-glitter.ngrok-free.dev"
```

**Problema**: La URL de ngrok cambia cada vez que reinicia Colab. Necesita ser configurable en runtime.

### 3. **Token Ngrok Expuesto**

Token hardcodeado en:
- `colab/FFAI_Colab_Notebook.ipynb` celda 7
- `app/src/main/java/com/ffai/assistant/network/ServerConfig.kt`

---

## ✅ Soluciones Recomendadas

### Opción A: Cambiar APK a SocketIO (Recomendado)

Reemplazar Ktor WebSocket con `socket.io-client-java`:

```kotlin
// build.gradle.kts dependencies
implementation("io.socket:socket.io-client:2.1.0")
```

Ventajas:
- Compatible con Flask-SocketIO
- Manejo de reconexión automática
- Fallback a polling HTTP si WebSocket falla

### Opción B: Cambiar Servidor a WebSocket Puro

Reemplazar Flask-SocketIO con WebSocket puro:

```python
# En lugar de flask-socketio
import websockets
import asyncio

async def handler(websocket, path):
    async for message in websocket:
        # Procesar frame
        await websocket.send(response)
```

Desventaja: Pierde compatibilidad con HTTP fallback.

### Opción C: Implementar Configuración Dinámica (Quick Fix)

1. **ServerConfig.kt** - Permitir configuración en runtime:
```kotlin
object ServerConfig {
    var SERVER_URL: String = ""
        private set
    
    fun configure(url: String) {
        SERVER_URL = url
    }
}
```

2. **UI en MainActivity.kt** - Campo para ingresar URL de ngrok

---

## 📝 Estado Actual del Notebook (Post-Corrección)

✅ **Corregido en celda 15:**
- Token ngrok ahora lee de variable de entorno primero
- URL de conexión documentada correctamente

⚠️ **Pendiente:**
- APK necesita cambio de librería WebSocket → SocketIO
- O implementar configuración dinámica de URL

---

## 🔄 Flujo de Trabajo Recomendado

1. **Usuario inicia Colab** → Obtiene URL ngrok
2. **Usuario abre APK** → Ingresa URL manualmente o escanea QR
3. **APK conecta** vía SocketIO al servidor
4. **Comunicación bidireccional** establecida

---

## 📋 Checklist para Implementar

- [ ] Decidir: Opción A (SocketIO en APK) u Opción C (config dinámica)
- [ ] Implementar cambio de librería si se elige Opción A
- [ ] Agregar UI de configuración de servidor en APK
- [ ] Actualizar documentación de setup
- [ ] Probar conexión end-to-end

---

**Nota**: El notebook de Colab está funcional. El problema está en la compatibilidad protocolo cliente-servidor.
