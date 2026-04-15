# FF AI Assistant

Asistente AI para Free Fire con aprendizaje online.

## Características

- 🤖 **15 acciones** (disparar, curar, moverse, etc.)
- 🧠 **Aprendizaje Online** - Mejora con cada partida
- 📱 **Android 12+** compatible
- 🎯 **60 FPS** captura de pantalla

## Estructura

```
app/src/main/java/com/ffai/assistant/
├── core/           # Brain, Experience
├── perception/     # VisionProcessor
├── decision/       # NeuralNetwork
├── action/         # GestureController
├── learning/       # LearningEngine, Database
├── capture/        # ScreenCapture
└── config/         # Constants
```

## Build

El proyecto se compila automáticamente con GitHub Actions.

### Descargar APK

Ve a **Actions** → **Build APK** → Último workflow → Descargar artifact.

## Instalación

1. Descargar APK del workflow
2. `adb install FFAIAssistant.apk`
3. Activar servicio de accesibilidad
4. Abrir Free Fire

## Desarrollo

```bash
# Crear modelo inicial
cd model_training
python3 create_initial_model.py

# Compilar localmente
./gradlew assembleDebug
```

## Licencia

Uso personal.
