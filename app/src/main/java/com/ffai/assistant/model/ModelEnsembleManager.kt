package com.ffai.assistant.model

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.overlay.FrameData
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FASE 2: ModelEnsembleManager - Gestor del ensemble de modelos IA.
 * 
 * Coordina 8 modelos especializados:
 * - CombatNet (25MB): Detección enemigos, predicción movimiento
 * - TacticalNet (20MB): Decisiones tácticas
 * - StrategyNet (15MB): Estrategia macro
 * - VisionNet (18MB): Análisis visual profundo
 * - UINet (12MB): OCR, HP, textos
 * - MapNet (15MB): Interpretación mapa
 * - RecoilNet (8MB): Compensación recoil
 * - ConfidenceNet (7MB): Evaluación confianza
 * 
 * Total: ~120MB con inferencia async y votación ponderada.
 */
class ModelEnsembleManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        const val TAG = "ModelEnsembleManager"
        
        // Tamaños aproximados (MB)
        const val COMBAT_NET_SIZE = 25
        const val TACTICAL_NET_SIZE = 20
        const val STRATEGY_NET_SIZE = 15
        const val VISION_NET_SIZE = 18
        const val UI_NET_SIZE = 12
        const val MAP_NET_SIZE = 15
        const val RECOIL_NET_SIZE = 8
        const val CONFIDENCE_NET_SIZE = 7
        
        const val TOTAL_SIZE_MB = COMBAT_NET_SIZE + TACTICAL_NET_SIZE + STRATEGY_NET_SIZE +
                                   VISION_NET_SIZE + UI_NET_SIZE + MAP_NET_SIZE +
                                   RECOIL_NET_SIZE + CONFIDENCE_NET_SIZE
    }

    // Modelos del ensemble
    private val combatNet = CombatNet(context)
    private val tacticalNet = TacticalNet(context)
    private val strategyNet = StrategyNet(context)
    private val visionNet = VisionNet(context)
    private val uiNet = UINet(context)
    private val mapNet = MapNet(context)
    private val recoilNet = RecoilNet(context)
    private val confidenceNet = ConfidenceNet(context)
    
    // Estado de carga
    private val isInitialized = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    
    // Cache de inferencias para evitar re-computación
    private val inferenceCache = ConcurrentHashMap<String, InferenceResult>(100)
    private val cacheExpiryMs = 100L // Cache válido por 100ms
    
    // Callbacks
    private var onModelLoaded: ((String, Boolean) -> Unit)? = null
    private var onEnsembleResult: ((EnsembleResult) -> Unit)? = null
    private var onInferenceError: ((String, Exception) -> Unit)? = null

    /**
     * Inicializa todos los modelos del ensemble.
     * Carga asíncrona para no bloquear UI.
     */
    suspend fun initialize() = coroutineScope {
        Logger.i(TAG, "Inicializando ensemble de modelos (~${TOTAL_SIZE_MB}MB)...")
        
        val models = listOf(
            Triple("CombatNet", COMBAT_NET_SIZE) { combatNet.initialize() },
            Triple("TacticalNet", TACTICAL_NET_SIZE) { tacticalNet.initialize() },
            Triple("StrategyNet", STRATEGY_NET_SIZE) { strategyNet.initialize() },
            Triple("VisionNet", VISION_NET_SIZE) { visionNet.initialize() },
            Triple("UINet", UI_NET_SIZE) { uiNet.initialize() },
            Triple("MapNet", MAP_NET_SIZE) { mapNet.initialize() },
            Triple("RecoilNet", RECOIL_NET_SIZE) { recoilNet.initialize() },
            Triple("ConfidenceNet", CONFIDENCE_NET_SIZE) { confidenceNet.initialize() }
        )
        
        // Cargar en paralelo con límites de concurrencia
        val deferreds = models.map { (name, size, init) ->
            async {
                try {
                    Logger.d(TAG, "Cargando $name (${size}MB)...")
                    init()
                    onModelLoaded?.invoke(name, true)
                    Logger.d(TAG, "$name cargado exitosamente")
                    true
                } catch (e: Exception) {
                    Logger.e(TAG, "Error cargando $name", e)
                    onModelLoaded?.invoke(name, false)
                    onInferenceError?.invoke(name, e)
                    false
                }
            }
        }
        
        val results = deferreds.awaitAll()
        val loadedCount = results.count { it }
        
        isInitialized.set(loadedCount > 0)
        Logger.i(TAG, "Ensemble inicializado: $loadedCount/${models.size} modelos cargados")
        
        // Calcular memoria usada
        val usedMemory = models.filterIndexed { index, _ -> results[index] }
                               .sumOf { it.second }
        Logger.i(TAG, "Memoria usada por modelos: ~${usedMemory}MB")
    }

    /**
     * Ejecuta inferencia del ensemble completo.
     * Corre todos los modelos en paralelo y combina resultados.
     */
    suspend fun runEnsemble(frameData: FrameData): EnsembleResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        
        // Verificar cache
        val cacheKey = generateCacheKey(frameData)
        inferenceCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < cacheExpiryMs) {
                return@coroutineScope cached.result
            }
        }
        
        // Ejecutar inferencias en paralelo
        val combatDeferred = async { runModel("combat") { combatNet.predict(frameData) } }
        val tacticalDeferred = async { runModel("tactical") { tacticalNet.predict(frameData) } }
        val visionDeferred = async { runModel("vision") { visionNet.predict(frameData) } }
        val uiDeferred = async { runModel("ui") { uiNet.predict(frameData) } }
        val confidenceDeferred = async { runModel("confidence") { confidenceNet.predict(frameData) } }
        
        // Esperar resultados (con timeout para evitar bloqueos)
        val combatResult = combatDeferred.await()
        val tacticalResult = tacticalDeferred.await()
        val visionResult = visionDeferred.await()
        val uiResult = uiDeferred.await()
        val confidenceResult = confidenceDeferred.await()
        
        // Calcular confianza ponderada
        val weightedConfidence = calculateWeightedConfidence(
            listOfNotNull(combatResult, tacticalResult, visionResult, uiResult),
            confidenceResult
        )
        
        // Combinar detecciones de enemigos
        val mergedEnemies = mergeEnemyDetections(
            combatResult?.enemies ?: emptyList(),
            visionResult?.objects?.filter { it.type == DetectedObjectType.ENEMY } ?: emptyList()
        )
        
        // Determinar acción óptima por votación ponderada
        val votedAction = voteOnAction(
            combatResult?.suggestedAction,
            tacticalResult?.suggestedAction,
            visionResult?.suggestedAction,
            weightedConfidence
        )
        
        val result = EnsembleResult(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            combatOutput = combatResult,
            tacticalOutput = tacticalResult,
            visionOutput = visionResult,
            uiOutput = uiResult,
            confidence = weightedConfidence,
            mergedEnemies = mergedEnemies,
            suggestedAction = votedAction,
            requiresStrategic = tacticalResult?.requiresStrategicUpdate ?: false
        )
        
        // Guardar en cache
        inferenceCache[cacheKey] = InferenceResult(result, System.currentTimeMillis())
        
        // Limpiar cache antiguo si es muy grande
        if (inferenceCache.size > 100) {
            val now = System.currentTimeMillis()
            inferenceCache.entries.removeIf { now - it.value.timestamp > 500 }
        }
        
        onEnsembleResult?.invoke(result)
        result
    }

    /**
     * Ejecuta inferencia rápida (solo modelos ligeros).
     * Usar en modo "Corto" (<8ms).
     */
    suspend fun runFastInference(frameData: FrameData): FastEnsembleResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        
        val combatDeferred = async { runModel("combat") { combatNet.predictFast(frameData) } }
        val confidenceDeferred = async { runModel("confidence") { confidenceNet.predictFast(frameData) } }
        
        val combatResult = combatDeferred.await()
        val confidenceResult = confidenceDeferred.await()
        
        FastEnsembleResult(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            enemies = combatResult?.enemies ?: emptyList(),
            suggestedAction = combatResult?.suggestedAction,
            confidence = confidenceResult?.confidenceScore ?: 0.5f,
            aimTarget = combatResult?.aimTarget
        )
    }

    /**
     * Ejecuta inferencia táctica (modelos medianos).
     * Usar en modo "Medio" (8-30ms).
     */
    suspend fun runTacticalInference(frameData: FrameData): TacticalEnsembleResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        
        val combatDeferred = async { runModel("combat") { combatNet.predict(frameData) } }
        val tacticalDeferred = async { runModel("tactical") { tacticalNet.predict(frameData) } }
        val recoilDeferred = async { runModel("recoil") { recoilNet.predict(frameData) } }
        val confidenceDeferred = async { runModel("confidence") { confidenceNet.predict(frameData) } }
        
        val combatResult = combatDeferred.await()
        val tacticalResult = tacticalDeferred.await()
        val recoilResult = recoilDeferred.await()
        val confidenceResult = confidenceDeferred.await()
        
        TacticalEnsembleResult(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            combatOutput = combatResult,
            tacticalOutput = tacticalResult,
            recoilCompensation = recoilResult?.recoilPattern,
            confidence = confidenceResult?.confidenceScore ?: 0.5f,
            suggestedAction = tacticalResult?.suggestedAction ?: combatResult?.suggestedAction
        )
    }

    /**
     * Ejecuta inferencia estratégica (todos los modelos).
     * Usar en modo "Largo" (30-80ms).
     */
    suspend fun runStrategicInference(frameData: FrameData, mapBitmap: Bitmap?): StrategicEnsembleResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        
        // Todos los modelos
        val allResults = runEnsemble(frameData)
        
        // Mapa (async opcional)
        val mapDeferred = mapBitmap?.let { bitmap ->
            async { runModel("map") { mapNet.predict(bitmap) } }
        }
        
        val strategyDeferred = async { runModel("strategy") { strategyNet.predict(allResults) } }
        
        val mapResult = mapDeferred?.await()
        val strategyResult = strategyDeferred.await()
        
        StrategicEnsembleResult(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            baseResults = allResults,
            mapOutput = mapResult,
            strategyOutput = strategyResult,
            recommendedRoute = strategyResult?.recommendedRoute,
            nextZonePrediction = mapResult?.nextSafeZone,
            riskAssessment = strategyResult?.riskAssessment
        )
    }

    /**
     * Corre modelo individual con manejo de errores.
     */
    private suspend fun <T> runModel(name: String, block: suspend () -> T): T? {
        return try {
            withTimeout(100) { // 100ms timeout por modelo
                block()
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w(TAG, "$name timeout")
            null
        } catch (e: Exception) {
            Logger.e(TAG, "Error en $name", e)
            onInferenceError?.invoke(name, e)
            null
        }
    }

    /**
     * Calcula confianza ponderada basada en resultados de múltiples modelos.
     */
    private fun calculateWeightedConfidence(
        results: List<ModelOutput>,
        confidenceResult: ConfidenceOutput?
    ): Float {
        if (results.isEmpty()) return 0.5f
        
        val baseConfidence = results.map { it.confidence }.average().toFloat()
        val modelConfidence = confidenceResult?.confidenceScore ?: 0.5f
        
        // Ponderar: 60% confianza base, 40% modelo de confianza
        return (baseConfidence * 0.6f + modelConfidence * 0.4f).coerceIn(0f, 1f)
    }

    /**
     * Mergea detecciones de enemigos de múltiples modelos.
     * Elimina duplicados y promedia posiciones.
     */
    private fun mergeEnemyDetections(
        combatEnemies: List<EnemyDetection>,
        visionEnemies: List<DetectedObject>
    ): List<MergedEnemy> {
        val allDetections = mutableListOf<DetectionSource>()
        
        combatEnemies.forEach { enemy ->
            allDetections.add(DetectionSource(
                x = enemy.x,
                y = enemy.y,
                confidence = enemy.confidence,
                source = "combat",
                isLocked = enemy.isLocked
            ))
        }
        
        visionEnemies.forEach { enemy ->
            allDetections.add(DetectionSource(
                x = enemy.x,
                y = enemy.y,
                confidence = enemy.confidence,
                source = "vision",
                isLocked = false
            ))
        }
        
        // Clustering simple: agrupar por proximidad
        val clusters = mutableListOf<MutableList<DetectionSource>>()
        
        allDetections.forEach { detection ->
            val matchingCluster = clusters.find { cluster ->
                cluster.any { existing ->
                    val distance = kotlin.math.hypot(
                        existing.x - detection.x,
                        existing.y - detection.y
                    )
                    distance < 50f // Umbral de agrupación
                }
            }
            
            if (matchingCluster != null) {
                matchingCluster.add(detection)
            } else {
                clusters.add(mutableListOf(detection))
            }
        }
        
        // Convertir clusters a merged enemies
        return clusters.map { cluster ->
            val avgX = cluster.map { it.x }.average().toFloat()
            val avgY = cluster.map { it.y }.average().toFloat()
            val maxConfidence = cluster.maxOf { it.confidence }
            val sources = cluster.map { it.source }.distinct()
            val isLocked = cluster.any { it.isLocked }
            
            MergedEnemy(
                x = avgX,
                y = avgY,
                confidence = (maxConfidence * (1 + sources.size * 0.1f)).coerceAtMost(1f),
                sources = sources,
                isLocked = isLocked
            )
        }.sortedByDescending { it.confidence }
    }

    /**
     * Vota sobre la acción sugerida por múltiples modelos.
     */
    private fun voteOnAction(
        combatAction: ActionSuggestion?,
        tacticalAction: ActionSuggestion?,
        visionAction: ActionSuggestion?,
        confidence: Float
    ): ActionSuggestion {
        val actions = listOfNotNull(combatAction, tacticalAction, visionAction)
        if (actions.isEmpty()) return ActionSuggestion(ActionType.HOLD, 0.5f)
        
        // Contar votos ponderados por confianza
        val actionScores = mutableMapOf<ActionType, Float>()
        
        actions.forEach { action ->
            val current = actionScores[action.type] ?: 0f
            actionScores[action.type] = current + action.confidence
        }
        
        // Agregar confianza del ensemble
        val winner = actionScores.maxByOrNull { it.value }
            ?: return ActionSuggestion(ActionType.HOLD, 0.5f)
        
        return ActionSuggestion(
            type = winner.key,
            confidence = (winner.value / actions.size).coerceIn(0f, 1f) * confidence
        )
    }

    private fun generateCacheKey(frameData: FrameData): String {
        // Hash simple basado en timestamp y número de frame
        return "${frameData.frameNumber % 3}_${frameData.timestamp / 50}" // Cambia cada 50ms
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    fun setOnModelLoadedListener(listener: (String, Boolean) -> Unit) {
        onModelLoaded = listener
    }

    fun setOnEnsembleResultListener(listener: (EnsembleResult) -> Unit) {
        onEnsembleResult = listener
    }

    fun setOnInferenceErrorListener(listener: (String, Exception) -> Unit) {
        onInferenceError = listener
    }

    fun isInitialized(): Boolean = isInitialized.get()

    fun getLoadedModelsCount(): Int {
        var count = 0
        if (combatNet.isLoaded()) count++
        if (tacticalNet.isLoaded()) count++
        if (strategyNet.isLoaded()) count++
        if (visionNet.isLoaded()) count++
        if (uiNet.isLoaded()) count++
        if (mapNet.isLoaded()) count++
        if (recoilNet.isLoaded()) count++
        if (confidenceNet.isLoaded()) count++
        return count
    }

    /**
     * Libera recursos de todos los modelos.
     */
    fun release() {
        isRunning.set(false)
        coroutineScope.cancel()
        
        combatNet.release()
        tacticalNet.release()
        strategyNet.release()
        visionNet.release()
        uiNet.release()
        mapNet.release()
        recoilNet.release()
        confidenceNet.release()
        
        inferenceCache.clear()
        
        Logger.i(TAG, "ModelEnsembleManager liberado")
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class EnsembleResult(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val combatOutput: CombatOutput?,
    val tacticalOutput: TacticalOutput?,
    val visionOutput: VisionOutput?,
    val uiOutput: UIOutput?,
    val confidence: Float,
    val mergedEnemies: List<MergedEnemy>,
    val suggestedAction: ActionSuggestion,
    val requiresStrategic: Boolean
)

data class FastEnsembleResult(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val enemies: List<EnemyDetection>,
    val suggestedAction: ActionSuggestion?,
    val confidence: Float,
    val aimTarget: AimTarget?
)

data class TacticalEnsembleResult(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val combatOutput: CombatOutput?,
    val tacticalOutput: TacticalOutput?,
    val recoilCompensation: RecoilPattern?,
    val confidence: Float,
    val suggestedAction: ActionSuggestion?
)

data class StrategicEnsembleResult(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val baseResults: EnsembleResult,
    val mapOutput: MapOutput?,
    val strategyOutput: StrategyOutput?,
    val recommendedRoute: List<Pair<Float, Float>>?,
    val nextZonePrediction: ZonePrediction?,
    val riskAssessment: RiskAssessment?
)

data class MergedEnemy(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val sources: List<String>,
    val isLocked: Boolean
)

data class ActionSuggestion(
    val type: ActionType,
    val confidence: Float,
    val parameters: Map<String, Any> = emptyMap()
)

enum class ActionType {
    AIM, SHOOT, MOVE_FORWARD, MOVE_BACKWARD, MOVE_LEFT, MOVE_RIGHT,
    HEAL, RELOAD, CROUCH, JUMP, LOOT, REVIVE, ROTATE_LEFT, ROTATE_RIGHT, HOLD
}

private data class InferenceResult(
    val result: EnsembleResult,
    val timestamp: Long
)

private data class DetectionSource(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val source: String,
    val isLocked: Boolean
)
