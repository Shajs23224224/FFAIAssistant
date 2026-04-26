package com.ffai.assistant.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sistema Avanzado de Gestos - 3 Capas
 * Planner (Intención) → Synthesizer (Ejecución) → Dispatcher (Envío)
 * Soporta 14+ tipos de gestos complejos para shooter móvil
 */
class GestureSystem(private val service: AccessibilityService) {

    companion object {
        const val TAG = "GestureSystem"
        
        // Timing
        const val DEFAULT_GESTURE_DURATION = 100L
        const val MIN_GESTURE_DURATION = 16L      // 1 frame a 60Hz
        const val MAX_GESTURE_DURATION = 2000L
        
        // Configuración A21S
        const val TOUCHSCREEN_DEADZONE = 10f      // Píxeles
        const val DEFAULT_PRESSURE = 0.7f
    }

    // ============================================
    // CAPAS
    // ============================================
    
    val planner = GesturePlanner()
    val synthesizer = GestureSynthesizer()
    val dispatcher = GestureDispatcher(service)
    
    // Estado
    private val isRunning = AtomicBoolean(false)
    private val pendingGestures = ConcurrentLinkedQueue<GesturePlan>()
    private val gestureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ============================================
    // INICIALIZACIÓN
    // ============================================
    
    fun initialize() {
        Logger.i(TAG, "GestureSystem inicializado")
        isRunning.set(true)
        
        // Iniciar loop de procesamiento de gestos
        gestureScope.launch {
            while (isRunning.get()) {
                processNextGesture()
                delay(1) // Mínimo delay para no saturar CPU
            }
        }
    }

    fun shutdown() {
        isRunning.set(false)
        gestureScope.cancel()
        dispatcher.cancelPending()
        Logger.i(TAG, "GestureSystem detenido")
    }

    // ============================================
    // API PRINCIPAL
    // ============================================
    
    /**
     * Ejecuta una intención de acción como gesto completo
     */
    fun execute(intention: GestureIntention) {
        // 1. Planificar
        val plan = planner.plan(intention)
        
        // 2. Síntesis
        val rawGesture = synthesizer.synthesize(plan)
        
        // 3. Encolar para ejecución
        pendingGestures.offer(plan)
        
        Logger.d(TAG, "Gest encolado: ${intention::class.simpleName}")
    }

    /**
     * Ejecuta secuencia de gestos (combo)
     */
    fun executeSequence(intentions: List<GestureIntention>, delays: List<Long>) {
        gestureScope.launch {
            intentions.forEachIndexed { index, intention ->
                execute(intention)
                
                // Delay entre gestos
                val delay = delays.getOrNull(index) ?: 100
                delay(delay)
            }
        }
    }

    private suspend fun processNextGesture() {
        val plan = pendingGestures.poll() ?: return
        
        // Síntesis y ejecución
        val rawGesture = synthesizer.synthesize(plan)
        dispatcher.dispatch(rawGesture)
    }

    // ============================================
    // GESTURE PLANNER - Capa 1: Intención
    // ============================================
    
    class GesturePlanner {
        
        fun plan(intention: GestureIntention): GesturePlan {
            return when (intention) {
                is TapIntention -> planTap(intention)
                is HoldIntention -> planHold(intention)
                is SwipeIntention -> planSwipe(intention)
                is DragIntention -> planDrag(intention)
                is MultiTouchIntention -> planMultiTouch(intention)
                is CameraSwipeIntention -> planCameraSwipe(intention)
                is ComboIntention -> planCombo(intention)
            }
        }
        
        private fun planTap(intention: TapIntention): GesturePlan {
            return GesturePlan.SingleTap(
                x = intention.x,
                y = intention.y,
                durationMs = intention.durationMs,
                timing = TimingSpec(immediate = true)
            )
        }
        
        private fun planHold(intention: HoldIntention): GesturePlan {
            return GesturePlan.Hold(
                x = intention.x,
                y = intention.y,
                durationMs = intention.durationMs,
                pressureProfile = if (intention.variablePressure) {
                    PressureProfile.SINE_WAVE
                } else {
                    PressureProfile.CONSTANT
                }
            )
        }
        
