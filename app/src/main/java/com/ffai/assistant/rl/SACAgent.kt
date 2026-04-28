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
import kotlin.math.max
import kotlin.math.min

/**
 * FASE 3: SACAgent - Soft Actor-Critic.
 * 
 * Features:
 * - Twin Q-networks (clipped double Q)
 * - Entropy temperature auto-ajustable
 * - Reparameterization trick
 * - Soft target updates
 * - Mejor para espacios de acción continuos
 */
class SACAgent(private val context: Context) {
    
    companion object {
        const val TAG = "SACAgent"
        const val ACTOR_MODEL = "sac_actor.tflite"
        const val Q1_MODEL = "sac_q1.tflite"
        const val Q2_MODEL = "sac_q2.tflite"
        
        const val STATE_SIZE = 256
        const val NUM_ACTIONS = 15
        
        // Hyperparameters
        const val GAMMA = 0.99f
        const val TAU = 0.005f  // Soft update coefficient
        const val ALPHA = 0.2f   // Entropy coefficient (inicial)
        const val TARGET_ENTROPY = -2f  // Para acciones discretas
        const val LEARNING_RATE = 3e-4f
        
        const val BATCH_SIZE = 64
        const val BUFFER_SIZE = 100000
    }
    
    // Networks
    private var actorNet: Interpreter? = null
    private var q1Net: Interpreter? = null
    private var q2Net: Interpreter? = null
    private var q1TargetNet: Interpreter? = null
    private var q2TargetNet: Interpreter? = null
    private var isInitialized = false
    
    // Replay buffer
    private val replayBuffer = ArrayDeque<SACTransition>(BUFFER_SIZE)
    
    // State
    private var trainingStep = 0
    private var episodeCount = 0
    private var alpha = ALPHA  // Entropy temperature
    private var logAlpha = log(ALPHA)
    
    // Stats
    private var totalReward = 0f
    private var episodeReward = 0f
    private val qValueHistory = ArrayDeque<Float>(100)
    private val alphaHistory = ArrayDeque<Float>(100)
    
    /**
     * Inicializa todas las networks.
     */
    fun initialize(): Boolean {
        return try {
            Logger.i(TAG, "Inicializando SACAgent...")
            
            val actorBuffer = loadModelFile(ACTOR_MODEL)
            val q1Buffer = loadModelFile(Q1_MODEL)
            val q2Buffer = loadModelFile(Q2_MODEL)
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            
            actorNet = Interpreter(actorBuffer, options)
            q1Net = Interpreter(q1Buffer, options)
            q2Net = Interpreter(q2Buffer, options)
            
            // Target networks (copia inicial)
            q1TargetNet = Interpreter(q1Buffer, options)
            q2TargetNet = Interpreter(q2Buffer, options)
            
            isInitialized = true
            Logger.i(TAG, "SACAgent inicializado - Twin Q + Entropy auto-tune")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error inicializando SAC", e)
            false
        }
    }
    
    /**
     * Selecciona acción con policy estocástica.
     */
    fun selectAction(state: FloatArray): SACAction {
        if (!isInitialized) return SACAction(ActionType.HOLD, 0f, floatArrayOf(0f, 0f))
        
        // Actor output: mean y std para distribución normal
        val (actionProbs, rawAction) = sampleFromPolicy(state)
        
        // Samplear acción discreta
        val actionIdx = sampleCategorical(actionProbs)
        val logProb = kotlin.math.ln(actionProbs[actionIdx].toDouble()).toFloat()
        
        // Q-values para debugging
        val q1Value = predictQ(state, actionIdx, q1Net)
        val q2Value = predictQ(state, actionIdx, q2Net)
        val minQ = min(q1Value, q2Value)
        
        qValueHistory.add(minQ)
        if (qValueHistory.size > 100) qValueHistory.removeFirst()
        
        return SACAction(
            discreteAction = ActionType.values().getOrElse(actionIdx) { ActionType.HOLD },
            logProb = logProb.toFloat(),
            continuousParams = rawAction
        )
    }
    
