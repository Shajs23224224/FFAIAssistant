package com.ffai.assistant.rl.curiosity

import android.content.Context
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.exp
import kotlin.math.pow

/**
 * ICMModule - Intrinsic Curiosity Module (Pathak et al., 2017).
 * 
 * Genera recompensas intrínsecas basadas en la novedad de las transiciones.
 * Formula: r_t^i = ||φ(s_{t+1}) - φ(s_t)||^2
 * 
 * Componentes:
 * - Forward model: predice próximo estado dado φ(s_t) y a_t
 * - Inverse model: predice acción dado φ(s_t) y φ(s_{t+1})
 * - Feature extractor: codifica estados a espacio de features
 */
class ICMModule(private val context: Context) {
    
    companion object {
        const val TAG = "ICMModule"
        const val FORWARD_MODEL = "icm_forward.tflite"
        const val INVERSE_MODEL = "icm_inverse.tflite"
        const val FEATURE_MODEL = "icm_feature.tflite"
        
        // Dimensions
        const val STATE_DIM = 256
        const val FEATURE_DIM = 512
        const val ACTION_DIM = 15
        
        // Hyperparameters
        const val FORWARD_LOSS_WEIGHT = 0.5f
        const val INVERSE_LOSS_WEIGHT = 0.5f
        const val MAX_INTRINSIC_REWARD = 1.0f
    }
    
    // Networks
    private var forwardNet: Interpreter? = null
    private var inverseNet: Interpreter? = null
    private var featureNet: Interpreter? = null
    private var isInitialized = false
    
    // Feature history para comparación
    private val featureHistory = ArrayDeque<FloatArray>(100)
    
    // Estadísticas
    private var forwardLoss = 0f
    private var inverseLoss = 0f
    private var totalIntrinsicReward = 0f
    private var curiosityCalls = 0
    
    /**
     * Inicializa modelos ICM.
     */
    fun initialize(): Boolean = try {
        forwardNet = loadModel(FORWARD_MODEL)
        inverseNet = loadModel(INVERSE_MODEL)
        featureNet = loadModel(FEATURE_MODEL)
        isInitialized = true
        Logger.i(TAG, "ICM initialized - feature_dim=$FEATURE_DIM")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing ICM", e)
        false
    }
    
    /**
     * Extrae features de estado.
     * φ(s) - codificación comprimida del estado.
     */
    fun extractFeatures(state: FloatArray): FloatArray {
        if (!isInitialized || featureNet == null) {
            return state.sliceArray(0 until minOf(state.size, FEATURE_DIM))
        }
        
        val input = Array(1) { state }
        val output = Array(1) { FloatArray(FEATURE_DIM) }
        
        featureNet?.run(input, output)
        return output[0]
    }
    
    /**
     * Forward model: predice φ(s_{t+1}) dado φ(s_t) y a_t.
     * L_F = 1/2 ||φ(s_{t+1}) - f(φ(s_t), a_t)||^2
     */
    fun forwardModel(currentFeatures: FloatArray, action: Int): ForwardResult {
        if (!isInitialized || forwardNet == null) {
            return ForwardResult(currentFeatures, 0f, 0f)
        }
        
        val actionOneHot = FloatArray(ACTION_DIM) { 0f }.apply { if (action in 0..14) this[action] = 1f }
        val input = Array(1) { currentFeatures + actionOneHot }
        val output = Array(1) { FloatArray(FEATURE_DIM) }
        
        forwardNet?.run(input, output)
        
        return ForwardResult(
            predictedNextFeatures = output[0],
            confidence = 0.5f,
            lossEstimate = 0f
        )
    }
    
    /**
     * Inverse model: predice a_t dado φ(s_t) y φ(s_{t+1}).
     * L_I = -log(p(a_t | φ(s_t), φ(s_{t+1})))
     */
    fun inverseModel(currentFeatures: FloatArray, nextFeatures: FloatArray): InverseResult {
        if (!isInitialized || inverseNet == null) {
            return InverseResult(0, FloatArray(ACTION_DIM) { 1f / ACTION_DIM }, 0f)
        }
        
        val input = Array(1) { currentFeatures + nextFeatures }
        val output = Array(1) { FloatArray(ACTION_DIM) }
        
        inverseNet?.run(input, output)
        
        val probs = softmax(output[0])
        val predictedAction = probs.indices.maxByOrNull { probs[it] } ?: 0
        val entropy = calculateEntropy(probs)
        
        return InverseResult(predictedAction, probs, entropy)
    }
    
