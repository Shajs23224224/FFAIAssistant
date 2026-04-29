package com.ffai.assistant.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.action.GestureController
import com.ffai.assistant.config.GameConfig
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.perception.VisionProcessor
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * GameLoop - Loop principal de 3 threads para pipeline de IA.
 * 
 * Thread 1 (CAPTURE): Captura frames a 60Hz → cola de frames
 * Thread 2 (INFERENCE): Preprocesa + percibe + decide a 10-15Hz → cola de acciones
 * Thread 3 (ACTION): Ejecuta acciones inmediatamente
 * 
 * Comunicación entre threads vía ConcurrentLinkedQueue (lock-free).
 * 
 * Latencia objetivo total: <70ms
 *   - Capture: <10ms
 *   - Preprocess: <10ms
 *   - Inference: <30ms
 *   - Action: <20ms
 */
class GameLoop {
    private val visionProcessor = VisionProcessor()

    // Threads dedicados
    private val captureThread = HandlerThread("FF-Capture", Thread.NORM_PRIORITY + 2)
    private val inferenceThread = HandlerThread("FF-Inference", Thread.NORM_PRIORITY + 1)
    private val actionThread = HandlerThread("FF-Action", Thread.NORM_PRIORITY)

    private var captureHandler: Handler? = null
    private var inferenceHandler: Handler? = null
    private var actionHandler: Handler? = null

    // Colas lock-free entre threads
    private val frameQueue = ConcurrentLinkedQueue<FrameData>()
    private val actionQueue = ConcurrentLinkedQueue<ActionData>()

    // Componentes del pipeline
    private var captureManager: CaptureManager? = null
    private var preprocessor: Preprocessor? = null
    private var roiTracker: ROITracker? = null
    private var reflexEngine: ReflexEngine? = null
    private var decisionEngine: DecisionEngine? = null
    private var gestureController: GestureController? = null

    // Estado del loop
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Métricas de latencia por etapa
    private val captureLatency = AtomicLong(0)
    private val preprocessLatency = AtomicLong(0)
    private val inferenceLatency = AtomicLong(0)
    private val actionLatency = AtomicLong(0)
    private val totalLatency = AtomicLong(0)

    // Frame counter
    private val frameCounter = AtomicLong(0)

    // Intervalo de inferencia (ms)
    var inferenceIntervalMs: Long = 66L  // ~15Hz
    var strategyIntervalMs: Long = 2000L

    @Volatile
    private var lastStrategyUpdateMs: Long = 0L

    // Último estado del juego (compartido entre threads, volatile)
    @Volatile
    private var currentGameState: GameState = GameState.DEFAULT

    companion object {
        private const val MAX_FRAME_QUEUE_SIZE = 3
        private const val MAX_ACTION_QUEUE_SIZE = 5
    }

    /**
     * Inicializa el GameLoop con todos los componentes.
     */
    fun initialize(
        captureManager: CaptureManager,
        preprocessor: Preprocessor,
        roiTracker: ROITracker,
        reflexEngine: ReflexEngine,
        decisionEngine: DecisionEngine,
        gestureController: GestureController,
        gameConfig: GameConfig
    ) {
        this.captureManager = captureManager
        this.preprocessor = preprocessor
        this.roiTracker = roiTracker
        this.reflexEngine = reflexEngine
        this.decisionEngine = decisionEngine
        this.gestureController = gestureController

        // Inicializar ROI tracker con dimensiones de pantalla
        roiTracker.reset()

        Logger.i("GameLoop: Components initialized")
    }

