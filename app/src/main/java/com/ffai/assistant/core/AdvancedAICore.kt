package com.ffai.assistant.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
import com.ffai.assistant.telemetry.PerformanceMonitor.PipelineStage
import com.ffai.assistant.navigation.MapInterpreter
import com.ffai.assistant.overlay.AnalysisArea
import com.ffai.assistant.overlay.DynamicOverlayService
import com.ffai.assistant.overlay.FrameData
import com.ffai.assistant.overlay.ScreenAnalyzer
import com.ffai.assistant.rl.DeepRLCore
import com.ffai.assistant.rl.RewardShaper
import com.ffai.assistant.model.ThreatLevel
import com.ffai.assistant.model.MergedEnemy
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// === NUEVOS COMPONENTES FASE 1-6 ===
import com.ffai.assistant.vision.YOLODetector
import com.ffai.assistant.vision.FramePreprocessor
import com.ffai.assistant.vision.VisionFusionEngine
import com.ffai.assistant.vision.FusedEnemy
import com.ffai.assistant.vision.DetectionPostProcessor
import com.ffai.assistant.core.SituationAnalysis
import com.ffai.assistant.core.SituationLevel
import com.ffai.assistant.core.RecommendedAction
import com.ffai.assistant.core.ConfidenceMode
import com.ffai.assistant.rl.EnsembleRLCoordinator
import com.ffai.assistant.rl.RewardEngine
import com.ffai.assistant.rl.ShaperRewardStats
import com.ffai.assistant.rl.EnsembleDecision
import com.ffai.assistant.rl.DeepRLStats
import com.ffai.assistant.rl.EnsembleStats
import com.ffai.assistant.memory.EpisodicMemory
import com.ffai.assistant.gesture.GestureEngine
import com.ffai.assistant.gesture.WeaponController
import com.ffai.assistant.gesture.MovementController
import com.ffai.assistant.telemetry.PerformanceMonitor
import com.ffai.assistant.telemetry.StructuredLogger
import com.ffai.assistant.telemetry.PerformanceMetrics

// === NUEVOS COMPONENTES REDES NEURONALES AVANZADAS (FASE 10-15) ===
import com.ffai.assistant.rl.SuperAgentCoordinator
import com.ffai.assistant.rl.SuperDecision
import com.ffai.assistant.rl.worldmodel.DreamerAgent
import com.ffai.assistant.rl.worldmodel.WorldModel
import com.ffai.assistant.rl.transformer.TransformerAgent
import com.ffai.assistant.rl.curiosity.ICMModule
import com.ffai.assistant.rl.curiosity.IntrinsicRewardEngine
import com.ffai.assistant.rl.hierarchical.MetaController
import com.ffai.assistant.rl.hierarchical.SubPolicyManager
import com.ffai.assistant.rl.metalearning.MAMLAgent
import com.ffai.assistant.rl.metalearning.FastAdaptation

