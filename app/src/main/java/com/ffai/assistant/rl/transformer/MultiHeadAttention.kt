package com.ffai.assistant.rl.transformer

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * MultiHeadAttention - Implementación de atención multi-cabeza.
 * 
 * Fórmula: Attention(Q, K, V) = softmax(QK^T / sqrt(d_k)) * V
 * MultiHead: concat(head_1, ..., head_h) * W^O
 * 
 * Usado en TransformerAgent para capturar dependencias temporales.
 */
class MultiHeadAttention(
    val embedDim: Int = 256,
    val numHeads: Int = 8,
    val dropout: Float = 0.1f
) {
    companion object {
        const val TAG = "MultiHeadAttention"
    }
    
    private val headDim = embedDim / numHeads
    private val scale = 1f / sqrt(headDim.toFloat())
    
    // Pesos (en implementación real vendrían del modelo TFLite)
    private var wq: Array<FloatArray>? = null
    private var wk: Array<FloatArray>? = null
    private var wv: Array<FloatArray>? = null
    private var wo: Array<FloatArray>? = null
    
    /**
     * Inicializa pesos con Xavier/Glorot.
     * En producción, estos pesos vienen del modelo entrenado.
     */
    fun initializeWeights() {
        wq = initializeMatrix(embedDim, embedDim)
        wk = initializeMatrix(embedDim, embedDim)
        wv = initializeMatrix(embedDim, embedDim)
        wo = initializeMatrix(embedDim, embedDim)
    }
    
    /**
     * Forward pass de multi-head attention.
     * 
     * @param query Query matrix [seq_len, embed_dim]
     * @param key Key matrix [seq_len, embed_dim]
     * @param value Value matrix [seq_len, embed_dim]
     * @return Output [seq_len, embed_dim] + attention weights
     */
    fun forward(
        query: Array<FloatArray>,
        key: Array<FloatArray>,
        value: Array<FloatArray>
    ): AttentionOutput {
        val seqLen = query.size
        
        // Proyecciones lineales
        val q = linearProjection(query, wq ?: return AttentionOutput.empty(seqLen, embedDim))
        val k = linearProjection(key, wk ?: return AttentionOutput.empty(seqLen, embedDim))
        val v = linearProjection(value, wv ?: return AttentionOutput.empty(seqLen, embedDim))
        
        // Split en múltiples cabezas
        val qHeads = splitHeads(q, numHeads)
        val kHeads = splitHeads(k, numHeads)
        val vHeads = splitHeads(v, numHeads)
        
        // Atención por cabeza
        val headOutputs = mutableListOf<HeadOutput>()
        
        for (h in 0 until numHeads) {
            val headResult = scaledDotProductAttention(qHeads[h], kHeads[h], vHeads[h])
            headOutputs.add(headResult)
        }
        
        // Concatenar cabezas
        val concatenated = concatenateHeads(headOutputs.map { it.output })
        
        // Proyección final
        val output = linearProjection(concatenated, wo ?: return AttentionOutput.empty(seqLen, embedDim))
        
        // Combinar attention weights de todas las cabezas (promedio)
        val combinedAttention = combineAttentionWeights(headOutputs.map { headOut -> headOut.attentionWeights })
        
        return AttentionOutput(
            output = output,
            attentionWeights = combinedAttention,
            headAttentions = headOutputs.map { headOut -> headOut.attentionWeights }
        )
    }
    
    /**
     * Self-attention: Q = K = V = input.
     */
    fun selfAttention(input: Array<FloatArray>): AttentionOutput {
        return forward(input, input, input)
    }
    
    /**
     * Calcula atención escalada.
     * attention = softmax(Q * K^T / sqrt(d_k))
     */
    private fun scaledDotProductAttention(
        q: Array<FloatArray>,
        k: Array<FloatArray>,
        v: Array<FloatArray>
    ): HeadOutput {
        val seqLen = q.size
        val headDim = q[0].size
        
        // Q * K^T
        val scores = Array(seqLen) { FloatArray(seqLen) }
        for (i in 0 until seqLen) {
            for (j in 0 until seqLen) {
                var dot = 0f
                for (d in 0 until headDim) {
                    dot += q[i][d] * k[j][d]
                }
                scores[i][j] = dot * scale
            }
        }
        
        // Softmax
        val attentionWeights = softmaxMatrix(scores)
        
        // Attention * V
        val output = Array(seqLen) { FloatArray(headDim) { 0f } }
        for (i in 0 until seqLen) {
            for (d in 0 until headDim) {
                var sum = 0f
                for (j in 0 until seqLen) {
                    sum += attentionWeights[i][j] * v[j][d]
                }
                output[i][d] = sum
            }
        }
        
        return HeadOutput(output, attentionWeights)
    }
    
    /**
     * Divide matriz en múltiples cabezas.
     */
    private fun splitHeads(x: Array<FloatArray>, numHeads: Int): List<Array<FloatArray>> {
        val seqLen = x.size
        val headDim = x[0].size / numHeads
        
        return (0 until numHeads).map { h ->
            Array(seqLen) { i ->
                FloatArray(headDim) { d ->
                    x[i][h * headDim + d]
                }
            }
        }
    }
    
    /**
     * Concatena salidas de múltiples cabezas.
     */
    private fun concatenateHeads(heads: List<Array<FloatArray>>): Array<FloatArray> {
        val seqLen = heads[0].size
        val headDim = heads[0][0].size
        
        return Array(seqLen) { i ->
            FloatArray(headDim * heads.size) { idx ->
                val h = idx / headDim
                val d = idx % headDim
                heads[h][i][d]
            }
        }
    }
    
    /**
     * Proyección lineal: out = input * W.
     */
    private fun linearProjection(input: Array<FloatArray>, weight: Array<FloatArray>): Array<FloatArray> {
        val seqLen = input.size
        val outDim = weight[0].size
        
        return Array(seqLen) { i ->
            FloatArray(outDim) { j ->
                var sum = 0f
                for (k in input[i].indices) {
                    sum += input[i][k] * weight[k][j]
                }
                sum
            }
        }
    }
    
    /**
     * Softmax sobre cada fila de la matriz.
     */
    private fun softmaxMatrix(scores: Array<FloatArray>): Array<FloatArray> {
        return scores.map { row ->
            val maxScore = row.maxOrNull() ?: 0f
            val expShifted = row.map { exp((it - maxScore).toFloat()) }
            val sumExp = expShifted.sum()
            expShifted.map { expVal -> (expVal / sumExp).toFloat() }.toFloatArray()
        }.toTypedArray()
    }
    
    /**
     * Combina attention weights de múltiples cabezas.
     */
    private fun combineAttentionWeights(attentions: List<Array<FloatArray>>): Array<FloatArray> {
        val seqLen = attentions[0].size
        
        return Array(seqLen) { i ->
            FloatArray(seqLen) { j ->
                attentions.map { it[i][j] }.average().toFloat()
            }
        }
    }
    
    /**
     * Inicializa matriz con Xavier initialization.
     */
    private fun initializeMatrix(rows: Int, cols: Int): Array<FloatArray> {
        val stddev = sqrt(2f / (rows + cols))
        return Array(rows) {
            FloatArray(cols) {
                (kotlin.random.Random.nextFloat() - 0.5f) * 2 * stddev
            }
        }
    }
    
    /**
     * Obtiene visualización de attention weights.
     */
    fun visualizeAttention(attentionWeights: Array<FloatArray>): AttentionVisualization {
        val seqLen = attentionWeights.size
        
        // Calcular importancia de cada posición
        val importance = attentionWeights.map { it.average() }
        
        // Encontrar máximos
        val maxIndices = importance.withIndex()
            .sortedByDescending { it.value }
            .take(5)
            .map { it.index }
        
        return AttentionVisualization(
            sequenceLength = seqLen,
            positionImportance = importance,
            mostAttendedPositions = maxIndices,
            attentionEntropy = calculateEntropy(attentionWeights)
        )
    }
    
    /**
     * Calcula entropía de attention (medida de "enfoque").
     * Baja entropía = atención enfocada.
     * Alta entropía = atención difusa.
     */
    private fun calculateEntropy(attention: Array<FloatArray>): Float {
        val flat = attention.flatten()
        return -flat.filter { it > 0 }.sumOf { p ->
            val prob = p.toDouble()
            prob * kotlin.math.ln(prob)
        }.toFloat()
    }
}

data class AttentionOutput(
    val output: Array<FloatArray>,
    val attentionWeights: Array<FloatArray>,
    val headAttentions: List<Array<FloatArray>>
) {
    companion object {
        fun empty(seqLen: Int, embedDim: Int) = AttentionOutput(
            Array(seqLen) { FloatArray(embedDim) { 0f } },
            Array(seqLen) { FloatArray(seqLen) { 0f } },
            emptyList()
        )
    }
}

data class HeadOutput(
    val output: Array<FloatArray>,
    val attentionWeights: Array<FloatArray>
)

data class AttentionVisualization(
    val sequenceLength: Int,
    val positionImportance: List<Float>,
    val mostAttendedPositions: List<Int>,
    val attentionEntropy: Float
)