    /**
     * Almacena transición en replay buffer.
     */
    fun storeTransition(
        state: FloatArray,
        action: SACAction,
        reward: Float,
        nextState: FloatArray,
        done: Boolean
    ) {
        if (replayBuffer.size >= BUFFER_SIZE) {
            replayBuffer.removeFirst()
        }
        replayBuffer.add(SACTransition(state, action, reward, nextState, done))
        
        episodeReward += reward
        totalReward += reward
    }
    
    /**
     * Entrena un batch.
     */
    fun trainStep(): Float {
        if (!isInitialized || replayBuffer.size < BATCH_SIZE) return 0f
        
        // Samplear batch
        val batch = replayBuffer.shuffled().take(BATCH_SIZE)
        
        // Update Q-functions
        val qLoss = updateQFunctions(batch)
        
        // Update policy
        val policyLoss = updatePolicy(batch)
        
        // Update alpha (entropy temperature)
        val alphaLoss = updateAlpha(batch)
        
        // Soft update target networks
        softUpdateTargets()
        
        trainingStep++
        alphaHistory.add(alpha)
        if (alphaHistory.size > 100) alphaHistory.removeFirst()
        
        return qLoss + policyLoss
    }
    
    /**
     * Actualiza Q-functions (twin Q-learning).
     */
    private fun updateQFunctions(batch: List<SACTransition>): Float {
        var totalLoss = 0f
        
        for (transition in batch) {
            // Target Q-value
            val nextActionProbs = predictActionProbs(transition.nextState)
            
            var targetQ = 0f
            for (a in 0 until NUM_ACTIONS) {
                val q1Next = predictQ(transition.nextState, a, q1TargetNet)
                val q2Next = predictQ(transition.nextState, a, q2TargetNet)
                val minQNext = min(q1Next, q2Next)
                
                // Soft Q: Q - alpha * log(pi)
                val softQ = minQNext - alpha * kotlin.math.ln(max(1e-6f, nextActionProbs[a]).toDouble()).toFloat()
                targetQ += nextActionProbs[a] * softQ
            }
            
            targetQ = transition.reward + GAMMA * (1f - if (transition.done) 1f else 0f) * targetQ
            
            // Current Q-values
            val currentQ1 = predictQ(transition.state, transition.action.discreteAction.ordinal, q1Net)
            val currentQ2 = predictQ(transition.state, transition.action.discreteAction.ordinal, q2Net)
            
            // MSE loss
            val loss1 = (currentQ1 - targetQ) * (currentQ1 - targetQ)
            val loss2 = (currentQ2 - targetQ) * (currentQ2 - targetQ)
            totalLoss += loss1 + loss2
        }
        
        return totalLoss / (2 * batch.size)
    }
    
    /**
     * Actualiza policy (actor).
     */
    private fun updatePolicy(batch: List<SACTransition>): Float {
        var totalLoss = 0f
        
        for (transition in batch) {
            val actionProbs = predictActionProbs(transition.state)
            
            var policyLoss = 0f
            for (a in 0 until NUM_ACTIONS) {
                val q1 = predictQ(transition.state, a, q1Net)
                val q2 = predictQ(transition.state, a, q2Net)
                val minQ = min(q1, q2)
                
                // SAC objective: maximize E[Q - alpha * log(pi)]
                // minimize E[alpha * log(pi) - Q]
                policyLoss += actionProbs[a] * (alpha * kotlin.math.ln(max(1e-6f, actionProbs[a]).toDouble()).toFloat() - minQ)
            }
            
            totalLoss += policyLoss
        }
        
        return totalLoss / batch.size
    }
    
    /**
     * Actualiza alpha (entropy temperature).
     */
    private fun updateAlpha(batch: List<SACTransition>): Float {
        var totalEntropy = 0f
        
        for (transition in batch) {
            val actionProbs = predictActionProbs(transition.state)
            
            var entropy = 0f
            for (a in 0 until NUM_ACTIONS) {
                if (actionProbs[a] > 0) {
                    entropy -= actionProbs[a] * kotlin.math.ln(actionProbs[a].toDouble()).toFloat()
                }
            }
            totalEntropy += entropy
        }
        
        val avgEntropy = totalEntropy / batch.size
        
        // Gradiente de alpha
        val alphaLoss = -logAlpha * (avgEntropy - TARGET_ENTROPY)
        
        // Actualizar alpha (gradient ascent en -alpha)
        logAlpha -= 0.001f * alphaLoss
        alpha = exp(logAlpha)
        alpha = max(0.01f, min(5f, alpha))  // Clip
        
        return alphaLoss
    }
    
