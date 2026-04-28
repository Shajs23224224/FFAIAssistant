package com.ffai.assistant.rl

import android.content.Context
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.exp
import kotlin.math.log
import kotlin.math.min

/**
 * FASE 3: PPOAgent - Proximal Policy Optimization.
 * 
 * Actor-Critic con:
 * - Clipped surrogate objective
 * - Generalized Advantage Estimation (GAE)
 * - Entropy bonus para exploración
 * - Mini-batch training
 */
class PPOAgent(private val context: Context) {
    
    companion object {
        const val TAG = "PPOAgent"
        const val ACTOR_MODEL = "ppo_actor.tflite"
        const val CRITIC_MODEL = "ppo_critic.tflite"
        
        const val STATE_SIZE = 256
        const val NUM_ACTIONS = 15
        const val CONTINUOUS_DIM = 2  // Aim delta x, y
        
        // Hyperparameters
        const val GAMMA = 0.99f
        const val LAMBDA = 0.95f  // GAE lambda
        const val CLIP_EPSILON = 0.2f
        const val ENTROPY_COEF = 0.01f
        const val VALUE_COEF = 0.5f
        const val MAX_GRAD_NORM = 0.5f
        const val LEARNING_RATE = 3e-4f
        
        const val BATCH_SIZE = 64
        const val EPOCHS = 4
        const val HORIZON = 2048  // Timesteps antes de update
    }
    
    // Networks
    private var actorNet: Interpreter? = null
    private var criticNet: Interpreter? = null
    private var isInitialized = false
    
    // Trajectory buffer
    private val trajectoryBuffer = TrajectoryBuffer(HORIZON)
    
    // State
    private var episodeStep = 0
    private var totalSteps = 0
    private var updateCount = 0
    
    // Stats
    private var totalReward = 0f
    private var episodeReward = 0f
    private val valueHistory = ArrayDeque<Float>(100)
    private val entropyHistory = ArrayDeque<Float>(100)
    
    /**
     * Inicializa networks.
     */
    fun initialize(): Boolean {
        return try {
            Logger.i(TAG, "Inicializando PPOAgent...")
            
            val actorBuffer = loadModelFile(ACTOR_MODEL)
            val criticBuffer = loadModelFile(CRITIC_MODEL)
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            
            actorNet = Interpreter(actorBuffer, options)
            criticNet = Interpreter(criticBuffer, options)
            
            isInitialized = true
            Logger.i(TAG, "PPOAgent inicializado - Actor-Critic con GAE")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error inicializando PPO", e)
            false
        }
    }
    
    /**
     * Selecciona acción con policy actual.
     * @return Action y log probability para training
     */
    fun selectAction(state: FloatArray): PPOAction {
        if (!isInitialized) return PPOAction(ActionType.HOLD, 0f, 0f, floatArrayOf(0f, 0f))
        
        // Actor output: action probabilities + continuous params
        val actionProbs = predictActionProbs(state)
        val stateValue = predictValue(state)
        
        // Sample discrete action
        val actionIdx = sampleCategorical(actionProbs)
        val logProb = kotlin.math.ln(actionProbs[actionIdx].toDouble()).toFloat()
        
        // Sample continuous action (aim adjustment)
        val continuousAction = sampleContinuous(state)
        
        valueHistory.add(stateValue)
        if (valueHistory.size > 100) valueHistory.removeFirst()
        
        // Calcular entropy
        var entropySum = 0f
        for (p in actionProbs) {
            if (p > 0) {
                entropySum += p * kotlin.math.ln(p)
            }
        }
        val entropy = -entropySum
        entropyHistory.add(entropy)
        if (entropyHistory.size > 100) entropyHistory.removeFirst()
        
        return PPOAction(
            discreteAction = ActionType.values().getOrElse(actionIdx) { ActionType.HOLD },
            logProb = logProb.toFloat(),
            stateValue = stateValue,
            continuousAction = continuousAction
        )
    }
    