    /**
     * Inicia el loop de 3 threads.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Logger.w("GameLoop: Already running")
            return
        }

        // Iniciar threads
        captureThread.start()
        inferenceThread.start()
        actionThread.start()

        captureHandler = Handler(captureThread.looper)
        inferenceHandler = Handler(inferenceThread.looper)
        actionHandler = Handler(actionThread.looper)

        // Iniciar pipeline de inferencia
        scheduleInference()

        // Iniciar pipeline de acciones
        scheduleActionExecution()

        Logger.i("GameLoop: Started 3-thread pipeline")
    }

    /**
     * Detiene el loop completamente.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        captureHandler?.removeCallbacksAndMessages(null)
        inferenceHandler?.removeCallbacksAndMessages(null)
        actionHandler?.removeCallbacksAndMessages(null)

        captureThread.quitSafely()
        inferenceThread.quitSafely()
        actionThread.quitSafely()

        frameQueue.clear()
        actionQueue.clear()

        Logger.i("GameLoop: Stopped")
    }

    /**
     * Pausa/reanuda la ejecución de acciones (mantiene captura e inferencia).
     */
    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        Logger.i("GameLoop: ${if (paused) "Paused" else "Resumed"}")
    }

    /**
     * Recibe un frame del CaptureManager y lo encola para inferencia.
     * Llamado desde el thread de captura de MediaProjection.
     */
    fun onFrameAvailable(bitmap: Bitmap) {
        if (!isRunning.get()) return

        // Limitar cola de frames (descartar viejos si se acumulan)
        while (frameQueue.size >= MAX_FRAME_QUEUE_SIZE) {
            val old = frameQueue.poll()
            old?.bitmap?.let { captureManager?.releaseBitmap(it) }
        }

        frameQueue.add(FrameData(
            bitmap = bitmap,
            timestamp = SystemClock.elapsedRealtimeNanos() / 1_000_000,
            frameNumber = frameCounter.incrementAndGet()
        ))
    }

    /**
     * Actualiza el estado del juego (llamado desde perception engine).
     */
    fun updateGameState(state: GameState) {
        currentGameState = state
    }

    /**
     * Obtiene el estado actual del juego.
     */
    fun getCurrentGameState(): GameState = currentGameState

    // ============================================
    // INFERENCE THREAD
    // ============================================

    private fun scheduleInference() {
        inferenceHandler?.postDelayed({
            if (!isRunning.get()) return@postDelayed

            processNextFrame()
            scheduleInference()
        }, inferenceIntervalMs)
    }

    private fun processNextFrame() {
        val frameData = frameQueue.poll()
        if (frameData == null) return

        val inferenceStart = System.currentTimeMillis()

        try {
            val bitmap = frameData.bitmap
            val captureTime = frameData.timestamp

            // 1. Preprocesar (ROI + resize + buffer)
            val roi = if (roiTracker?.isROIContractionActive() == true) {
                roiTracker!!.getCurrentROI()
            } else null

            val preprocessStart = System.currentTimeMillis()
            val inputBuffer = preprocessor?.preprocess(bitmap, roi)
            preprocessLatency.set(System.currentTimeMillis() - preprocessStart)

            // 2. Extraer features rápidos para reflejos
            val quickFeatures = preprocessor?.extractQuickFeatures(bitmap)
            val visionState = visionProcessor.analyze(bitmap)
            currentGameState = synthesizeGameState(visionState, currentGameState, quickFeatures)

            // 3. Decidir acción (Reflejos primero → Táctico después)
            val decisionStart = System.currentTimeMillis()
            maybeUpdateStrategy(decisionStart)
            val action = decisionEngine?.decide(currentGameState, quickFeatures, inputBuffer)
            inferenceLatency.set(System.currentTimeMillis() - decisionStart)

            // 4. Encolar acción para ejecución
            if (action != null && action.type != ActionType.HOLD) {
                actionQueue.add(ActionData(
                    action = action,
                    timestamp = SystemClock.elapsedRealtimeNanos() / 1_000_000,
                    frameNumber = frameData.frameNumber
                ))
            }

            // 5. Actualizar ROI tracker
            if (quickFeatures != null) {
                if (quickFeatures.enemyPresent) {
                    roiTracker?.onEnemyDetected(quickFeatures.enemyScreenX, quickFeatures.enemyScreenY)
                } else {
                    roiTracker?.onNothingDetected()
                }
            }

            // 6. Adaptar frame skip basado en latencia
            val totalInferenceTime = System.currentTimeMillis() - inferenceStart
            captureManager?.adaptSkipRatio(totalInferenceTime)

            totalLatency.set(System.currentTimeMillis() - inferenceStart)

        } catch (e: Exception) {
            Logger.e("GameLoop: Error in inference", e)
        } finally {
            // Liberar bitmap al pool
            captureManager?.releaseBitmap(frameData.bitmap)
        }
    }

    // ============================================
    // ACTION THREAD
    // ============================================

    private fun scheduleActionExecution() {
        actionHandler?.postDelayed({
            if (!isRunning.get()) return@postDelayed

            executeNextAction()
            scheduleActionExecution()
        }, 16L)  // ~60Hz check
    }

    private fun executeNextAction() {
        if (isPaused.get()) {
            // Descartar acciones acumuladas mientras está pausado
            while (actionQueue.isNotEmpty()) {
                actionQueue.poll()
            }
            return
        }

        val actionData = actionQueue.poll() ?: return

        val actionStart = System.currentTimeMillis()
        try {
            gestureController?.execute(actionData.action)
            actionLatency.set(System.currentTimeMillis() - actionStart)

            // Notificar ROI tracker
            roiTracker?.onActionPerformed(actionData.action.x, actionData.action.y)

        } catch (e: Exception) {
            Logger.e("GameLoop: Error executing action", e)
        }
    }

    private fun maybeUpdateStrategy(nowMs: Long) {
        if (nowMs - lastStrategyUpdateMs < strategyIntervalMs) return
        try {
            decisionEngine?.updateStrategy()
            lastStrategyUpdateMs = nowMs
        } catch (e: Exception) {
            Logger.e("GameLoop: Error updating strategy", e)
        }
    }

    private fun synthesizeGameState(
        visionState: GameState,
        previous: GameState,
        features: QuickVisualFeatures?
    ): GameState {
        if (features == null) return visionState

        val enemyXNorm = if (features.enemyPresent) {
            (((features.enemyScreenX / 1080f) * 2f) - 1f).coerceIn(-1f, 1f)
        } else visionState.enemyX

        val enemyYNorm = if (features.enemyPresent) {
            (((features.enemyScreenY / 2400f) * 2f) - 1f).coerceIn(-1f, 1f)
        } else visionState.enemyY

        val estimatedEnemyDistance = if (features.enemyPresent) {
            (1f - features.centerThreat.coerceIn(0f, 1f)).coerceIn(0.05f, 1f)
        } else {
            (visionState.enemyDistance + 0.05f).coerceAtMost(1f)
        }

        val smoothedHealth = (visionState.healthRatio * 0.55f + previous.healthRatio * 0.15f + features.healthRatio * 0.30f)
            .coerceIn(0f, 1f)
        val smoothedAmmo = (visionState.ammoRatio * 0.55f + previous.ammoRatio * 0.10f + features.ammoRatio * 0.35f)
            .coerceIn(0f, 1f)

        return visionState.copy(
            healthRatio = smoothedHealth,
            ammoRatio = smoothedAmmo,
            enemyPresent = visionState.enemyPresent || features.enemyPresent || features.enemyPersistence > 0.45f,
            enemyX = enemyXNorm,
            enemyY = enemyYNorm,
            enemyDistance = estimatedEnemyDistance,
            enemyCount = maxOf(visionState.enemyCount, if (features.enemyPresent || features.enemyPersistence > 0.55f) 1 else 0),
            shootCooldown = if (features.isFiring) 0.85f else 0f,
            isAiming = visionState.isAiming || features.centerThreat > 0.35f || previous.isAiming,
            isInSafeZone = visionState.isInSafeZone && features.safeZoneIndicator > 0.20f,
            hasHealItems = visionState.hasHealItems || previous.hasHealItems,
            safeZoneShrinking = visionState.safeZoneShrinking || features.safeZoneIndicator < 0.25f,
            distanceToSafeZone = (1f - features.safeZoneIndicator).coerceIn(0f, 1f),
            damageTaken = if (features.recentDamageLikely) previous.damageTaken + 8f else previous.damageTaken * 0.94f,
            timestamp = System.currentTimeMillis()
        )
    }

    // ============================================
    // STATS & DIAGNOSTICS
    // ============================================

    fun getLatencyStats(): LatencyStats {
        return LatencyStats(
            captureMs = captureLatency.get(),
            preprocessMs = preprocessLatency.get(),
            inferenceMs = inferenceLatency.get(),
            actionMs = actionLatency.get(),
            totalMs = totalLatency.get(),
            frameQueueSize = frameQueue.size,
            actionQueueSize = actionQueue.size,
            isRunning = isRunning.get(),
            isPaused = isPaused.get()
        )
    }

    fun destroy() {
        stop()
        captureManager?.destroy()
        preprocessor?.destroy()
    }

    // Data classes internas
    private data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Long,
        val frameNumber: Long
    )

    private data class ActionData(
        val action: Action,
        val timestamp: Long,
        val frameNumber: Long
    )

    data class LatencyStats(
        val captureMs: Long,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val actionMs: Long,
        val totalMs: Long,
        val frameQueueSize: Int,
        val actionQueueSize: Int,
        val isRunning: Boolean,
        val isPaused: Boolean
    )
}
