# FFAI Assistant - Capacidades de IA

## 📊 Resumen Ejecutivo

| Métrica | Valor |
|---------|-------|
| **Componentes IA** | 23+ módulos |
| **Modelos TFLite** | 21+ modelos neuronales |
| **Latencia objetivo** | ~30-50ms por decisión |
| **Clases detectables** | 6 tipos de objetos |
| **Acciones ejecutables** | 15 acciones de juego |
| **Niveles decisión** | 4 capas (Reflejos → Táctico → Estratégico → Hold) |

---

## 🎯 1. Sistema de Acciones

### Acciones Disponibles (15 tipos)

| Acción | ID | Cooldown | Uso Principal |
|--------|-----|----------|---------------|
| **AIM** | 0 | 0ms | Apuntar a coordenadas (x,y) |
| **SHOOT** | 1 | 200ms | Disparar al enemigo detectado |
| **MOVE_FORWARD** | 2 | 0ms | Avanzar |
| **MOVE_BACKWARD** | 3 | 0ms | Retroceder |
| **MOVE_LEFT** | 4 | 0ms | Mover izquierda |
| **MOVE_RIGHT** | 5 | 0ms | Mover derecha |
| **HEAL** | 6 | 3000ms | Curarse (vida < 25%) |
| **RELOAD** | 7 | 2000ms | Recargar munición |
| **CROUCH** | 8 | 300ms | Agacharse (menos visible) |
| **JUMP** | 9 | 500ms | Saltar (evadir) |
| **LOOT** | 10 | 1000ms | Saquear items cercanos |
| **REVIVE** | 11 | 3000ms | Revivir compañero caído |
| **ROTATE_LEFT** | 12 | 0ms | Rotar cámara izquierda |
| **ROTATE_RIGHT** | 13 | 0ms | Rotar cámara derecha |
| **HOLD** | 14 | 0ms | No hacer nada (fallback) |

---

## 👁️ 2. Sistema de Visión (YOLO Detector)

### Detección de Objetos

| Clase | ID | Color | Descripción | Confianza mínima |
|-------|-----|-------|-------------|------------------|
| **enemy** | 0 | 🔴 Rojo | Jugadores enemigos | 0.25 |
| **loot_weapon** | 1 | 🟢 Verde | Armas en el suelo | 0.25 |
| **loot_heal** | 2 | 🔵 Cian | Botiquines/medkits | 0.25 |
| **loot_ammo** | 3 | 🟡 Amarillo | Munición | 0.25 |
| **vehicle** | 4 | 🟣 Magenta | Vehículos | 0.25 |
| **cover** | 5 | ⚪ Gris | Cobertura/estructuras | 0.25 |

### Especificaciones Técnicas

| Parámetro | Valor |
|-----------|-------|
| Arquitectura | YOLOv8n-FP16 |
| Tamaño modelo | 3.2 MB |
| Input | 640x640 RGB |
| Output | 8400 detecciones máx |
| NMS IoU Threshold | 0.45 |
| Aceleración | GPU Delegate (Mali-G52) |
| Latencia target | 10-15ms (Samsung A21S) |

---

## 🧠 3. Arquitectura de Decisión

### Pipeline de 4 Niveles

```
┌─────────────────────────────────────────┐
│  NIVEL 1: REFLEX ENGINE (<5ms)          │
│  • Vida crítica → HEAL                  │
│  • Sin munición + enemigo → RELOAD      │
│  • Enemigo detectado → AIM+SHOOT        │
└──────────────────┬──────────────────────┘
                   │ (si no hay reflejo)
┌──────────────────▼──────────────────────┐
│  NIVEL 2: FAST TACTICAL (<20ms)         │
│  • Modelo TFLite optimizado             │
│  • Vector estado 32 features            │
│  • 15 clases de acción                  │
└──────────────────┬──────────────────────┘
                   │ (si no hay acción)
┌──────────────────▼──────────────────────┐
│  NIVEL 3: LEGACY TACTICAL (<30ms)       │
│  • Ensemble RL (DQN+PPO+SAC)            │
│  • Votación ponderada 3 agentes         │
│  • Fallback heurístico                  │
└──────────────────┬──────────────────────┘
                   │ (último recurso)
┌──────────────────▼──────────────────────┐
│  NIVEL 4: HOLD                          │
│  • Acción segura por defecto            │
└─────────────────────────────────────────┘
```

