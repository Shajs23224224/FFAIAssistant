package com.ffai.assistant.rl

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.rl.worldmodel.*
import com.ffai.assistant.rl.transformer.*
import com.ffai.assistant.rl.curiosity.*
import com.ffai.assistant.rl.hierarchical.*
import com.ffai.assistant.rl.metalearning.*
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SuperAgentCoordinator - Coordina todos los agentes avanzados.
 * Integra: World Model, Transformer, ICM, Hierarchical RL, MAML
 */
class SuperAgentCoordinator(private val context: Context) {
    companion object {
        const val TAG = "SuperAgentCoordinator"
        const val USE_WM = true
        const val USE_TX = true
        const val USE_ICM = true
        const val USE_HRL = true
        const val USE_MAML = true
    }

    private lateinit var worldModel: WorldModel
    private lateinit var dreamer: DreamerAgent
    private lateinit var transformer: TransformerAgent
    private lateinit var icm: ICMModule
    private lateinit var intrinsic: IntrinsicRewardEngine
    private lateinit var metaController: MetaController
    private lateinit var subPolicy: SubPolicyManager
    private lateinit var maml: MAMLAgent
    private lateinit var fastAdapt: FastAdaptation

    private var isInit = false
    private var step = 0

    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        try {
            if (USE_WM) {
                worldModel = WorldModel(context).apply { initialize() }
                dreamer = DreamerAgent(context, worldModel).apply { initialize() }
            }
            if (USE_TX) transformer = TransformerAgent(context).apply { initialize() }
            if (USE_ICM) {
                icm = ICMModule(context).apply { initialize() }
                intrinsic = IntrinsicRewardEngine(icm)
            }
            if (USE_HRL) {
                metaController = MetaController(context).apply { initialize() }
                subPolicy = SubPolicyManager(context).apply { initialize() }
            }
            if (USE_MAML) {
                maml = MAMLAgent(context).apply { initialize() }
                fastAdapt = FastAdaptation(maml)
            }
            isInit = true
            Logger.i(TAG, "SuperAgent initialized")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Init error", e)
            false
        }
    }

    suspend fun decide(bitmap: Bitmap, state: FloatArray): SuperDecision = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        step++

        val latent = if (USE_WM) worldModel.encodeObservation(bitmap) else null
        val txAct = if (USE_TX) transformer.selectAction(state) else null
        val dreamAct = if (USE_WM && latent != null) dreamer.selectAction(latent, DreamerAgent.Mode.PLANNER) else null
        val goal = if (USE_HRL) metaController.selectGoal(state) else MetaController.Goal.ENGAGE
        val subAct = if (USE_HRL) subPolicy.selectAction(state, goal) else null

        if (USE_MAML) {
            val adapt = fastAdapt.detectAndAdapt(state, 0, 0, 1f, "normal")
            if (adapt.adapted) Logger.d(TAG, "Adapt: ${adapt.contextType}")
        }

        val action = ensembleVote(txAct?.action, dreamAct?.action, subAct?.action)
        val latency = System.currentTimeMillis() - start
        
        // Calcular confianza basada en consenso de componentes
        val confidence = calculateConfidence(txAct?.confidence, dreamAct?.confidence, subAct?.confidence)

        SuperDecision(action, goal, latency, ComponentResults(txAct?.action, dreamAct?.action, subAct?.action), confidence)
    }
    
    private fun calculateConfidence(tx: Float?, dreamer: Float?, sub: Float?): Float {
        val confidences = listOfNotNull(tx, dreamer, sub)
        return if (confidences.isNotEmpty()) confidences.average().toFloat() else 0.5f
    }

    private fun ensembleVote(t: Int?, d: Int?, s: Int?): Int {
        val votes = mutableMapOf<Int, Float>()
        t?.let { votes[it] = votes.getOrDefault(it, 0f) + 0.3f }
        d?.let { votes[it] = votes.getOrDefault(it, 0f) + 0.4f }
        s?.let { votes[it] = votes.getOrDefault(it, 0f) + 0.3f }
        return votes.maxByOrNull { it.value }?.key ?: (s ?: 0)
    }

    fun computeReward(s: FloatArray, a: Int, ns: FloatArray, r: Float): TotalReward {
        return if (USE_ICM) intrinsic.computeTotalReward(s, a, ns, r)
        else TotalReward(r, r, 0f, 0f, 0f, 0f)
    }

    fun endEpisode(finalR: Float) {
        if (USE_WM) dreamer.endEpisode()
        if (USE_TX) transformer.resetSequence()
        if (USE_ICM) intrinsic.reset()
        if (USE_HRL) metaController.resetEpisode()
        if (USE_MAML) fastAdapt.reset()
        Logger.i(TAG, "Episode ended. Reward: $finalR")
    }

    fun release() {
        if (USE_WM) { dreamer.release(); worldModel.release() }
        if (USE_TX) transformer.release()
        if (USE_ICM) icm.release()
        if (USE_HRL) { metaController.release(); subPolicy.release() }
        if (USE_MAML) maml.release()
    }
}

data class SuperDecision(
    val action: Int,
    val goal: MetaController.Goal,
    val latencyMs: Long,
    val components: ComponentResults,
    val confidence: Float = 0.5f
)

data class ComponentResults(val tx: Int?, val dreamer: Int?, val sub: Int?)
