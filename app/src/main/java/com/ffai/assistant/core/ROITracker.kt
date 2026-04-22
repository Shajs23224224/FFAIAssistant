package com.ffai.assistant.core

import android.graphics.Rect
import com.ffai.assistant.utils.Logger
import kotlin.math.abs

/**
 * ROITracker - Region of Interest dinámico.
 * 
 * Rastrea dónde ocurrió la última acción significativa y recorta
 * la región de interés para reducir carga de inferencia en 40-60%.
 * 
 * Comportamiento:
 * - Si hay enemigo detectado → ROI centrado en enemigo
 * - Si hay acción reciente → ROI centrado en acción
 * - Si no hay ROI conocido → pantalla completa
 * - Se expande gradualmente si no encuentra nada
 */
class ROITracker(
    private val screenWidth: Int = 1080,
    private val screenHeight: Int = 2400
) {
    // ROI actual (en coordenadas de pantalla)
    private var currentROI: Rect = fullScreen()
    
    // Última posición de interés
    private var lastInterestX: Int = screenWidth / 2
    private var lastInterestY: Int = screenHeight / 2
    
    // Frames sin encontrar nada → expandir ROI
    private var framesWithoutInterest: Int = 0
    private val maxFramesBeforeExpand: Int = 5
    
    // Tamaño mínimo del ROI (porcentaje de pantalla)
    private val minROIScale: Float = 0.4f   // 40% de pantalla
    private val maxROIScale: Float = 1.0f    // 100% = pantalla completa
    
    // ROI scale actual (se expande/contrae dinámicamente)
    private var roiScale: Float = 0.6f  // Empezar en 60%

    /**
     * Actualiza el ROI basado en detección de enemigo.
     * Contrae ROI alrededor del punto de interés.
     */
    fun onEnemyDetected(enemyScreenX: Int, enemyScreenY: Int) {
        lastInterestX = enemyScreenX
        lastInterestY = enemyScreenY
        framesWithoutInterest = 0
        
        // Contraer ROI al mínimo cuando hay enemigo
        roiScale = minROIScale
        
        currentROI = computeROI(enemyScreenX, enemyScreenY, roiScale)
    }

    /**
     * Actualiza el ROI basado en una acción ejecutada.
     * Mantiene ROI centrado en zona de acción.
     */
    fun onActionPerformed(actionX: Int, actionY: Int) {
        lastInterestX = actionX
        lastInterestY = actionY
        framesWithoutInterest = 0
        // Mantener ROI actual, solo reposicionar
        currentROI = computeROI(actionX, actionY, roiScale)
    }

    /**
     * Notifica que no se encontró nada de interés en el frame.
     * Expande gradualmente el ROI.
     */
    fun onNothingDetected() {
        framesWithoutInterest++
        
        if (framesWithoutInterest >= maxFramesBeforeExpand) {
            // Expandir ROI gradualmente
            roiScale = (roiScale + 0.1f).coerceAtMost(maxROIScale)
            framesWithoutInterest = 0
            
            if (roiScale >= maxROIScale) {
                // ROI es pantalla completa, no hay necesidad de recortar
                currentROI = fullScreen()
            } else {
                currentROI = computeROI(lastInterestX, lastInterestY, roiScale)
            }
        }
    }

    /**
     * Obtiene el ROI actual para recorte de frame.
     */
    fun getCurrentROI(): Rect = currentROI

    /**
     * Verifica si el ROI está contraído (ahorra procesamiento).
     */
    fun isROIContractionActive(): Boolean = roiScale < 0.9f

    /**
     * Porcentaje de reducción de píxeles vs pantalla completa.
     */
    fun getPixelReduction(): Float {
        val fullArea = screenWidth * screenHeight
        val roiArea = currentROI.width() * currentROI.height()
        return if (fullArea > 0) 1f - (roiArea.toFloat() / fullArea) else 0f
    }

    /**
     * Resetea el ROI a pantalla completa.
     */
    fun reset() {
        roiScale = 0.6f
        framesWithoutInterest = 0
        currentROI = fullScreen()
        lastInterestX = screenWidth / 2
        lastInterestY = screenHeight / 2
    }

    private fun computeROI(centerX: Int, centerY: Int, scale: Float): Rect {
        val roiWidth = (screenWidth * scale).toInt()
        val roiHeight = (screenHeight * scale).toInt()
        
        // Centrar en punto de interés, con bounds checking
        val left = (centerX - roiWidth / 2).coerceIn(0, screenWidth - roiWidth)
        val top = (centerY - roiHeight / 2).coerceIn(0, screenHeight - roiHeight)
        val right = (left + roiWidth).coerceAtMost(screenWidth)
        val bottom = (top + roiHeight).coerceAtMost(screenHeight)
        
        return Rect(left, top, right, bottom)
    }

    private fun fullScreen(): Rect = Rect(0, 0, screenWidth, screenHeight)
}
