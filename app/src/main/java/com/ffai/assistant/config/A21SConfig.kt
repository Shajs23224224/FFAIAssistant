package com.ffai.assistant.config

import android.util.Size

/**
 * Configuración específica para Samsung Galaxy A21S
 * Optimizado para Exynos 850, 4GB RAM, Android 12
 */
object A21SConfig {
    
    // ============================================
    // DISPOSITIVO
    // ============================================
    
    const val DEVICE_NAME = "Samsung Galaxy A21S"
    const val CHIPSET = "Exynos 850"
    const val RAM_GB = 4
    const val ANDROID_VERSION = 12
    
    // ============================================
    // PERFORMANCE TARGETS
    // ============================================
    
    // FPS objetivo (realista para Exynos 850)
    const val TARGET_FPS = 30
    const val FRAME_TIME_MS = 33L  // ~30fps
    
    // Latencias objetivo
    const val REFLEX_MAX_MS = 3L
    const val TACTICAL_MAX_MS = 12L
    const val STRATEGIC_INTERVAL_MS = 500L
    
    // ============================================
    // MEMORIA
    // ============================================
    
    // Presupuesto de RAM para IA (de ~1.5GB disponibles)
    const val AI_MEMORY_BUDGET_MB = 800
    
    // Límites de memoria jerárquica
    const val MAX_ULTRASHORT_ACTIONS = 30
    const val MAX_SHORTTERM_EVENTS = 100
    const val MAX_MEDIUM_ENEMIES = 50
    const val MAX_LONGTERM_PATTERNS = 200
    
    // ============================================
    // MODELOS IA
    // ============================================
    
    // Configuración de inferencia
    const val INFERENCE_THREADS = 4  // De 8 núcleos disponibles
    const val USE_GPU_DELEGATE = false  // Mali-G52 MP1 no rentable
    const val USE_XNNPACK = true        // CPU optimizado
    const val MODEL_QUANTIZATION = "INT8"
    
    // Tamaños de modelos (cuantizados)
    val MODEL_SIZES_MB = mapOf(
        "combat_net_lite" to 15,
        "vision_net_lite" to 20,
        "tactical_net" to 25,
        "recoil_predictor" to 8,
        "zone_predictor" to 12,
        "aim_net" to 18,
        "enemy_behavior" to 15,
        "movement_net" to 12
    )
    
    val TOTAL_MODEL_SIZE_MB = MODEL_SIZES_MB.values.sum()
    
    // ============================================
    // PANTALLA Y ANÁLISIS
    // ============================================
    
    // Resolución nativa A21S
    val NATIVE_RESOLUTION = Size(720, 1600)
    
    // Resolución de análisis (1/2 para rendimiento)
    val ANALYSIS_RESOLUTION = Size(360, 800)
    
    // ROI (Región de Interés) para detección
    const val ROI_SIZE = 120
    const val ROI_OVERLAP = 0.3f
    
    // ============================================
    // TÉRMICO / BATERÍA
    // ============================================
    
    // Umbrales de temperatura
    const val TEMP_OPTIMAL_C = 45f
    const val TEMP_WARNING_C = 60f
    const val TEMP_CRITICAL_C = 65f
    const val TEMP_THROTTLE_C = 70f
    
    // Estrategias de throttling
    val THERMAL_STRATEGIES = listOf(
        ThermalStrategy(70f, fpsReduction = 0.5f, qualityReduction = 0.7f, disableNonCritical = true),
        ThermalStrategy(65f, fpsReduction = 0.3f, qualityReduction = 0.8f, disableNonCritical = false),
        ThermalStrategy(60f, fpsReduction = 0.0f, qualityReduction = 0.9f, disableNonCritical = false)
    )
    
    // Batería
    const val BATTERY_OPTIMAL_THRESHOLD = 30
    const val BATTERY_LOW_THRESHOLD = 15
    const val BATTERY_CRITICAL_THRESHOLD = 10
    
    // ============================================
    // GESTOS Y CÁMARA
    // ============================================
    
