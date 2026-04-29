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
    private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
    private const val KEY_AI_START_REQUESTED = "ai_start_requested"
    private const val KEY_CAPTURE_ACTIVE = "capture_active"
    
    private var prefs: SharedPreferences? = null
    
    private var fpsTarget: Int = 15
    private var debugMode: Boolean = false
    private var keepAliveEnabled: Boolean = true
    private var aiStartRequested: Boolean = false
    private var captureActive: Boolean = false
    
    /**
     * Inicializa la configuración con el contexto de la aplicación.
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            fpsTarget = prefs?.getInt(KEY_FPS_TARGET, 15) ?: 15
            debugMode = prefs?.getBoolean(KEY_DEBUG_MODE, false) ?: false
            keepAliveEnabled = prefs?.getBoolean(KEY_KEEP_ALIVE_ENABLED, true) ?: true
            aiStartRequested = prefs?.getBoolean(KEY_AI_START_REQUESTED, false) ?: false
            captureActive = prefs?.getBoolean(KEY_CAPTURE_ACTIVE, false) ?: false
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

    fun isKeepAliveEnabled(): Boolean = keepAliveEnabled

    fun setKeepAliveEnabled(enabled: Boolean) {
        keepAliveEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_KEEP_ALIVE_ENABLED, keepAliveEnabled)?.apply()
    }

    fun isAiStartRequested(): Boolean = aiStartRequested

    fun setAiStartRequested(requested: Boolean) {
        aiStartRequested = requested
        prefs?.edit()?.putBoolean(KEY_AI_START_REQUESTED, aiStartRequested)?.apply()
    }

    fun isCaptureActive(): Boolean = captureActive

    fun setCaptureActive(active: Boolean) {
        captureActive = active
        prefs?.edit()?.putBoolean(KEY_CAPTURE_ACTIVE, captureActive)?.apply()
    }
}
