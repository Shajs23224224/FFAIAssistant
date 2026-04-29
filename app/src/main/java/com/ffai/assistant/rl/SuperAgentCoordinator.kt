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
    }

    // Feature flags - se deshabilitan automáticamente si falla la carga
    private var useWM = true
    private var useTX = true
    private var useICM = true
    private var useHRL = true
    private var useMAML = true

    // Componentes - nullable para manejar fallos de inicialización
    private var worldModel: WorldModel? = null
    private var dreamer: DreamerAgent? = null
    private var transformer: TransformerAgent? = null
    private var icm: ICMModule? = null
    private var intrinsic: IntrinsicRewardEngine? = null
    private var metaController: MetaController? = null
    private var subPolicy: SubPolicyManager? = null
    private var maml: MAMLAgent? = null
    private var fastAdapt: FastAdaptation? = null

    private var isInit = false
    private var step = 0

    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        try {
            var wmOk = false
            var txOk = false
            var icmOk = false
            var hrlOk = false
            var mamlOk = false
            
            // World Model + Dreamer
            if (useWM) {
                try {
                    val wm = WorldModel(context)
                    wmOk = wm.initialize()
                    if (wmOk) {
                        worldModel = wm
                        val dr = DreamerAgent(context, wm)
                        if (dr.initialize()) {
                            dreamer = dr
                        } else {
                            wmOk = false
                            Logger.w(TAG, "Dreamer failed to initialize")
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "WorldModel/Dreamer error", e)
                    wmOk = false
                }
                useWM = wmOk
            }
            
            // Transformer
            if (useTX) {
                try {
                    val tx = TransformerAgent(context)
                    txOk = tx.initialize()
                    if (txOk) transformer = tx
                } catch (e: Exception) {
                    Logger.e(TAG, "Transformer error", e)
                    txOk = false
                }
                useTX = txOk
            }
            
            // ICM Curiosity
            if (useICM) {
                try {
                    val ic = ICMModule(context)
                    icmOk = ic.initialize()
                    if (icmOk) {
                        icm = ic
                        intrinsic = IntrinsicRewardEngine(ic)
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "ICM error", e)
                    icmOk = false
                }
                useICM = icmOk
            }
            
            // Hierarchical RL
            if (useHRL) {
                try {
                    val mc = MetaController(context)
                    val mcOk = mc.initialize()
                    if (mcOk) {
                        metaController = mc
                        val sp = SubPolicyManager(context)
                        val spOk = sp.initialize()
                        if (spOk) {
                            subPolicy = sp
                            hrlOk = true
                        } else {
                            Logger.w(TAG, "SubPolicy failed")
                            hrlOk = false
                        }
                    } else {
                        Logger.w(TAG, "MetaController failed")
                        hrlOk = false
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "HRL error", e)
                    hrlOk = false
                }
                useHRL = hrlOk
            }
            
            // MAML Meta-learning
            if (useMAML) {
                try {
                    val ml = MAMLAgent(context)
                    mamlOk = ml.initialize()
                    if (mamlOk) {
                        maml = ml
                        fastAdapt = FastAdaptation(ml)
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "MAML error", e)
                    mamlOk = false
                }
                useMAML = mamlOk
            }
            
            isInit = wmOk || txOk || icmOk || hrlOk || mamlOk // Al menos uno funciona
            Logger.i(TAG, "SuperAgent initialized: WM=$wmOk TX=$txOk ICM=$icmOk HRL=$hrlOk MAML=$mamlOk")
            isInit
        } catch (e: Exception) {
            Logger.e(TAG, "Critical init error", e)
            false
        }
    }

    suspend fun decide(bitmap: Bitmap, state: FloatArray): SuperDecision = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        step++

        val latent = if (useWM) worldModel?.encodeObservation(bitmap) else null
        val txAct = if (useTX) transformer?.selectAction(state) else null
        val dreamAct = if (useWM && latent != null) dreamer?.selectAction(latent, DreamerAgent.Mode.PLANNER) else null
        val goal = if (useHRL) {
            metaController?.selectGoal(state) ?: MetaController.Goal.ENGAGE
        } else {
            MetaController.Goal.ENGAGE
        }
        val subAct = if (useHRL) subPolicy?.selectAction(state, goal) else null

        if (useMAML) {
            val adapt = fastAdapt?.detectAndAdapt(state, 0, 0, 1f, "normal")
            if (adapt?.adapted == true) Logger.d(TAG, "Adapt: ${adapt.contextType}")
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
        return if (useICM) intrinsic?.computeTotalReward(s, a, ns, r)
            ?: TotalReward(r, r, 0f, 0f, 0f, 0f)
        else TotalReward(r, r, 0f, 0f, 0f, 0f)
    }

    fun endEpisode(finalR: Float) {
        if (useWM) dreamer?.endEpisode()
        if (useTX) transformer?.resetSequence()
        if (useICM) intrinsic?.reset()
        if (useHRL) metaController?.resetEpisode()
        if (useMAML) fastAdapt?.reset()
        Logger.i(TAG, "Episode ended. Reward: $finalR")
    }

    fun release() {
        if (useWM) { dreamer?.release(); worldModel?.release() }
        if (useTX) transformer?.release()
        if (useICM) icm?.release()
        if (useHRL) { metaController?.release(); subPolicy?.release() }
        if (useMAML) maml?.release()
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
