package com.ffai.assistant.rl.transformer

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * PositionalEncoding - Codificación posicional para Transformers.
 * 
 * Fórmulas (Vaswani et al. 2017):
 * PE(pos, 2i) = sin(pos / 10000^(2i/d_model))
 * PE(pos, 2i+1) = cos(pos / 10000^(2i/d_model))
 * 
 * Permite al Transformer distinguir posición temporal.
 */
class PositionalEncoding(
    val maxSequenceLength: Int = 64,
    val embedDim: Int = 256
) {
    companion object {
        const val BASE = 10000.0
    }
    
    // Pre-computar codificaciones posicionales
    private val encodingMatrix: Array<FloatArray> = computeEncodings()
    
    /**
     * Computa matriz de encodings posicionales.
     */
    private fun computeEncodings(): Array<FloatArray> {
        return Array(maxSequenceLength) { pos ->
            FloatArray(embedDim) { i ->
                val angle = pos / BASE.pow((2 * (i / 2)) / embedDim.toDouble())
                if (i % 2 == 0) sin(angle).toFloat()
                else cos(angle).toFloat()
            }
        }
    }
    
    /**
     * Añade encoding posicional a embeddings.
     * output = embeddings + positional_encoding
     * 
     * @param embeddings [seq_len, embed_dim]
     * @return embeddings con posicional encoding
     */
    fun addPositionalEncoding(embeddings: Array<FloatArray>): Array<FloatArray> {
        val seqLen = minOf(embeddings.size, maxSequenceLength)
        
        return Array(seqLen) { i ->
            FloatArray(embedDim) { j ->
                embeddings[i][j] + encodingMatrix[i][j]
            }
        }
    }
    
    /**
     * Obtiene encoding para posición específica.
     */
    fun getEncoding(position: Int): FloatArray {
        val pos = position.coerceIn(0, maxSequenceLength - 1)
        return encodingMatrix[pos].copyOf()
    }
    
    /**
     * Obtiene matriz completa de encodings.
     */
    fun getAllEncodings(): Array<FloatArray> {
        return encodingMatrix.map { it.copyOf() }.toTypedArray()
    }
    
    /**
     * Crea embeddings posicionales aprendidos (alternativa a sinusoidal).
     * Inicialización aleatoria, aprendido durante entrenamiento.
     */
    fun createLearnedEmbeddings(): LearnedPositionalEmbeddings {
        val embeddings = Array(maxSequenceLength) {
            FloatArray(embedDim) { 
                (kotlin.random.Random.nextFloat() - 0.5f) * 0.02f
            }
        }
        
        return LearnedPositionalEmbeddings(embeddings)
    }
    
    /**
     * Visualiza patrones de encoding.
     */
    fun visualize(): PositionalEncodingVisualization {
        val heatmap = Array(maxSequenceLength) { pos ->
            FloatArray(embedDim) { dim ->
                encodingMatrix[pos][dim]
            }
        }
        
        // Calcular periodos
        val periods = (0 until embedDim step 2).map { i ->
            (2 * kotlin.math.PI * BASE.pow((2 * i) / embedDim.toDouble())).toFloat()
        }
        
        return PositionalEncodingVisualization(
            maxSequenceLength = maxSequenceLength,
            embedDim = embedDim,
            heatmap = heatmap,
            wavelengthPeriods = periods
        )
    }
    
    /**
     * Codificación posicional relativa (alternativa para long sequences).
     * Usa embeddings relativos en lugar de absolutos.
     */
    fun relativePositionalEncoding(maxRelativeDistance: Int = 32): RelativePositionalEncoding {
        val embeddings = Array(2 * maxRelativeDistance + 1) { relPos ->
            FloatArray(embedDim) { dim ->
                val actualPos = (relPos - maxRelativeDistance).toDouble()
                val angle = actualPos / BASE.pow((2 * (dim / 2)) / embedDim.toDouble())
                if (dim % 2 == 0) sin(angle).toFloat()
                else cos(angle).toFloat()
            }
        }
        
        return RelativePositionalEncoding(
            maxDistance = maxRelativeDistance,
            embeddings = embeddings
        )
    }
    
    /**
     * Codificación temporal para game frames.
     * Añade información de tiempo real (ms desde inicio).
     */
    fun temporalEncoding(timeMs: Long, fps: Float = 30f): FloatArray {
        val frameIndex = (timeMs / (1000f / fps)).toInt()
        return getEncoding(frameIndex % maxSequenceLength)
    }
    
    /**
     * Codificación de frecuencia rotativa (RoPE).
     * Más eficiente para atención.
     */
    fun rotaryPositionalEncoding(q: FloatArray, k: FloatArray, position: Int): Pair<FloatArray, FloatArray> {
        val rotatedQ = FloatArray(q.size)
        val rotatedK = FloatArray(k.size)
        
        for (i in q.indices step 2) {
            val theta = position / BASE.pow((2 * i) / embedDim.toDouble())
            val cosTheta = cos(theta).toFloat()
            val sinTheta = sin(theta).toFloat()
            
            if (i + 1 < q.size) {
                // Rotar Q
                rotatedQ[i] = q[i] * cosTheta - q[i + 1] * sinTheta
                rotatedQ[i + 1] = q[i] * sinTheta + q[i + 1] * cosTheta
                
                // Rotar K
                rotatedK[i] = k[i] * cosTheta - k[i + 1] * sinTheta
                rotatedK[i + 1] = k[i] * sinTheta + k[i + 1] * cosTheta
            }
        }
        
        return rotatedQ to rotatedK
    }
}

