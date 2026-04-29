package com.ffai.assistant.rl.transformer

import android.content.Context
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.sqrt

/**
 * TransformerAgent - Agente RL basado en Transformers.
 * 
 * Usa multi-head self-attention para procesar secuencias de estados.
 * Ventajas sobre LSTM:
 * - Paralelización completa
 * - Long-range dependencies
 * - Attention interpretable
 * 
 * Arquitectura:
 * Input: [s_{t-n}, ..., s_t] (sequence de estados)
 * → Embeddings + Positional Encoding
 * → Multi-Head Self-Attention (4 layers)
 * → Feed-Forward
 * → Policy + Value heads
 */
class TransformerAgent(private val context: Context) {
    
    companion object {
        const val TAG = "TransformerAgent"
        const val MODEL_NAME = "transformer_policy.tflite"
        
        // Transformer config
        const val SEQUENCE_LENGTH = 64
        const val STATE_DIM = 256
        const val EMBEDDING_DIM = 256
        const val NUM_HEADS = 8
        const val NUM_LAYERS = 4
        const val NUM_ACTIONS = 15
        
        // FF config
        const val FF_HIDDEN_DIM = 512
        const val DROPOUT_RATE = 0.1f
    }
    
    private var transformerNet: Interpreter? = null
    private var isInitialized = false
    private var lastSmoothedPolicy = FloatArray(NUM_ACTIONS) { 1f / NUM_ACTIONS }
    
    // State sequence buffer
    private val stateSequence = ArrayDeque<FloatArray>(SEQUENCE_LENGTH)
    
    // Attention history (para interpretability)
    private val attentionHistory = ArrayDeque<AttentionWeights>(100)
    
    /**
     * Inicializa el modelo Transformer.
     */
    fun initialize(): Boolean = try {
        transformerNet = loadModel(MODEL_NAME)
        isInitialized = true
        Logger.i(TAG, "TransformerAgent initialized: $NUM_LAYERS layers, $NUM_HEADS heads")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing TransformerAgent", e)
        false
    }
    
    /**
     * Procesa nuevo estado y selecciona acción.
     * Mantiene secuencia de estados para contexto temporal.
     */
    fun selectAction(state: FloatArray): TransformerAction {
        // Actualizar secuencia
        updateSequence(state)
        
        // Preparar input: [seq_len, state_dim]
        val sequenceInput = prepareSequenceInput()
        
        // Ejecutar inferencia
        val result = runInference(sequenceInput)
        
        // Extraer acción y valor
        val actionProbs = smoothPolicy(result.sliceArray(0 until NUM_ACTIONS))
        val value = result[NUM_ACTIONS]
        
        // Samplear acción
        val action = selectStableAction(actionProbs)
        val confidence = actionProbs.getOrElse(action) { 0.5f }
        
        return TransformerAction(
            action = action,
            value = value,
            actionProbabilities = actionProbs.copyOf(),
            attentionWeights = null, // En TFLite no extraemos attention weights directamente
            confidence = confidence
        )
    }
    
    /**
     * Procesa secuencia completa (batch).
     */
    fun processSequence(states: List<FloatArray>): SequenceResult {
        if (states.isEmpty()) return SequenceResult.empty()
        
        // Update internal sequence
        states.forEach { updateSequence(it) }
        
        val sequenceInput = prepareSequenceInput()
        val result = runInference(sequenceInput)
        
        val actionProbs = result.sliceArray(0 until NUM_ACTIONS)
        val value = result[NUM_ACTIONS]
        val action = actionProbs.indices.maxByOrNull { actionProbs[it] } ?: 0
        
        return SequenceResult(
            finalAction = action,
            finalValue = value,
            actionSequence = List(states.size) { action },
            valueSequence = List(states.size) { value }
        )
    }
    
    /**
     * Calcula atención sobre secuencia (simulado).
     * En implementación real, extraería attention weights del modelo.
     */
    fun computeAttentionMap(): AttentionMap? {
        if (stateSequence.isEmpty()) return null
        
        // Simular attention uniforme para placeholder
        val seqLen = stateSequence.size
        val attentionMatrix = Array(seqLen) { FloatArray(seqLen) { 1f / seqLen } }
        
        return AttentionMap(
            sequenceLength = seqLen,
            attentionWeights = attentionMatrix,
            mostAttendedIndices = (0 until seqLen).toList()
        )
    }
    
    /**
     * Obtiene importancia temporal (qué frames importan más).
     */
    fun getTemporalImportance(): List<Float> {
        val attentionMap = computeAttentionMap() ?: return emptyList()
        
        // Sumar attention por posición
        return attentionMap.attentionWeights.map { row -> row.average().toFloat() }
    }
    
    /**
     * Detecta patrones temporales importantes.
     */
    fun detectImportantPatterns(): List<TemporalPattern> {
        if (stateSequence.size < 10) return emptyList()
        
        val patterns = mutableListOf<TemporalPattern>()
        val importance = getTemporalImportance()
        
        // Detectar picos de atención
        for (i in 1 until importance.size - 1) {
            if (importance[i] > importance[i-1] && importance[i] > importance[i+1] && importance[i] > 0.5f) {
                patterns.add(TemporalPattern(
                    type = PatternType.PEAK_ATTENTION,
                    startIndex = maxOf(0, i - 2),
                    endIndex = minOf(importance.size - 1, i + 2),
                    importance = importance[i],
                    description = "High attention at frame $i"
                ))
            }
        }
        
        return patterns
    }
    