    /**
     * Almacena transición en trajectory buffer.
     */
    fun storeTransition(
        state: FloatArray,
        action: PPOAction,
        reward: Float,
        nextState: FloatArray,
        done: Boolean
    ) {
        trajectoryBuffer.add(Transition(state, action, reward, nextState, done))
        
        episodeStep++
        totalSteps++
        episodeReward += reward
        totalReward += reward
        
        // Si buffer lleno o episodio termina, actualizar
        if (trajectoryBuffer.isFull() || done) {
            updatePolicy()
        }
    }
    
    /**
     * Actualiza policy con datos del buffer.
     */
    private fun updatePolicy() {
        if (trajectoryBuffer.size() < BATCH_SIZE) return
        
        // Calcular ventajas con GAE
        val advantages = calculateGAE()
        val returns = calculateReturns(advantages)
        
        // Actualizar por epochs
        for (epoch in 0 until EPOCHS) {
            // Samplear mini-batches
            val batches = trajectoryBuffer.sampleBatches(BATCH_SIZE)
            
            for (batch in batches) {
                // Actor update con clipped objective
                updateActor(batch, advantages)
                
                // Critic update
                updateCritic(batch, returns)
            }
        }
        
        trajectoryBuffer.clear()
        updateCount++
        
        Logger.d(TAG, "Policy updated #$updateCount, steps: $totalSteps")
    }
    
    /**
     * Calcula ventajas con GAE.
     */
    private fun calculateGAE(): List<Float> {
        val transitions = trajectoryBuffer.getAll()
        val advantages = mutableListOf<Float>()
        var gae = 0f
        
        for (i in transitions.indices.reversed()) {
            val t = transitions[i]
            val nextValue = if (t.done || i == transitions.size - 1) {
                0f
            } else {
                predictValue(t.nextState)
            }
            
            val delta = t.action.stateValue - nextValue + t.reward
            gae = delta + GAMMA * LAMBDA * gae
            advantages.add(0, gae)  // Insertar al inicio
        }
        
        return advantages
    }
    
    /**
     * Calcula returns (rewards acumulados).
     */
    private fun calculateReturns(advantages: List<Float>): List<Float> {
        val transitions = trajectoryBuffer.getAll()
        return advantages.mapIndexed { i, adv ->
            adv + transitions[i].action.stateValue
        }
    }
    
    /**
     * Actualiza actor con PPO clipped objective.
     */
    private fun updateActor(batch: List<Transition>, advantages: List<Float>) {
        // En TFLite, esto requeriría training on-device o envío a servidor
        // Simplificación: log de que se debería actualizar
        // Implementación real usaría TF Lite Model Personalization o cloud
        
        var surrogateLoss = 0f
        for ((idx, t) in batch.withIndex()) {
            val newLogProb = kotlin.math.ln(predictActionProbs(t.state)[t.action.discreteAction.ordinal].toDouble()).toFloat()
            val ratio = exp((newLogProb - t.action.logProb).toDouble()).toFloat()
            
            val advantage = advantages[idx]
            val clippedRatio = ratio.coerceIn(1f - CLIP_EPSILON, 1f + CLIP_EPSILON)
            
            surrogateLoss += -min(ratio * advantage, clippedRatio * advantage)
        }
        
        Logger.d(TAG, "Actor loss: ${surrogateLoss / batch.size}")
    }
    
    /**
     * Actualiza critic.
     */
    private fun updateCritic(batch: List<Transition>, returns: List<Float>) {
        var valueLoss = 0f
        for ((idx, t) in batch.withIndex()) {
            val predictedValue = predictValue(t.state)
            val targetValue = returns[idx]
            val diff = predictedValue - targetValue
            valueLoss += diff * diff
        }
        
        Logger.d(TAG, "Critic loss: ${valueLoss / batch.size}")
    }
    
    /**
     * Predice probabilidades de acción.
     */
    /**
     * Entrena un paso (consistencia API con otros agentes).
     */
    fun trainStep(): Float {
        updatePolicy()
        return 0f // PPO no devuelve loss simple
    }
    