        private fun planSwipe(intention: SwipeIntention): GesturePlan {
            return GesturePlan.Swipe(
                startX = intention.startX,
                startY = intention.startY,
                endX = intention.endX,
                endY = intention.endY,
                durationMs = intention.durationMs,
                curveType = intention.curve,
                acceleration = intention.acceleration
            )
        }
        
        private fun planDrag(intention: DragIntention): GesturePlan {
            return GesturePlan.DragAndHold(
                path = intention.path,
                holdPoints = intention.holdPoints,
                durations = intention.segmentDurations
            )
        }
        
        private fun planMultiTouch(intention: MultiTouchIntention): GesturePlan {
            return GesturePlan.MultiTouch(
                points = intention.points,
                synchronized = intention.synchronized
            )
        }
        
        private fun planCameraSwipe(intention: CameraSwipeIntention): GesturePlan {
            return GesturePlan.CameraSwipe(
                deltaX = intention.deltaX,
                deltaY = intention.deltaY,
                durationMs = intention.durationMs,
                easing = intention.easing
            )
        }
        
        private fun planCombo(intention: ComboIntention): GesturePlan {
            return GesturePlan.Combo(
                gestures = intention.gestures.map { plan(it) },
                delays = intention.delays
            )
        }
    }

    // ============================================
    // GESTURE SYNTHESIZER - Capa 2: Síntesis
    // ============================================
    
    class GestureSynthesizer {
        
        fun synthesize(plan: GesturePlan): RawGesture {
            return when (plan) {
                is GesturePlan.SingleTap -> synthesizeTap(plan)
                is GesturePlan.DoubleTap -> synthesizeDoubleTap(plan)
                is GesturePlan.TripleTap -> synthesizeTripleTap(plan)
                is GesturePlan.Hold -> synthesizeHold(plan)
                is GesturePlan.Swipe -> synthesizeSwipe(plan)
                is GesturePlan.DragAndHold -> synthesizeDrag(plan)
                is GesturePlan.MultiTouch -> synthesizeMultiTouch(plan)
                is GesturePlan.CameraSwipe -> synthesizeCameraSwipe(plan)
                is GesturePlan.Combo -> synthesizeCombo(plan)
            }
        }
        
        private fun synthesizeTap(plan: GesturePlan.SingleTap): RawGesture {
            return RawGesture(
                touchPoints = listOf(TouchPoint(plan.x, plan.y, DEFAULT_PRESSURE)),
                timing = listOf(plan.durationMs),
                pressure = listOf(DEFAULT_PRESSURE),
                type = GestureType.SINGLE_TAP
            )
        }
        
        private fun synthesizeDoubleTap(plan: GesturePlan.DoubleTap): RawGesture {
            val points = listOf(
                TouchPoint(plan.x, plan.y, DEFAULT_PRESSURE),
                TouchPoint(plan.x, plan.y, DEFAULT_PRESSURE)
            )
            val timing = listOf(50L, plan.intervalMs, 50L)
            return RawGesture(points, timing, listOf(DEFAULT_PRESSURE, DEFAULT_PRESSURE), GestureType.DOUBLE_TAP)
        }
        
        private fun synthesizeTripleTap(plan: GesturePlan.TripleTap): RawGesture {
            val points = List(3) { TouchPoint(plan.x, plan.y, DEFAULT_PRESSURE) }
            val timing = listOf(50L, plan.intervalMs, 50L, plan.intervalMs, 50L)
            return RawGesture(points, timing, List(3) { DEFAULT_PRESSURE }, GestureType.TRIPLE_TAP)
        }
        
        private fun synthesizeHold(plan: GesturePlan.Hold): RawGesture {
            val steps = (plan.durationMs / 50).toInt().coerceAtLeast(1)
            val pressurePoints = generatePressureProfile(plan.pressureProfile, steps)
            
            return RawGesture(
                touchPoints = List(steps) { TouchPoint(plan.x, plan.y, pressurePoints[it]) },
                timing = List(steps) { 50L },
                pressure = pressurePoints,
                type = GestureType.HOLD
            )
        }
        
