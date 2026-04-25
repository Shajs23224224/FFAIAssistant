# FF AI Assistant - APK Offline

App Android 100% offline que usa Accessibility Service para jugar Free Fire automáticamente. Sin conexión a internet, sin cloud, sin servidores externos.

## Arquitectura

```
FF AI Assistant (APK) - 100% Local
├── Accessibility Service (captura de pantalla + toques)
├── NeuralNetwork (TensorFlow Lite - local)
├── FeatureExtractor (OpenCV - local)
└── ActionController (inyección de gestos)
         │
         │ MediaProjection
         ▼
Free Fire APK (sin modificar)
```

**Características:**
- IA completamente local (TensorFlow Lite)
- Sin conexión a internet requerida
- Sin Google Drive / Colab / Cloud
- Funciona 100% offline

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

## GitHub Actions release firmada

El repo ya incluye un workflow de release firmado en `.github/workflows/release.yml`.

Configura estos `Secrets and variables > Actions > Repository secrets`:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Para generar `ANDROID_KEYSTORE_BASE64`:

```bash
base64 -w 0 tu-keystore.jks
```

Luego ejecuta el workflow manualmente desde `Actions > Build Signed Release APK`
o crea un tag tipo `v1.0.0` y haz push. El artifact subido quedará en
`FFAIAssistant-release-apk`.

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
