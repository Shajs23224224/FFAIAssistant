package com.ffai.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Indicador visual del área de ROI (Region of Interest).
 * Dibuja un rectángulo que muestra qué área de la pantalla está siendo analizada.
 */
class ROIIndicatorView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var roiRect = RectF(0f, 0f, 0f, 0f)
    private var isVisible = true
    
    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(150, 0, 255, 100) // Verde brillante semi-transparente
        
        textPaint.color = Color.argb(180, 0, 255, 100)
        textPaint.textSize = 20f
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isVisible) return
        if (roiRect.width() == 0f || roiRect.height() == 0f) return
        
        // Dibujar rectángulo ROI
        canvas.drawRect(roiRect, paint)
        
        // Dibujar esquinas con líneas más gruesas
        val cornerLength = 40f
        val cornerStroke = 4f
        
        paint.strokeWidth = cornerStroke
        
        // Esquina superior izquierda
        canvas.drawLine(roiRect.left, roiRect.top, roiRect.left + cornerLength, roiRect.top, paint)
        canvas.drawLine(roiRect.left, roiRect.top, roiRect.left, roiRect.top + cornerLength, paint)
        
        // Esquina superior derecha
        canvas.drawLine(roiRect.right, roiRect.top, roiRect.right - cornerLength, roiRect.top, paint)
        canvas.drawLine(roiRect.right, roiRect.top, roiRect.right, roiRect.top + cornerLength, paint)
        
        // Esquina inferior izquierda
        canvas.drawLine(roiRect.left, roiRect.bottom, roiRect.left + cornerLength, roiRect.bottom, paint)
        canvas.drawLine(roiRect.left, roiRect.bottom, roiRect.left, roiRect.bottom - cornerLength, paint)
        
        // Esquina inferior derecha
        canvas.drawLine(roiRect.right, roiRect.bottom, roiRect.right - cornerLength, roiRect.bottom, paint)
        canvas.drawLine(roiRect.right, roiRect.bottom, roiRect.right, roiRect.bottom - cornerLength, paint)
        
        // Texto con dimensiones
        val percentText = "${((roiRect.width() / width) * 100).toInt()}%"
        canvas.drawText(
            "ROI: $percentText",
            roiRect.left + 10,
            roiRect.top + 25,
            textPaint
        )
        
        // Líneas de cuadrícula (opcional, ayuda a visualizar)
        paint.strokeWidth = 1f
        paint.color = Color.argb(50, 0, 255, 100)
        
        // Línea vertical central
        val centerX = roiRect.centerX()
        canvas.drawLine(centerX, roiRect.top, centerX, roiRect.bottom, paint)
        
        // Línea horizontal central
        val centerY = roiRect.centerY()
        canvas.drawLine(roiRect.left, centerY, roiRect.right, centerY, paint)
        
        // Restaurar color original
        paint.color = Color.argb(150, 0, 255, 100)
    }

    /**
     * Establece la región de interés.
     */
    fun setROI(x: Int, y: Int, width: Int, height: Int) {
        roiRect.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        invalidate()
    }

    /**
     * Muestra u oculta el indicador.
     */
    fun setVisible(visible: Boolean) {
        isVisible = visible
        invalidate()
    }

    /**
     * Obtiene el rectángulo actual del ROI.
     */
    fun getROI(): RectF = RectF(roiRect)
}
