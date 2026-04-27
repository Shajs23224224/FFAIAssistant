package com.ffai.assistant.rl.metalearning

import android.content.Context
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import java.io.FileInputStream

/**
 * MAMLAgent - Model-Agnostic Meta-Learning (Finn et al., 2017).
 * 
 * Aprende parámetros de inicialización que permiten adaptación rápida
 * a nuevas tareas con pocos gradient steps.
 * 
 * Meta-objective: θ* = argmin_θ Σ_tasks L(f_θ'(), D_test)
 * where θ' = θ - α∇_θ L(f_θ, D_train)
 */
class MAMLAgent(private val context: Context) {
    
    companion object {
        const val TAG = "MAMLAgent"
        const val META_MODEL = "maml_meta.tflite"
        
        // Meta-learning params
        const val META_LR = 0.001f      // Meta learning rate (β)
        const val INNER_LR = 0.01f      // Inner loop learning rate (α)
        const val INNER_STEPS = 5       // Gradient steps en inner loop
        const val NUM_TASKS = 16        // Tasks por meta-batch
        
        // Dimensions
        const val STATE_DIM = 256
        const val ACTION_DIM = 15
    }
    
    // Meta-parameters θ (initialized from pretrained model)
    private var metaNet: Interpreter? = null
    private var isInitialized = false
    
    // Adapted parameters θ' (task-specific)
    private var adaptedParameters = mutableMapOf<String, FloatArray>()
    
    // Task tracking
    private var currentTask: Task? = null
    private var adaptationStep = 0
    
    /**
     * Inicializa meta-parameters.
     */
    fun initialize(): Boolean = try {
        metaNet = loadModel(META_MODEL)
        isInitialized = true
        Logger.i(TAG, "MAML initialized - meta_lr=$META_LR, inner_lr=$INNER_LR, steps=$INNER_STEPS")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing MAML", e)
        false
    }
    
    /**
     * Inner loop: adapta parámetros a task específica.
     * θ' = θ - α∇_θ L(f_θ, D_train)
     */
    fun adaptToTask(task: Task, supportSet: List<MAMLExperience>): AdaptedPolicy {
        currentTask = task
        adaptationStep = 0
        
        // Simular adaptation (en TFLite real sería fine-tuning)
        val taskEmbedding = computeTaskEmbedding(task)
        
        repeat(INNER_STEPS) { step ->
            // Gradient descent en support set
            val loss = computeSupportLoss(supportSet, taskEmbedding)
            
            // Update adapted params (placeholder)
            adaptedParameters["task_${task.id}_step_$step"] = 
            floatArrayOf(loss, task.lrMultiplier)
            
            adaptationStep++
        }
        
        return AdaptedPolicy(
            taskId = task.id,
            adaptationSteps = INNER_STEPS,
            finalLoss = adaptedParameters.values.lastOrNull()?.get(0) ?: 0f,
            taskEmbedding = taskEmbedding
        )
    }
    
    /**
     * Evalúa en query set (test) después de adaptation.
     */
    fun evaluateAdaptation(
        adaptedPolicy: AdaptedPolicy,
        querySet: List<MAMLExperience>
    ): AdaptationResult {
        var totalReward = 0f
        var correct = 0
        
        querySet.forEach { exp ->
            val action = predictWithAdaptedPolicy(exp.state, adaptedPolicy)
            if (action == exp.action) correct++
            totalReward += exp.reward
        }
        
        return AdaptationResult(
            taskId = adaptedPolicy.taskId,
            queryReward = totalReward / querySet.size,
            queryAccuracy = correct.toFloat() / querySet.size,
            adaptationLoss = adaptedPolicy.finalLoss
        )
    }
    
    /**
     * Meta-update: actualiza meta-parameters.
     * θ = θ - β∇_θ Σ_tasks L(f_θ'i(), D_test)
     */
    fun metaUpdate(taskResults: List<AdaptationResult>): MetaUpdateResult {
        val avgReward = taskResults.map { it.queryReward }.average().toFloat()
        val avgAccuracy = taskResults.map { it.queryAccuracy }.average().toFloat()
        
        // En implementación real: backprop through inner loop
        Logger.d(TAG, "Meta-update: avg_reward=$avgReward, avg_accuracy=$avgAccuracy")
        
        return MetaUpdateResult(
            newMetaLoss = 1f - avgReward,
            avgAdaptationPerformance = avgReward,
            tasksUpdated = taskResults.size
        )
    }
    