        private fun synthesizeSwipe(plan: GesturePlan.Swipe): RawGesture {
            val steps = ((plan.durationMs / 16).toInt()).coerceIn(5, 60)
            val path = interpolatePath(
                plan.startX, plan.startY,
                plan.endX, plan.endY,
                steps,
                plan.curveType
            )
            
            // Aplicar aceleración
            val timing = applyAcceleration(steps, plan.durationMs, plan.acceleration)
            
            return RawGesture(
                touchPoints = path.map { TouchPoint(it.x, it.y, DEFAULT_PRESSURE) },
                timing = timing,
                pressure = List(steps) { DEFAULT_PRESSURE },
                type = GestureType.SWIPE
            )
        }
        
        private fun synthesizeDrag(plan: GesturePlan.DragAndHold): RawGesture {
            val allPoints = mutableListOf<TouchPoint>()
            val allTiming = mutableListOf<Long>()
            
            plan.path.forEachIndexed { index, point ->
                allPoints.add(TouchPoint(point.x, point.y, DEFAULT_PRESSURE))
                allTiming.add(plan.durations.getOrElse(index) { 100L })
                
                // Si es punto de hold, añadir duración extra
                if (index in plan.holdPoints) {
                    allTiming[allTiming.lastIndex] = 500L
                }
            }
            
            return RawGesture(allPoints, allTiming, List(allPoints.size) { DEFAULT_PRESSURE }, GestureType.DRAG_HOLD)
        }
        
        private fun synthesizeMultiTouch(plan: GesturePlan.MultiTouch): RawGesture {
            return RawGesture(
                touchPoints = plan.points,
                timing = if (plan.synchronized) {
                    List(plan.points.size) { 0L } // Simultáneo
                } else {
                    List(plan.points.size) { index -> index * 50L } // Secuencial
                },
                pressure = plan.points.map { it.pressure },
                type = GestureType.MULTI_TOUCH
            )
        }
        
        private fun synthesizeCameraSwipe(plan: GesturePlan.CameraSwipe): RawGesture {
            val steps = ((plan.durationMs / 16).toInt()).coerceIn(10, 120)
            val easing = plan.easing
            
            // Generar puntos con easing
            val points = (0 until steps).map { step ->
                val t = step / steps.toFloat()
                val easedT = applyEasing(t, easing)
                
                val x = plan.deltaX * easedT
                val y = plan.deltaY * easedT
                
                TouchPoint(x, y, DEFAULT_PRESSURE)
            }
            
            val timing = List(steps) { plan.durationMs / steps }
            
            return RawGesture(points, timing, List(steps) { DEFAULT_PRESSURE }, GestureType.CAMERA_SWIPE)
        }
        
        private fun synthesizeCombo(plan: GesturePlan.Combo): RawGesture {
            val combinedGestures = plan.gestures.map { synthesize(it) }
            
            return RawGesture(
                touchPoints = combinedGestures.flatMap { it.touchPoints },
                timing = combinedGestures.flatMapIndexed { index, gesture ->
                    gesture.timing.map { it + (plan.delays.getOrNull(index) ?: 0) }
                },
                pressure = combinedGestures.flatMap { it.pressure },
                type = GestureType.COMBO
            )
        }
        
        // Helpers
        private fun generatePressureProfile(profile: PressureProfile, steps: Int): List<Float> {
            return when (profile) {
                PressureProfile.CONSTANT -> List(steps) { DEFAULT_PRESSURE }
                PressureProfile.SINE_WAVE -> (0 until steps).map {
                    0.5f + 0.3f * kotlin.math.sin(it * kotlin.math.PI / steps)
                }
                PressureProfile.DECAY -> (0 until steps).map {
                    DEFAULT_PRESSURE * (1 - it / steps.toFloat() * 0.5f)
                }
                PressureProfile.BUILDUP -> (0 until steps).map {
                    DEFAULT_PRESSURE * (0.5f + it / steps.toFloat() * 0.5f)
                }
            }
        }
        
