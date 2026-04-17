package com.ffai.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ffai.assistant.utils.Config
import com.ffai.assistant.utils.Logger

/**
 * Actividad principal - Panel de control de la IA.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var seekBarFps: SeekBar
    private lateinit var tvFpsValue: TextView
    private lateinit var btnCalibrate: Button
    private lateinit var tvLastAction: TextView
    
    private var isServiceEnabled = false
    private var isCapturing = false
    
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.ffai.assistant.STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: ""
                    runOnUiThread { tvStatus.text = "Estado: $status" }
                }
            }
        }
    }
    
    // Launcher para solicitar permiso de MediaProjection
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Iniciar MediaProjection en el servicio
            FFAccessibilityService.instance?.startMediaProjection(
                result.resultCode,
                result.data!!
            )
            isCapturing = true
            updateUIState()
            Toast.makeText(this, "Captura de pantalla iniciada", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permiso denegado. La IA no puede funcionar.", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        loadSettings()
        registerReceivers()
        
        Logger.i("MainActivity creada")
    }
    
    override fun onResume() {
        super.onResume()
        checkServiceStatus()
        updateUIState()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
    
    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        seekBarFps = findViewById(R.id.seekBarFps)
        tvFpsValue = findViewById(R.id.tvFpsValue)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        tvLastAction = findViewById(R.id.tvLastAction)
        
        btnToggleService.setOnClickListener {
            toggleService()
        }
        
        btnCalibrate.setOnClickListener {
            startCalibration()
        }
        
        seekBarFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = (progress + 5).coerceIn(5, 30)  // Mínimo 5 FPS
                tvFpsValue.text = fps.toString()
                if (fromUser) {
                    Config.setFpsTarget(fps)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun loadSettings() {
        Config.init(this)
        
        val fps = Config.getFpsTarget()
        seekBarFps.progress = fps - 5
        tvFpsValue.text = fps.toString()
    }
    
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction("com.ffai.assistant.STATUS_UPDATE")
        }
        registerReceiver(statusReceiver, filter)
    }
    
    private fun checkServiceStatus() {
        // Verificar si el servicio de accesibilidad está activado
        val accessibilityEnabled = try {
            val enabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
            enabled == 1
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
        
        isServiceEnabled = accessibilityEnabled && FFAccessibilityService.isServiceRunning
    }
    
    private fun updateUIState() {
        btnToggleService.text = if (isCapturing) {
            "Desactivar Servicio"
        } else {
            "Activar Servicio"
        }
        
        tvStatus.text = when {
            isCapturing -> "Estado: Activo - Procesando"
            isServiceEnabled -> "Estado: Servicio listo"
            else -> "Estado: Servicio no activado"
        }
    }
    
    private fun toggleService() {
        if (!isServiceEnabled) {
            showEnableAccessibilityDialog()
            return
        }
        
        if (isCapturing) {
            // Detener
            FFAccessibilityService.instance?.stopAI()
            isCapturing = false
            updateUIState()
            Toast.makeText(this, "Servicio detenido", Toast.LENGTH_SHORT).show()
        } else {
            // Iniciar - solicitar permiso de captura de pantalla
            requestMediaProjection()
        }
    }
    
    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
    
    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Activar Servicio de Accesibilidad")
            .setMessage("Para funcionar, la app necesita el permiso de Accesibilidad. " +
                    "Esto permite detectar cuando Free Fire está abierto e inyectar toques automáticos.\n\n" +
                    "Ve a Ajustes > Accesibilidad > FF AI Assistant y actívalo.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
    
    private fun startCalibration() {
        // TODO: Implementar pantalla de calibración
        // Por ahora mostrar instrucciones
        AlertDialog.Builder(this)
            .setTitle("Calibración")
            .setMessage("La calibración automática detectará:\n" +
                    "• Joystick izquierdo (movimiento)\n" +
                    "• Joystick derecho (mira)\n" +
                    "• Botón de disparo\n" +
                    "• Botón de curación\n" +
                    "• Botón de recarga\n\n" +
                    "Abre Free Fire y toca cada control cuando se indique.")
            .setPositiveButton("Entendido", null)
            .show()
    }
}