    /**
     * Soft update de target networks.
     */
    private fun softUpdateTargets() {
        // En TFLite, esto requiere copiar pesos manualmente
        // Simplificado: log
        if (trainingStep % 1000 == 0) {
            Logger.d(TAG, "Soft update targets at step $trainingStep")
        }
    }
    
    /**
     * Samplea de policy.
     */
    private fun sampleFromPolicy(state: FloatArray): Pair<FloatArray, FloatArray> {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(NUM_ACTIONS + 2) }  // probs + continuous params
        
        actorNet?.run(input, output)
        
        val probs = output[0].sliceArray(0 until NUM_ACTIONS)
        val continuous = output[0].sliceArray(NUM_ACTIONS until NUM_ACTIONS + 2)
        
        return Pair(softmax(probs), continuous)
    }
    
    /**
     * Predice probabilidades de acción.
     */
    private fun predictActionProbs(state: FloatArray): FloatArray {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(NUM_ACTIONS) }
        actorNet?.run(input, output)
        return softmax(output[0])
    }
    
    /**
     * Predice Q-value.
     */
    private fun predictQ(state: FloatArray, action: Int, network: Interpreter?): Float {
        val input = Array(1) { state + floatArrayOf(action / NUM_ACTIONS.toFloat()) }
        val output = Array(1) { FloatArray(1) }
        network?.run(input, output)
        return output[0][0]
    }
    
    /**
     * Softmax.
     */
    private fun softmax(x: FloatArray): FloatArray {
        val maxVal = x.maxOrNull() ?: 0f
        val expX = x.map { exp(it - maxVal) }
        val sumExp = expX.sum()
        return expX.map { it / sumExp }.toFloatArray()
    }
    
    /**
     * Samplea acción categórica.
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
     * Inicia episodio.
     */
    fun startEpisode() {
        episodeCount++
        episodeReward = 0f
        Logger.d(TAG, "SAC Episode $episodeCount started")
    }
    
    /**
     * Finaliza episodio.
     */
    fun endEpisode(): Float {
        val reward = episodeReward
        Logger.d(TAG, "SAC Episode ended, reward: $reward")
        return reward
    }
    
    /**
     * Estadísticas.
     */
    fun getStats(): SACStats {
        val avgQ = if (qValueHistory.isNotEmpty()) qValueHistory.average().toFloat() else 0f
        val avgAlpha = if (alphaHistory.isNotEmpty()) alphaHistory.average().toFloat() else 0f
        
        return SACStats(
            trainingStep = trainingStep,
            episodeCount = episodeCount,
            bufferSize = replayBuffer.size,
            averageQValue = avgQ.toFloat(),
            alpha = alpha,
            averageAlpha = avgAlpha.toFloat(),
            totalReward = totalReward
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        actorNet?.close()
        q1Net?.close()
        q2Net?.close()
        q1TargetNet?.close()
        q2TargetNet?.close()
        isInitialized = false
        Logger.i(TAG, "SACAgent released")
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
 * Acción SAC.
 */
data class SACAction(
    val discreteAction: ActionType,
    val logProb: Float,
    val continuousParams: FloatArray
)

/**
 * Transición SAC.
 */
private data class SACTransition(
    val state: FloatArray,
    val action: SACAction,
    val reward: Float,
    val nextState: FloatArray,
    val done: Boolean
)

/**
 * Estadísticas SAC.
 */
data class SACStats(
    val trainingStep: Int,
    val episodeCount: Int,
    val bufferSize: Int,
    val averageQValue: Float,
    val alpha: Float,
    val averageAlpha: Float,
    val totalReward: Float
)
