package com.ffai.assistant.action

/**
 * Tipos de gesto soportados por el controlador.
 * Cada tipo define un patrón de interacción táctil diferente.
 */
enum class GestureType(val priority: Int) {
    // Toque simple y rápido
    TAP(5),

    // Mantener presionado sin mover (ej: botón de habilidad)
    HOLD(4),

    // Deslizar de un punto a otro (ej: swipe para cambiar arma)
    DRAG(3),

    // Mantener presionado y luego deslizar (ej: joystick)
    HOLD_DRAG(2),

    // Swipe rápido (ej: cambiar arma deslizando)
    SWIPE(3),

    // Múltiples dedos simultáneos (ej: mover + apuntar + disparar)
    MULTI_TOUCH(1),

    // Drag que continúa de un frame anterior (sin levantar dedo)
    CONTINUOUS_DRAG(2);

    /**
     * True si el gesto requiere mantener presionado
     */
    val requiresHold: Boolean
        get() = this == HOLD || this == HOLD_DRAG

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
    val priority: Int = type.priority,
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
