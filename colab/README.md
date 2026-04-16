# Google Colab Setup

## Quick Start (2 minutos)

### 1. Abrir en Colab
[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/YOUR_USERNAME/FFAIAssistant/blob/main/colab/FFAI_Server.ipynb)

O manualmente:
1. Ve a [colab.research.google.com](https://colab.research.google.com)
2. File → Upload notebook → Selecciona `FFAI_Server.ipynb`

### 2. Ejecutar
```
Runtime → Run all (Ctrl+F9)
```

### 3. Copiar URL
Espera a ver:
```
🌐 URL PÚBLICA: https://abc123-def.ngrok.io
```

### 4. Configurar APK
Edita `app/src/main/java/com/ffai/assistant/network/ServerConfig.kt`:
```kotlin
const val SERVER_WS_URL = "wss://abc123-def.ngrok.io/ws"
```

### 5. Build & Install
```bash
./gradlew assembleDebug
adb install -r app-debug.apk
```

## Token ngrok (Opcional pero recomendado)

Para evitar que la URL cambie:
1. Crea cuenta gratuita: [ngrok.com](https://ngrok.com)
2. Copia tu token
3. Pégalo en la celda 5 del notebook

## Troubleshooting

| Problema | Solución |
|----------|----------|
| "No se pudo conectar" | Verifica la URL en ServerConfig.kt |
| Muy lento | Reduce MAX_FRAME_WIDTH a 160 |
| Colab se desconecta | Ejecuta celda 6 de nuevo |
| ngrok expiró | Reinicia el notebook completo |