### Estados del Juego Analizados

| Estado | Descripción | Rango |
|--------|-------------|-------|
| healthRatio | Vida del jugador | 0.0 - 1.0 |
| ammoRatio | Munición disponible | 0.0 - 1.0 |
| enemyPresent | ¿Hay enemigo visible? | true/false |
| enemyX, enemyY | Posición enemigo normalizada | -1.0 - 1.0 |
| enemyDistance | Distancia al enemigo | 0.0 - 1.0 |
| isInSafeZone | ¿Dentro de zona segura? | true/false |
| safeZoneShrinking | ¿Zona encogiéndose? | true/false |
| isCrouching | ¿Agachado? | true/false |
| hasHealItems | ¿Tiene items de cura? | true/false |
| teammateNeedsRevive | ¿Compañero caído? | true/false |

---

## 🤖 4. Sistema RL Ensemble

### Agentes Coordinados (3)

| Agente | Peso | Algoritmo | Característica |
|--------|------|-----------|----------------|
| **DQN** | 0.35 | Deep Q-Network | Bueno para estados discretos |
| **PPO** | 0.35 | Proximal Policy Optimization | Estable, buen para continuo |
| **SAC** | 0.30 | Soft Actor-Critic | Muestra eficiente, explora bien |

### Lógica de Consenso

| Situación | Decisión |
|-----------|----------|
| 2+ agentes concuerdan | Ejecutar acción mayoritaria |
| Todos discrepan | Usar agente con mayor confianza |
| Confianza < 0.15 | HOLD (acción segura) |
| Consenso + confianza alta | Ejecutar inmediatamente |

---

## 🧬 5. Redes Neuronales Avanzadas (Fases 10-15)

### Componentes SuperAgent

| Componente | Fase | Función | Tamaño |
|------------|------|---------|--------|
| **WorldModel** | 10 | RSSM - Planificación lookahead | +5MB |
| **Dreamer** | 10 | Imaginación de trayectorias | Integrado |
| **Transformer** | 11 | Memoria temporal 64 frames | +8MB |
| **ICM** | 12 | Curiosidad intrínseca | +3MB |
| **Hierarchical RL** | 13 | Meta-controller + 7 sub-policies | +4MB |
| **MAML** | 14 | Adaptación rápida (5 steps) | +2MB |

### Metas Jerárquicas (7 goals)

| Goal | Descripción | Prioridad |
|------|-------------|-----------|
| **PUSH** | Avanzar agresivamente | Alta (vida alta) |
| **DEFEND** | Defender posición | Media |
| **FLANK** | Flanquear enemigo | Alta (enemigo detectado) |
| **LOOT** | Buscar recursos | Media (fase temprana) |
| **RETREAT** | Retirarse | Alta (vida crítica) |
| **ENGAGE** | Enfrentar enemigo | Alta |
| **ROTATE** | Rotar búsqueda | Baja (sin enemigos) |

---

## 🛡️ 6. Sistema de Resiliencia (Fase 2)

### Degradación Graceful

| Componente | Fallos antes de degradar | Acción fallback |
|------------|-------------------------|-----------------|
| YOLO Detector | 5 | Detecciones vacías |
| SuperAgent | 5 | Pipeline legacy |
| Ensemble RL | 5 | Heurístico puro |
| GestureEngine | 5 | Acciones básicas |

### Estados del Sistema

