package com.ffai.assistant.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.Rect
import android.view.WindowManager

/**
 * Configuración de coordenadas de UI de Free Fire
 * Guarda posiciones de joysticks y botones
 */
class GameConfig(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // Resolución actual del dispositivo
    val screenWidth: Int
    val screenHeight: Int
    val scaleX: Float
    val scaleY: Float
    
    init {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        
        // Calcular escala basada en resolución base 1080x2400 (Android 12 típico)
        scaleX = screenWidth / 1080f
        scaleY = screenHeight / 2400f
    }
    
    // Coordenadas base (para 1080x2400)
    companion object {
        private const val PREFS_NAME = "GameConfig"
        
        // Posiciones base (ajustar según dispositivo)
        private val DEFAULT_JOYSTICK_LEFT = PointF(180f, 1900f)
        private val DEFAULT_JOYSTICK_RIGHT = PointF(900f, 1900f)
        private val DEFAULT_BUTTON_FIRE = PointF(950f, 1700f)
        private val DEFAULT_BUTTON_HEAL = PointF(750f, 1600f)
        private val DEFAULT_BUTTON_RELOAD = PointF(850f, 1500f)
        private val DEFAULT_BUTTON_CROUCH = PointF(700f, 2000f)
        private val DEFAULT_BUTTON_JUMP = PointF(1000f, 1400f)
        private val DEFAULT_BUTTON_LOOT = PointF(540f, 1800f)
        private val DEFAULT_BUTTON_REVIVE = PointF(540f, 1400f)
        
        private const val DEFAULT_JOYSTICK_RADIUS = 120f
    }
    
    // Getters escalados
    val joystickLeft: PointF get() = getScaledPoint("joy_left", DEFAULT_JOYSTICK_LEFT)
    val joystickRight: PointF get() = getScaledPoint("joy_right", DEFAULT_JOYSTICK_RIGHT)
    val buttonFire: PointF get() = getScaledPoint("btn_fire", DEFAULT_BUTTON_FIRE)
    val buttonHeal: PointF get() = getScaledPoint("btn_heal", DEFAULT_BUTTON_HEAL)
    val buttonReload: PointF get() = getScaledPoint("btn_reload", DEFAULT_BUTTON_RELOAD)
    val buttonCrouch: PointF get() = getScaledPoint("btn_crouch", DEFAULT_BUTTON_CROUCH)
    val buttonJump: PointF get() = getScaledPoint("btn_jump", DEFAULT_BUTTON_JUMP)
    val buttonLoot: PointF get() = getScaledPoint("btn_loot", DEFAULT_BUTTON_LOOT)
    val buttonRevive: PointF get() = getScaledPoint("btn_revive", DEFAULT_BUTTON_REVIVE)
    
    val joystickRadius: Float get() = DEFAULT_JOYSTICK_RADIUS * minOf(scaleX, scaleY)
    
    private fun getScaledPoint(key: String, default: PointF): PointF {
        val x = prefs.getFloat("${key}_x", default.x) * scaleX
        val y = prefs.getFloat("${key}_y", default.y) * scaleY
        return PointF(x, y)
    }
    
    /**
     * Guarda coordenadas calibradas por el usuario
     */
    fun saveCalibration(
        joyLeft: PointF,
        joyRight: PointF,
        fire: PointF,
        heal: PointF,
        reload: PointF,
        crouch: PointF,
        jump: PointF,
        loot: PointF,
        revive: PointF
    ) {
        prefs.edit().apply {
            putFloat("joy_left_x", joyLeft.x / scaleX)
            putFloat("joy_left_y", joyLeft.y / scaleY)
            putFloat("joy_right_x", joyRight.x / scaleX)
            putFloat("joy_right_y", joyRight.y / scaleY)
            putFloat("btn_fire_x", fire.x / scaleX)
            putFloat("btn_fire_y", fire.y / scaleY)
            putFloat("btn_heal_x", heal.x / scaleX)
            putFloat("btn_heal_y", heal.y / scaleY)
            putFloat("btn_reload_x", reload.x / scaleX)
            putFloat("btn_reload_y", reload.y / scaleY)
            putFloat("btn_crouch_x", crouch.x / scaleX)
            putFloat("btn_crouch_y", crouch.y / scaleY)
            putFloat("btn_jump_x", jump.x / scaleX)
            putFloat("btn_jump_y", jump.y / scaleY)
            putFloat("btn_loot_x", loot.x / scaleX)
            putFloat("btn_loot_y", loot.y / scaleY)
            putFloat("btn_revive_x", revive.x / scaleX)
            putFloat("btn_revive_y", revive.y / scaleY)
            apply()
        }
    }
    
    /**
     * Resetea a valores por defecto
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
    
    /**
     * Obtiene región de interés para detección de HP
     */
    fun getHpRegion(): Rect {
        return Rect(
            (100 * scaleX).toInt(),
            (100 * scaleY).toInt(),
            (400 * scaleX).toInt(),
            (200 * scaleY).toInt()
        )
    }
    
    /**
     * Obtiene región de interés para detección de munición
     */
    fun getAmmoRegion(): Rect {
        return Rect(
            (100 * scaleX).toInt(),
            (220 * scaleY).toInt(),
            (300 * scaleX).toInt(),
            (300 * scaleY).toInt()
        )
    }
    
    /**
     * Obtiene región central para detección de enemigos
     */
    fun getEnemyDetectionRegion(): Rect {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val w = (300 * scaleX).toInt()
        val h = (300 * scaleY).toInt()
        return Rect(centerX - w/2, centerY - h/2, centerX + w/2, centerY + h/2)
    }
}
