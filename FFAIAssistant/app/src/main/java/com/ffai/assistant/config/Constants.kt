package com.ffai.assistant.config

/**
 * Constantes globales de la aplicación
 */
object Constants {
    
    // Version
    const val VERSION = "2.0.0"
    const val VERSION_CODE = 200
    
    // Timing
    const val CAPTURE_FPS = 60
    const val INFERENCE_FPS = 15
    const val INFERENCE_INTERVAL_MS = 66L  // 1000 / 15
    const val ACTION_COOLDOWN_MS = 100L
    
    // Modelo
    const val INPUT_FEATURES = 8
    const val NUM_ACTIONS = 15
    const val HIDDEN_LAYER_1 = 32
    const val HIDDEN_LAYER_2 = 16
    
    // Nombres de acciones (deben coincidir con índices 0-14)
    val ACTION_NAMES = listOf(
        "AIM",           // 0 - Apuntar
        "SHOOT",         // 1 - Disparar
        "MOVE_FORWARD",  // 2 - Avanzar
        "MOVE_BACKWARD", // 3 - Retroceder
        "MOVE_LEFT",     // 4 - Izquierda
        "MOVE_RIGHT",    // 5 - Derecha
        "HEAL",          // 6 - Curar
        "RELOAD",        // 7 - Recargar
        "CROUCH",        // 8 - Agacharse
        "JUMP",          // 9 - Saltar
        "LOOT",          // 10 - Saquear
        "REVIVE",        // 11 - Revivir
        "ROTATE_LEFT",   // 12 - Girar izq
        "ROTATE_RIGHT",  // 13 - Girar der
        "HOLD"           // 14 - Esperar
    )
    
    // Cooldowns específicos por acción (ms)
    val ACTION_COOLDOWNS = mapOf(
        "SHOOT" to 200L,
        "HEAL" to 3000L,
        "RELOAD" to 2000L,
        "JUMP" to 500L,
        "CROUCH" to 300L
    )
    
    // Aprendizaje
    const val LEARNING_RATE = 0.001f
    const val GAMMA = 0.99f  // Discount factor
    const val EPSILON_START = 0.3f
    const val EPSILON_MIN = 0.05f
    const val EPSILON_DECAY = 0.995f
    const val BATCH_SIZE = 32
    const val MEMORY_SIZE = 10000
    const val TARGET_UPDATE_INTERVAL = 100
    
    // Recompensas
    const val REWARD_KILL = 100f
    const val REWARD_HIT = 10f
    const val REWARD_SURVIVAL = 0.1f
    const val REWARD_DAMAGE_TAKEN = -10f
    const val REWARD_DEATH = -500f
    const val REWARD_HEAL = 5f
    const val REWARD_RELOAD = 2f
    const val REWARD_WIN = 1000f
    const val REWARD_TOP_5 = 500f
    const val REWARD_TOP_10 = 200f
    
    // Detección
    const val ENEMY_DETECTION_THRESHOLD = 0.6f
    const val HP_LOW_THRESHOLD = 0.3f
    const val AMMO_LOW_THRESHOLD = 0.25f
    
    // Procesamiento de imagen
    const val FRAME_WIDTH = 320
    const val FRAME_HEIGHT = 240
    const val VISION_GRID_SIZE = 8  // Divide pantalla en 8x8 grid
    
    // Anti-detection
    const val HUMAN_DELAY_VARIANCE_MS = 15
    const val HUMAN_OFFSET_PX = 8
    const val HUMAN_PAUSE_PROBABILITY = 0.02f
    
    // Base de datos
    const val DB_NAME = "ffai_learning.db"
    const val DB_VERSION = 1
    
    // Archivos
    const val MODEL_DIR = "models"
    const val MODEL_CURRENT = "model_current.tflite"
    const val MODEL_BACKUP = "model_backup.tflite"
    const val LOGS_DIR = "logs"
    
    // Debug
    const val DEBUG_TAG = "FFAI"
}
