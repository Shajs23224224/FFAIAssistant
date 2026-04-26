package com.ffai.assistant.action

/**
 * Tipos de gesto soportados por el controlador.
 * Cada tipo define un patrón de interacción táctil diferente.
 * Unificación de todos los gestos del sistema.
 */
enum class GestureType(val priority: Int) {
    // Gestos básicos (usados por GestureController y GestureSystem)
    TAP(5),           // Toque simple
    SINGLE_TAP(5),    // Sinónimo de TAP
    HOLD(4),          // Mantener presionado
    DRAG(3),          // Arrastrar (mover sin levantar)
    HOLD_DRAG(2),     // Mantener + arrastrar
    DRAG_HOLD(2),     // Sinónimo de HOLD_DRAG
    SWIPE(3),         // Deslizar rápido
    CAMERA_SWIPE(3),  // Deslizar para cámara
    MULTI_TOUCH(1),   // Múltiples dedos
    CONTINUOUS_DRAG(2),  // Arrastre continuo
    
    // Gestos compuestos avanzados
    DOUBLE_TAP(4),    // Doble toque
    TRIPLE_TAP(3),    // Triple toque
    COMBO(1);         // Combinación de gestos

    /**
     * True si el gesto requiere mantener presionado
     */
    val requiresHold: Boolean
        get() = this == HOLD || this == HOLD_DRAG || this == DRAG_HOLD

    /**
     * True si el gesto involucra movimiento
     */
    val requiresMovement: Boolean
        get() = this == DRAG || this == HOLD_DRAG || this == SWIPE || this == CONTINUOUS_DRAG

    /**
     * True si involucra múltiples strokes
     */
    val isMultiTouch: Boolean
        get() = this == MULTI_TOUCH
}

/**
 * Estado de un puntero táctil virtual (un "dedo").
 */
enum class PointerState {
    /** Dedo abajo (touch start) */
    DOWN,
    /** Dedo moviéndose (drag) */
    MOVE,
    /** Dedo levantado (touch end) */
    UP,
    /** Dedo mantenido en posición sin mover */
    STATIONARY
}

/**
 * Representa un dedo virtual con estado completo.
 * Permite tracking continuo para gestos hold+drag y multi-touch.
 */
data class TouchPointer(
    val id: Int,
    var state: PointerState = PointerState.DOWN,
    val startX: Float,
    val startY: Float,
    var currentX: Float = startX,
    var currentY: Float = startY,
    val startTimeMs: Long = System.currentTimeMillis(),
    var lastMoveTimeMs: Long = startTimeMs,
    var totalMoveTimeMs: Long = 0L
) {
    /** Duración total del toque hasta ahora */
    val holdDurationMs: Long
        get() = System.currentTimeMillis() - startTimeMs

    /** Distancia recorrida desde inicio */
    val totalDistance: Float
        get() {
            val dx = currentX - startX
            val dy = currentY - startY
            return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }

    /** Distancia desde último movimiento */
    var lastDeltaX: Float = 0f
    var lastDeltaY: Float = 0f

    /** Velocidad actual del movimiento (px/ms) */
    var velocity: Float = 0f

    /**
     * Actualiza posición del puntero.
     */
    fun moveTo(x: Float, y: Float) {
        lastDeltaX = x - currentX
        lastDeltaY = y - currentY
        currentX = x
        currentY = y

        val now = System.currentTimeMillis()
        val dt = (now - lastMoveTimeMs).coerceAtLeast(1)
        val dist = kotlin.math.sqrt((lastDeltaX * lastDeltaX + lastDeltaY * lastDeltaY).toDouble()).toFloat()
        velocity = dist / dt
        totalMoveTimeMs += dt
        lastMoveTimeMs = now

        state = PointerState.MOVE
    }

    /**
     * Marca el puntero como levantado.
     */
    fun up() {
        state = PointerState.UP
    }

    /**
     * Marca el puntero como estacionario (hold sin mover).
     */
    fun stationary() {
        state = PointerState.STATIONARY
    }

    /**
     * True si el puntero sigue activo (no se ha levantado).
     */
    val isActive: Boolean
        get() = state != PointerState.UP
}

/**
 * Comando de gesto completo para el GestureController.
 * Puede representar un gesto simple o compuesto (multi-touch).
 */
data class GestureCommand(
    val type: GestureType,
    val pointers: List<TouchPointer>,
    val durationMs: Long = 100L,
    val priority: Int = 5,
    val tag: String = ""
) {
    /**
     * True si es un gesto multi-touch
     */
    val isMultiTouch: Boolean
        get() = pointers.size > 1 || type == GestureType.MULTI_TOUCH

    /**
     * Punto central del gesto
     */
    val center: android.graphics.PointF
        get() {
            if (pointers.isEmpty()) return android.graphics.PointF(0f, 0f)
            val cx = pointers.map { it.currentX }.average().toFloat()
            val cy = pointers.map { it.currentY }.average().toFloat()
            return android.graphics.PointF(cx, cy)
        }
}
