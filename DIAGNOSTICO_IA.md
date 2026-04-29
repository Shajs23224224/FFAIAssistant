# Diagnóstico - IA No Hace Nada

## 🔍 Análisis del Problema

Basado en la revisión del código, identifico **6 posibles causas**:

---

## Causa 1: Umbral de Confianza Muy Alto (Más Probable)

**Archivo:** `EnsembleRLCoordinator.kt` línea 33

```kotlin
const val MIN_CONFIDENCE = 0.4f  // 40%
```

**Problema:** Si los modelos TFLite no están entrenados (son placeholders), la confianza siempre será < 0.4, resultando en:

```kotlin
// Línea 133-143
} else {
    // Confianza muy baja, usar acción segura
    EnsembleDecision(
        action = ActionType.HOLD,  // ← NO HACE NADA
        confidence = 0f,
        ...
    )
}
```

**Solución:** Reducir umbral temporalmente:

```kotlin
// Cambiar en EnsembleRLCoordinator.kt línea 33
const val MIN_CONFIDENCE = 0.15f  // 15% para pruebas
```

---

## Causa 2: Modelos TFLite Son Placeholders (Vacíos)

**Verificación:** Los modelos `.tflite` en `assets/` tienen tamaño, pero pueden ser:
- Archivos dummy/placeholders
- Modelos no entrenados (pesos aleatorios)
- Formatos incorrectos

**Logs a buscar:**
```
"ModelManager: Modelo encontrado en assets: XXXX"  ← Debe aparecer 18 veces
"YOLODetector: YOLODetector inicializado correctamente"
"DQNAgent: DQNAgent inicializado"
```

**Diagnóstico:**
```bash
# Verificar si los modelos son válidos (desde PC con adb)
adb shell run-as com.ffai.assistant ls -la /assets/
```

**Solución:** Si los modelos son placeholders, entrenarlos primero o usar valores dummy más permisivos.

---

## Causa 3: YOLO No Detecta Enemigos

**Flujo:**
1. YOLO detecta objetos → `detections`
2. Si `detections` está vacío → `fusedEnemies` está vacío
3. `buildStateVector()` crea estado "vacío"
4. RL agents no saben qué hacer → `HOLD`

**Verificación en `processFrameEnhanced()`:**
```kotlin
val detections = yoloDetector.detect(bitmap)  // ¿Vacío?
val fusedEnemies = visionFusionEngine.fuseEnemyDetections(...)  // ¿Vacío?
```

**Logs a buscar:**
```
"YOLODetector: FPS: X, Latencia: Yms"  ← Si no aparece, YOLO no corre
```

**Solución:** Verificar que YOLO está cargando el modelo correctamente.

---

## Causa 4: Servicio de Accesibilidad No Ejecuta Gestos

**Problema:** Aunque la IA decida una acción, el gesto puede fallar silenciosamente.

**Verificación en `GestureEngine.kt` línea 331-342:**
```kotlin
val result = accessibilityService.dispatchGesture(...)
// ¿Result es false?
```

**Logs a buscar:**
```
"GestureEngine: Gesture completed: TAP"
"GestureEngine: Gesture cancelled: TAP"  ← Si aparece, hay problema
"GestureEngine: Error executing gesture"  ← Error crítico
```

**Causas comunes:**
- No tiene permiso `canPerformGestures`
- Servicio de accesibilidad no está habilitado en Configuración
- Pantalla bloqueada o app en background

---

## Causa 5: No Hay Frames de Captura

**Flujo de captura:**
1. `ScreenCaptureService` debe estar corriendo
2. Debe enviar broadcast `CAPTURE_STARTED`
3. `FFAccessibilityService` recibe y llama `startGameLoop()`
4. `onFrameAvailable(bitmap)` debe llamarse cada ~100ms

**Logs a buscar:**
```
"ScreenCaptureService: Captura iniciada"
"FFAccessibilityService: Captura iniciada"
"FFAccessibilityService: Free Fire detectado"
```

**Si NO aparecen estos logs:**
- El usuario no presionó "Iniciar Servicio"
- No concedió permiso MediaProjection
- ScreenCaptureService crasheó

---

## Causa 6: AdvancedAICore No Se Inicializa

**Verificación:** En `FFAccessibilityService.kt` línea 259:

```kotlin
advancedAICore = AdvancedAICore(...)
advancedAICore?.initialize()  // ¿Está completando sin errores?
```

**Si hay excepción en `initialize()`:**
- `advancedAICore` queda en null o incompleto
- Nunca se llama `start()`
- Nunca se procesan frames

**Logs a buscar:**
```
"AdvancedAICore: AdvancedAICore inicializado correctamente"  ← Debe aparecer
"IA Avanzada Ensemble inicializada (8 modelos, +120MB)"  ← Debe aparecer
"Error inicializando IA Avanzada"  ← Si aparece, hay error
```

---

## 🛠️ Plan de Acción para Diagnosticar

### Paso 1: Verificar Logs con ADB

```bash
# Conectar dispositivo y ver logs en tiempo real
adb logcat -c  # Limpiar logs
adb logcat -s ModelManager YOLODetector EnsembleRLCoordinator AdvancedAICore FFAccessibilityService GestureEngine *:E

# Abrir app y ver qué aparece
```

**Buscar específicamente:**
1. ¿Aparece `"Modelo encontrado en assets"` 18 veces?
2. ¿Aparece `"YOLODetector inicializado correctamente"`?
3. ¿Aparece `"EnsembleRLCoordinator: DQN: true, PPO: true, SAC: true"`?
4. ¿Aparece `"IA Avanzada Ensemble inicializada"`?
5. ¿Aparece `"Captura iniciada"`?
6. ¿Aparece `"Free Fire detectado"`?
7. ¿Aparece algún error (E/) relacionado?

