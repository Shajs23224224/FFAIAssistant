package com.ffai.assistant.rl.hierarchical

import android.content.Context
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import java.io.FileInputStream

/**
 * MetaController - Controlador de alto nivel (meta-policy).
 * 
 * Selecciona "intenciones" o "goals" cada N steps.
 * Goals disponibles: PUSH, DEFEND, FLANK, LOOT, RETREAT, ENGAGE, ROTATE
 * 
 * Basado en Options Framework (Bacon et al., 2017) y FeUdal Networks.
 */
class MetaController(private val context: Context) {
    
    companion object {
        const val TAG = "MetaController"
        const val MODEL_NAME = "meta_controller.tflite"
        
        const val STATE_DIM = 256
        const val NUM_GOALS = 7
        const val GOAL_DURATION = 30  // frames (1s a 30fps)
        const val GOAL_EMBED_DIM = 64
    }
    
    // Goals posibles
    enum class Goal {
        PUSH,       // Avanzar agresivamente
        DEFEND,     // Defender posición
        FLANK,      // Flanquear
        LOOT,       // Lootear recursos
        RETREAT,    // Retirada
        ENGAGE,     // Enganchar enemigo
        ROTATE      // Rotar posición
    }
    
    private var metaNet: Interpreter? = null
    private var isInitialized = false
    
    // Estado
    private var currentGoal: Goal? = null
    private var goalStepCount = 0
    private var totalGoalSwitches = 0
    private var goalHistory = mutableListOf<Pair<Int, Goal>>() // (step, goal)
    
    // Goal embeddings
    private val goalEmbeddings = mutableMapOf<Goal, FloatArray>()
    
    /**
     * Inicializa meta-controller.
     */
    fun initialize(): Boolean = try {
        metaNet = loadModel(MODEL_NAME)
        initializeGoalEmbeddings()
        isInitialized = true
        Logger.i(TAG, "MetaController initialized - goals: ${Goal.values().toList()}")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing MetaController", e)
        false
    }
    
    /**
     * Inicializa embeddings de goals.
     */
    private fun initializeGoalEmbeddings() {
        Goal.values().forEachIndexed { idx, goal ->
            goalEmbeddings[goal] = FloatArray(GOAL_EMBED_DIM) { i ->
                kotlin.math.sin(idx * 0.5f + i * 0.1f)
            }
        }
    }
    
    /**
     * Selecciona goal cada GOAL_DURATION steps.
     */
    fun selectGoal(state: FloatArray, force: Boolean = false): Goal {
        goalStepCount++
        
        // Cambiar goal solo si pasó duración o forzado
        if (!force && currentGoal != null && goalStepCount < GOAL_DURATION) {
            return currentGoal!!
        }
        
        val selected = if (isInitialized && metaNet != null) {
            selectWithNetwork(state)
        } else {
            selectHeuristic(state)
        }
        
        // Actualizar estadísticas
        if (selected != currentGoal) {
            totalGoalSwitches++
            goalHistory.add(goalStepCount to selected)
        }
        
        currentGoal = selected
        goalStepCount = 0
        
        return selected
    }
    
    /**
     * Selección con red neuronal.
     */
    private fun selectWithNetwork(state: FloatArray): Goal {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(NUM_GOALS) }
        
        metaNet?.run(input, output)
        
