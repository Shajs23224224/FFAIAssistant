package com.ffai.assistant.rl.hierarchical

import android.content.Context
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import java.io.FileInputStream

/**
 * SubPolicyManager - Gestiona sub-policies especializadas.
 * 
 * Cada goal del MetaController tiene una sub-policy optimizada.
 * Policies: PushPolicy, DefendPolicy, FlankPolicy, LootPolicy, RetreatPolicy, EngagePolicy, RotatePolicy
 */
class SubPolicyManager(private val context: Context) {
    
    companion object {
        const val TAG = "SubPolicyManager"
        const val MODEL_NAME = "sub_policies.tflite"
        
        const val STATE_DIM = 256
        const val GOAL_EMBED_DIM = 64
        const val NUM_ACTIONS = 15
        
        // Goal indices
        const val GOAL_PUSH = 0
        const val GOAL_DEFEND = 1
        const val GOAL_FLANK = 2
        const val GOAL_LOOT = 3
        const val GOAL_RETREAT = 4
        const val GOAL_ENGAGE = 5
        const val GOAL_ROTATE = 6
    }
    
    private var policyNet: Interpreter? = null
    private var isInitialized = false
    
    // Cache de últimas acciones por goal
    private val actionCache = mutableMapOf<Int, Int>()
    
    // Switching cost tracker
    private var lastGoal = -1
    private var switchCount = 0
    
    /**
     * Inicializa sub-policies.
     */
    fun initialize(): Boolean = try {
        policyNet = loadModel(MODEL_NAME)
        isInitialized = true
        Logger.i(TAG, "SubPolicyManager initialized - 7 specialized sub-policies")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing SubPolicyManager", e)
        false
    }
    
    /**
     * Selecciona acción usando sub-policy para goal específico.
     */
    fun selectAction(state: FloatArray, goal: MetaController.Goal): SubPolicyAction {
        val goalIdx = goal.ordinal
        
        // Track switches
        if (goalIdx != lastGoal) {
            switchCount++
            lastGoal = goalIdx
        }
        
        return if (isInitialized && policyNet != null) {
            selectWithNetwork(state, goalIdx)
        } else {
            selectHeuristic(state, goal)
        }
    }
    
    /**
     * Selección con red multi-task.
     * Input: state + goal_embedding
     * Output: action_probs + goal_specific_value
     */
    private fun selectWithNetwork(state: FloatArray, goalIdx: Int): SubPolicyAction {
        val goalOneHot = FloatArray(7) { if (it == goalIdx) 1f else 0f }
        val input = Array(1) { state + goalOneHot }
        val output = Array(1) { FloatArray(NUM_ACTIONS + 1) } // actions + value
        
        policyNet?.run(input, output)
        
        val probs = output[0].sliceArray(0 until NUM_ACTIONS)
        val value = output[0][NUM_ACTIONS]
        
        // Samplear o max
        val action = probs.indices.maxByOrNull { probs[it] } ?: 0
        
        actionCache[goalIdx] = action
        
        return SubPolicyAction(
            action = action,
            value = value,
            goal = goalIdx,
            confidence = probs[action],
            actionProbs = probs.copyOf()
        )
    }
    
    /**
     * Selección heurística por goal.
     */
    private fun selectHeuristic(state: FloatArray, goal: MetaController.Goal): SubPolicyAction {
        val action = when (goal) {
            MetaController.Goal.PUSH -> 0
            MetaController.Goal.DEFEND -> 1
            MetaController.Goal.FLANK -> 2
            MetaController.Goal.LOOT -> 3
            MetaController.Goal.RETREAT -> 4
            MetaController.Goal.ENGAGE -> 5
            MetaController.Goal.ROTATE -> 6
        }
        
        return SubPolicyAction(action, 0.5f, goal.ordinal, 0.5f, floatArrayOf())
    }
    
    /**
     * Evalúa todas las sub-policies y retorna la mejor.
     */
    fun evaluateAllPolicies(state: FloatArray): PolicyEvaluation {
        val evaluations = MetaController.Goal.values().map { goal ->
            val action = selectAction(state, goal)
            goal to action
        }.sortedByDescending { it.second.value }
        
        return PolicyEvaluation(
            bestGoal = evaluations.first().first,
            bestAction = evaluations.first().second,
            allEvaluations = evaluations.toMap()
        )
    }
    
    /**
     * Calcula costo de cambio entre goals.
     */
    fun computeSwitchingCost(fromGoal: MetaController.Goal, toGoal: MetaController.Goal): Float {
        return if (fromGoal == toGoal) 0f else 0.1f
    }
    
    /**
     * Entrena sub-policy específica (placeholder).
     */
    fun trainSubPolicy(goal: MetaController.Goal, experiences: List<SubPolicyExperience>) {
        Logger.d(TAG, "Training ${goal.name} policy with ${experiences.size} experiences")
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats() = SubPolicyStats(
        switchCount = switchCount,
        activeSubPolicies = actionCache.size,
        lastGoal = lastGoal
    )
    
    fun reset() {
        actionCache.clear()
        lastGoal = -1
        switchCount = 0
    }
    
    fun release() {
        policyNet?.close()
        isInitialized = false
    }
    
    private fun loadModel(name: String): Interpreter? = try {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                .let { Interpreter(it, Interpreter.Options().apply { setNumThreads(2) }) }
        }
    } catch (e: Exception) { null }
}

data class SubPolicyAction(
    val action: Int,
    val value: Float,
    val goal: Int,
    val confidence: Float,
    val actionProbs: FloatArray
)

data class SubPolicyExperience(
    val state: FloatArray,
    val goal: Int,
    val action: Int,
    val reward: Float,
    val nextState: FloatArray
)

data class PolicyEvaluation(
    val bestGoal: MetaController.Goal,
    val bestAction: SubPolicyAction,
    val allEvaluations: Map<MetaController.Goal, SubPolicyAction>
)

data class SubPolicyStats(
    val switchCount: Int,
    val activeSubPolicies: Int,
    val lastGoal: Int
)