/**
 * Embeddings posicionales aprendidos.
 */
class LearnedPositionalEmbeddings(private val embeddings: Array<FloatArray>) {
    fun getEmbedding(position: Int): FloatArray {
        val pos = position.coerceIn(0, embeddings.size - 1)
        return embeddings[pos].copyOf()
    }
    
    fun applyToInput(input: Array<FloatArray>): Array<FloatArray> {
        return Array(input.size) { i ->
            val posEmbed = getEmbedding(i)
            FloatArray(input[i].size) { j ->
                input[i][j] + posEmbed.getOrElse(j) { 0f }
            }
        }
    }
}

/**
 * Codificación posicional relativa.
 */
data class RelativePositionalEncoding(
    val maxDistance: Int,
    val embeddings: Array<FloatArray>
) {
    fun getEmbedding(relativePosition: Int): FloatArray {
        val index = (relativePosition + maxDistance).coerceIn(0, embeddings.size - 1)
        return embeddings[index].copyOf()
    }
}

/**
 * Visualización de encoding posicional.
 */
data class PositionalEncodingVisualization(
    val maxSequenceLength: Int,
    val embedDim: Int,
    val heatmap: Array<FloatArray>,
    val wavelengthPeriods: List<Float>
)

/**
 * Fábrica de codificaciones posicionales.
 */
object PositionalEncodingFactory {
    
    fun createSinusoidal(maxSeqLen: Int = 64, embedDim: Int = 256): PositionalEncoding {
        return PositionalEncoding(maxSeqLen, embedDim)
    }
    
    fun createLearned(maxSeqLen: Int = 64, embedDim: Int = 256): LearnedPositionalEmbeddings {
        return PositionalEncoding(maxSeqLen, embedDim).createLearnedEmbeddings()
    }
    
    fun createRelative(maxSeqLen: Int = 64, embedDim: Int = 256, maxRelDist: Int = 32): RelativePositionalEncoding {
        return PositionalEncoding(maxSeqLen, embedDim).relativePositionalEncoding(maxRelDist)
    }
    
    /**
     * Codificación escalada por tiempo (para games).
     * Ajusta posición basándose en tiempo real.
     */
    fun createTimeScaled(maxSeqLen: Int = 64, embedDim: Int = 256, timeScale: Float = 1f): TimeScaledEncoding {
        val baseEncoding = PositionalEncoding(maxSeqLen, embedDim)
        return TimeScaledEncoding(baseEncoding, timeScale)
    }
}

/**
 * Codificación con escalado temporal.
 */
class TimeScaledEncoding(
    private val baseEncoding: PositionalEncoding,
    private val timeScale: Float
) {
    fun encodeAtTime(timeMs: Float): FloatArray {
        val scaledPosition = (timeMs * timeScale).toInt()
        return baseEncoding.getEncoding(scaledPosition)
    }
    
    fun applyToSequence(embeddings: Array<FloatArray>, startTimeMs: Float): Array<FloatArray> {
        val frameTime = 1000f / 30f // Assuming 30 FPS
        
        return Array(embeddings.size) { i ->
            val time = startTimeMs + i * frameTime
            val posEncoding = encodeAtTime(time)
            
            FloatArray(embeddings[i].size) { j ->
                embeddings[i][j] + posEncoding.getOrElse(j) { 0f }
            }
        }
    }
}
