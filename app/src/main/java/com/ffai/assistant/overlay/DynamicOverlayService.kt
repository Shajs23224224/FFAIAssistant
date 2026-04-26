package com.ffai.assistant.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.ffai.assistant.R
import com.ffai.assistant.utils.Logger

/**
 * FASE 1: DynamicOverlayService - Ventana flotante que cubre toda la pantalla
 * sin interferir con los toques del usuario.
 * 
 * Características:
 * - Cubre toda la pantalla (720x1600 en A21S)
 * - No intercepta toques (FLAG_NOT_TOUCHABLE)
 * - UI de control draggable y redimensionable
 * - Indicadores visuales de análisis en tiempo real
 * - Controles de ROI dinámico
 */
class DynamicOverlayService : Service() {

    companion object {
        const val TAG = "DynamicOverlayService"
        
        // Tamaños de ROI
        const val ROI_FULL = 1.0f
        const val ROI_LARGE = 0.75f
        const val ROI_MEDIUM = 0.5f
        const val ROI_SMALL = 0.25f
        
        // Velocidades de cámara
        const val CAMERA_SMOOTH = 0
        const val CAMERA_MEDIUM = 1
        const val CAMERA_AGGRESSIVE = 2
        
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var controlPanel: View? = null
    
    // Layouts principales
    private var analysisOverlay: AnalysisOverlayView? = null
    private var roiIndicator: ROIIndicatorView? = null
    
    // Callbacks
    private var onROIChanged: ((Float, Float, Float) -> Unit)? = null // (x, y, scale)
    private var onCameraSpeedChanged: ((Int) -> Unit)? = null
    private var onAnalysisToggle: ((Boolean) -> Unit)? = null
    
    // Estado
    private var currentROIScale = ROI_FULL
    private var currentCameraSpeed = CAMERA_MEDIUM
    private var isAnalysisEnabled = true
    private var screenWidth = 720
    private var screenHeight = 1600
    
    // Handler para UI
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "DynamicOverlayService creado")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        getScreenDimensions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            createOverlay()
            createControlPanel()
            isRunning = true
            Logger.i(TAG, "Overlay iniciado - Pantalla: ${screenWidth}x${screenHeight}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        isRunning = false
        Logger.i(TAG, "Overlay detenido")
    }

    /**
     * Crea el overlay principal de análisis que cubre toda la pantalla.
     * NO intercepta toques (FLAG_NOT_TOUCHABLE).
     */
    private fun createOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.START

        // Crear vista de análisis personalizada
        analysisOverlay = AnalysisOverlayView(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Añadir indicador de ROI
        roiIndicator = ROIIndicatorView(this).apply {
            this.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Contenedor principal
        val container = FrameLayout(this).apply {
            addView(analysisOverlay)
            addView(roiIndicator)
        }

        overlayView = container
        windowManager.addView(overlayView, layoutParams)
        
        updateROIIndicator()
    }

    /**
     * Crea el panel de control flotante (draggable).
     */
    private fun createControlPanel() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.TOP or Gravity.END
        layoutParams.x = 20
        layoutParams.y = 100

        controlPanel = LayoutInflater.from(this).inflate(R.layout.overlay_control_panel, null)
        
        setupControlPanel(controlPanel!!, layoutParams)
        windowManager.addView(controlPanel, layoutParams)
    }