    // Configuración táctil
    const val TOUCHSCREEN_DEADZONE_PX = 10
    const val DEFAULT_GESTURE_DURATION_MS = 100L
    const val MIN_GESTURE_DURATION_MS = 16L
    const val MAX_GESTURE_DURATION_MS = 2000L
    
    // Perfiles de cámara (ajustados para A21S)
    val CAMERA_PROFILES = mapOf(
        "SMOOTH" to CameraProfileSettings(
            speedMin = 15f,  // deg/s
            speedMax = 45f,
            easing = "easeInOutCubic",
            microJitter = 2f
        ),
        "MEDIUM" to CameraProfileSettings(
            speedMin = 30f,
            speedMax = 90f,
            easing = "easeOutQuad",
            microJitter = 3f
        ),
        "AGGRESSIVE" to CameraProfileSettings(
            speedMin = 60f,
            speedMax = 180f,
            easing = "linear",
            microJitter = 5f
        )
    )
    
    // Sensibilidad de cámara (más baja para precisión)
    const val DEFAULT_CAMERA_SENSITIVITY = 0.7f
    const val MAX_CAMERA_DELTA_PER_FRAME = 0.3f
    
    // ============================================
    // APRENDIZAJE
    // ============================================
    
    // Deep RL local
    const val DQN_LEARNING_RATE = 0.00025f
    const val DQN_GAMMA = 0.99f
    const val DQN_EPSILON_START = 1.0f
    const val DQN_EPSILON_MIN = 0.05f
    const val DQN_EPSILON_DECAY = 0.995f
    const val DQN_BATCH_SIZE = 16  // Reducido para A21S
    const val DQN_MEMORY_SIZE = 5000  // Buffer más pequeño
    
    // ============================================
    // MÉTODOS DE AYUDA
    // ============================================
    
    /**
     * Verifica si la configuración es viable para el dispositivo
     */
    fun validateConfiguration(): ValidationResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Verificar RAM necesaria
        val estimatedRamNeeded = estimateRamUsage()
        if (estimatedRamNeeded > AI_MEMORY_BUDGET_MB) {
            issues.add("RAM estimada ($estimatedRamNeeded MB) excede presupuesto ($AI_MEMORY_BUDGET_MB MB)")
        }
        
        // Verificar tamaño de modelos
        if (TOTAL_MODEL_SIZE_MB > 150) {
            warnings.add("Tamaño total de modelos ($TOTAL_MODEL_SIZE_MB MB) es alto para A21S")
        }
        
        return ValidationResult(issues.isEmpty(), issues, warnings)
    }
    
    private fun estimateRamUsage(): Int {
        // Estimación: modelos cargados + buffers + memoria jerárquica
        val modelRam = (TOTAL_MODEL_SIZE_MB * 1.5).toInt()  // x1.5 para overhead
        val bufferRam = 100  // MB para buffers de frames
        val memorySystemRam = 50  // MB para sistema de memoria
        
        return modelRam + bufferRam + memorySystemRam
    }
    
    /**
     * Obtiene estrategia térmica según temperatura actual
     */
    fun getThermalStrategy(currentTemp: Float): ThermalStrategy {
        return THERMAL_STRATEGIES.find { currentTemp >= it.tempThreshold }
            ?: ThermalStrategy(0f, 0f, 1.0f, false)
    }
    
    /**
     * Escala resolución de análisis según carga
     */
    fun getAdaptiveResolution(currentFps: Int): Size {
        return when {
            currentFps < 20 -> Size(240, 533)  // 1/3 resolución
            currentFps < 25 -> Size(300, 667)  // 5/12 resolución
            else -> ANALYSIS_RESOLUTION
        }
    }
}

// ============================================
// DATA CLASSES DE CONFIGURACIÓN
// ============================================

data class ThermalStrategy(
    val tempThreshold: Float,
    val fpsReduction: Float,        // 0.0 - 1.0
    val qualityReduction: Float,   // 0.0 - 1.0
    val disableNonCritical: Boolean
)

data class CameraProfileSettings(
    val speedMin: Float,      // grados/segundo
    val speedMax: Float,
    val easing: String,
    val microJitter: Float     // píxeles de variación
)

data class ValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val warnings: List<String>
)
