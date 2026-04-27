package com.ffai.assistant.core

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.ffai.assistant.utils.Logger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * FASE 2: ThermalManager - Gestión térmica para Samsung A21S.
 * 
 * Monitorea temperatura y throttlea rendimiento:
 * - >75°C: Reducir a MEDIUM
 * - >85°C: Reducir a SHORT (solo YOLO)
 * - >90°C: Pausar inferencias
 * 
 * También monitorea batería.
 */
class ThermalManager(private val context: Context) {
    
    companion object {
        const val TAG = "ThermalManager"
        
        // Umbrales de temperatura
        const val TEMP_MEDIUM_THRESHOLD = 75f
        const val TEMP_SHORT_THRESHOLD = 85f
        const val TEMP_PAUSE_THRESHOLD = 90f
        
        // Umbrales de batería
        const val BATTERY_LOW_THRESHOLD = 20
        const val BATTERY_CRITICAL_THRESHOLD = 10
        
        // Intervalo de polling
        const val POLL_INTERVAL_MS = 5000L
    }
    
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var currentTemp = 0f
    private var currentBattery = 100
    private var isThrottling = false
    
    private var onTempThresholdListener: ((ThermalState) -> Unit)? = null
    
    /**
     * Estados térmicos.
     */
    enum class ThermalState {
        NORMAL,    // <75°C
        MEDIUM,    // 75-85°C
        SHORT,     // 85-90°C
        CRITICAL   // >90°C
    }
    
    /**
     * Inicia monitoreo periódico.
     */
    fun startMonitoring() {
        scheduler.scheduleAtFixedRate({
            checkTemperature()
            checkBattery()
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        
        Logger.i(TAG, "Monitoreo térmico iniciado")
    }
    
    /**
     * Obtiene temperatura actual del SoC.
     */
    fun getCurrentTemperature(): Float {
        return try {
            // Intentar leer desde thermal zones
            val thermalDir = File("/sys/class/thermal/")
            if (thermalDir.exists()) {
                val zones = thermalDir.listFiles { file ->
                    file.name.startsWith("thermal_zone")
                } ?: emptyArray()
                
                var maxTemp = 0f
                for (zone in zones) {
                    try {
                        val tempFile = File(zone, "temp")
                        if (tempFile.exists()) {
                            val temp = tempFile.readText().trim().toFloat() / 1000f
                            if (temp > maxTemp) maxTemp = temp
                        }
                    } catch (e: Exception) {
                        // Ignorar zonas no legibles
                    }
                }
                maxTemp
            } else {
                0f
            }
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo leer temperatura", e)
            0f
        }
    }
    
    private fun checkTemperature() {
        val temp = getCurrentTemperature()
        if (temp != currentTemp) {
            currentTemp = temp
            
            val newState = when {
                temp >= TEMP_PAUSE_THRESHOLD -> ThermalState.CRITICAL
                temp >= TEMP_SHORT_THRESHOLD -> ThermalState.SHORT
                temp >= TEMP_MEDIUM_THRESHOLD -> ThermalState.MEDIUM
                else -> ThermalState.NORMAL
            }
            
            if (newState != getThermalState() && temp > 0) {
                Logger.w(TAG, "Temperatura: ${temp}°C → Estado: $newState")
                onTempThresholdListener?.invoke(newState)
            }
        }
    }
    
    private fun checkBattery() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            if (level != currentBattery) {
                currentBattery = level
                
                when {
                    level <= BATTERY_CRITICAL_THRESHOLD -> {
                        Logger.w(TAG, "Batería crítica: $level%")
                    }
                    level <= BATTERY_LOW_THRESHOLD -> {
                        Logger.w(TAG, "Batería baja: $level%")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error leyendo batería", e)
        }
    }
    
    /**
     * Establece listener para cambios de estado térmico.
     */
    fun onThermalThreshold(listener: (ThermalState) -> Unit) {
        onTempThresholdListener = listener
    }
    
    /**
     * Obtiene estado térmico actual.
     */
    fun getThermalState(): ThermalState {
        return when {
            currentTemp >= TEMP_PAUSE_THRESHOLD -> ThermalState.CRITICAL
            currentTemp >= TEMP_SHORT_THRESHOLD -> ThermalState.SHORT
            currentTemp >= TEMP_MEDIUM_THRESHOLD -> ThermalState.MEDIUM
            else -> ThermalState.NORMAL
        }
    }
    
    /**
     * Verifica si se debe aplicar throttling.
     */
    fun shouldThrottle(): Boolean {
        return getThermalState() != ThermalState.NORMAL
    }
    
    /**
     * Obtiene modo recomendado según temperatura.
     */
    fun getRecommendedMode(): ReasoningMode {
        return when (getThermalState()) {
            ThermalState.NORMAL -> ReasoningMode.LONG
            ThermalState.MEDIUM -> ReasoningMode.MEDIUM
            ThermalState.SHORT, ThermalState.CRITICAL -> ReasoningMode.SHORT
        }
    }
    
    /**
     * Obtiene temperatura actual.
     */
    fun getTemperature(): Float = currentTemp
    
    /**
     * Obtiene nivel de batería.
     */
    fun getBatteryLevel(): Int = currentBattery
    
    /**
     * Detiene monitoreo.
     */
    fun stopMonitoring() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
        Logger.i(TAG, "Monitoreo térmico detenido")
    }
    
    fun isMonitoring(): Boolean = !scheduler.isShutdown
}