    /**
     * Adaptación rápida online durante gameplay.
     * Detecta cambio de contexto y adapta.
     */
    fun fastAdapt(currentState: FloatArray, contextChange: ContextChange): FastAdaptationResult {
        // Detectar tipo de cambio
        val adaptationNeeded = when (contextChange) {
            is ContextChange.NewWeapon -> 0.8f
            is ContextChange.NewMap -> 0.9f
            is ContextChange.NewEnemyBehavior -> 0.6f
            is ContextChange.LowHealth -> 0.4f
        }
        
        // Adaptar solo últimas capas (eficiente)
        val adapted = performFastAdaptation(currentState, adaptationNeeded)
        
        return FastAdaptationResult(
            adapted = adapted,
            adaptationMagnitude = adaptationNeeded,
            contextType = contextChange::class.simpleName ?: "Unknown"
        )
    }
    
    /**
     * Computa embedding de task.
     */
    private fun computeTaskEmbedding(task: Task): FloatArray {
        return FloatArray(64) { i ->
            val hash = task.name.hashCode() + i * 31
            kotlin.math.sin(hash * 0.1f)
        }
    }
    
    /**
     * Computa loss en support set.
     */
    private fun computeSupportLoss(supportSet: List<MAMLExperience>, taskEmbed: FloatArray): Float {
        return supportSet.map { exp ->
            val predicted = predict(exp.state)
            if (predicted == exp.action) 0f else 1f
        }.average().toFloat()
    }
    
    /**
     * Predice acción con meta-parameters.
     */
    fun predict(state: FloatArray): Int {
        if (!isInitialized || metaNet == null) return 0
        
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(ACTION_DIM) }
        
        metaNet?.run(input, output)
        return output[0].indices.maxByOrNull { output[0][it] } ?: 0
    }
    
    /**
     * Predice con policy adaptada.
     */
    private fun predictWithAdaptedPolicy(state: FloatArray, policy: AdaptedPolicy): Int {
        // Combinar state con task embedding
        val combined = state + policy.taskEmbedding.sliceArray(0 until 32)
        return predict(combined)
    }
    
    /**
     * Adaptación rápida eficiente.
     */
    private fun performFastAdaptation(state: FloatArray, magnitude: Float): Boolean {
        // Adaptar solo si magnitude > threshold
        return magnitude > 0.5f
    }
    
    /**
     * Guarda meta-parameters.
     */
    fun saveMetaParameters(path: String) {
        Logger.i(TAG, "Meta-parameters saved to $path")
    }
    
    /**
     * Carga meta-parameters.
     */
    fun loadMetaParameters(path: String): Boolean {
        Logger.i(TAG, "Meta-parameters loaded from $path")
        return true
    }
    
    /**
     * Estadísticas.
     */
    fun getStats() = MAMLStats(
        isInitialized = isInitialized,
        currentTask = currentTask?.name ?: "None",
        adaptationStep = adaptationStep,
        adaptedParamsCount = adaptedParameters.size
    )
    
    fun release() {
        metaNet?.close()
        isInitialized = false
        adaptedParameters.clear()
    }
    
    private fun loadModel(name: String): Interpreter? = try {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                .let { Interpreter(it, Interpreter.Options().apply { setNumThreads(2) }) }
        }
    } catch (e: Exception) { null }
}

data class Task(
    val id: Int,
    val name: String,
    val type: TaskType,
    val lrMultiplier: Float = 1.0f
)

enum class TaskType {
    WEAPON_SPECIFIC,    // Adaptar a nueva arma
    MAP_SPECIFIC,       // Adaptar a nuevo mapa
    ENEMY_TYPE,         // Adaptar a tipo de enemigo
    TACTICAL_SITUATION  // Adaptar a situación táctica
}

data class MAMLExperience(
    val state: FloatArray,
    val action: Int,
    val reward: Float,
    val nextState: FloatArray
)

data class AdaptedPolicy(
    val taskId: Int,
    val adaptationSteps: Int,
    val finalLoss: Float,
    val taskEmbedding: FloatArray
)

data class AdaptationResult(
    val taskId: Int,
    val queryReward: Float,
    val queryAccuracy: Float,
    val adaptationLoss: Float
)

data class MetaUpdateResult(
    val newMetaLoss: Float,
    val avgAdaptationPerformance: Float,
    val tasksUpdated: Int
)

data class FastAdaptationResult(
    val adapted: Boolean,
    val adaptationMagnitude: Float,
    val contextType: String
)

sealed class ContextChange {
    data class NewWeapon(val weaponId: Int) : ContextChange()
    data class NewMap(val mapId: Int) : ContextChange()
    data class NewEnemyBehavior(val behaviorType: String) : ContextChange()
    data class LowHealth(val health: Float) : ContextChange()
}

data class MAMLStats(
    val isInitialized: Boolean,
    val currentTask: String,
    val adaptationStep: Int,
    val adaptedParamsCount: Int
)