        val goalIdx = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        return Goal.values().getOrElse(goalIdx) { Goal.ENGAGE }
    }
    
    /**
     * Selección heurística (fallback).
     */
    private fun selectHeuristic(state: FloatArray): Goal {
        val hp = state.getOrElse(0) { 1f }
        val enemies = state.getOrElse(2) { 0f }
        
        return when {
            hp < 0.3f -> Goal.RETREAT
            enemies > 0.5f -> Goal.ENGAGE
            hp > 0.8f -> Goal.PUSH
            else -> Goal.DEFEND
        }
    }
    
    /**
     * Obtiene embedding del goal actual.
     */
    fun getCurrentGoalEmbedding(): FloatArray {
        return goalEmbeddings[currentGoal ?: Goal.ENGAGE]!!
    }
    
    /**
     * Goal a vector one-hot.
     */
    fun goalToOneHot(goal: Goal): FloatArray {
        return FloatArray(NUM_GOALS) { if (it == goal.ordinal) 1f else 0f }
    }
    
    /**
     * Verifica si es momento de cambiar goal.
     */
    fun shouldSwitchGoal(): Boolean {
        return goalStepCount >= GOAL_DURATION
    }
    
    /**
     * Calcula recompensa para meta-controller.
     * Basada en progreso hacia el goal.
     */
    fun computeGoalProgress(state: FloatArray, nextState: FloatArray): Float {
        return when (currentGoal) {
            Goal.PUSH -> nextState.getOrElse(0) { 0f } - state.getOrElse(0) { 0f } // HP change
            Goal.DEFEND -> 0.1f // Reward por sobrevivir
            Goal.FLANK -> nextState.getOrElse(2) { 0f } * 0.5f // Enemies in sight
            Goal.LOOT -> 0.2f
            Goal.RETREAT -> if (nextState.getOrElse(0) { 0f } > state.getOrElse(0) { 0f }) 0.3f else 0f
            Goal.ENGAGE -> nextState.getOrElse(2) { 0f } - state.getOrElse(2) { 0f }
            Goal.ROTATE -> 0.1f
            null -> 0f
        }
    }
    
    /**
     * Estrategia de meta-controller por goal.
     */
    fun getGoalStrategy(): Strategy {
        return when (currentGoal) {
            Goal.PUSH -> Strategy(aggressiveness = 0.8f, exploration = 0.3f)
            Goal.DEFEND -> Strategy(aggressiveness = 0.3f, exploration = 0.1f)
            Goal.FLANK -> Strategy(aggressiveness = 0.7f, exploration = 0.5f)
            Goal.LOOT -> Strategy(aggressiveness = 0.1f, exploration = 0.4f)
            Goal.RETREAT -> Strategy(aggressiveness = 0.0f, exploration = 0.6f)
            Goal.ENGAGE -> Strategy(aggressiveness = 0.9f, exploration = 0.2f)
            Goal.ROTATE -> Strategy(aggressiveness = 0.4f, exploration = 0.7f)
            null -> Strategy(0.5f, 0.5f)
        }
    }
    
    /**
     * Descripción del goal actual.
     */
    fun getGoalDescription(): String {
        return when (currentGoal) {
            Goal.PUSH -> "Agresivo: Empujar"
            Goal.DEFEND -> "Defensivo: Mantener posición"
            Goal.FLANK -> "Táctico: Flanquear"
            Goal.LOOT -> "Económico: Lootear"
            Goal.RETREAT -> "Defensivo: Retirarse"
            Goal.ENGAGE -> "Combate: Enganchar"
            Goal.ROTATE -> "Movimiento: Rotar"
            null -> "Ningún objetivo"
        }
    }
    
    /**
     * Reset episódico.
     */
    fun resetEpisode() {
        currentGoal = null
        goalStepCount = 0
        goalHistory.clear()
        Logger.d(TAG, "MetaController episode reset")
    }
    
    /**
     * Estadísticas.
     */
    fun getStats() = MetaControllerStats(
        currentGoal = currentGoal?.name ?: "NONE",
        goalDuration = goalStepCount,
        totalSwitches = totalGoalSwitches,
        goalHistorySize = goalHistory.size,
        goalDistribution = goalHistory.groupingBy { it.second }.eachCount()
    )
    
    fun release() {
        metaNet?.close()
        isInitialized = false
    }
    
    private fun loadModel(name: String): Interpreter? = try {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val buffer = fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
        }
    } catch (e: Exception) { null }
}

data class Strategy(val aggressiveness: Float, val exploration: Float)

data class MetaControllerStats(
    val currentGoal: String,
    val goalDuration: Int,
    val totalSwitches: Int,
    val goalHistorySize: Int,
    val goalDistribution: Map<MetaController.Goal, Int>
)
