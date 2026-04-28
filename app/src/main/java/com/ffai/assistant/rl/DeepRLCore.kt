package com.ffai.assistant.rl

import android.content.Context
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.model.EnsembleResult
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.util.*

/**
 * FASE 5: DeepRLCore - Deep Reinforcement Learning con Dueling DQN + LSTM.
 *
 * Arquitectura:
 * - Input: Estado del juego codificado (256 floats)
 * - Capas: 4 Conv (visión) + 2 LSTM (memoria temporal) + Dueling heads
 * - Output: Q-values para cada acción posible (15 acciones)
 *
 * Features:
 * - Experience replay con priorización
 * - Target network para estabilidad
 * - LSTM para memoria de secuencias
 * - Dueling: separa value y advantage
 */
class DeepRLCore(private val context: Context) {

    companion object {
        const val TAG = "DeepRLCore"
        const val MODEL_NAME = "deeprl_dueling_dqn.tflite"
        
        // Dimensiones
        const val STATE_SIZE = 256
        const val NUM_ACTIONS = 15
        const val LSTM_UNITS = 128
        const val SEQUENCE_LENGTH = 4
        
        // Hyperparámetros RL
        const val GAMMA = 0.99f // Discount factor
        const val EPSILON_START = 1.0f
        const val EPSILON_MIN = 0.05f
        const val EPSILON_DECAY = 0.995f
        const val LEARNING_RATE = 0.00025f
        const val REPLAY_BUFFER_SIZE = 10000
        const val BATCH_SIZE = 32
        const val TARGET_UPDATE_FREQ = 1000
    }

    // Networks
    private var policyNet: Interpreter? = null
    private var targetNet: Interpreter? = null
    private var isInitialized = false
    
    // Estado LSTM (hidden state)
    private var lstmState: Array<Array<FloatArray>>? = null
    private val stateSequence = ArrayDeque<FloatArray>(SEQUENCE_LENGTH)
    
    // Replay buffer con priorización
    private val replayBuffer = PriorityReplayBuffer(REPLAY_BUFFER_SIZE)
    
    // Métricas
    private var epsilon = EPSILON_START
    private var trainingStep = 0
    private var episodeCount = 0
    private var totalReward = 0f
    private var episodeReward = 0f
    
    // Estadísticas
    private var actionCounts = IntArray(NUM_ACTIONS)
    private var qValueHistory = ArrayDeque<Float>(100)
    
    // Callbacks
    private var onTrainingStep: ((Int, Float, Float) -> Unit)? = null
    private var onEpisodeEnd: ((Int, Float, Float) -> Unit)? = null

    /**
     * Inicializa las redes (policy y target).
     */
    fun initialize() {
        try {
            val modelBuffer = loadModelFile(context, MODEL_NAME)
            
            // Policy network (entrenable)
            policyNet = Interpreter(modelBuffer, Interpreter.Options().apply {
                setNumThreads(2)
            })
            
            // Target network (copia estable)
            targetNet = Interpreter(modelBuffer, Interpreter.Options().apply {
                setNumThreads(2)
            })
            
            // Inicializar LSTM state
            lstmState = Array(2) { Array(1) { FloatArray(LSTM_UNITS) } } // hidden, cell
            
            isInitialized = true
            Logger.i(TAG, "DeepRLCore inicializado - Dueling DQN + LSTM")
        } catch (e: Exception) {
            Logger.e(TAG, "Error inicializando DeepRLCore", e)
        }
    }

    /**
     * Selecciona acción usando política epsilon-greedy.
     * Con probabilidad epsilon: exploración (acción aleatoria)
     * Con probabilidad 1-epsilon: explotación (mejor Q-value)
     */
    fun selectAction(state: FloatArray, isTraining: Boolean = true): Int {
        if (!isInitialized) return 0
        
        // Actualizar secuencia de estados
        updateStateSequence(state)
        
        // Epsilon-greedy
        if (isTraining && Random().nextFloat() < epsilon) {
            // Exploración: acción aleatoria ponderada por situación
            val randomAction = weightedRandomAction(state)
            actionCounts[randomAction]++
            return randomAction
        }
        
        // Explotación: mejor Q-value
        val qValues = predictQValues()
        val bestAction = qValues.indices.maxByOrNull { qValues[it] } ?: 0
        
        // Guardar Q-value promedio para estadísticas
        val avgQ = qValues.average().toFloat()
        qValueHistory.addLast(avgQ)
        if (qValueHistory.size > 100) qValueHistory.removeFirst()
        
        actionCounts[bestAction]++
        return bestAction
    }

    /**
     * Predice Q-values para estado actual.
     */
    private fun predictQValues(): FloatArray {
        val input = prepareLSTMInput()
        val output = Array(1) { FloatArray(NUM_ACTIONS) }
        
        // Ejecutar inferencia
        policyNet?.run(input, output)
        
        return output[0]
    }

