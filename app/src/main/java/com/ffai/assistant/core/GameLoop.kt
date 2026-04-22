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

            // 3. Decidir acción (Reflejos primero → Táctico después)
            val decisionStart = System.currentTimeMillis()
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