        private fun interpolatePath(
            startX: Float, startY: Float,
            endX: Float, endY: Float,
            steps: Int,
            curveType: CurveType
        ): List<PointF> {
            return (0 until steps).map { step ->
                val t = step / (steps - 1).toFloat()
                
                val (x, y) = when (curveType) {
                    CurveType.LINEAR -> Pair(
                        startX + (endX - startX) * t,
                        startY + (endY - startY) * t
                    )
                    CurveType.EASE_IN -> {
                        val easedT = t * t
                        Pair(
                            startX + (endX - startX) * easedT,
                            startY + (endY - startY) * easedT
                        )
                    }
                    CurveType.EASE_OUT -> {
                        val easedT = 1 - (1 - t) * (1 - t)
                        Pair(
                            startX + (endX - startX) * easedT,
                            startY + (endY - startY) * easedT
                        )
                    }
                    CurveType.EASE_IN_OUT -> {
                        val easedT = if (t < 0.5f) 2 * t * t else 1 - (2 - 2 * t) * (2 - 2 * t) / 2
                        Pair(
                            startX + (endX - startX) * easedT,
                            startY + (endY - startY) * easedT
                        )
                    }
                    CurveType.BEZIER -> {
                        // Simplificación: curva cuadrática
                        val controlX = (startX + endX) / 2 + (endY - startY) * 0.2f
                        val controlY = (startY + endY) / 2 - (endX - startX) * 0.2f
                        
                        val x = (1 - t) * (1 - t) * startX + 2 * (1 - t) * t * controlX + t * t * endX
                        val y = (1 - t) * (1 - t) * startY + 2 * (1 - t) * t * controlY + t * t * endY
                        Pair(x, y)
                    }
                }
                
                PointF(x, y)
            }
        }
        
        private fun applyAcceleration(steps: Int, totalDuration: Long, acceleration: Float): List<Long> {
            // Distribución no uniforme del tiempo según aceleración
            val baseTime = totalDuration / steps
            return (0 until steps).map { step ->
                val factor = 1 + (acceleration - 1) * kotlin.math.sin(step * kotlin.math.PI / steps)
                (baseTime * factor).toLong().coerceAtLeast(MIN_GESTURE_DURATION)
            }
        }
        
        private fun applyEasing(t: Float, easing: EasingFunction): Float {
            return when (easing) {
                EasingFunction.LINEAR -> t
                EasingFunction.EASE_IN_QUAD -> t * t
                EasingFunction.EASE_OUT_QUAD -> 1 - (1 - t) * (1 - t)
                EasingFunction.EASE_IN_OUT_QUAD -> 
                    if (t < 0.5f) 2 * t * t else 1 - (2 - 2 * t) * (2 - 2 * t) / 2
                EasingFunction.EASE_IN_CUBIC -> t * t * t
                EasingFunction.EASE_OUT_CUBIC -> 1 - (1 - t) * (1 - t) * (1 - t)
                EasingFunction.EASE_IN_OUT_CUBIC -> 
                    if (t < 0.5f) 4 * t * t * t else 1 - (2 - 2 * t) * (2 - 2 * t) * (2 - 2 * t) / 2
            }
        }
    }

    // ============================================
    // GESTURE DISPATCHER - Capa 3: Ejecución
    // ============================================
    
    class GestureDispatcher(private val service: AccessibilityService) {
        
        private val pendingGestures = ConcurrentLinkedQueue<RawGesture>()
        private var currentGesture: Job? = null
        private val dispatcherScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        init {
            // Iniciar loop de ejecución
            dispatcherScope.launch {
                while (isActive) {
                    processNext()
                    delay(1)
                }
            }
        }
        
        fun dispatch(gesture: RawGesture) {
            pendingGestures.offer(gesture)
        }
        
        fun cancelPending() {
            pendingGestures.clear()
            currentGesture?.cancel()
        }
        
