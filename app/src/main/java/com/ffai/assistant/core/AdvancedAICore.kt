package com.ffai.assistant.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.CameraController
import com.ffai.assistant.action.GestureController
import com.ffai.assistant.action.SmartAimTrainer
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.model.ActionSuggestion
import com.ffai.assistant.model.EnsembleResult
import com.ffai.assistant.model.FastEnsembleResult
import com.ffai.assistant.model.ModelEnsembleManager
import com.ffai.assistant.core.ReasoningMode
import com.ffai.assistant.model.StrategicEnsembleResult
import com.ffai.assistant.model.TacticalEnsembleResult
import com.ffai.assistant.core.ConfidenceResult
import com.ffai.assistant.navigation.MapInterpreter
import com.ffai.assistant.overlay.AnalysisArea
import com.ffai.assistant.overlay.DynamicOverlayService
import com.ffai.assistant.overlay.FrameData
import com.ffai.assistant.overlay.ScreenAnalyzer
import com.ffai.assistant.rl.DeepRLCore
import com.ffai.assistant.rl.RewardShaper
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * FASE 9: AdvancedAICore - Núcleo principal de IA avanzada 100% Ensemble.
 *
 * Reemplaza DecisionEngine con arquitectura completa:
 * - Ensemble de 8 modelos (+120MB)
 * - 3 modos de razonamiento automático (corto/medio/largo)
 * - Deep RL para aprendizaje continuo
 * - Control de cámara con 3 velocidades
 * - Interpretación de mapa
 * - Confianza dinámica
 * - Visión dinámica amplia (overlay flotante)
 *
 * Pipeline de decisión:
 * 1. Captura pantalla (ScreenAnalyzer)
 * 2. Determina modo de razonamiento (ReasoningEngine)
 * 3. Ejecuta inferencia del ensemble según modo
 * 4. Selecciona acción óptima
 * 5. Ejecuta acción (GestureController + CameraController + AimTrainer)
 * 6. Aprende de resultado (DeepRLCore + RewardShaper)
 * 7. Actualiza overlay visual
 */
