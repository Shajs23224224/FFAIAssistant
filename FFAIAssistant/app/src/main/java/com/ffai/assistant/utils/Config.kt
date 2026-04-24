package com.ffai.assistant.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuración global de la aplicación.
 * Gestiona preferencias como FPS objetivo y modo debug.
 */
object Config {
    
    private const val PREFS_NAME = "ffai_config"
    private const val KEY_FPS_TARGET = "fps_target"
    private const val KEY_DEBUG_MODE = "debug_mode"
    
    private var prefs: SharedPreferences? = null
    
    private var fpsTarget: Int = 15
    private var debugMode: Boolean = false
    
    /**
     * Inicializa la configuración con el contexto de la aplicación.
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            fpsTarget = prefs?.getInt(KEY_FPS_TARGET, 15) ?: 15
            debugMode = prefs?.getBoolean(KEY_DEBUG_MODE, false) ?: false
        }
    }
    
    /**
     * Obtiene el FPS objetivo configurado.
     */
    fun getFpsTarget(): Int = fpsTarget
    
    /**
     * Establece el FPS objetivo.
     */
    fun setFpsTarget(fps: Int) {
        fpsTarget = fps.coerceIn(5, 30)
        prefs?.edit()?.putInt(KEY_FPS_TARGET, fpsTarget)?.apply()
    }
    
    /**
     * Verifica si el modo debug está activado.
     */
    fun isDebugMode(): Boolean = debugMode
    
    /**
     * Establece el modo debug.
     */
    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
        prefs?.edit()?.putBoolean(KEY_DEBUG_MODE, debugMode)?.apply()
    }
}
