package com.ffai.assistant.rl.worldmodel

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * WorldModel - RSSM (Recurrent State-Space Model).
 * Predice estados futuros y rewards para planificación.
 */
class WorldModel(private val context: Context) {
    companion object {
        const val TAG = "WorldModel"
        const val ENCODER_MODEL = "world_model_encoder.tflite"
        const val TRANSITION_MODEL = "world_model_transition.tflite"
        const val REWARD_MODEL = "world_model_reward.tflite"
        const val LATENT_DIM = 1024
        const val STOCHASTIC_DIM = 32
        const val DETERMINISTIC_DIM = 256
        const val ACTION_DIM = 15
    }

    private var encoderNet: Interpreter? = null
    private var transitionNet: Interpreter? = null
    private var rewardNet: Interpreter? = null
    private var isInitialized = false
    private var deterministicState = FloatArray(DETERMINISTIC_DIM) { 0f }
    private var stochasticState = FloatArray(STOCHASTIC_DIM) { 0f }
    private var predictionCount = 0
    private val errorHistory = ArrayDeque<Float>(100)

    fun initialize(): Boolean = try {
        encoderNet = loadModel(ENCODER_MODEL)
        transitionNet = loadModel(TRANSITION_MODEL)
        rewardNet = loadModel("world_model_reward.tflite")
        isInitialized = true
        Logger.i(TAG, "WorldModel initialized")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing WorldModel", e)
        false
    }

    fun encodeObservation(bitmap: Bitmap): LatentState {
        if (!isInitialized) return LatentState.empty()
        val inputBuffer = preprocessBitmap(bitmap)
        val input = Array(1) { inputBuffer }
        val output = Array(1) { FloatArray(STOCHASTIC_DIM) }
        encoderNet?.run(input, output)
        stochasticState = output[0].copyOf()
        return LatentState(stochasticState.copyOf(), deterministicState.copyOf(), deterministicState.copyOf())
    }

    fun predictNextState(action: Int): PredictedState {
        if (!isInitialized) return PredictedState.empty()
        val actionOneHot = FloatArray(ACTION_DIM) { 0f }.apply { if (action in 0 until ACTION_DIM) this[action] = 1f }
        val combined = deterministicState + stochasticState + actionOneHot
        val input = Array(1) { combined }
        val output = Array(1) { FloatArray(DETERMINISTIC_DIM + STOCHASTIC_DIM + 2) }
        transitionNet?.run(input, output)
        val result = output[0]
        deterministicState = result.sliceArray(0 until DETERMINISTIC_DIM)
        stochasticState = result.sliceArray(DETERMINISTIC_DIM until DETERMINISTIC_DIM + STOCHASTIC_DIM)
        predictionCount++
        return PredictedState(
            LatentState(stochasticState.copyOf(), deterministicState.copyOf(), deterministicState.copyOf()),
            result[DETERMINISTIC_DIM + STOCHASTIC_DIM],
            sigmoid(result[DETERMINISTIC_DIM + STOCHASTIC_DIM + 1])
        )
    }

    fun imagineTrajectory(initialState: LatentState, actions: List<Int>): ImaginedTrajectory {
        deterministicState = initialState.deterministic.copyOf()
        stochasticState = initialState.stochastic.copyOf()
        val states = mutableListOf(initialState)
        val rewards = mutableListOf<Float>()
        val terminals = mutableListOf<Float>()
        for (action in actions) {
            val pred = predictNextState(action)
            states.add(pred.latent)
            rewards.add(pred.reward)
            terminals.add(pred.terminalProb)
            if (pred.terminalProb > 0.9f) break
        }
        return ImaginedTrajectory(states, rewards, terminals)
    }

    fun planActionCEM(currentState: LatentState, horizon: Int = 15, numTrajectories: Int = 50): CEMResult {
        val trajectories = (0 until numTrajectories).map {
            val actions = List(horizon) { (0 until ACTION_DIM).random() }
            val traj = imagineTrajectory(currentState, actions)
            actions to traj.calculateValue()
        }
        val best = trajectories.maxByOrNull { it.second } ?: (listOf(0) to 0f)
        return CEMResult(best.first.firstOrNull() ?: 0, best.second, floatArrayOf())
    }

    fun resetState() {
        deterministicState = FloatArray(DETERMINISTIC_DIM) { 0f }
        stochasticState = FloatArray(STOCHASTIC_DIM) { 0f }
    }

    fun getStats() = WorldModelStats(predictionCount, if (errorHistory.isNotEmpty()) errorHistory.average().toFloat() else 0f, STOCHASTIC_DIM, DETERMINISTIC_DIM)

    fun release() {
        encoderNet?.close(); transitionNet?.close(); rewardNet?.close(); isInitialized = false
    }

    private fun loadModel(name: String): Interpreter? = try {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val channel = fis.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
        }
    } catch (e: Exception) { null }

    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, 160, 96, true)
        val pixels = IntArray(160 * 96)
        resized.getPixels(pixels, 0, 160, 0, 0, 160, 96)
        return FloatArray(160 * 96 * 3) { i ->
            val p = pixels[i / 3]
            when (i % 3) {
                0 -> ((p shr 16) and 0xFF) / 255f
                1 -> ((p shr 8) and 0xFF) / 255f
                else -> (p and 0xFF) / 255f
            }
        }
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
}

data class LatentState(val stochastic: FloatArray, val deterministic: FloatArray, val hidden: FloatArray) {
    companion object {
        fun empty() = LatentState(floatArrayOf(), floatArrayOf(), floatArrayOf())
    }
}

data class PredictedState(val latent: LatentState, val reward: Float, val terminalProb: Float) {
    companion object {
        fun empty() = PredictedState(LatentState.empty(), 0f, 0f)
    }
}

data class ImaginedTrajectory(val states: List<LatentState>, val rewards: List<Float>, val terminals: List<Float>) {
    fun calculateValue(gamma: Float = 0.99f): Float {
        var value = 0f
        var discount = 1f
        for (i in rewards.indices) {
            value += discount * rewards[i] * (1 - terminals.getOrElse(i) { 0f })
            discount *= gamma
        }
        return value
    }
}

data class CEMResult(val bestAction: Int, val expectedValue: Float, val actionDistribution: FloatArray)
data class WorldModelStats(val totalPredictions: Int, val avgError: Float, val stochDim: Int, val detDim: Int)