        private suspend fun processNext() {
            val gesture = pendingGestures.poll() ?: return
            
            try {
                when (gesture.type) {
                    GestureType.SINGLE_TAP -> executeTap(gesture)
                    GestureType.DOUBLE_TAP -> executeDoubleTap(gesture)
                    GestureType.TRIPLE_TAP -> executeTripleTap(gesture)
                    GestureType.HOLD -> executeHold(gesture)
                    GestureType.SWIPE -> executeSwipe(gesture)
                    GestureType.DRAG_HOLD -> executeDrag(gesture)
                    GestureType.MULTI_TOUCH -> executeMultiTouch(gesture)
                    GestureType.CAMERA_SWIPE -> executeCameraSwipe(gesture)
                    GestureType.COMBO -> executeCombo(gesture)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error ejecutando gesto", e)
            }
        }
        
        private suspend fun executeTap(gesture: RawGesture) {
            val point = gesture.touchPoints.firstOrNull() ?: return
            
            val path = Path().apply {
                moveTo(point.x, point.y)
            }
            
            val gestureDesc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, gesture.timing.firstOrNull() ?: 50))
                .build()
            
            service.dispatchGesture(gestureDesc, null, null)
            delay(gesture.timing.firstOrNull() ?: 50)
        }
        
        private suspend fun executeDoubleTap(gesture: RawGesture) {
            // Primer tap
            val point1 = gesture.touchPoints.getOrNull(0) ?: return
            val path1 = Path().apply { moveTo(point1.x, point1.y) }
            
            val gesture1 = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0, 50))
                .build()
            
            service.dispatchGesture(gesture1, null, null)
            delay(gesture.timing.getOrElse(1) { 100 })
            
            // Segundo tap
            val point2 = gesture.touchPoints.getOrNull(1) ?: return
            val path2 = Path().apply { moveTo(point2.x, point2.y) }
            
            val gesture2 = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path2, 0, 50))
                .build()
            
            service.dispatchGesture(gesture2, null, null)
        }
        
        private suspend fun executeTripleTap(gesture: RawGesture) {
            // Similar a double tap pero con 3 taps
            repeat(3) { index ->
                val point = gesture.touchPoints.getOrNull(index) ?: return
                val path = Path().apply { moveTo(point.x, point.y) }
                
                val gestureDesc = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                
                service.dispatchGesture(gestureDesc, null, null)
                
                if (index < 2) {
                    delay(gesture.timing.getOrElse(index * 2 + 1) { 80 })
                }
            }
        }
        
        private suspend fun executeHold(gesture: RawGesture) {
            val point = gesture.touchPoints.firstOrNull() ?: return
            val duration = gesture.timing.sum()
            
            val path = Path().apply {
                moveTo(point.x, point.y)
                lineTo(point.x, point.y) // Stay in place
            }
            
            val gestureDesc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration, true))
                .build()
            
            service.dispatchGesture(gestureDesc, null, null)
            delay(duration)
        }
        
        private suspend fun executeSwipe(gesture: RawGesture) {
            if (gesture.touchPoints.size < 2) return
            
            val path = Path().apply {
                val start = gesture.touchPoints.first()
                moveTo(start.x, start.y)
                
                gesture.touchPoints.drop(1).forEach { point ->
                    lineTo(point.x, point.y)
                }
            }
            
            val duration = gesture.timing.sum()
            
            val gestureDesc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            
            service.dispatchGesture(gestureDesc, null, null)
            delay(duration)
        }
        
        private suspend fun executeDrag(gesture: RawGesture) {
            // Similar a swipe pero con pausas en puntos de hold
            executeSwipe(gesture) // Simplificación
        }
        
        private suspend fun executeMultiTouch(gesture: RawGesture) {
            // Android AccessibilityService tiene limitaciones con multi-touch real
            // Simulamos secuencial muy rápido
            val builder = GestureDescription.Builder()
            
            gesture.touchPoints.forEachIndexed { index, point ->
                val path = Path().apply { moveTo(point.x, point.y) }
                val delay = gesture.timing.getOrElse(index) { 0 }
                val duration = 50L
                
                builder.addStroke(GestureDescription.StrokeDescription(path, delay, duration))
            }
            
            service.dispatchGesture(builder.build(), null, null)
            delay(gesture.timing.sum() + 50)
        }
        
        private suspend fun executeCameraSwipe(gesture: RawGesture) {
            // Similar a swipe normal pero optimizado para cámara
            executeSwipe(gesture)
        }
        
        private suspend fun executeCombo(gesture: RawGesture) {
            // Ejecutar cada sub-gesto de la combo
            // Por simplicidad, tratamos como swipe largo
            executeSwipe(gesture)
        }
    }
}

