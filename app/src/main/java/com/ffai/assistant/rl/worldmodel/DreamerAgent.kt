package com.ffai.assistant.rl.worldmodel

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import java.io.FileInputStream

/**
 * DreamerAgent - Agente que usa World Model para planificación.
 * Basado en "Dream to Control" (Hafner et al., 2020).
 * 
 * Combina:
 * - World Model (RSSM) para imaginación
 * - Actor-Critic en espacio latente
 * - Planificación con Cross-Entropy Method
 */
class DreamerAgent(private val context: Context, private val worldModel: WorldModel) {
    
    companion object {
        const val TAG = "DreamerAgent"
        const val ACTOR_MODEL = "dreamer_actor.tflite"
        const val CRITIC_MODEL = "dreamer_critic.tflite"
        
        // Hyperparameters
        const val IMAGINATION_HORIZON = 15
        const val NUM_TRAJECTORIES = 50
        const val GAMMA = 0.99f
        const val LAMBDA = 0.95f  // GAE lambda
    }
    
    private var actorNet: Interpreter? = null
    private var criticNet: Interpreter? = null
    private var isInitialized = false
    
    // State tracking
    private var currentLatentState: LatentState? = null
    private var episodeStep = 0
    private var imaginedTrajectories = 0
    
    /**
     * Inicializa los modelos Actor-Critic.
     */
    fun initialize(): Boolean = try {
        actorNet = loadModel(ACTOR_MODEL)
        criticNet = loadModel(CRITIC_MODEL)
        isInitialized = true
        Logger.i(TAG, "DreamerAgent initialized with horizon=$IMAGINATION_HORIZON")
        true
    } catch (e: Exception) {
        Logger.e(TAG, "Error initializing DreamerAgent", e)
        false
    }
    
    /**
     * Codifica observación a espacio latente.
     */
    fun encode(bitmap: Bitmap): LatentState {
        val state = worldModel.encodeObservation(bitmap)
        currentLatentState = state
        return state
    }
    
    /**
     * Selecciona acción usando behavior learning o planificación CEM.
     * Modo 'behavior': usa actor directamente (rápido)
     * Modo 'planner': usa CEM (mejor calidad, más lento)
     */
    fun selectAction(state: LatentState, mode: Mode = Mode.PLANNER): DreamerAction {
        return when (mode) {
            Mode.BEHAVIOR -> selectActionBehavior(state)
            Mode.PLANNER -> selectActionPlanner(state)
        }
    }
    
    /**
     * Behavior learning: Actor directo.
     * Más rápido pero menos exploratorio.
     */
    private fun selectActionBehavior(state: LatentState): DreamerAction {
        if (!isInitialized || actorNet == null) {
            return DreamerAction(0, 0f, Mode.BEHAVIOR)
        }
        
        val input = Array(1) { state.stochastic + state.deterministic }
        val output = Array(1) { FloatArray(15) }
        
        actorNet?.run(input, output)
        
        val action = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        val confidence = output[0][action]
        
        return DreamerAction(action, confidence, Mode.BEHAVIOR)
    }
    
    /**
     * Planner: Usa CEM para planificar en espacio latente.
     * Imagina trayectorias y selecciona mejor acción inicial.
     */
    private fun selectActionPlanner(state: LatentState): DreamerAction {
        imaginedTrajectories++
        
        // Planificación CEM
        val planResult = worldModel.planActionCEM(
            currentState = state,
            horizon = IMAGINATION_HORIZON,
            numTrajectories = NUM_TRAJECTORIES
        )
        
        // Evaluar valor con Critic para la acción seleccionada
        val value = evaluateValue(state, planResult.bestAction)
        
        return DreamerAction(
            action = planResult.bestAction,
            confidence = planResult.expectedValue,
            mode = Mode.PLANNER,
            value = value
        )
    }
    