class AdvancedAICore(
    private val context: Context,
    private val service: AccessibilityService,
    private val gameConfig: GameConfig
) {

    companion object {
        const val TAG = "AdvancedAICore"
        
        // Frecuencias de ejecución
        const val FRAME_RATE_FPS = 30
        const val FRAME_INTERVAL_MS = 33L
        const val STRATEGIC_UPDATE_INTERVAL_MS = 2000L // 2 segundos
    }

    // Scope para coroutines
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Componentes principales
    private lateinit var screenAnalyzer: ScreenAnalyzer
    private lateinit var overlayService: DynamicOverlayService
    private lateinit var ensembleManager: ModelEnsembleManager
    private lateinit var reasoningEngine: ReasoningEngine
    private lateinit var gestureController: GestureController
    private lateinit var cameraController: CameraController
    private lateinit var smartAimTrainer: SmartAimTrainer
    private lateinit var mapInterpreter: MapInterpreter
    private lateinit var deepRLCore: DeepRLCore
    private lateinit var rewardShaper: RewardShaper
    private lateinit var confidenceEngine: ConfidenceEngine
    
    // Estado
    private val isRunning = AtomicBoolean(false)
    private val lastFrameTime = AtomicLong(0)
    private val currentReasoningMode = AtomicReference(ReasoningMode.MEDIUM)
    private val lastEnsembleResult = AtomicReference<EnsembleResult?>(null)
    private val lastStrategicUpdate = AtomicLong(0)
    private val frameCount = AtomicLong(0)
    
    // Cola de frames para procesamiento
    private val frameQueue = ConcurrentLinkedQueue<FrameData>()

    /**
     * Inicializa todos los componentes del sistema.
     */
    suspend fun initialize() {
        Logger.i(TAG, "Inicializando AdvancedAICore...")
        
        // 1. Screen Analyzer
        screenAnalyzer = ScreenAnalyzer(
            width = gameConfig.screenWidth,
            height = gameConfig.screenHeight
        )
        screenAnalyzer.initialize()
        
        // 2. Overlay Service (visión dinámica amplia)
        overlayService = DynamicOverlayService()
        
        // 3. Model Ensemble (120MB)
        ensembleManager = ModelEnsembleManager(context, coroutineScope)
        ensembleManager.initialize()
        
        // 4. Reasoning Engine (3 modos)
        reasoningEngine = ReasoningEngine(coroutineScope)
        
        // 5. Controladores
        gestureController = GestureController(service, gameConfig)
        cameraController = CameraController(service, gameConfig)
        smartAimTrainer = SmartAimTrainer()
        
        // 6. Map Interpreter
        mapInterpreter = MapInterpreter()
        
        // 7. RL Components
        deepRLCore = DeepRLCore(context)
        deepRLCore.initialize()
        rewardShaper = RewardShaper()
        
        // 8. Confidence Engine
        confidenceEngine = ConfidenceEngine()
        
        // Configurar callbacks
        setupCallbacks()
        
        Logger.i(TAG, "AdvancedAICore inicializado correctamente")
        Logger.i(TAG, "Modelos cargados: ${ensembleManager.getLoadedModelsCount()}/8")
    }

    /**
     * Inicia el loop principal de procesamiento.
     */
    fun start() {
        if (isRunning.get()) return
        
        isRunning.set(true)
        screenAnalyzer.start()
        
        coroutineScope.launch {
            while (isRunning.get()) {
                val frameStart = System.currentTimeMillis()
                
                // 1. Obtener frame
                val frameData = screenAnalyzer.pollFrame()
                
                if (frameData != null) {
                    processFrame(frameData)
                }
                
                // Control de FPS
                val elapsed = System.currentTimeMillis() - frameStart
                val sleepTime = FRAME_INTERVAL_MS - elapsed
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        }
        
        // Loop de actualización estratégica (cada 2 segundos)
        coroutineScope.launch {
            while (isRunning.get()) {
                delay(STRATEGIC_UPDATE_INTERVAL_MS)
                updateStrategicLayer()
            }
        }
        
        Logger.i(TAG, "AdvancedAICore iniciado")
    }

    /**
     * Detiene el sistema.
     */
    fun stop() {
        isRunning.set(false)
        screenAnalyzer.stop()
        coroutineScope.cancel()
        Logger.i(TAG, "AdvancedAICore detenido")
    }

    /**
     * Procesa un frame individual.
     */
    private suspend fun processFrame(frameData: FrameData) {
        frameCount.incrementAndGet()
        
        // 1. Determinar modo de razonamiento
        val mode = reasoningEngine.determineMode(frameData, lastEnsembleResult.get())
        currentReasoningMode.set(mode)
        
        // 2. Ejecutar inferencia según modo
        val inferenceStart = System.currentTimeMillis()
        
        val (result, fastResult, tacticalResult) = when (mode) {
            ReasoningMode.SHORT -> {
                val fast = ensembleManager.runFastInference(frameData)
                reasoningEngine.reportInferenceTime(mode, System.currentTimeMillis() - inferenceStart)
                Triple(null, fast, null)
            }
            ReasoningMode.MEDIUM -> {
                val tactical = ensembleManager.runTacticalInference(frameData)
                reasoningEngine.reportInferenceTime(mode, System.currentTimeMillis() - inferenceStart)
                Triple(null, null, tactical)
            }
            ReasoningMode.LONG -> {
                val full = ensembleManager.runEnsemble(frameData)
                val tactical = ensembleManager.runTacticalInference(frameData)
                reasoningEngine.reportInferenceTime(mode, System.currentTimeMillis() - inferenceStart)
                Triple(full, null, tactical)
            }
        }
        
        // Guardar resultado
        result?.let { lastEnsembleResult.set(it) }
        
        // 3. Seleccionar acción
        val selectedAction = when {
            result != null -> result.suggestedAction
            tacticalResult != null -> tacticalResult.suggestedAction
            fastResult != null -> fastResult.suggestedAction
            else -> ActionSuggestion(ActionType.HOLD, 0.5f)
        } ?: ActionSuggestion(ActionType.HOLD, 0.5f)
        
        // 4. Ejecutar acción
        executeAction(selectedAction, result, tacticalResult, fastResult)
        
        // 5. Aprendizaje RL
        val state = result?.let { deepRLCore.encodeState(it) } ?: FloatArray(256) { 0f }
        val action = selectedAction.type.ordinal
        
        // Calcular recompensa
        val reward = rewardShaper.calculateReward(
            lastEnsembleResult.get(),
            result ?: EnsembleResult(
                timestamp = System.currentTimeMillis(),
                inferenceTimeMs = 0,
                combatOutput = null,
                tacticalOutput = null,
                visionOutput = null,
                uiOutput = null,
                confidence = 0.5f,
                mergedEnemies = fastResult?.enemies?.mapIndexed { index, it -> 
                    com.ffai.assistant.model.MergedEnemy(
                        id = "enemy_${index}_${it.x.toInt()}_${it.y.toInt()}",
                        x = it.x, 
                        y = it.y, 
                        confidence = it.confidence, 
                        sources = listOf("combat"), 
                        isLocked = it.isLocked
                    )
                } ?: emptyList(),
                suggestedAction = selectedAction,
                requiresStrategic = false
            ),
            selectedAction.type,
            System.currentTimeMillis()
        )
        
        deepRLCore.storeExperience(state, action, reward, state, false)
        deepRLCore.trainStep()
        
        // 6. Actualizar confianza
        confidenceEngine.calculateConfidence()
        
        // 7. Actualizar overlay visual
        updateOverlay(result, fastResult, selectedAction)
        
        frameData.recycle()
    }

    /**
     * Ejecuta acción seleccionada con todos los controladores.
     */
    private fun executeAction(
        action: ActionSuggestion,
        ensembleResult: EnsembleResult?,
        tacticalResult: TacticalEnsembleResult?,
        fastResult: FastEnsembleResult?
    ) {
        when (action.type) {
            ActionType.AIM -> {
                // Aim con lead y compensación recoil
                val target = fastResult?.aimTarget 
                    ?: ensembleResult?.combatOutput?.aimTarget
                
                target?.let {
                    cameraController.aimWithLead(it.x, it.y, 0f, 0f, 100f)
                    
                    // Dibujar en overlay
                    overlayService.drawAimPoint(it.x, it.y, it.confidence)
                }
            }
            
            ActionType.SHOOT -> {
                // Start firing con recoil compensation
                val weapon = ensembleResult?.uiOutput?.weaponInfo?.currentWeapon ?: "M416"
                smartAimTrainer.startFiring(weapon)
                
                // Compensación por frame
                val compensation = smartAimTrainer.calculateCompensation()
                if (!compensation.shouldStopBurst()) {
                    // Ejecutar disparo
                    gestureController.execute(com.ffai.assistant.action.Action.shoot())
                    
                    // Aplicar compensación
                    cameraController.rotate(compensation.compensationX, compensation.compensationY)
                } else {
                    smartAimTrainer.stopFiring()
                }
            }
            
            ActionType.MOVE_FORWARD, ActionType.MOVE_BACKWARD,
            ActionType.MOVE_LEFT, ActionType.MOVE_RIGHT -> {
                // Movimiento con cámara suave
                cameraController.setProfile(CameraController.CameraProfile.SMOOTH)
                gestureController.execute(convertActionType(action.type))
            }
            
            ActionType.HEAL -> {
                gestureController.execute(com.ffai.assistant.action.Action.heal())
            }
            
            ActionType.RELOAD -> {
                gestureController.execute(com.ffai.assistant.action.Action.reload())
            }
            
            ActionType.CROUCH -> {
                gestureController.execute(com.ffai.assistant.action.Action.crouch())
            }
            
            ActionType.JUMP -> {
                gestureController.execute(com.ffai.assistant.action.Action.jump())
            }
            
            ActionType.ROTATE_LEFT, ActionType.ROTATE_RIGHT -> {
                // Rotación según modo de confianza
                val profile = when (confidenceEngine.getCurrentMode()) {
                    com.ffai.assistant.core.ConfidenceMode.AGGRESSIVE -> 
                        CameraController.CameraProfile.AGGRESSIVE
                    com.ffai.assistant.core.ConfidenceMode.CONSERVATIVE -> 
                        CameraController.CameraProfile.SMOOTH
                    else -> CameraController.CameraProfile.MEDIUM
                }
                cameraController.setProfile(profile)
                
                val direction = if (action.type == ActionType.ROTATE_LEFT) -90f else 90f
                cameraController.rotate(direction, 0f)
            }
            
            ActionType.HOLD -> {
                // No hacer nada
            }
            
            else -> {
                gestureController.execute(convertActionType(action.type))
            }
        }
        
        // Reportar decisión para confianza
        confidenceEngine.reportDecision(
            action.type,
            com.ffai.assistant.core.ConfidenceResult.SUCCESS // Determinar basado en resultado
        )
    }

    /**
     * Actualiza capa estratégica (mapa, rutas, etc.).
     */
    private suspend fun updateStrategicLayer() {
        // Actualizar interpretación de mapa
        // (En implementación real, capturaría mapa completo periódicamente)
        
        // Actualizar reasoning engine
        reasoningEngine.updateFPS(getCurrentFPS())
    }

    /**
     * Actualiza overlay visual con información de debug.
     */
    private fun updateOverlay(
        result: EnsembleResult?,
        fastResult: FastEnsembleResult?,
        action: ActionSuggestion
    ) {
        // Dibujar enemigos detectados
        result?.mergedEnemies?.forEach { enemy ->
            overlayService.drawEnemyDetection(
                enemy.x,
                enemy.y,
                enemy.confidence,
                enemy.isLocked
            )
        }
        
        // Dibujar enemigos de fast inference
        fastResult?.enemies?.forEach { enemy ->
            overlayService.drawEnemyDetection(
                enemy.x,
                enemy.y,
                enemy.confidence,
                false
            )
        }
        
        // Mostrar info de modo
        val modeText = when (currentReasoningMode.get()) {
            ReasoningMode.SHORT -> "MODO: CORTO (Instinto)"
            ReasoningMode.MEDIUM -> "MODO: MEDIO (Táctico)"
            ReasoningMode.LONG -> "MODO: LARGO (Estratégico)"
        }
        overlayService.showStatusInfo(modeText, 1000)
    }

    /**
     * Configura callbacks entre componentes.
     */
    private fun setupCallbacks() {
        // Reasoning engine cambio de modo
        reasoningEngine.setOnModeChangedListener { oldMode, newMode ->
            Logger.i(TAG, "Modo de razonamiento: $oldMode -> $newMode")
        }
        
        // Ensemble resultado
        ensembleManager.setOnEnsembleResultListener { result ->
            // Actualizar overlay con confianza
            overlayService.showStatusInfo(
                "Confianza: ${(result.confidence * 100).toInt()}%",
                500
            )
        }
        
        // Confianza cambio de modo
        confidenceEngine.setOnModeChangedListener { oldMode, newMode ->
            Logger.i(TAG, "Modo de confianza: $oldMode -> $newMode")
            
            // Ajustar comportamiento según modo
            when (newMode) {
                com.ffai.assistant.core.ConfidenceMode.CONSERVATIVE -> {
                    cameraController.setProfile(CameraController.CameraProfile.SMOOTH)
                }
                com.ffai.assistant.core.ConfidenceMode.AGGRESSIVE -> {
                    cameraController.setProfile(CameraController.CameraProfile.MEDIUM)
                }
                else -> {}
            }
        }
    }

    /**
     * Convierte ActionType del modelo a Action del sistema.
     */
    private fun convertActionType(type: ActionType): Action {
        return when (type) {
            ActionType.AIM -> Action.aim(360, 800)
            ActionType.SHOOT -> Action.shoot()
            ActionType.MOVE_FORWARD -> Action(ActionType.MOVE_FORWARD, 0, 0, duration = 300)
            ActionType.MOVE_BACKWARD -> Action(ActionType.MOVE_BACKWARD, 0, 0, duration = 300)
            ActionType.MOVE_LEFT -> Action(ActionType.MOVE_LEFT, 0, 0, duration = 300)
            ActionType.MOVE_RIGHT -> Action(ActionType.MOVE_RIGHT, 0, 0, duration = 300)
            ActionType.HEAL -> Action.heal()
            ActionType.RELOAD -> Action.reload()
            ActionType.CROUCH -> Action.crouch()
            ActionType.JUMP -> Action.jump()
            ActionType.LOOT -> Action.loot()
            ActionType.REVIVE -> Action.revive()
            ActionType.ROTATE_LEFT -> Action(ActionType.ROTATE_LEFT, duration = 200)
            ActionType.ROTATE_RIGHT -> Action(ActionType.ROTATE_RIGHT, duration = 200)
            ActionType.HOLD -> Action.hold()
        }
    }

    /**
     * Calcula FPS actual.
     */
    private fun getCurrentFPS(): Int {
        // Calcular basado en tiempo entre frames
        return 30 // Placeholder
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    /**
     * Reporta evento de kill.
     */
    fun reportKill(isEnemy: Boolean, isHeadshot: Boolean = false) {
        if (isEnemy) {
            confidenceEngine.reportEnemyKill(wasHeadshot = isHeadshot)
            rewardShaper.calculateReward(
                lastEnsembleResult.get(),
                lastEnsembleResult.get() ?: return,
                com.ffai.assistant.action.ActionType.SHOOT,
                System.currentTimeMillis()
            )
        } else {
            confidenceEngine.reportFriendlyFire("ally", 100f)
        }
    }

    /**
     * Reporta muerte.
     */
    fun reportDeath() {
        confidenceEngine.reportDeath()
        deepRLCore.endEpisode()
        rewardShaper.reset()
    }

    /**
     * Reporta victoria.
     */
    fun reportVictory() {
        confidenceEngine.reportVictory()
        deepRLCore.endEpisode()
        rewardShaper.reset()
    }

    /**
     * Fuerza modo de razonamiento específico.
     */
    fun forceReasoningMode(mode: ReasoningMode) {
        reasoningEngine.forceMode(mode)
    }

    /**
     * Obtiene estadísticas del sistema.
     */
    fun getStats(): AdvancedAIStats {
        return AdvancedAIStats(
            reasoningMode = currentReasoningMode.get(),
            confidenceMode = confidenceEngine.getCurrentMode(),
            currentConfidence = confidenceEngine.getCurrentConfidence(),
            loadedModels = ensembleManager.getLoadedModelsCount(),
            framesProcessed = frameCount.get(),
            rlStats = deepRLCore.getStats(),
            rewardStats = rewardShaper.getStats()
        )
    }

    /**
     * Libera todos los recursos.
     */
    fun release() {
        stop()
        
        screenAnalyzer.release()
        ensembleManager.release()
        deepRLCore.release()
        gestureController.resetAimPID()
        cameraController.reset()
        smartAimTrainer.reset()
        mapInterpreter.reset()
        confidenceEngine.reset()
        rewardShaper.reset()
        reasoningEngine.reset()
        
        Logger.i(TAG, "AdvancedAICore liberado")
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class AdvancedAIStats(
    val reasoningMode: ReasoningMode,
    val confidenceMode: com.ffai.assistant.core.ConfidenceMode,
    val currentConfidence: Float,
    val loadedModels: Int,
    val framesProcessed: Long,
    val rlStats: com.ffai.assistant.rl.DeepRLStats,
    val rewardStats: com.ffai.assistant.rl.RewardStats
)
