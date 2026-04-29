# Guía de Debug - IA No Funciona

## Estado Actual (Post-Correcciones)

### ✅ Problemas Corregidos

1. **Nombres de modelos incorrectos**
   - `deeprl_dueling_dqn.tflite` → `dqn_dueling.tflite` ✓
   - `dreamer_actor.tflite` → `world_model_transition.tflite` ✓
   - `dreamer_critic.tflite` → `world_model_reward.tflite` ✓

2. **ModelManager ahora usa assets**
   - ANTES: Buscaba en `getExternalFilesDir(null)/models/`
   - AHORA: Verifica directamente en `context.assets`

3. **Faltaba `icm_feature.tflite` en lista**
   - Añadido a `requiredModels` en ModelManager

4. **KeepAliveService no se iniciaba**
   - Añadido inicio en `MainActivity.onCreate()`
   - Añadido `BootReceiver` para auto-inicio

### ⚠️ Problemas Potenciales Restantes

#### 1. SuperAgentCoordinator no verifica fallos individuales
```kotlin
// PROBLEMA: Si un componente falla, el código continúa
worldModel = WorldModel(context).apply { initialize() }  // No verifica resultado
```

**Impacto:** Si WorldModel falla, DreamerAgent puede crashear al usar `worldModel.encodeObservation()`

#### 2. Captura de pantalla requiere permiso MediaProjection
El usuario debe:
1. Abrir la app
2. Click "Iniciar Servicio"
3. Aceptar diálogo "Permitir captura de pantalla"
4. Luego activar Accessibility Service

#### 3. Accessibility Service debe habilitarse manualmente
**NO se puede iniciar automáticamente por seguridad de Android.**

Pasos del usuario:
```
Configuración > Accesibilidad > Servicios instalados > FFAI Assistant > Habilitar
```

#### 4. Los modelos TFLite pueden fallar en dispositivos sin GPU
Si `GpuDelegate()` falla, usa CPU (correcto), pero:
- YOLOv8n puede ser lento en CPU-only
- 8 modelos RL simultáneos pueden saturar memoria

### 🔍 Cómo Verificar si la IA Carga

#### Logs a buscar en LogCat:
```
# Éxito - Modelos cargando
"ModelManager: Modelo encontrado en assets: yolov8n_fp16.tflite"
"YOLODetector: YOLODetector inicializado correctamente"
"DQNAgent: DQNAgent inicializado"
"PPOAgent: PPOAgent inicializado"
"SACAgent: SACAgent inicializado"
"EnsembleRLCoordinator: DQN: true, PPO: true, SAC: true"
"AdvancedAICore: IA Avanzada Ensemble inicializada"

# Error - Modelo no encontrado
"ModelManager: Modelo NO encontrado en assets: XXXX"
"DQNAgent: Error inicializando DQN"

# Error - Permisos
"FFAccessibilityService: Servicio conectado"  ← Debe aparecer
"CaptureManager: Error iniciando captura"
```

### 🧪 Test Paso a Paso

1. **Instalar APK**
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

2. **Verificar assets incluidos**
   ```bash
   adb shell run-as com.ffai.assistant ls -la assets/
   ```

3. **Iniciar app y ver logs**
   ```bash
   adb logcat -s ModelManager YOLODetector DQNAgent PPOAgent SACAgent EnsembleRLCoordinator AdvancedAICore FFAccessibilityService
   ```

4. **Verificar modelo YOLO carga**
   - Buscar: `"YOLODetector: YOLODetector inicializado correctamente"`

5. **Verificar RL agents**
   - Buscar: `"EnsembleRLCoordinator: DQN: true, PPO: true, SAC: true"`

6. **Activar Accessibility Service**
   - Ir a Configuración > Accesibilidad > FFAI Assistant
   - Habilitar
   - Ver log: `"FFAccessibilityService: Servicio conectado"`

7. **Iniciar captura**
   - En app, click "Iniciar Servicio"
   - Aceptar diálogo MediaProjection
   - Ver log: `"ScreenCaptureService: Captura iniciada"`