    /**
     * Evalúa valor de estado-acción usando Critic.
     */
    fun evaluateValue(state: LatentState, action: Int): Float {
        if (!isInitialized || criticNet == null) return 0f
        
        val actionOneHot = FloatArray(15) { 0f }.apply { if (action in 0..14) this[action] = 1f }
        val input = Array(1) { state.stochastic + state.deterministic + actionOneHot }
        val output = Array(1) { FloatArray(1) }
        
        criticNet?.run(input, output)
        return output[0][0]
    }
    
    /**
     * Imagina trayectoria y calcula returns con GAE.
     */
    fun imagineAndEvaluate(
        initialState: LatentState,
        actionSequence: List<Int>
    ): TrajectoryEvaluation {
        val trajectory = worldModel.imagineTrajectory(initialState, actionSequence)
        
        // Calcular valores para cada estado imaginado
        val values = trajectory.states.mapIndexed { index, state ->
            val action = actionSequence.getOrElse(index) { 0 }
            evaluateValue(state, action)
        }
        
        // Calcular returns con GAE
        val returns = calculateGAE(trajectory.rewards, values, trajectory.terminals)
        
        return TrajectoryEvaluation(
            trajectory = trajectory,
            values = values,
            returns = returns,
            totalReturn = returns.sum()
        )
    }
    
    /**
     * Calcula Generalized Advantage Estimation.
     */
    private fun calculateGAE(
        rewards: List<Float>,
        values: List<Float>,
        terminals: List<Float>,
        gamma: Float = GAMMA,
        lambda: Float = LAMBDA
    ): List<Float> {
        val returns = mutableListOf<Float>()
        var gae = 0f
        
        for (t in rewards.indices.reversed()) {
            val nextValue = if (t + 1 < values.size) values[t + 1] else 0f
            val tdError = rewards[t] + gamma * nextValue * (1 - terminals[t]) - values[t]
            gae = tdError + gamma * lambda * (1 - terminals[t]) * gae
            returns.add(0, gae + values[t])
        }
        
        return returns
    }
    
    /**
     * Update step (placeholder para entrenamiento).
     */
    fun update(experiences: List<DreamerExperience>) {
        // En implementación completa, actualizaría:
        // - World Model (reconstruction, reward, transition losses)
        // - Actor (policy gradient con imagined trajectories)
        // - Critic (value function loss)
        Logger.d(TAG, "Dreamer update with ${experiences.size} experiences")
    }
    
    /**
     * Notifica fin de episodio.
     */
    fun endEpisode() {
        worldModel.resetState()
        currentLatentState = null
        episodeStep = 0
        Logger.i(TAG, "Episode ended. Trajectories imagined: $imaginedTrajectories")
        imaginedTrajectories = 0
    }
    
    /**
     * Obtiene estadísticas.
     */
    fun getStats(): DreamerStats {
        val wmStats = worldModel.getStats()
        return DreamerStats(
            episodeStep = episodeStep,
            imaginedTrajectories = imaginedTrajectories,
            latentStateDim = wmStats.stochDim + wmStats.detDim,
            imaginationHorizon = IMAGINATION_HORIZON,
            worldModelStats = wmStats
        )
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        actorNet?.close()
        criticNet?.close()
        isInitialized = false
        Logger.i(TAG, "DreamerAgent released")
    }
    
    private fun loadModel(name: String): Interpreter? = try {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val channel = fis.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
        }
    } catch (e: Exception) { null }
    
    enum class Mode { BEHAVIOR, PLANNER }
}

data class DreamerAction(
    val action: Int,
    val confidence: Float,
    val mode: DreamerAgent.Mode,
    val value: Float = 0f
)

data class DreamerExperience(
    val state: LatentState,
    val action: Int,
    val reward: Float,
    val nextState: LatentState,
    val done: Boolean
)

data class TrajectoryEvaluation(
    val trajectory: ImaginedTrajectory,
    val values: List<Float>,
    val returns: List<Float>,
    val totalReturn: Float
)

data class DreamerStats(
    val episodeStep: Int,
    val imaginedTrajectories: Int,
    val latentStateDim: Int,
    val imaginationHorizon: Int,
    val worldModelStats: WorldModelStats
)