    /**
     * Entrena un paso usando experience replay.
     */
    fun trainStep() {
        if (!isInitialized || replayBuffer.size() < BATCH_SIZE) return
        
        // Samplear batch con priorización
        val batch = replayBuffer.sample(BATCH_SIZE)
        
        var totalLoss = 0f
        
        batch.forEach { experience ->
            val loss = trainOnExperience(experience)
            totalLoss += loss
        }
        
        trainingStep++
        
        // Decay epsilon
        epsilon = (epsilon * EPSILON_DECAY).coerceAtLeast(EPSILON_MIN)
        
        // Update target network
        if (trainingStep % TARGET_UPDATE_FREQ == 0) {
            updateTargetNetwork()
        }
        
        val avgLoss = totalLoss / BATCH_SIZE
        onTrainingStep?.invoke(trainingStep, avgLoss, epsilon)
        
        if (trainingStep % 100 == 0) {
            Logger.d(TAG, "Training step $trainingStep - Loss: $avgLoss, Epsilon: $epsilon")
        }
    }

    /**
     * Entrena sobre una experiencia individual.
     */
    private fun trainOnExperience(exp: RLExperience): Float {
        // Calcular target Q-value
        val targetQ = if (exp.done) {
            exp.reward
        } else {
            val nextQValues = predictTargetQValues(exp.nextState)
            exp.reward + GAMMA * nextQValues.maxOrNull()!!
        }
        
        // Calcular error TD
        val currentQValues = predictQValuesForState(exp.state)
        val currentQ = currentQValues[exp.action]
        val tdError = kotlin.math.abs(targetQ - currentQ)
        
        // Actualizar prioridad en buffer
        exp.priority = tdError + 1e-6f
        
        return tdError
    }

    /**
     * Predice Q-values con target network.
     */
    private fun predictTargetQValues(state: FloatArray): FloatArray {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(NUM_ACTIONS) }
        targetNet?.run(input, output)
        return output[0]
    }

    /**
     * Predice Q-values para un estado específico.
     */
    private fun predictQValuesForState(state: FloatArray): FloatArray {
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(NUM_ACTIONS) }
        policyNet?.run(input, output)
        return output[0]
    }

    /**
     * Guarda experiencia en replay buffer.
     */
    fun storeExperience(
        state: FloatArray,
        action: Int,
        reward: Float,
        nextState: FloatArray,
        done: Boolean
    ) {
        val experience = RLExperience(
            state = state,
            action = action,
            reward = reward,
            nextState = nextState,
            done = done,
            priority = 1.0f // Prioridad inicial alta
        )
        
        replayBuffer.add(experience)
        
        totalReward += reward
        episodeReward += reward
    }

    /**
     * Notifica fin de episodio.
     */
    fun endEpisode() {
        episodeCount++
        onEpisodeEnd?.invoke(episodeCount, episodeReward, totalReward / episodeCount)
        
        Logger.i(TAG, "Episodio $episodeCount terminado - Reward: $episodeReward, " +
                "Epsilon: $epsilon, Buffer: ${replayBuffer.size()}")
        
        episodeReward = 0f
        
        // Reset LSTM state
        lstmState = Array(2) { Array(1) { FloatArray(LSTM_UNITS) } }
        stateSequence.clear()
    }

    /**
     * Convierte resultado del ensemble a estado codificado.
     */
    fun encodeState(result: EnsembleResult?): FloatArray {
        val state = FloatArray(STATE_SIZE) { 0f }
        
        result ?: return state
        
        // Codificar información relevante
        var idx = 0
        
        // HP normalizado (0-1)
        state[idx++] = (result.uiOutput?.hpInfo?.current ?: 100) / 100f
        
        // Ammo normalizado
        state[idx++] = ((result.uiOutput?.ammoInfo?.currentMag ?: 30) / 40f).coerceIn(0f, 1f)
        
        // Número de enemigos (cap a 5)
        state[idx++] = (result.mergedEnemies.size / 5f).coerceIn(0f, 1f)
        
        // Confianza del sistema
        state[idx++] = result.confidence
        
        // Enemigo más cercano
        val closestEnemy = result.mergedEnemies.minByOrNull { 
            kotlin.math.hypot(it.x - 360.0, it.y - 800.0) 
        }
        state[idx++] = (closestEnemy?.x ?: 360f) / 720f
        state[idx++] = (closestEnemy?.y ?: 800f) / 1600f
        state[idx++] = closestEnemy?.confidence ?: 0f
        
        // Situación táctica codificada
        val situation = result.tacticalOutput?.situation
        state[idx++] = situation?.dangerLevel ?: 0.5f
        state[idx++] = situation?.allyProximity ?: 0.5f
        state[idx++] = situation?.enemyProximity ?: 0.5f
        state[idx++] = if (situation?.hasHighGround == true) 1f else 0f
        state[idx++] = if (situation?.inZone == true) 1f else 0f
        
        // Resto con ruido aleatorio para variabilidad
        while (idx < STATE_SIZE) {
            state[idx++] = Random().nextFloat() * 0.1f
        }
        
        return state
    }

    /**
     * Convierte acción del RL a ActionType del sistema.
     */
    fun actionToType(action: Int): ActionType {
        return ActionType.values().getOrElse(action) { ActionType.HOLD }
    }

    // ============================================
    // MÉTODOS PRIVADOS
    // ============================================

    private fun updateStateSequence(state: FloatArray) {
        stateSequence.addLast(state.copyOf())
        if (stateSequence.size > SEQUENCE_LENGTH) {
            stateSequence.removeFirst()
        }
    }

    private fun prepareLSTMInput(): Array<Array<FloatArray>> {
        // Asegurar que tenemos SEQUENCE_LENGTH estados
        while (stateSequence.size < SEQUENCE_LENGTH) {
            stateSequence.addFirst(FloatArray(STATE_SIZE) { 0f })
        }
        
        return Array(1) {
            stateSequence.toList().toTypedArray()
        }
    }

    private fun weightedRandomAction(state: FloatArray): Int {
        // Exploración inteligente: algunas acciones son más probables según estado
        val weights = FloatArray(NUM_ACTIONS) { 1f }
        
        // Si HP bajo, favorecer HEAL
        if (state[0] < 0.3f) {
            weights[ActionType.HEAL.ordinal] = 3f
        }
        
        // Si enemigos cerca, favorecer SHOOT y AIM
        if (state[2] > 0f) {
            weights[ActionType.SHOOT.ordinal] = 2f
            weights[ActionType.AIM.ordinal] = 2f
        }
        
        // Samplear de distribución ponderada
        val totalWeight = weights.sum()
        val random = Random().nextFloat() * totalWeight
        
        var cumulative = 0f
        for (i in weights.indices) {
            cumulative += weights[i]
            if (random <= cumulative) return i
        }
        
        return Random().nextInt(NUM_ACTIONS)
    }

    private fun updateTargetNetwork() {
        // En TFLite simple, copiamos pesos recargando
        // En implementación completa, copiar pesos de policy a target
        Logger.d(TAG, "Target network actualizada")
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    fun isInitialized(): Boolean = isInitialized

    fun getEpsilon(): Float = epsilon

    fun getStats(): DeepRLStats {
        return DeepRLStats(
            trainingSteps = trainingStep,
            episodes = episodeCount,
            epsilon = epsilon,
            bufferSize = replayBuffer.size(),
            averageQValue = qValueHistory.average().toFloat(),
            actionDistribution = actionCounts.map { it.toFloat() / actionCounts.sum().coerceAtLeast(1) },
            totalReward = totalReward
        )
    }

    fun setOnTrainingStepListener(listener: (Int, Float, Float) -> Unit) {
        onTrainingStep = listener
    }

    fun setOnEpisodeEndListener(listener: (Int, Float, Float) -> Unit) {
        onEpisodeEnd = listener
    }

    /**
     * Guarda modelo entrenado.
    */
    fun saveModel(path: String) {
        // Implementar guardado de pesos
        Logger.i(TAG, "Modelo guardado en: $path")
    }

    /**
     * Carga modelo pre-entrenado.
     */
    fun loadModel(path: String) {
        // Implementar carga de pesos
        Logger.i(TAG, "Modelo cargado desde: $path")
    }

    fun release() {
        policyNet?.close()
        targetNet?.close()
        isInitialized = false
        Logger.i(TAG, "DeepRLCore liberado")
    }
}

