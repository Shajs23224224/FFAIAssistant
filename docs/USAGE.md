# FF AI Assistant - Guía de Uso

## Requisitos

- **Android 12** (API 31) o superior
- **4GB RAM** mínimo
- **Permisos requeridos:**
  - Accesibilidad (BIND_ACCESSIBILITY_SERVICE)
  - Captura de pantalla (MediaProjection)
  - Almacenamiento (para guardar modelo y datos)

## Instalación

### 1. Descargar APK
El archivo APK firmado está en:
```
app/build/outputs/apk/release/FFAIAssistant-v2.apk
```

### 2. Instalar
```bash
adb install -r FFAIAssistant-v2.apk
```

### 3. Configurar Permisos

1. **Abrir la app** - Aparecerá el panel de control
2. **Activar Accesibilidad:**
   - Ir a: Ajustes > Accesibilidad > FF AI Assistant
   - Activar el servicio
3. **Conceder permisos adicionales** cuando la app los solicite

### 4. Primera Ejecución

1. Abrir **Free Fire**
2. La app detectará automáticamente el juego
3. Solicitará permiso de captura de pantalla
4. Una vez concedido, la IA se activará automáticamente

## Panel de Control

El panel flotante muestra:
- **Estado:** Activo/Detenido
- **FPS:** Frames por segundo procesados
- **Acción actual:** Última acción ejecutada
- **Vida/Munición:** Niveles detectados
- **Botón de emergencia:** Detener IA inmediatamente

## Acciones de la IA

La IA puede ejecutar 15 acciones diferentes:

| Acción | Descripción |
|--------|-------------|
| AIM | Apuntar al enemigo detectado |
| SHOOT | Disparar con cooldown de 200ms |
| MOVE_FORWARD | Avanzar |
| MOVE_BACKWARD | Retroceder (cobertura) |
| MOVE_LEFT | Moverse a la izquierda |
| MOVE_RIGHT | Moverse a la derecha |
| HEAL | Usar botiquín |
| RELOAD | Recargar arma |
| CROUCH | Agacharse |
| JUMP | Saltar |
| LOOT | Saquear/Lootear |
| REVIVE | Revivir compañero |
| ROTATE_LEFT | Girar cámara izquierda |
| ROTATE_RIGHT | Girar cámara derecha |
| HOLD | Esperar |

## Aprendizaje Online

La IA **aprende mientras juega**:

- Guarda experiencias en SQLite
- Calcula recompensas por kills, supervivencia, daño
- Ajusta política de decisiones cada 10 pasos
- Guarda modelo mejorado cada 1000 pasos
- Backup automático cada 10 episodios

### Recompensas

| Evento | Recompensa |
|--------|------------|
| Victoria (1er lugar) | +1000 |
| Top 5 | +500 |
| Top 10 | +200 |
| Kill | +100 |
| Hit al enemigo | +10 |
| Segundo sobrevivido | +0.1 |
| Curación efectiva | +5 |
| Recarga oportuna | +2 |
| Daño recibido | -10 |
| Muerte | -500 |

## Calibración

Si los toques no coinciden con los controles:

1. Ir al panel de control
2. Tocar **"Calibrar"**
3. Seguir instrucciones para tocar cada control
4. Guardar configuración

## Solución de Problemas

### "Servicio no detecta Free Fire"
- Verificar que Free Fire esté en primer plano
- Reiniciar servicio de accesibilidad

### "La IA no dispara"
- Verificar que el modelo TFLite esté cargado
- Comprobar que haya enemigos detectados
- Revisar cooldowns en logs

### "FPS muy bajo"
- Reducir FPS objetivo en configuración
- Cerrar otras apps
- Activar modo rendimiento del dispositivo

### "La app se cierra sola"
- Verificar que no esté siendo optimizada por batería
- Agregar a apps "sin restricciones"

## Logs

Los logs se guardan en:
```
Android/data/com.ffai.assistant/logs/
```

Para ver logs en tiempo real:
```bash
adb logcat -s FFAI:D
```

## Seguridad

- La app **no modifica** el APK de Free Fire
- Usa APIs oficiales de Android
- Los toques se inyectan via AccessibilityService
- **Riesgo de ban:** Medio-Alto (usar bajo tu propia responsabilidad)

## Desarrollo

### Estructura del Proyecto

```
FFAIAssistant/
├── app/src/main/java/com/ffai/assistant/
│   ├── core/           # Brain, Experience, LearningEngine
│   ├── perception/     # VisionProcessor, GameState
│   ├── decision/       # NeuralNetwork, PolicyNetwork
│   ├── action/         # GestureController, ActionTypes
│   ├── learning/       # LearningEngine, RewardFunction, Database
│   ├── capture/        # ScreenCapture
│   ├── config/         # Constants, GameConfig
│   └── utils/          # Logger
├── model_training/     # Scripts para crear modelo
└── build_scripts/      # Scripts de compilación
```

### Generar Nuevo Modelo

```bash
cd model_training
pip3 install tensorflow
python3 create_initial_model.py
```

### Compilar APK

```bash
cd build_scripts
./build_apk.sh
```

## Soporte

Para reportar problemas o sugerencias, revisar logs en:
```
adb logcat -s FFAI:D > ffai_logs.txt
```

---

**Versión:** 2.0.0  
**Compatible con:** Android 12+ (API 31)  
**Licencia:** Uso personal