    /**
     * Reset secuencia (nuevo episodio).
     */
    fun resetSequence() {
        stateSequence.clear()
        attentionHistory.clear()
        Logger.d(TAG, "Sequence reset")
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): TransformerStats {
        return TransformerStats(
            sequenceLength = stateSequence.size,
            maxSequenceLength = SEQUENCE_LENGTH,
            attentionHistorySize = attentionHistory.size,
            embeddingDim = EMBEDDING_DIM,
            numHeads = NUM_HEADS,
            numLayers = NUM_LAYERS
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        transformerNet?.close()
        isInitialized = false
        stateSequence.clear()
        Logger.i(TAG, "TransformerAgent released")
    }
    
    // ============================================
    // MÉTODOS PRIVADOS
    // ============================================
    
    private fun updateSequence(state: FloatArray) {
        stateSequence.addLast(projectState(state))
        if (stateSequence.size > SEQUENCE_LENGTH) {
            stateSequence.removeFirst()
        }
    }
    
    private fun prepareSequenceInput(): Array<FloatArray> {
        // Pad si es necesario
        while (stateSequence.size < SEQUENCE_LENGTH) {
            stateSequence.addFirst(FloatArray(STATE_DIM) { 0f })
        }
        
        return stateSequence.toList().toTypedArray()
    }

    private fun projectState(state: FloatArray): FloatArray {
        if (state.size == STATE_DIM) return state.copyOf()

        val projected = FloatArray(STATE_DIM) { 0f }
        val chunkSize = kotlin.math.ceil(STATE_DIM / state.size.toFloat()).toInt().coerceAtLeast(1)
        for (i in projected.indices) {
            val sourceIdx = (i / chunkSize).coerceAtMost(state.size - 1)
            projected[i] = state[sourceIdx]
        }

        // Resumen global extra para robustez temporal
        val avg = if (state.isNotEmpty()) state.average().toFloat() else 0f
        val max = state.maxOrNull() ?: 0f
        val min = state.minOrNull() ?: 0f
        projected[STATE_DIM - 3] = avg
        projected[STATE_DIM - 2] = max
        projected[STATE_DIM - 1] = min
        return projected
    }
    
    private fun runInference(sequence: Array<FloatArray>): FloatArray {
        if (!isInitialized || transformerNet == null) {
            return FloatArray(NUM_ACTIONS + 1) { 0f }
        }
        
        return try {
            val input = Array(1) { sequence }
            val output = Array(1) { FloatArray(NUM_ACTIONS + 1) }
            
            transformerNet?.run(input, output)
            output[0]
        } catch (e: Exception) {
            Logger.e(TAG, "Error en runInference", e)
            FloatArray(NUM_ACTIONS + 1) { 0f } // Valores por defecto seguros
        }
    }
    
    private fun sampleAction(probs: FloatArray): Int {
        val random = kotlin.random.Random.nextFloat()
        var cumulative = 0f
        
        for (i in probs.indices) {
            cumulative += probs[i]
            if (random <= cumulative) return i
        }
        
        return probs.indices.maxByOrNull { probs[it] } ?: 0
    }

    private fun selectStableAction(probs: FloatArray): Int {
        val bestIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val bestProb = probs[bestIdx]
        return if (bestProb >= 0.58f) bestIdx else sampleAction(probs)
    }

    private fun smoothPolicy(current: FloatArray): FloatArray {
        val normalized = normalize(current)
        val smoothed = FloatArray(NUM_ACTIONS)
        var total = 0f
        for (i in 0 until NUM_ACTIONS) {
            smoothed[i] = normalized[i] * 0.7f + lastSmoothedPolicy[i] * 0.3f
            total += smoothed[i]
        }
        if (total > 0f) {
            for (i in smoothed.indices) smoothed[i] /= total
        }
        lastSmoothedPolicy = smoothed.copyOf()
        return smoothed
    }

    private fun normalize(values: FloatArray): FloatArray {
        val positives = values.map { it.coerceAtLeast(0f) }.toFloatArray()
        val sum = positives.sum()
        return if (sum > 0f) {
            FloatArray(positives.size) { idx -> positives[idx] / sum }
        } else {
            FloatArray(values.size) { 1f / values.size }
        }
    }
    
    private fun loadModel(name: String): Interpreter? = try {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val channel = fis.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
        }
    } catch (e: Exception) { null }
}

data class TransformerAction(
    val action: Int,
    val value: Float,
    val actionProbabilities: FloatArray,
    val attentionWeights: AttentionWeights?,
    val confidence: Float = 0.5f
)

data class AttentionWeights(
    val query: FloatArray,
    val key: FloatArray,
    val value: FloatArray,
    val attentionMatrix: Array<FloatArray>
)

data class SequenceResult(
    val finalAction: Int,
    val finalValue: Float,
    val actionSequence: List<Int>,
    val valueSequence: List<Float>
) {
    companion object {
        fun empty() = SequenceResult(0, 0f, emptyList(), emptyList())
    }
}

data class AttentionMap(
    val sequenceLength: Int,
    val attentionWeights: Array<FloatArray>,
    val mostAttendedIndices: List<Int>
)

data class TemporalPattern(
    val type: PatternType,
    val startIndex: Int,
    val endIndex: Int,
    val importance: Float,
    val description: String
)

enum class PatternType {
    PEAK_ATTENTION,
    STATE_TRANSITION,
    ACTION_SEQUENCE,
    REWARD_SPIKE
}

data class TransformerStats(
    val sequenceLength: Int,
    val maxSequenceLength: Int,
    val attentionHistorySize: Int,
    val embeddingDim: Int,
    val numHeads: Int,
    val numLayers: Int
)