    /**
     * Configura los controles del panel.
     */
    private fun setupControlPanel(panel: View, layoutParams: WindowManager.LayoutParams) {
        // Botón para mover panel
        val dragHandle = panel.findViewById<ImageButton>(R.id.dragHandle)
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (touchX - event.rawX).toInt()
                    layoutParams.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(panel, layoutParams)
                    true
                }
                else -> false
            }
        }

        // Toggle análisis
        val toggleAnalysis = panel.findViewById<ImageButton>(R.id.toggleAnalysis)
        toggleAnalysis?.setOnClickListener {
            isAnalysisEnabled = !isAnalysisEnabled
            analysisOverlay?.setVisible(isAnalysisEnabled)
            roiIndicator?.setVisible(isAnalysisEnabled)
            onAnalysisToggle?.invoke(isAnalysisEnabled)
            Logger.d(TAG, "Análisis: $isAnalysisEnabled")
        }

        // SeekBar ROI
        val roiSeekBar = panel.findViewById<SeekBar>(R.id.roiSeekBar)
        val roiLabel = panel.findViewById<TextView>(R.id.roiLabel)
        
        roiSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentROIScale = when (progress) {
                    0 -> ROI_SMALL
                    1 -> ROI_MEDIUM
                    2 -> ROI_LARGE
                    else -> ROI_FULL
                }
                roiLabel?.text = "ROI: ${(currentROIScale * 100).toInt()}%"
                updateROIIndicator()
                notifyROIChanged()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Botones velocidad cámara
        val btnSmooth = panel.findViewById<ImageButton>(R.id.cameraSmooth)
        val btnMedium = panel.findViewById<ImageButton>(R.id.cameraMedium)
        val btnAggressive = panel.findViewById<ImageButton>(R.id.cameraAggressive)

        btnSmooth?.setOnClickListener { setCameraSpeed(CAMERA_SMOOTH, btnSmooth, btnMedium, btnAggressive) }
        btnMedium?.setOnClickListener { setCameraSpeed(CAMERA_MEDIUM, btnSmooth, btnMedium, btnAggressive) }
        btnAggressive?.setOnClickListener { setCameraSpeed(CAMERA_AGGRESSIVE, btnSmooth, btnMedium, btnAggressive) }

        // Status
        val statusText = panel.findViewById<TextView>(R.id.statusText)
        statusText?.text = "IA Avanzada: Lista"
    }

    private fun setCameraSpeed(speed: Int, vararg buttons: ImageButton?) {
        currentCameraSpeed = speed
        buttons.forEachIndexed { index, button ->
            button?.alpha = if (index == speed) 1.0f else 0.5f
        }
        onCameraSpeedChanged?.invoke(speed)
        Logger.d(TAG, "Velocidad cámara: $speed")
    }

    /**
     * Actualiza el indicador visual del ROI.
     */
    private fun updateROIIndicator() {
        val roiWidth = (screenWidth * currentROIScale).toInt()
        val roiHeight = (screenHeight * currentROIScale).toInt()
        val roiX = (screenWidth - roiWidth) / 2
        val roiY = (screenHeight - roiHeight) / 2
        
        roiIndicator?.setROI(roiX, roiY, roiWidth, roiHeight)
        
        // También actualizar el área de análisis
        analysisOverlay?.setAnalysisArea(roiX, roiY, roiWidth, roiHeight)
    }

    private fun notifyROIChanged() {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        onROIChanged?.invoke(centerX, centerY, currentROIScale)
    }

    private fun getScreenDimensions() {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager.removeView(it) }
        controlPanel?.let { windowManager.removeView(it) }
        overlayView = null
        controlPanel = null
    }

    // ============================================
    // API PÚBLICA
    // ============================================

    fun setOnROIChangedListener(listener: (Float, Float, Float) -> Unit) {
        onROIChanged = listener
    }

    fun setOnCameraSpeedChangedListener(listener: (Int) -> Unit) {
        onCameraSpeedChanged = listener
    }

    fun setOnAnalysisToggleListener(listener: (Boolean) -> Unit) {
        onAnalysisToggle = listener
    }

    /**
     * Dibuja detección de enemigo en el overlay.
     */
    fun drawEnemyDetection(x: Float, y: Float, confidence: Float, isLocked: Boolean = false) {
        uiHandler.post {
            analysisOverlay?.drawEnemy(x, y, confidence, isLocked)
        }
    }

    /**
     * Dibuja punto de aim.
     */
    fun drawAimPoint(x: Float, y: Float, accuracy: Float) {
        uiHandler.post {
            analysisOverlay?.drawAimPoint(x, y, accuracy)
        }
    }

    /**
     * Dibuja línea de trayectoria.
     */
    fun drawTrajectory(points: List<Pair<Float, Float>>, color: Int) {
        uiHandler.post {
            analysisOverlay?.drawTrajectory(points, color)
        }
    }

    /**
     * Muestra información de estado en overlay.
     */
    fun showStatusInfo(text: String, durationMs: Long = 2000) {
        uiHandler.post {
            analysisOverlay?.showStatus(text, durationMs)
        }
    }

    /**
     * Limpia todas las marcas de análisis.
     */
    fun clearAnalysis() {
        uiHandler.post {
            analysisOverlay?.clear()
        }
    }

    /**
     * Obtiene área actual de análisis.
     */
    fun getAnalysisArea(): AnalysisArea {
        val roiWidth = (screenWidth * currentROIScale).toInt()
        val roiHeight = (screenHeight * currentROIScale).toInt()
        val roiX = (screenWidth - roiWidth) / 2
        val roiY = (screenHeight - roiHeight) / 2
        return AnalysisArea(roiX, roiY, roiWidth, roiHeight, currentROIScale)
    }

    fun getCurrentCameraSpeed(): Int = currentCameraSpeed
    fun isAnalysisActive(): Boolean = isAnalysisEnabled
}

/**
 * Data class para área de análisis.
 */
data class AnalysisArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Float
)