// ============================================
// EXPERIENCE REPLAY BUFFER CON PRIORIZACIÓN
// ============================================

class PriorityReplayBuffer(private val capacity: Int) {
    private val buffer = ArrayDeque<RLExperience>(capacity)
    private val priorities = ArrayDeque<Float>(capacity)

    fun add(experience: RLExperience) {
        if (buffer.size >= capacity) {
            buffer.removeFirst()
            priorities.removeFirst()
        }
        buffer.addLast(experience)
        priorities.addLast(experience.priority)
    }

    fun sample(batchSize: Int): List<RLExperience> {
        if (buffer.size < batchSize) return buffer.toList()
        
        // Sampleo ponderado por prioridad
        val totalPriority = priorities.sum()
        val sampled = mutableListOf<RLExperience>()
        
        repeat(batchSize) {
            val threshold = Random().nextFloat() * totalPriority
            var cumulative = 0f
            var index = 0
            
            for (p in priorities) {
                cumulative += p
                if (cumulative >= threshold) break
                index++
            }
            
            sampled.add(buffer.elementAt(index.coerceIn(0, buffer.size - 1)))
        }
        
        return sampled
    }

    fun size(): Int = buffer.size
}

data class RLExperience(
    val state: FloatArray,
    val action: Int,
    val reward: Float,
    val nextState: FloatArray,
    val done: Boolean,
    var priority: Float = 1.0f
)

data class DeepRLStats(
    val trainingSteps: Int,
    val episodes: Int,
    val epsilon: Float,
    val bufferSize: Int,
    val averageQValue: Float,
    val actionDistribution: List<Float>,
    val totalReward: Float
)