| Estado | Descripción | Acción automática |
|--------|-------------|-------------------|
| **ACTIVE** | Todo operativo | Funcionamiento normal |
| **DEGRADED_MODE** | Algunos componentes fallando | Usar fallbacks |
| **RECOVERING** | Intentando recuperación | Reintento con backoff |
| **FATAL_ERROR** | No puede recuperarse | Notificar usuario |
| **PERMISSIONS_REQUIRED** | Falta autorización | Solicitar permiso |

---

## 📈 7. Métricas de Performance

| Métrica | Target | Máximo aceptable |
|---------|--------|------------------|
| Latencia decisión | 30ms | 100ms |
| Latencia YOLO | 15ms | 30ms |
| Latencia ensemble | 20ms | 50ms |
| FPS procesamiento | 30 | 15 mínimo |
| Consenso RL | 70% | 50% mínimo |
| Tasa éxito acciones | 85% | 70% mínimo |

---

## 🔄 8. Pipeline de Procesamiento

```
Frame de Pantalla
       ↓
[YOLO Detector] → Detecciones [enemy, loot, cover]
       ↓
[Vision Fusion] → Enemigos fusionados (múltiples fuentes)
       ↓
[Situation Analyzer] → Estado de situación (ENGAGE, DEFEND, etc)
       ↓
[Build State Vector] → Vector 256 dimensiones
       ↓
┌─────────────────┐
│ SuperAgent?     │──Sí──→ [WorldModel+Transformer+ICM+Meta] → Decisión
│ (No degradado)  │                ↓
└─────────────────┘      [Hierarchical + MAML]
       │ No                          ↓
       ↓                    Acción final
[Ensemble RL Coordinator]
       ↓
[Gesture Engine] → Ejecución táctil
       ↓
[Learning] → Almacenar experiencia + entrenar
```

---

## ✅ 9. Capacidades Implementadas por Fase

### Fase 1-2: Visión Robusta
- ✅ YOLOv8n detector de objetos
- ✅ Preprocesamiento GPU
- ✅ Fusión de detecciones múltiples
- ✅ Degradación automática

### Fase 3: RL Ensemble
- ✅ DQN + PPO + SAC coordinados
- ✅ Sistema de recompensas adaptativo
- ✅ Memoria episódica

### Fase 4: Gestos Precisos
- ✅ GestureEngine táctil
- ✅ WeaponController
- ✅ MovementController fluido

### Fase 5-6: Telemetría
- ✅ PerformanceMonitor
- ✅ StructuredLogger
- ✅ Latencia adaptativa

### Fase 10: World Models
- ✅ RSSM (Recurrent State-Space Model)
- ✅ Dreamer para planificación

### Fase 11: Transformer
- ✅ Multi-head attention (8 heads)
- ✅ Contexto 64 frames

### Fase 12: Curiosidad
- ✅ ICM (Intrinsic Curiosity Module)
- ✅ Novelty detection

### Fase 13: Hierarchical
- ✅ Meta-controller 7 goals
- ✅ Sub-policies especializadas

### Fase 14: Meta-Learning
- ✅ MAML (Model-Agnostic Meta-Learning)
- ✅ Fast adaptation

---

## 🎮 10. Casos de Uso

| Situación de Juego | Detección | Decisión | Acción |
|--------------------|-----------|----------|--------|
| Enemigo a 10m, vida 100% | YOLO: enemy@0.8 | SuperAgent: ENGAGE | AIM + SHOOT |
| Vida 20%, sin enemigo | - | Reflex: HEAL | HEAL prioritario |
| Zona encogiendo, fuera | UI: danger | Tactical: MOVE_FORWARD | Avanzar a zona |
| Compañero caído | - | Strategic: REVIVE | REVIVE seguro |
| Loot cerca, seguro | YOLO: loot_weapon | Tactical: LOOT | LOOT |
| 3 enemigos cercanos | YOLO: 3×enemy | SuperAgent: RETREAT | Retroceder + cubierta |

---

*Documento generado: Fase 1-2 completadas | Arquitectura estable y resiliente*