### Paso 2: Si los Modelos Fallan

Si ves `"Error inicializando DQN"` o similar:

```bash
# Extraer modelo del APK para verificar
adb shell cp /data/app/.../base.apk /sdcard/ffai.apk
adb pull /sdcard/ffai.apk

# Descomprimir y verificar assets
unzip ffai.apk -d ffai_extracted
ls -la ffai_extracted/assets/*.tflite
```

### Paso 3: Si YOLO No Detecta

Añadir logs de debug en `YOLODetector.kt`:

```kotlin
fun detect(bitmap: Bitmap): List<Detection> {
    ...
    val detections = postprocess(bitmap.width, bitmap.height)
    
    // DEBUG: Log de detecciones
    Logger.i(TAG, "YOLO detections: ${detections.size} objetos")
    detections.forEachIndexed { i, det ->
        Logger.d(TAG, "  [$i] class=${det.classId} conf=${det.confidence} box=${det.boundingBox}")
    }
    
    return detections
}
```

### Paso 4: Si Los Gestos No Funcionan

Añadir logs en `GestureEngine.kt`:

```kotlin
private fun executeGesture(gesture: Gesture): Boolean {
    Logger.i(TAG, "Executing gesture: ${gesture.type} at (${gesture.x}, ${gesture.y})")
    ...
    val result = accessibilityService.dispatchGesture(...)
    Logger.i(TAG, "Gesture result: $result")
    return result
}
```

---

## 🔧 Soluciones Inmediatas

### Opción A: Reducir Umbral de Confianza (Prueba Rápida)

Editar `EnsembleRLCoordinator.kt`:

```kotlin
companion object {
    const val TAG = "EnsembleRLCoordinator"
    
    // Pesos de confianza iniciales
    const val WEIGHT_DQN = 0.35f
    const val WEIGHT_PPO = 0.35f
    const val WEIGHT_SAC = 0.30f
    
    // Umbrales - REDUCIDOS PARA PRUEBAS
    const val CONSENSUS_THRESHOLD = 0.5f  // Antes: 0.6f
    const val MIN_CONFIDENCE = 0.15f      // Antes: 0.4f  ← CAMBIO CRÍTICO
}
```

### Opción B: Forzar Acción Siempre (Debug Mode)

En `executeEnhancedAction()`, forzar que siempre haga algo:

```kotlin
private fun executeEnhancedAction(...) {
    Logger.i(TAG, "executeEnhancedAction: ${decision.action}, enemies=${enemies.size}")
    
    when (decision.action) {
        ActionType.AIM -> { ... }
        ActionType.SHOOT -> { ... }
        ActionType.HOLD -> {
            // DEBUG: En lugar de HOLD, hacer un TAP de prueba
            Logger.w(TAG, "HOLD decidido, pero haciendo TAP de debug")
            gestureEngine.tap(500f, 500f)  // Tap en centro de pantalla
        }
        ...
    }
}
```

### Opción C: Verificar Inicialización Paso a Paso

Añadir verificación explícita en cada componente:

```kotlin
// En AdvancedAICore.initialize()
Logger.i(TAG, "=== VERIFICACIÓN DE INICIALIZACIÓN ===")

val yoloOk = yoloDetector.initialize()
Logger.i(TAG, "YOLO: $yoloOk")

val rlOk = ensembleRL.initialize()
Logger.i(TAG, "EnsembleRL: $rlOk")

val dqnOk = ensembleRL.dqnAgent.isInitialized  // Añadir getter si es privado
val ppoOk = ensembleRL.ppoAgent.isInitialized
val sacOk = ensembleRL.sacAgent.isInitialized
Logger.i(TAG, "Agentes: DQN=$dqnOk PPO=$ppoOk SAC=$sacOk")
```

---

## 📋 Checklist de Verificación

- [ ] ¿18 modelos TFLite están en assets y tienen tamaño > 1KB?
- [ ] ¿ModelManager loguea "Modelo encontrado" para todos?
- [ ] ¿YOLODetector se inicializa sin errores?
- [ ] ¿DQN, PPO, SAC agents se inicializan (logs dicen "true")?
- [ ] ¿EnsembleRLCoordinator tiene MIN_CONFIDENCE = 0.15f?
- [ ] ¿ScreenCaptureService se inicia y loguea "Captura iniciada"?
- [ ] ¿Free Fire se detecta (log "Free Fire detectado")?
- [ ] ¿onFrameCaptured se llama (log cada 100ms)?
- [ ] ¿YOLO detecta enemigos (log "detections: X objetos")?
- [ ] ¿executeEnhancedAction/executeSuperAction se llaman?
- [ ] ¿GestureEngine.dispatchGesture devuelve true?
- [ ] ¿Aparece "Gesture completed" en logs?

---

## 🎯 Conclusión

Las causas más probables son:

1. **MIN_CONFIDENCE = 0.4f es demasiado alto** para modelos no entrenados
2. **Los modelos son placeholders** y devuelven confianza baja aleatoria
3. **YOLO no detecta enemigos** (modelo vacío o pantalla incorrecta)

**Recomendación inmediata:**
1. Cambiar `MIN_CONFIDENCE` a `0.15f`
2. Añadir logs de debug en cada paso
3. Verificar que los modelos no son placeholders

---

*Generado: 2024-04-29*
*Build: 1fb7a83*