// ============================================
// INTENCIONES DE GESTO
// ============================================

sealed class GestureIntention {
    abstract val priority: Int
}

data class TapIntention(
    val x: Float,
    val y: Float,
    val durationMs: Long = 50,
    override val priority: Int = 50
) : GestureIntention()

data class HoldIntention(
    val x: Float,
    val y: Float,
    val durationMs: Long,
    val variablePressure: Boolean = false,
    override val priority: Int = 60
) : GestureIntention()

data class SwipeIntention(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
    val curve: CurveType = CurveType.EASE_OUT,
    val acceleration: Float = 1.0f,
    override val priority: Int = 40
) : GestureIntention()

data class DragIntention(
    val path: List<PointF>,
    val holdPoints: List<Int> = emptyList(),
    val segmentDurations: List<Long>,
    override val priority: Int = 45
) : GestureIntention()

data class MultiTouchIntention(
    val points: List<TouchPoint>,
    val synchronized: Boolean = true,
    override val priority: Int = 70
) : GestureIntention()

data class CameraSwipeIntention(
    val deltaX: Float,
    val deltaY: Float,
    val durationMs: Long,
    val easing: EasingFunction = EasingFunction.EASE_OUT_QUAD,
    override val priority: Int = 55
) : GestureIntention()

data class ComboIntention(
    val gestures: List<GestureIntention>,
    val delays: List<Long>,
    override val priority: Int = 80
) : GestureIntention()

// ============================================
// PLANES DE GESTO
// ============================================

sealed class GesturePlan {
    data class SingleTap(val x: Float, val y: Float, val durationMs: Long, val timing: TimingSpec) : GesturePlan()
    data class DoubleTap(val x: Float, val y: Float, val intervalMs: Long) : GesturePlan()
    data class TripleTap(val x: Float, val y: Float, val intervalMs: Long) : GesturePlan()
    data class Hold(val x: Float, val y: Float, val durationMs: Long, val pressureProfile: PressureProfile) : GesturePlan()
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val durationMs: Long, val curveType: CurveType, val acceleration: Float) : GesturePlan()
    data class DragAndHold(val path: List<PointF>, val holdPoints: List<Int>, val durations: List<Long>) : GesturePlan()
    data class MultiTouch(val points: List<TouchPoint>, val synchronized: Boolean) : GesturePlan()
    data class CameraSwipe(val deltaX: Float, val deltaY: Float, val durationMs: Long, val easing: EasingFunction) : GesturePlan()
    data class Combo(val gestures: List<GesturePlan>, val delays: List<Long>) : GesturePlan()
}

// ============================================
// RAW GESTURE (para ejecución)
// ============================================

data class RawGesture(
    val touchPoints: List<TouchPoint>,
    val timing: List<Long>,
    val pressure: List<Float>,
    val type: GestureType
)

data class TouchPoint(
    val x: Float,
    val y: Float,
    val pressure: Float
)


// ============================================
// ENUMS Y CONFIGURACIÓN
// ============================================

enum class CurveType { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, BEZIER }

enum class PressureProfile { CONSTANT, SINE_WAVE, DECAY, BUILDUP }

enum class EasingFunction {
    LINEAR,
    EASE_IN_QUAD, EASE_OUT_QUAD, EASE_IN_OUT_QUAD,
    EASE_IN_CUBIC, EASE_OUT_CUBIC, EASE_IN_OUT_CUBIC
}

data class TimingSpec(
    val immediate: Boolean = false,
    val delayMs: Long = 0,
    val jitterMs: Long = 0  // Variación aleatoria para humanización
)
