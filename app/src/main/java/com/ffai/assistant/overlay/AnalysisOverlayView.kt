package com.ffai.assistant.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

/**
 * Vista de análisis visual que dibuja detecciones, aim points, trayectorias.
 * Usa Canvas para renderizado ligero en tiempo real.
 */
class AnalysisOverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    
    // Elementos a dibujar
    private val enemies = mutableListOf<EnemyMarker>()
    private var aimPoint: AimPoint? = null
    private var trajectoryPoints: List<Pair<Float, Float>> = emptyList()
    private var trajectoryColor: Int = Color.GREEN
    private var statusText: String = ""
    private var statusTimeout: Long = 0
    
    // Área de análisis
    private var analysisRect = RectF(0f, 0f, 0f, 0f)
    private var isVisible = true
    
    // Handler para limpiar automáticamente
    private val handler = Handler(Looper.getMainLooper())
    private val clearRunnable = Runnable { clear() }
    
    companion object {
        const val ENEMY_VALIDITY_MS = 500L // Enemigos válidos por 500ms
    }

    init {
        // Paint para enemigos
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        
        // Paint para texto
        textPaint.color = Color.WHITE
        textPaint.textSize = 24f
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isVisible) return
        
        // Dibujar área de análisis (sutil)
        paint.color = Color.argb(30, 0, 255, 0)
        paint.style = Paint.Style.FILL
        canvas.drawRect(analysisRect, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(100, 0, 255, 0)
        canvas.drawRect(analysisRect, paint)
        
        // Dibujar enemigos
        val now = System.currentTimeMillis()
        enemies.removeAll { now - it.timestamp > ENEMY_VALIDITY_MS }
        
        enemies.forEach { enemy ->
            drawEnemyMarker(canvas, enemy)
        }
        
        // Dibujar aim point
        aimPoint?.let { aim ->
            drawAimMarker(canvas, aim)
        }
        
        // Dibujar trayectoria
        if (trajectoryPoints.size >= 2) {
            drawTrajectoryPath(canvas)
        }
        
        // Dibujar status
        if (statusText.isNotEmpty() && now < statusTimeout) {
            drawStatusText(canvas)
        }
        
        // Auto-redraw para animaciones
        if (enemies.isNotEmpty() || aimPoint != null || now < statusTimeout) {
            invalidate()
        }
    }

    private fun drawEnemyMarker(canvas: Canvas, enemy: EnemyMarker) {
        val size = 60f + (enemy.confidence * 40f) // Tamaño según confianza
        val halfSize = size / 2
        
        val rect = RectF(
            enemy.x - halfSize,
            enemy.y - halfSize,
            enemy.x + halfSize,
            enemy.y + halfSize
        )
        
        // Color según confianza
        val color = when {
            enemy.confidence > 0.8f -> Color.RED
            enemy.confidence > 0.5f -> Color.YELLOW
            else -> Color.GREEN
        }
        
        paint.color = color
        paint.strokeWidth = if (enemy.isLocked) 5f else 2f
        
        // Box
        canvas.drawRect(rect, paint)
        
        // Esquinas destacadas si está lockeado
        if (enemy.isLocked) {
            val cornerSize = 15f
            paint.strokeWidth = 4f
            // Esquina TL
            canvas.drawLine(rect.left, rect.top, rect.left + cornerSize, rect.top, paint)
            canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerSize, paint)
            // Esquina TR
            canvas.drawLine(rect.right, rect.top, rect.right - cornerSize, rect.top, paint)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerSize, paint)
            // Esquina BL
            canvas.drawLine(rect.left, rect.bottom, rect.left + cornerSize, rect.bottom, paint)
            canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerSize, paint)
            // Esquina BR
            canvas.drawLine(rect.right, rect.bottom, rect.right - cornerSize, rect.bottom, paint)
            canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerSize, paint)
        }
        
        // Texto de confianza
        textPaint.color = color
        canvas.drawText("${(enemy.confidence * 100).toInt()}%", enemy.x - 20, enemy.y - halfSize - 10, textPaint)
    }

    private fun drawAimMarker(canvas: Canvas, aim: AimPoint) {
        val radius = 15f + (aim.accuracy * 25f)
        
        paint.color = Color.CYAN
        paint.strokeWidth = 3f
        paint.style = Paint.Style.STROKE
        
        // Círculo
        canvas.drawCircle(aim.x, aim.y, radius, paint)
        
        // Cruz
        canvas.drawLine(aim.x - radius, aim.y, aim.x + radius, aim.y, paint)
        canvas.drawLine(aim.x, aim.y - radius, aim.x, aim.y + radius, paint)
        
        // Punto central
        paint.style = Paint.Style.FILL
        canvas.drawCircle(aim.x, aim.y, 5f, paint)
        paint.style = Paint.Style.STROKE
        
        // Texto
        textPaint.color = Color.CYAN
        canvas.drawText("AIM", aim.x + 20, aim.y, textPaint)
    }

    private fun drawTrajectoryPath(canvas: Canvas) {
        val path = Path()
        path.moveTo(trajectoryPoints[0].first, trajectoryPoints[0].second)
        
        for (i in 1 until trajectoryPoints.size) {
            path.lineTo(trajectoryPoints[i].first, trajectoryPoints[i].second)
        }
        
        paint.color = trajectoryColor
        paint.strokeWidth = 4f
        canvas.drawPath(path, paint)
        
        // Dibujar puntos
        paint.style = Paint.Style.FILL
        trajectoryPoints.forEach { (x, y) ->
            canvas.drawCircle(x, y, 6f, paint)
        }
        paint.style = Paint.Style.STROKE
    }

    private fun drawStatusText(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        
        // Fondo semi-transparente
        val textWidth = textPaint.measureText(statusText)
        val bgRect = RectF(
            centerX - textWidth / 2 - 20,
            centerY - 40,
            centerX + textWidth / 2 + 20,
            centerY + 20
        )
        
        paint.color = Color.argb(180, 0, 0, 0)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(bgRect, 10f, 10f, paint)
        
        // Texto
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(statusText, centerX, centerY, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    fun drawEnemy(x: Float, y: Float, confidence: Float, isLocked: Boolean) {
        val enemy = EnemyMarker(x, y, confidence, isLocked, System.currentTimeMillis())
        enemies.add(enemy)
        
        // Limpiar duplicados cercanos
        enemies.removeAll { 
            it != enemy && kotlin.math.hypot(it.x - x, it.y - y) < 50f 
        }
        
        invalidate()
        
        // Auto-clear después de tiempo
        handler.removeCallbacks(clearRunnable)
        handler.postDelayed(clearRunnable, ENEMY_VALIDITY_MS + 100)
    }

    fun drawAimPoint(x: Float, y: Float, accuracy: Float) {
        aimPoint = AimPoint(x, y, accuracy)
        invalidate()
    }

    fun drawTrajectory(points: List<Pair<Float, Float>>, color: Int) {
        trajectoryPoints = points
        trajectoryColor = color
        invalidate()
    }

    fun showStatus(text: String, durationMs: Long) {
        statusText = text
        statusTimeout = System.currentTimeMillis() + durationMs
        invalidate()
    }

    fun setAnalysisArea(x: Int, y: Int, width: Int, height: Int) {
        analysisRect.set(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        invalidate()
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        invalidate()
    }

    fun clear() {
        enemies.clear()
        aimPoint = null
        trajectoryPoints = emptyList()
        statusText = ""
        invalidate()
    }

    // ============================================
    // DATA CLASSES
    // ============================================

    private data class EnemyMarker(
        val x: Float,
        val y: Float,
        val confidence: Float,
        val isLocked: Boolean,
        val timestamp: Long
    )

    private data class AimPoint(
        val x: Float,
        val y: Float,
        val accuracy: Float
    )
}