    /**
     * Calcula recompensa intrínseca (curiosity).
     * r^i = η * ||φ(s_{t+1}) - φ_hat(s_{t+1})||^2
     */
    fun computeIntrinsicReward(
        state: FloatArray,
        action: Int,
        nextState: FloatArray,
        scale: Float = 1.0f
    ): IntrinsicRewardResult {
        curiosityCalls++
        
        // Extraer features
        val currentFeatures = extractFeatures(state)
        val nextFeatures = extractFeatures(nextState)
        
        // Forward prediction
        val forwardResult = forwardModel(currentFeatures, action)
        
        // Calcular error de predicción (sorpresa)
        val predictionError = calculateMSE(nextFeatures, forwardResult.predictedNextFeatures)
        
        // Inverse prediction
        val inverseResult = inverseModel(currentFeatures, nextFeatures)
        
        // Calcular recompensa intrínseca
        val intrinsicReward = (predictionError * scale).coerceIn(0f, MAX_INTRINSIC_REWARD)
        
        // Actualizar estadísticas
        forwardLoss = (forwardLoss * 0.99f) + (predictionError * 0.01f)
        inverseLoss = (inverseLoss * 0.99f) + (inverseResult.entropy * 0.01f)
        totalIntrinsicReward += intrinsicReward
        
        // Guardar feature
        featureHistory.add(currentFeatures)
        if (featureHistory.size > 100) featureHistory.removeFirst()
        
        return IntrinsicRewardResult(
            intrinsicReward = intrinsicReward,
            forwardError = predictionError,
            inverseEntropy = inverseResult.entropy,
            predictedAction = inverseResult.predictedAction,
            actualAction = action,
            featureDistance = calculateDistance(currentFeatures, nextFeatures)
        )
    }
    
    /**
     * Calcula sorpresa episódica basada en historial de features.
     */
    fun computeEpisodicSurprise(features: FloatArray): Float {
        if (featureHistory.isEmpty()) return 1.0f
        
        val distances = featureHistory.map { calculateDistance(features, it) }
        val minDistance = distances.minOrNull() ?: 1.0f
        
        // Sorpresa inversamente proporcional a similitud con experiencias previas
        return (1.0f - exp(-minDistance * 5)).coerceIn(0f, 1f)
    }
    
    /**
     * Detecta estados novedosos.
     */
    fun isNovelState(features: FloatArray, threshold: Float = 0.7f): Boolean {
        return computeEpisodicSurprise(features) > threshold
    }
    
    /**
     * Entrena ICM (placeholder - en implementación real haría backprop).
     */
    fun trainStep(state: FloatArray, action: Int, nextState: FloatArray) {
        val result = computeIntrinsicReward(state, action, nextState, 1.0f)
        
        // En implementación real, actualizaría pesos de forward/inverse models
        // basándose en los errores calculados
        Logger.d(TAG, "ICM train step - forward_loss=${result.forwardError}, " +
                      "inverse_accuracy=${result.predictedAction == action}")
    }
    
    /**
     * Reset episódico.
     */
    fun resetEpisode() {
        featureHistory.clear()
        Logger.d(TAG, "ICM episode reset")
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): ICMStats {
        return ICMStats(
            forwardLoss = forwardLoss,
            inverseLoss = inverseLoss,
            avgIntrinsicReward = if (curiosityCalls > 0) totalIntrinsicReward / curiosityCalls else 0f,
            featureHistorySize = featureHistory.size,
            totalCalls = curiosityCalls,
            featureDim = FEATURE_DIM
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        forwardNet?.close()
        inverseNet?.close()
        featureNet?.close()
        isInitialized = false
        Logger.i(TAG, "ICM released")
    }
    
    // ============================================
    // UTILIDADES
    // ============================================
    
    private fun calculateMSE(a: FloatArray, b: FloatArray): Float {
        return a.zip(b).map { (x, y) -> (x - y).pow(2) }.average().toFloat()
    }
    
    private fun calculateDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return kotlin.math.sqrt(sum)
    }
    
    private fun softmax(x: FloatArray): FloatArray {
        val exp = x.map { exp(it) }
        val sum = exp.sum()
        return exp.map { it / sum }.toFloatArray()
    }
    
    private fun calculateEntropy(probs: FloatArray): Float {
        var sum = 0f
        for (p in probs) {
            if (p > 0) {
                sum += p * kotlin.math.ln(p)
            }
        }
        return -sum
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

data class ForwardResult(
    val predictedNextFeatures: FloatArray,
    val confidence: Float,
    val lossEstimate: Float
)

data class InverseResult(
    val predictedAction: Int,
    val actionProbabilities: FloatArray,
    val entropy: Float
)

data class IntrinsicRewardResult(
    val intrinsicReward: Float,
    val forwardError: Float,
    val inverseEntropy: Float,
    val predictedAction: Int,
    val actualAction: Int,
    val featureDistance: Float
) {
    fun inverseAccuracy(): Float = if (predictedAction == actualAction) 1f else 0f
}

data class ICMStats(
    val forwardLoss: Float,
    val inverseLoss: Float,
    val avgIntrinsicReward: Float,
    val featureHistorySize: Int,
    val totalCalls: Int,
    val featureDim: Int
)