8. **Abrir Free Fire**
   - Ver log: `"FFAccessibilityService: Free Fire detectado"`
   - Ver log: `"AdvancedAICore: Procesando frame..."`

### 🔧 Soluciones Adicionales Implementadas

#### SuperAgentCoordinator más robusto:
```kotlin
// Cambiar de:
worldModel = WorldModel(context).apply { initialize() }

// A:
val wm = WorldModel(context)
if (!wm.initialize()) {
    Logger.w(TAG, "WorldModel failed, disabling WM features")
    USE_WM = false
}
```

#### Manejo de memoria para modelos grandes:
- YOLOv8n: 6.5MB (ok)
- Ensemble RL: ~300KB cada uno (ok)
- Transformer: 190KB (ok)
- SuperAgent: ~500KB total (ok)

**Total memoria estimada:** ~8MB para todos los modelos en memoria

### 🚨 Problemas Conocidos de Android

1. **Android 12+ (API 31+)** requiere:
   - `FOREGROUND_SERVICE_MEDIA_PROJECTION` declarado
   - Notificación persistente para ScreenCaptureService
   - Permiso `POST_NOTIFICATIONS` en Android 13+

2. **Fabricantes (Samsung, Xiaomi, etc.)**
   - Pueden matar servicios en segundo plano
   - Solución: KeepAliveService + BootReceiver + optimización batería desactivada

3. **GPU Delegates**
   - Algunos dispositivos no soportan GPU TFLite
   - El código ya maneja fallback a CPU

### 📋 Checklist para Usuario

- [ ] APK instalado correctamente
- [ ] 19 archivos `.tflite` en `/assets/` (verificar con `adb`)
- [ ] Accessibility Service habilitado en Configuración
- [ ] Permiso "Dibujar sobre otras apps" concedido
- [ ] Permiso "Ignorar optimización batería" concedido
- [ ] App iniciada y KeepAliveService corriendo (notificación persistente)
- [ ] Botón "Iniciar Servicio" presionado
- [ ] Diálogo MediaProjection aceptado
- [ ] Free Fire abierto
- [ ] Logs muestran "Procesando frame" cada ~100ms

### 📞 Si Aún No Funciona

1. **Capturar logs completos:**
   ```bash
   adb logcat -d > ffai_logs.txt
   ```

2. **Verificar errores específicos:**
   ```bash
   adb logcat -s *:E | grep ffai
   ```

3. **Verificar carga de modelos:**
   ```bash
   adb shell run-as com.ffai.assistant cat /data/data/com.ffai.assistant/files/logs/model_status.log
   ```

### 🔄 Flujo Correcto de Inicialización

```
Usuario abre app
    ↓
MainActivity.onCreate()
    ↓
startKeepAliveService() → Notificación persistente
    ↓
Usuario habilita Accessibility Service (manual)
    ↓
FFAccessibilityService.onServiceConnected()
    ↓
initHybridArchitecture() [async]
    ↓
initHybridArchitecture() → AdvancedAICore.initialize()
    ↓
[FASE 0] ModelManager.initialize() → Verifica 19 modelos en assets
    ↓
[FASE 1-2] YOLODetector.initialize() → Carga yolov8n_fp16.tflite
    ↓
[FASE 3] EnsembleRL.initialize() → Carga DQN + PPO + SAC
    ↓
[FASE 4] GestureEngine.initialize()
    ↓
[FASE 5-6] PerformanceMonitor + StructuredLogger
    ↓
[FASE 10-15] SuperAgentCoordinator.initialize() → WorldModel + Transformer + ICM + HRL + MAML
    ↓
"AdvancedAICore: IA Avanzada Ensemble inicializada"
    ↓
Usuario click "Iniciar Servicio"
    ↓
ScreenCaptureService.startForeground() + MediaProjection
    ↓
Broadcast: CAPTURE_STARTED
    ↓
FFAccessibilityService recibe broadcast → startGameLoop()
    ↓
Usuario abre Free Fire
    ↓
onFrameAvailable() → processFrame() cada 100ms
    ↓
YOLODetector.detect() → EnsembleRL.selectAction() → GestureController.execute()
```

---

**Última actualización:** Build `0d49f4c` con todos los modelos corregidos.