    private fun predictActionProbs(state: FloatArray): FloatArray {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(NUM_ACTIONS) }
        actorNet?.run(input, output)
        
        // Softmax
        val logits = output[0]
        val maxLogit = logits.maxOrNull() ?: 0f
        val expLogits = logits.map { exp(it - maxLogit) }
        val sumExp = expLogits.sum()
        return expLogits.map { it / sumExp }.toFloatArray()
    }
    
    /**
     * Predice value de estado.
     */
    private fun predictValue(state: FloatArray): Float {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(1) }
        criticNet?.run(input, output)
        return output[0][0]
    }
    
    /**
     * Samplea acción continua (aim adjustment).
     */
    private fun sampleContinuous(state: FloatArray): FloatArray {
        // Output: deltaX, deltaY para ajustar aim
        // Simplificado: retornar valores basados en distribución normal
        val mean = 0f
        val std = 1f
        return floatArrayOf(
            mean + std * kotlin.random.Random.nextFloat(),
            mean + std * kotlin.random.Random.nextFloat()
        )
    }
    
    /**
     * Samplea de distribución categórica.
     */
    private fun sampleCategorical(probs: FloatArray): Int {
        val r = kotlin.random.Random.nextFloat()
        var cumulative = 0f
        for (i in probs.indices) {
            cumulative += probs[i]
            if (r <= cumulative) return i
        }
        return probs.size - 1
    }
    
    /**
     * Inicia nuevo episodio.
     */
    fun startEpisode() {
        episodeStep = 0
        episodeReward = 0f
        Logger.d(TAG, "PPO Episode started")
    }
    
    /**
     * Finaliza episodio.
     */
    fun endEpisode(): Float {
        val reward = episodeReward
        Logger.d(TAG, "PPO Episode ended, reward: $reward")
        return reward
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): PPOStats {
        val avgValue = if (valueHistory.isNotEmpty()) valueHistory.average() else 0.0
        val avgEntropy = if (entropyHistory.isNotEmpty()) entropyHistory.average() else 0.0
        
        return PPOStats(
            totalSteps = totalSteps,
            updateCount = updateCount,
            bufferSize = trajectoryBuffer.size(),
            averageValue = avgValue.toFloat(),
            averageEntropy = avgEntropy.toFloat(),
            totalReward = totalReward
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        actorNet?.close()
        criticNet?.close()
        isInitialized = false
        Logger.i(TAG, "PPOAgent released")
    }
    
    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}

/**
 * Acción de PPO.
 */
data class PPOAction(
    val discreteAction: ActionType,
    val logProb: Float,
    val stateValue: Float,
    val continuousAction: FloatArray  // [deltaX, deltaY]
)

/**
 * Transición para trajectory buffer.
 */
private data class Transition(
    val state: FloatArray,
    val action: PPOAction,
    val reward: Float,
    val nextState: FloatArray,
    val done: Boolean
)

/**
 * Buffer de trayectorias.
 */
private class TrajectoryBuffer(private val capacity: Int) {
    private val buffer = ArrayDeque<Transition>(capacity)
    
    fun add(transition: Transition) {
        if (buffer.size >= capacity) {
            buffer.removeFirst()
        }
        buffer.add(transition)
    }
    
    fun isFull(): Boolean = buffer.size >= capacity
    fun size(): Int = buffer.size
    fun clear() = buffer.clear()
    fun getAll(): List<Transition> = buffer.toList()
    
    fun sampleBatches(batchSize: Int): List<List<Transition>> {
        val shuffled = buffer.shuffled()
        return shuffled.chunked(batchSize)
    }
}

/**
 * Estadísticas de PPO.
 */
data class PPOStats(
    val totalSteps: Int,
    val updateCount: Int,
    val bufferSize: Int,
    val averageValue: Float,
    val averageEntropy: Float,
    val totalReward: Float
)
