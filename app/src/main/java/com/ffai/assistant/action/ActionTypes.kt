package com.ffai.assistant.action

import com.ffai.assistant.config.Constants

/**
 * Enumeración de todas las acciones posibles
 */
enum class ActionType(val index: Int, val cooldownMs: Long) {
    AIM(0, 0),
    SHOOT(1, 200),
    MOVE_FORWARD(2, 0),
    MOVE_BACKWARD(3, 0),
    MOVE_LEFT(4, 0),
    MOVE_RIGHT(5, 0),
    HEAL(6, 3000),
    RELOAD(7, 2000),
    CROUCH(8, 300),
    JUMP(9, 500),
    LOOT(10, 1000),
    REVIVE(11, 3000),
    ROTATE_LEFT(12, 0),
    ROTATE_RIGHT(13, 0),
    HOLD(14, 0);
    
    companion object {
        fun fromIndex(index: Int): ActionType {
            return values().getOrElse(index) { HOLD }
        }
        
        fun fromName(name: String): ActionType {
            return values().find { it.name == name } ?: HOLD
        }
    }
}

/**
 * Representa una acción con parámetros adicionales
 */
data class Action(
    val type: ActionType,
    val x: Int = 0,           // Coordenada X (ej: 960)
    val y: Int = 0,           // Coordenada Y (ej: 700)
    val duration: Int = 100,  // Duración en ms
    val confidence: Float = 1f,  // Confianza 0-1
    val timestamp: Long = System.currentTimeMillis()
) {
    val name: String get() = type.name
    val cooldownMs: Long get() = type.cooldownMs
    
    companion object {
        fun aim(x: Int, y: Int) = Action(ActionType.AIM, x, y)
        fun shoot() = Action(ActionType.SHOOT)
        fun moveForward(duration: Int = 100) = Action(ActionType.MOVE_FORWARD, duration = duration)
        fun moveBackward(duration: Int = 100) = Action(ActionType.MOVE_BACKWARD, duration = duration)
        fun moveLeft(duration: Int = 100) = Action(ActionType.MOVE_LEFT, duration = duration)
        fun moveRight(duration: Int = 100) = Action(ActionType.MOVE_RIGHT, duration = duration)
        fun heal() = Action(ActionType.HEAL)
        fun reload() = Action(ActionType.RELOAD)
        fun crouch() = Action(ActionType.CROUCH)
        fun jump() = Action(ActionType.JUMP)
        fun loot() = Action(ActionType.LOOT)
        fun revive() = Action(ActionType.REVIVE)
        fun rotateLeft() = Action(ActionType.ROTATE_LEFT)
        fun rotateRight() = Action(ActionType.ROTATE_RIGHT)
        fun hold() = Action(ActionType.HOLD)
    }
}
