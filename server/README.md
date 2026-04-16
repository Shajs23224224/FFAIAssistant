# Servidor Free Fire AI

Servidor Python para procesar frames de gameplay y decidir acciones.

## Instalación

```bash
cd server
pip install -r requirements.txt
```

## Uso

### Iniciar servidor:

```bash
python main.py
```

El servidor iniciará en:
- HTTP: http://localhost:8080
- WebSocket: ws://localhost:8080/ws

### Endpoints:

- `GET /` - Health check
- `GET /stats` - Estadísticas de frames procesados
- `WebSocket /ws` - Comunicación con APK

## Configuración del APK

Editar `ServerConfig.kt` con la IP de tu servidor:

```kotlin
const val SERVER_WS_URL = "ws://192.168.1.100:8080/ws"
```

## Arquitectura

```
APK (Android)          Servidor (Python)
     |                        |
     |-- WebSocket --------->|
     |   Frame + estado      |
     |                       |-- IA Procesa
     |<-- Acción ------------|
     |   (SHOOT, MOVE, etc)  |
```

## Notas

- La IA actual usa lógica básica (placeholder)
- Para producción, reemplazar `FreeFireAI` con modelo entrenado (YOLO + RL)
- El servidor puede correr en PC local, VPS cloud, o Google Colab
