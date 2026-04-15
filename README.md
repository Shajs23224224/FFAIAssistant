# FF AI Assistant - APK Separado

App Android que usa Accessibility Service para jugar Free Fire automáticamente.

## Arquitectura

```
FF AI Assistant (APK)
├── Accessibility Service (captura de pantalla + toques)
├── NeuralNetwork (TensorFlow Lite)
├── FeatureExtractor (OpenCV)
└── ActionController (inyección de gestos)
         │
         │ MediaProjection
         ▼
Free Fire APK (sin modificar)
```

## Requisitos

- Android 8.0+ (API 26+)
- Permiso de Accesibilidad
- Permiso de Captura de Pantalla
- 2GB RAM mínimo

## Compilación

```bash
cd FFAIAssistant

# Convertir modelo (si tienes .npz)
python3 convert_model.py --input ../checkpoints/final_best.npz

# Compilar APK
./gradlew assembleRelease

# Instalar
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

## Uso

1. Instalar APK
2. Abrir app y activar servicio de accesibilidad
3. Conceder permiso de captura de pantalla
4. Abrir Free Fire
5. La IA se activa automáticamente

## Permisos

- `BIND_ACCESSIBILITY_SERVICE`: Inyectar toques
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Captura de pantalla
- `SYSTEM_ALERT_WINDOW`: Overlay de debug (opcional)

## Estructura

```
app/src/main/java/com/ffai/assistant/
├── MainActivity.kt              # UI principal
├── FFAccessibilityService.kt    # Servicio core
├── ai/
│   ├── NeuralNetwork.kt         # Inferencia TFLite
│   ├── FeatureExtractor.kt      # Análisis de imagen
│   └── GameState.kt             # Estado del juego
├── control/
│   └── ActionController.kt      # Ejecución de toques
├── capture/
│   └── ScreenCapture.kt         # MediaProjection
└── utils/
    ├── Config.kt                # Constantes
    └── Logger.kt                # Logging
```

## Notas

- La app no modifica el APK de Free Fire
- Usa APIs de Android para interactuar con el juego
- Puede ser detectada por sistemas anti-cheat
- Uso bajo tu propia responsabilidad