// === SISTEMA DE MODELOS TFLITE ===
import com.ffai.assistant.model.ModelManager

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
    
    // Componentes LEGACY (mantenidos para compatibilidad)
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
    
    // === NUEVOS COMPONENTES FASE 1-6 ===
    // Fase 1-2: Visión y Latencia
    private lateinit var yoloDetector: YOLODetector
    private lateinit var framePreprocessor: FramePreprocessor
    private lateinit var visionFusionEngine: VisionFusionEngine
    private lateinit var situationAnalyzer: SituationAnalyzer
    private lateinit var adaptiveInferencePipeline: AdaptiveInferencePipeline
    private lateinit var inferenceScheduler: InferenceScheduler
    private lateinit var thermalManager: ThermalManager
    
    // Fase 3: RL Ensemble
    private lateinit var ensembleRL: EnsembleRLCoordinator
    private lateinit var rewardEngine: RewardEngine
    private lateinit var episodicMemory: EpisodicMemory
    
    // Fase 4: Gestos
    private lateinit var gestureEngine: GestureEngine
    private lateinit var weaponController: WeaponController
    private lateinit var movementController: MovementController
    
    // Fase 5-6: Persistencia y Telemetría
    private lateinit var performanceMonitor: PerformanceMonitor
    private lateinit var structuredLogger: StructuredLogger
    
    // === NUEVOS COMPONENTES REDES NEURONALES AVANZADAS (FASE 10-15) ===
    private lateinit var superAgentCoordinator: SuperAgentCoordinator
    
    // === SISTEMA DE MODELOS TFLITE ===
    private lateinit var modelManager: ModelManager
    
    // Flags de configuración
    private val useAdvancedNeuralPipeline = true  // NUEVO: Pipeline avanzado
    private val useSuperAgent = true              // NUEVO: SuperAgentCoordinator
    private val enableAutoDownload = true         // NUEVO: Descarga automática de modelos
    
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
        Logger.i(TAG, "Inicializando AdvancedAICore con nuevos componentes...")
        
        // ========== SISTEMA DE MODELOS TFLITE (FASE 0) ==========
        Logger.i(TAG, "[FASE 0] Inicializando gestor de modelos...")
        
        modelManager = ModelManager(context)
        modelManager.initialize() // Crea placeholders si faltan modelos
        
        val modelStatus = modelManager.getStatus()
        val realModels = modelStatus.count { !it.value.isPlaceholder }
        val placeholders = modelStatus.count { it.value.isPlaceholder }
        Logger.i(TAG, "Modelos: $realModels reales, $placeholders placeholders")
        
        // ========== LEGACY COMPONENTS (mantenidos para compatibilidad) ==========
        
        // 1. Screen Analyzer
        screenAnalyzer = ScreenAnalyzer(
            width = gameConfig.screenWidth,
            height = gameConfig.screenHeight
        )
        screenAnalyzer.initialize()
        
        // 2. Overlay Service
        overlayService = DynamicOverlayService()
        
        // 3. Model Ensemble Legacy
        ensembleManager = ModelEnsembleManager(context, coroutineScope)
        ensembleManager.initialize()
        
        // 4. Reasoning Engine Legacy
        reasoningEngine = ReasoningEngine(coroutineScope)
        
        // 5. Controladores Legacy
        gestureController = GestureController(service, gameConfig)
        cameraController = CameraController(service, gameConfig)
        smartAimTrainer = SmartAimTrainer()
        
        // 6. Map Interpreter
        mapInterpreter = MapInterpreter()
        
        // 7. RL Legacy
        deepRLCore = DeepRLCore(context)
        deepRLCore.initialize()
        rewardShaper = RewardShaper()
        
        // 8. Confidence Engine
        confidenceEngine = ConfidenceEngine()
        
        // ========== NUEVOS COMPONENTES FASE 1-6 ==========
        
        // Fase 1-2: Visión Robusta y Latencia Adaptativa
        Logger.i(TAG, "[FASE 1-2] Inicializando visión y latencia...")
        
        framePreprocessor = FramePreprocessor(context)
        yoloDetector = YOLODetector(context)
        visionFusionEngine = VisionFusionEngine()
        
        situationAnalyzer = SituationAnalyzer()
        inferenceScheduler = InferenceScheduler()
        adaptiveInferencePipeline = AdaptiveInferencePipeline(
            yoloDetector = yoloDetector,
            preprocessor = framePreprocessor,
            postProcessor = DetectionPostProcessor()
        )
        thermalManager = ThermalManager(context)
        thermalManager.onThermalThreshold { thermalState ->
            Logger.w(TAG, "Thermal throttling: estado $thermalState")
            adaptiveInferencePipeline.onThermalThrottle(thermalState)
        }
        
        // Inicializar visión
        val yoloOk = yoloDetector.initialize()
        // VisionFusionEngine no requiere inicialización explícita
        Logger.i(TAG, "YOLO: ${if(yoloOk) "OK" else "FAIL"}, Fusion: OK")
        
        // Fase 3: Ensemble RL Profesional
        Logger.i(TAG, "[FASE 3] Inicializando Ensemble RL (DQN+PPO+SAC)...")
        
        ensembleRL = EnsembleRLCoordinator(context)
        val rlOk = ensembleRL.initialize()
        
        rewardEngine = RewardEngine()
        episodicMemory = EpisodicMemory()
        
        Logger.i(TAG, "Ensemble RL: ${if(rlOk) "OK" else "FAIL"}")
        
        // Fase 4: Gestos Precisos
        Logger.i(TAG, "[FASE 4] Inicializando control de gestos...")
        
        gestureEngine = GestureEngine(service)
        weaponController = WeaponController(gestureEngine)
        movementController = MovementController(gestureEngine)
        
        // Fase 5-6: Telemetría
        Logger.i(TAG, "[FASE 5-6] Inicializando telemetría...")
        
        performanceMonitor = PerformanceMonitor(coroutineScope)
        structuredLogger = StructuredLogger(context, coroutineScope)
        
        performanceMonitor.recordFrame(0L)
        structuredLogger.startLogging()
        
        // ========== NUEVOS COMPONENTES REDES NEURONALES AVANZADAS (FASE 10-15) ==========
        if (useSuperAgent) {
            Logger.i(TAG, "[FASE 10-15] Inicializando Redes Neuronales Avanzadas...")
            
            superAgentCoordinator = SuperAgentCoordinator(context)
            val superOk = superAgentCoordinator.initialize()
            
            Logger.i(TAG, "SuperAgentCoordinator: ${if(superOk) "OK" else "FAIL"}")
            Logger.i(TAG, "  - World Model + Dreamer: Planificación lookahead")
            Logger.i(TAG, "  - Transformer: Memoria temporal 64 frames")
            Logger.i(TAG, "  - ICM: Exploración por curiosidad")
            Logger.i(TAG, "  - Hierarchical RL: Meta-controller + 7 goals")
            Logger.i(TAG, "  - MAML: Adaptación rápida a nuevas tareas")
        }
        
        // Configurar callbacks
        setupCallbacks()
        
        Logger.i(TAG, "AdvancedAICore inicializado correctamente")
        Logger.i(TAG, "Legacy: ${ensembleManager.getLoadedModelsCount()}/8 modelos")
        Logger.i(TAG, "Nuevo: YOLO + Ensemble RL + GestureEngine activos")
        if (useSuperAgent) {
            Logger.i(TAG, "Advanced Neural: SuperAgentCoordinator activo (+20MB modelos)")
        }
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
     * Detiene el sistema y libera recursos.
     */
    fun stop() {
        isRunning.set(false)
        screenAnalyzer.stop()
        
        // Liberar nuevos componentes
        coroutineScope.launch {
            structuredLogger.stopLogging()
            performanceMonitor.recordFrame(0L)
        }
        
        yoloDetector.release()
        // visionFusionEngine no tiene método de release
        ensembleRL.release()
        gestureEngine.shutdown()
        thermalManager.stopMonitoring()
        
        // Liberar componentes avanzados
        if (useSuperAgent && ::superAgentCoordinator.isInitialized) {
            superAgentCoordinator.release()
        }
        
        coroutineScope.cancel()
        Logger.i(TAG, "AdvancedAICore detenido y recursos liberados")
        if (useSuperAgent) {
            Logger.i(TAG, "SuperAgentCoordinator: recursos liberados")
        }
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
                    MergedEnemy(
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
                    gestureController.execute(Action.shoot())
                    
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
                gestureController.execute(Action.heal())
            }
            
            ActionType.RELOAD -> {
                gestureController.execute(Action.reload())
            }
            
            ActionType.CROUCH -> {
                gestureController.execute(Action.crouch())
            }
            
            ActionType.JUMP -> {
                gestureController.execute(Action.jump())
            }
            
            ActionType.ROTATE_LEFT, ActionType.ROTATE_RIGHT -> {
                // Rotación según modo de confianza
                val profile = when (confidenceEngine.getCurrentMode()) {
                    ConfidenceMode.AGGRESSIVE -> 
                        CameraController.CameraProfile.AGGRESSIVE
                    ConfidenceMode.CONSERVATIVE -> 
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
            ConfidenceResult.SUCCESS // Determinar basado en resultado
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
                ConfidenceMode.CONSERVATIVE -> {
                    cameraController.setProfile(CameraController.CameraProfile.SMOOTH)
                }
                ConfidenceMode.AGGRESSIVE -> {
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
                ActionType.SHOOT,
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
            rewardStats = rewardShaper.getStats(),
            yoloDetections = if (::yoloDetector.isInitialized) yoloDetector.getStats().totalDetections else 0,
            ensembleRLStats = if (::ensembleRL.isInitialized) ensembleRL.getStats() else null,
            performanceMetrics = if (::performanceMonitor.isInitialized) performanceMonitor.getMetrics() else null
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

    // ============================================
    // NUEVO PIPELINE INTEGRADO (FASES 1-6)
    // ============================================

    /**
     * Procesa frame con nuevo pipeline integrado.
     * Este método utiliza YOLO + Ensemble RL + GestureEngine
     * o SuperAgentCoordinator (World Model + Transformer + ICM + Hierarchical + MAML)
     * PÚBLICO: Llamado desde FFAccessibilityService
     */
    suspend fun processFrameEnhanced(bitmap: android.graphics.Bitmap) {
        val frameStart = System.currentTimeMillis()
        
        // ========== PIPELINE AVANZADO CON SUPER AGENT ==========
        if (useSuperAgent && ::superAgentCoordinator.isInitialized) {
            val superStart = System.currentTimeMillis()
            
            // 1. Preparar estado (256 dims) desde YOLO
            val preprocessStart = System.currentTimeMillis()
            val detections = yoloDetector.detect(bitmap)
            val fusedEnemies = visionFusionEngine.fuseEnemyDetections(
                yoloDetections = detections,
                combatOutput = null,
                visionOutput = null
            )
            val situation = situationAnalyzer.analyze(
                enemies = fusedEnemies,
                trackedObjects = emptyList(),
                hp = 100,
                ammo = 30,
                screenWidth = 1920,
                screenHeight = 1080
            )
            val state = buildStateVector(fusedEnemies, situation)
            
            // 2. SuperAgentCoordinator decide (integra: WorldModel, Transformer, ICM, Hierarchical, MAML)
            val decision = superAgentCoordinator.decide(bitmap, state)
            
            performanceMonitor.measureDecision(System.currentTimeMillis() - superStart)
            
            // 3. Ejecutar acción con GestureEngine
            executeSuperAction(decision, fusedEnemies)
            
            // 4. Logging del pipeline avanzado
            structuredLogger.logDecision(
                timestamp = System.currentTimeMillis(),
                state = mapOf(
                    "enemies" to fusedEnemies.size,
                    "goal" to decision.goal.name,
                    "super_action" to decision.action,
                    "pipeline" to "SuperAgent"
                ),
                action = when(decision.action) {
                    0 -> ActionType.AIM
                    1 -> ActionType.SHOOT
                    2 -> ActionType.MOVE_FORWARD
                    3 -> ActionType.MOVE_BACKWARD
                    4 -> ActionType.MOVE_LEFT
                    5 -> ActionType.MOVE_RIGHT
                    6 -> ActionType.JUMP
                    7 -> ActionType.CROUCH
                    else -> ActionType.HOLD
                },
                confidence = decision.confidence,
                reasoning = "SuperAgent: Goal=${decision.goal}, Components=${decision.components}",
                latencyMs = decision.latencyMs
            )
            
            val totalLatency = System.currentTimeMillis() - frameStart
            performanceMonitor.recordFrame(totalLatency)
            bitmap.recycle()
            return
        }
        
        // ========== PIPELINE LEGACY (YOLO + Ensemble RL) ==========
        
        // 1. PREPROCESAMIENTO (GPU)
        val preprocessStart = System.currentTimeMillis()
        val inputBuffer = framePreprocessor.preprocess(bitmap)
        performanceMonitor.recordStageTime(PipelineStage.PREPROCESS, System.currentTimeMillis() - preprocessStart)
        
        // 2. ANÁLISIS DE SITUACIÓN
        val situation = situationAnalyzer.analyze(
            enemies = emptyList(),
            trackedObjects = emptyList(),
            hp = 100,
            ammo = 30,
            screenWidth = 1920,
            screenHeight = 1080
        )
        
        // 3. INFERENCIA YOLO
        val yoloStart = System.currentTimeMillis()
        val detections = yoloDetector.detect(bitmap)
        performanceMonitor.recordStageTime(PipelineStage.YOLO_INFERENCE, System.currentTimeMillis() - yoloStart)
        
        // Fusionar con modelos legacy
        val fusedEnemies = visionFusionEngine.fuseEnemyDetections(
            yoloDetections = detections,
            combatOutput = null,
            visionOutput = null
        )
        
        // 4. ENSEMBLE RL DECISION
        val rlStart = System.currentTimeMillis()
        
        // Construir estado (256 dims)
        val state = buildStateVector(fusedEnemies, situation)
        
        // Seleccionar acción con ensemble
        val decision = ensembleRL.selectAction(state)
        performanceMonitor.recordStageTime(PipelineStage.RL_DECISION, System.currentTimeMillis() - rlStart)
        
        // 5. EJECUTAR ACCIÓN CON GESTUREENGINE
        val gestureStart = System.currentTimeMillis()
        executeEnhancedAction(decision, fusedEnemies)
        performanceMonitor.recordStageTime(PipelineStage.GESTURE_EXECUTION, System.currentTimeMillis() - gestureStart)
        
        // 6. APRENDIZAJE
        val reward = rewardEngine.calculateReward(
            action = decision.action,
            currentHealth = 100,
            currentAmmo = 30,
            enemies = fusedEnemies,
            screenWidth = gameConfig.screenWidth,
            screenHeight = gameConfig.screenHeight
        )
        
        // Almacenar experiencia
        val nextState = buildStateVector(fusedEnemies, situation)
        ensembleRL.storeExperience(state, decision.action, reward.totalReward, nextState, false)
        
        // Entrenar
        ensembleRL.trainStep()
        
        // 7. LOGGING
        structuredLogger.logDecision(
            timestamp = System.currentTimeMillis(),
            state = mapOf(
                "enemies" to fusedEnemies.size,
                "threat" to situation.level.name,
                "mode" to situation.recommendedMode.name,
                "pipeline" to "Legacy"
            ),
            action = decision.action,
            confidence = decision.confidence,
            reasoning = "Ensemble: ${decision.primaryAgent?.name ?: "none"}, Consensus: ${decision.consensus}",
            latencyMs = System.currentTimeMillis() - frameStart
        )
        
        // 8. ACTUALIZAR MÉTRICAS
        val totalLatency = System.currentTimeMillis() - frameStart
        performanceMonitor.recordFrame(totalLatency)
        
        // Liberar bitmap nativo
        bitmap.recycle()
    }

    /**
     * Construye vector de estado para RL.
     */
    private fun buildStateVector(
        enemies: List<FusedEnemy>,
        situation: SituationAnalysis
    ): FloatArray {
        val state = FloatArray(256) { 0f }
        
        // Enemigos (hasta 5, 20 valores cada uno)
        enemies.take(5).forEachIndexed { index, enemy ->
            val offset = index * 20
            state[offset] = enemy.centerX() / gameConfig.screenWidth
            state[offset + 1] = enemy.centerY() / gameConfig.screenHeight
            state[offset + 2] = enemy.fusedConfidence
            state[offset + 3] = enemy.area() / (gameConfig.screenWidth * gameConfig.screenHeight)
            state[offset + 4] = if (enemy.hasYOLOConfirmation) 1f else 0f
        }
        
        // Situación (últimos 156 valores)
        state[100] = when (situation.level) {
            SituationLevel.CRITICAL -> 1f
            SituationLevel.HIGH -> 0.8f
            SituationLevel.MEDIUM -> 0.5f
            SituationLevel.LOW -> 0.2f
            else -> 0f
        }
        state[101] = if (situation.recommendedAction == RecommendedAction.ENGAGE_NEAREST) 1f else 0f
        state[102] = if (situation.recommendedAction == RecommendedAction.RETREAT_TO_COVER) 1f else 0f
        
        return state
    }

    /**
     * Ejecuta acción usando nuevos componentes.
     */
    private fun executeEnhancedAction(
        decision: EnsembleDecision,
        enemies: List<FusedEnemy>
    ) {
        Logger.i(TAG, "executeEnhancedAction: action=${decision.action}, conf=${decision.confidence}, enemies=${enemies.size}")
        
        when (decision.action) {
            ActionType.AIM -> {
                // Aim al enemigo más cercano
                val target = enemies.maxByOrNull { it.fusedConfidence }
                target?.let {
                    gestureEngine.swipe(
                        startX = gameConfig.screenWidth / 2f,
                        startY = gameConfig.screenHeight / 2f,
                        deltaX = it.centerX() - gameConfig.screenWidth / 2f,
                        deltaY = it.centerY() - gameConfig.screenHeight / 2f,
                        speedFactor = 1.5f
                    )
                }
            }
            
            ActionType.SHOOT -> {
                val target = enemies.firstOrNull()
                if (target != null) {
                    weaponController.shoot(
                        targetX = target.centerX(),
                        targetY = target.centerY(),
                        distance = 50f  // TODO: Calcular distancia real
                    )
                }
            }
            
            ActionType.MOVE_FORWARD, ActionType.MOVE_BACKWARD,
            ActionType.MOVE_LEFT, ActionType.MOVE_RIGHT -> {
                // Usar MovementController para movimiento táctico
                val deltaX = when (decision.action) {
                    ActionType.MOVE_LEFT -> -100f
                    ActionType.MOVE_RIGHT -> 100f
                    else -> 0f
                }
                val deltaY = when (decision.action) {
                    ActionType.MOVE_FORWARD -> -100f
                    ActionType.MOVE_BACKWARD -> 100f
                    else -> 0f
                }
                movementController.moveTo(
                    targetX = gameConfig.screenWidth / 2f + deltaX,
                    targetY = gameConfig.screenHeight / 2f + deltaY
                )
            }
            
            ActionType.CROUCH -> {
                gestureEngine.crouch()
            }
            
            ActionType.JUMP -> {
                gestureEngine.jump()
            }
            
            ActionType.HEAL -> {
                gestureEngine.heal()
            }
            
            ActionType.RELOAD -> {
                gestureEngine.reload()
            }
            
            ActionType.HOLD -> {
                // No hacer nada
            }
            
            else -> {
                // Fallback a gesture controller legacy
                gestureController.execute(convertActionType(decision.action))
            }
        }
    }

    /**
     * Ejecuta acción usando SuperAgentCoordinator.
     */
    private fun executeSuperAction(
        decision: SuperDecision,
        enemies: List<FusedEnemy>
    ) {
        val actionType = when (decision.action) {
            0 -> ActionType.AIM
            1 -> ActionType.SHOOT
            2 -> ActionType.MOVE_FORWARD
            3 -> ActionType.MOVE_BACKWARD
            4 -> ActionType.MOVE_LEFT
            5 -> ActionType.MOVE_RIGHT
            6 -> ActionType.JUMP
            7 -> ActionType.CROUCH
            8 -> ActionType.HEAL
            9 -> ActionType.RELOAD
            10 -> ActionType.LOOT
            11 -> ActionType.REVIVE
            12 -> ActionType.ROTATE_LEFT
            13 -> ActionType.ROTATE_RIGHT
            else -> ActionType.HOLD
        }
        
        when (actionType) {
            ActionType.AIM -> {
                val target = enemies.maxByOrNull { it.fusedConfidence }
                target?.let {
                    gestureEngine.swipe(
                        startX = gameConfig.screenWidth / 2f,
                        startY = gameConfig.screenHeight / 2f,
                        deltaX = it.centerX() - gameConfig.screenWidth / 2f,
                        deltaY = it.centerY() - gameConfig.screenHeight / 2f,
                        speedFactor = 1.5f
                    )
                }
            }
            
            ActionType.SHOOT -> {
                val target = enemies.firstOrNull()
                if (target != null) {
                    weaponController.shoot(
                        targetX = target.centerX(),
                        targetY = target.centerY(),
                        distance = 50f
                    )
                }
            }
            
            ActionType.MOVE_FORWARD, ActionType.MOVE_BACKWARD,
            ActionType.MOVE_LEFT, ActionType.MOVE_RIGHT -> {
                val deltaX = when (actionType) {
                    ActionType.MOVE_LEFT -> -100f
                    ActionType.MOVE_RIGHT -> 100f
                    else -> 0f
                }
                val deltaY = when (actionType) {
                    ActionType.MOVE_FORWARD -> -100f
                    ActionType.MOVE_BACKWARD -> 100f
                    else -> 0f
                }
                movementController.moveTo(
                    targetX = gameConfig.screenWidth / 2f + deltaX,
                    targetY = gameConfig.screenHeight / 2f + deltaY
                )
            }
            
            ActionType.CROUCH -> gestureEngine.crouch()
            ActionType.JUMP -> gestureEngine.jump()
            ActionType.HEAL -> gestureEngine.heal()
            ActionType.RELOAD -> gestureEngine.reload()
            ActionType.HOLD -> { /* No hacer nada */ }
            else -> gestureController.execute(convertActionType(actionType))
        }
    }

    /**
     * Obtiene estadísticas completas incluyendo nuevos componentes.
     */
    fun getEnhancedStats(): AdvancedAIStats {
        return AdvancedAIStats(
            reasoningMode = currentReasoningMode.get(),
            confidenceMode = if (::confidenceEngine.isInitialized) confidenceEngine.getCurrentMode() else ConfidenceMode.CONSERVATIVE,
            currentConfidence = if (::confidenceEngine.isInitialized) confidenceEngine.getCurrentConfidence() else 0.5f,
            loadedModels = if (::ensembleManager.isInitialized) ensembleManager.getLoadedModelsCount() else 0,
            framesProcessed = frameCount.get(),
            rlStats = if (::deepRLCore.isInitialized) deepRLCore.getStats() else DeepRLStats(
                trainingSteps = 0,
                episodes = 0,
                epsilon = 0f,
                bufferSize = 0,
                averageQValue = 0f,
                actionDistribution = emptyList(),
                totalReward = 0f
            ),
            rewardStats = if (::rewardShaper.isInitialized) rewardShaper.getStats() else ShaperRewardStats(
                totalKills = 0,
                totalAllyKills = 0,
                totalDeaths = 0,
                totalFriendlyFire = 0,
                cumulativeReward = 0f,
                recentActionCount = 0
            ),
            yoloDetections = if (::yoloDetector.isInitialized) yoloDetector.getStats().totalDetections else 0,
            ensembleRLStats = if (::ensembleRL.isInitialized) ensembleRL.getStats() else null,
            performanceMetrics = if (::performanceMonitor.isInitialized) performanceMonitor.getMetrics() else null,
            superAgentStats = if (useSuperAgent && ::superAgentCoordinator.isInitialized) "Active" else null,
            activePipeline = if (useSuperAgent && ::superAgentCoordinator.isInitialized) "SuperAgent" else "Legacy"
        )
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class AdvancedAIStats(
    val reasoningMode: ReasoningMode,
    val confidenceMode: ConfidenceMode,
    val currentConfidence: Float,
    val loadedModels: Int,
    val framesProcessed: Long,
    val rlStats: DeepRLStats,
    val rewardStats: ShaperRewardStats,
    // Nuevos campos Fase 1-6
    val yoloDetections: Int = 0,
    val ensembleRLStats: EnsembleStats? = null,
    val performanceMetrics: PerformanceMetrics? = null,
    // Nuevos campos Fase 10-15 (Redes Neuronales Avanzadas)
    val superAgentStats: String? = null,
    val activePipeline: String = "Legacy"
)
