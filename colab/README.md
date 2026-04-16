# 🚀 Free Fire AI - Arquitectura Colab + Drive + Flask + Ngrok

## Arquitectura

```
📱 APK Android (tú)
    ↓ WebSocket
🔗 Ngrok (URL pública)
    ↓
🌐 Flask API (Colab)
    ↓
🧠 Motor IA (GPU Colab)
    ↓
💾 Google Drive (persistencia)
```

## Quick Start (3 minutos)

### 1. Abrir Notebook
[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/Shajs23224224/FFAIAssistant/blob/main/colab/FFAI_Colab_Notebook.ipynb)

O manualmente:
1. [colab.research.google.com](https://colab.research.google.com)
2. File → Upload notebook → `FFAI_Colab_Notebook.ipynb`

### 2. Configurar
```
Runtime → Change runtime type → GPU
Runtime → Run all (Ctrl+F9)
```

### 3. Copiar URL
Espera ver:
```
🌐 URL PÚBLICA: https://abc123-def.ngrok.io
```

### 4. Actualizar APK
Edita `ServerConfig.kt`:
```kotlin
const val SERVER_WS_URL = "wss://abc123-def.ngrok.io/ws"
```

### 5. Build & Deploy
```bash
# En GitHub Actions o local
./gradlew assembleDebug
adb install -r app-debug.apk
```

## Features

| Capa | Función |
|------|---------|
| **Colab** | Motor IA con GPU Tesla T4 |
| **Drive** | Persistencia de modelos/logs |
| **Flask** | API REST + WebSocket |
| **Ngrok** | URL pública temporal |

## Persistencia en Drive

El sistema guarda automáticamente:
```
MyDrive/FFAI/
├── models/ff_model.pt
├── checkpoints/checkpoint_*.pth
├── logs/session_*.log
└── states/current.json
```

## API Endpoints

| Endpoint | Uso |
|----------|-----|
| `/` | Status del sistema |
| `/health` | Health check |
| `/ws` | WebSocket para frames |

## Mensajes WebSocket

**Cliente → Servidor:**
```json
{
  "type": "frame",
  "imageBase64": "...",
  "clientState": {"fps": 15, "ping": 100}
}
```

**Servidor → Cliente:**
```json
{
  "type": "action",
  "action": "SHOOT",
  "coordinates": {"x": 1150, "y": 750},
  "duration": 100,
  "game_state": {"health": 0.85, "ammo": 0.3, "enemy": true}
}
```

## Troubleshooting

| Problema | Solución |
|----------|----------|
| "Disconnected" | Runtime → Run all (reiniciar) |
| Muy lento | Cambiar `MAX_FRAME_WIDTH` a 160 |
| URL expirada | Reejecutar celda de ngrok |
| GPU no detectada | Runtime → Change runtime → GPU |

## Token Ngrok (Recomendado)

Para URL fija:
1. Registro gratuito: [ngrok.com](https://ngrok.com)
2. Copia tu token
3. Descomenta y edita en celda 7:
```python
NGROK_TOKEN = "2Yr..."
ngrok.set_auth_token(NGROK_TOKEN)
```
