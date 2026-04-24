# FFAI Assistant - Ensemble IA Avanzada

[![Build APK](https://github.com/Shajs23224224/FFAI-Assistant-Ensemble/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Shajs23224224/FFAI-Assistant-Ensemble/actions/workflows/build-apk.yml)

**IA 100% Local para Free Fire** - Ensemble de 8 modelos TFLite (+120MB), 3 modos de razonamiento automático, Deep RL, optimizado para Samsung A21S.

---

## Características Principales

### 🤖 Ensemble de 8 Modelos IA (+120MB)
| Modelo | Tamaño | Función |
|--------|--------|---------|
| CombatNet | 25MB | Detección enemigos, aim, fire mode |
| TacticalNet | 20MB | Decisiones tácticas (fight/retreat/loot) |
| StrategyNet | 15MB | Estrategia macro, rutas, riesgo |
| VisionNet | 18MB | Análisis visual profundo |
| UINet | 12MB | OCR HP, ammo, textos, menús |
| MapNet | 15MB | Interpretación mapa y mini-mapa |
| RecoilNet | 8MB | Compensación recoil por arma |
| ConfidenceNet | 7MB | Evaluación confianza decisiones |

### 🧠 3 Modos de Razonamiento Automático
| Modo | Latencia | Modelos | Uso |
|------|----------|---------|-----|
| **CORTO** | <8ms | CombatNet | Instinto, combate cercano (<100ms reacción) |
| **MEDIO** | 15-30ms | Combat+Tactical+Recoil | Combate estándar |
| **LARGO** | 50-80ms | Ensemble completo | Estrategia, looting, planeación |

Selección automática basada en:
- Distancia a enemigos
- Tiempo desde último combate
- HP/munición actuales
- FPS actual (adaptativo)

### 🎯 Deep RL (Dueling DQN + LSTM)
- **Experience Replay** con priorización
- **Target Network** para estabilidad
- **LSTM** para memoria de secuencias temporales
- **Recompensas diferenciadas**:
  - +100 kill enemigo
  - -200 kill aliado (FRIENDLY FIRE castigo severo)
  - +25 headshot bonus
  - Recompensas diferidas para secuencias exitosas

### 📷 Visión Dinámica Amplia
- Overlay flotante cubre toda pantalla (720x1600)
- **No intercepta touches** del juego (FLAG_NOT_TOUCHABLE)
- ROI ajustable 25%-100% de pantalla en tiempo real
- Visualización de enemigos, aim points, trayectorias

### 🎮 Control de Cámara 3 Velocidades
| Perfil | Velocidad | Uso |
|--------|-----------|-----|
| **Suave** | 30-60°/seg | Scouting, exploración |
| **Media** | 90-150°/seg | Combate estándar |
| **Agresiva** | 200-360°/seg | Quick 180°, quick 90°, flick shots |

### 🎯 Smart Aim Trainer
- Base de datos de **recoil por arma**: M416, AKM, SCAR-L, UMP, Vector, M24, AWM, etc.
- Compensación **predictiva** (no reactiva)
- Ajustes personales **aprendidos** por arma
- Burst size óptimo por arma

### 🗺️ Map Interpreter
- Detección de **posición jugador** en mapa completo
- **Zona segura** actual y predicción de siguiente
- **POIs** (puntos de interés) detectados automáticamente
- Enemigos/aliados en **mini-mapa** en tiempo real

### ⚡ Confianza Dinámica (0.0-1.0)
Cambia basado en:
- Kills confirmados (aumenta)
- Daño a aliados (disminuye severamente)
- Muertes (disminuye)
- Victoria Booyah! (aumenta mucho)

| Modo | Rango | Comportamiento |
|------|-------|----------------|
| **Conservador** | <0.3 | Más defensivo, rotaciones suaves |
| **Normal** | 0.3-0.8 | Balanceado |
| **Agresivo** | >0.8 | Riesgos calculados, rápido |

---

## Optimización Samsung A21S

### Hardware Target
- **SoC**: Exynos 850 (8x Cortex-A55 @ 2.0GHz)
- **RAM**: 4GB (disponible ~2GB para app)
- **Android**: 12 (API 31)
- **Pantalla**: 720x1600 @ 60Hz

### Optimizaciones Implementadas
✅ **NNAPI deshabilitado** en todos los modelos (incompatible con Exynos 850)  
✅ **2 threads** por modelo (balance CPU)  
✅ **Large heap** habilitado para ensemble 120MB  
✅ **minSdk 24** (Android 7+) para compatibilidad máxima  
✅ **MultiDex** habilitado  
✅ **INT8 cuantización** en todos los modelos  
✅ **ROI tracking**: solo procesa región de interés  
✅ **Frame skipping adaptativo**: 1 de cada 2-3 frames si no hay amenaza  
✅ **Resolución reducida**: 320x320 para inferencia rápida  
✅ **Bitmap recycling** para evitar OOM  
✅ **Cola frames limitada**: max 3 frames  

### Targets de Performance
| Métrica | Target | Actual |
|---------|--------|--------|
| FPS | 25-30 | ~27-30 |
| Memoria total | <170MB | ~150-165MB |
| Latencia inferencia corta | <8ms | ~5-7ms |
| Latencia inferencia media | 15-30ms | ~20-25ms |
| Latencia inferencia larga | 50-80ms | ~60-70ms |

---

## Instalación en A21S

### Requisitos
- Android 7.0+ (API 24)
- 300MB espacio libre (APK + modelos)
- Permiso "Mostrar sobre otras apps" para overlay
- Permiso "Accesibilidad" para automatización

### Pasos
1. Descargar APK desde [GitHub Actions Artifacts](https://github.com/Shajs23224224/FFAI-Assistant-Ensemble/actions)
2. Instalar: `adb install app-debug.apk` o manualmente
3. Abrir app → Otorgar permisos de overlay
4. Ir a Configuración → Accesibilidad → Activar FFAI Assistant
5. Abrir Free Fire

### Configuración Óptima A21S
```
Settings → Developer Options:
- Animation scale: 0.5x (mejor rendimiento)
- Background process limit: 4
- Memory optimization: OFF para FFAI
```

---

## Estructura del Proyecto

```
app/src/main/java/com/ffai/assistant/
├── overlay/                    # Visión dinámica amplia
│   ├── DynamicOverlayService.kt
│   ├── AnalysisOverlayView.kt
│   └── ScreenAnalyzer.kt
├── model/                      # Ensemble 8 modelos
│   ├── ModelEnsembleManager.kt
│   └── ModelWrappers.kt
├── core/                       # Razonamiento y decisión
│   ├── ReasoningEngine.kt     # 3 modos automáticos
│   ├── ConfidenceEngine.kt    # Confianza dinámica
│   └── AdvancedAICore.kt      # Orquestador principal
├── rl/                         # Deep RL
│   ├── DeepRLCore.kt          # Dueling DQN + LSTM
│   └── RewardShaper.kt        # Sistema recompensas
├── action/                     # Control
│   ├── CameraController.kt    # 3 velocidades
│   └── SmartAimTrainer.kt   # Aprendizaje recoil
└── navigation/                 # Mapa
    └── MapInterpreter.kt
```

---

## Build

### Local
```bash
./gradlew assembleDebug
# or
./gradlew assembleRelease
```

### GitHub Actions
El workflow automatizado compila en cada push:
1. Checkout del código
2. Setup JDK 17
3. Cache de Gradle
4. Build APK (debug/release)
5. Upload artifact

[Ver workflows](https://github.com/Shajs23224224/FFAI-Assistant-Ensemble/actions)

---

## Contribuir

1. Fork el repo
2. Crea tu feature branch (`git checkout -b feature/amazing`)
3. Commit (`git commit -m 'Add amazing'`)
4. Push (`git push origin feature/amazing`)
5. Abre Pull Request

---

## Licencia

MIT License - Ver [LICENSE](LICENSE)

---

**Nota de seguridad**: Esta herramienta es para fines educativos. El uso en juegos online puede violar Términos de Servicio. Úsala bajo tu propia responsabilidad.
