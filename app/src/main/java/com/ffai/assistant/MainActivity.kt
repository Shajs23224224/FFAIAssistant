package com.ffai.assistant

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ffai.assistant.action.Action
import com.ffai.assistant.capture.ScreenCaptureService
// CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
// import com.ffai.assistant.cloud.BackupScheduler
// import com.ffai.assistant.cloud.DownloadProgress
// import com.ffai.assistant.cloud.GoogleAccount
// import com.ffai.assistant.cloud.GoogleAuthManager
// import com.ffai.assistant.cloud.ModelDownloader
import com.ffai.assistant.decision.NeuralNetwork
import com.ffai.assistant.network.ServerConfig
import com.ffai.assistant.network.SocketIOManager
import com.ffai.assistant.utils.Config
import com.ffai.assistant.utils.Logger
// CLOUD FEATURES DISABLED
// import com.google.android.gms.common.SignInButton
// import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

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
    
    // SocketIO UI elements
    private lateinit var etServerUrl: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvConnectionFps: TextView
    
    private var isServiceEnabled = false
    private var isCapturing = false
    private var receiverRegistered = false
    
    // SocketIO Manager
    private lateinit var socketIOManager: SocketIOManager
    
    /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
    // Google Sign-In & Drive
    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var modelDownloader: ModelDownloader
    private var currentGoogleAccount: GoogleAccount? = null
    
    // UI de Google Sign-In
    private lateinit var signInButton: SignInButton
    private lateinit var btnSignOut: Button
    private lateinit var tvGoogleStatus: TextView
    private lateinit var tvUserName: TextView
    private lateinit var ivUserPhoto: ImageView
    private lateinit var layoutGoogleProfile: LinearLayout
    private lateinit var progressBarModelDownload: ProgressBar
    private lateinit var tvModelDownloadStatus: TextView
    private lateinit var btnCheckModelUpdate: Button
    private lateinit var btnSyncNow: Button
    */
    
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.ffai.assistant.STATUS_UPDATE" -> {
                    val status = intent.getStringExtra("status") ?: ""
                    runOnUiThread { tvStatus.text = getString(R.string.status_format, status) }
                }
                "com.ffai.assistant.CAPTURE_STARTED" -> {
                    isCapturing = true
                    updateUIState()
                    Toast.makeText(context, getString(R.string.capture_started), Toast.LENGTH_SHORT).show()
                }
                "com.ffai.assistant.CAPTURE_STOPPED" -> {
                    isCapturing = false
                    updateUIState()
                    Toast.makeText(context, "Captura detenida", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Launcher para solicitar permiso de MediaProjection
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                // Iniciar el Foreground Service de captura con los datos de MediaProjection
                startScreenCaptureService(result.resultCode, data)
            } ?: run {
                Toast.makeText(this, getString(R.string.capture_permission_denied), Toast.LENGTH_LONG).show()
                isCapturing = false
                updateUIState()
            }
        } else {
            Toast.makeText(this, getString(R.string.capture_permission_denied), Toast.LENGTH_LONG).show()
            isCapturing = false
            updateUIState()
        }
    }
    
    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        Logger.i("MainActivity: Iniciando ScreenCaptureService...")
        
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_CAPTURE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Logger.e("MainActivity: Error iniciando ScreenCaptureService", e)
            Toast.makeText(this, getString(R.string.capture_start_error), Toast.LENGTH_LONG).show()
            isCapturing = false
            updateUIState()
        }
    }
    
    private fun stopScreenCaptureService() {
        Logger.i("MainActivity: Deteniendo ScreenCaptureService...")
        
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            Logger.e("MainActivity: Error deteniendo ScreenCaptureService", e)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
        // Inicializar Google Auth
        googleAuthManager = GoogleAuthManager(this)
        modelDownloader = ModelDownloader(this)
        */
        
        initViews()
        loadSettings()
        registerReceivers()
        
        /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
        // Verificar sesión de Google existente
        checkExistingGoogleSession()
        */
        
        Logger.i("MainActivity creada - IA 100% Local (Cloud desactivado)")
    }
    
    /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
    /**
     * Verifica si hay una sesión de Google activa.
     */
    private fun checkExistingGoogleSession() {
        if (googleAuthManager.isSignedIn()) {
            currentGoogleAccount = googleAuthManager.getCurrentAccount()
            updateGoogleUI(true)
            Logger.i("MainActivity: Sesión de Google existente - ${currentGoogleAccount?.email}")
        } else {
            updateGoogleUI(false)
        }
    }
    */
    
    override fun onResume() {
        super.onResume()
        checkServiceStatus()
        updateUIState()

        // Notificar al servicio que la actividad está en primer plano
        if (isServiceEnabled && FFAccessibilityService.isServiceRunning) {
            FFAccessibilityService.instance?.setActivityReady(true)
        }
    }

    override fun onPause() {
        super.onPause()
        // Notificar que la actividad ya no está en primer plano
        FFAccessibilityService.instance?.setActivityReady(false)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        
        // Cleanup SocketIO
        if (::socketIOManager.isInitialized) {
            socketIOManager.disconnect()
        }
    }
    
    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        seekBarFps = findViewById(R.id.seekBarFps)
        tvFpsValue = findViewById(R.id.tvFpsValue)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        tvLastAction = findViewById(R.id.tvLastAction)
        
        // SocketIO UI
        etServerUrl = findViewById(R.id.etServerUrl)
        btnConnect = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvLatency = findViewById(R.id.tvLatency)
        tvConnectionFps = findViewById(R.id.tvFps)
        
        /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
        // Google Sign-In UI
        signInButton = findViewById(R.id.signInButton)
        btnSignOut = findViewById(R.id.btnSignOut)
        tvGoogleStatus = findViewById(R.id.tvGoogleStatus)
        tvUserName = findViewById(R.id.tvUserName)
        ivUserPhoto = findViewById(R.id.ivUserPhoto)
        layoutGoogleProfile = findViewById(R.id.layoutGoogleProfile)
        progressBarModelDownload = findViewById(R.id.progressBarModelDownload)
        tvModelDownloadStatus = findViewById(R.id.tvModelDownloadStatus)
        btnCheckModelUpdate = findViewById(R.id.btnCheckModelUpdate)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        */
        
        // Initialize SocketIO Manager
        socketIOManager = SocketIOManager.getInstance()
        setupSocketIOCallbacks()
        
        /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
        // Setup Google Sign-In listeners
        setupGoogleSignInListeners()
        */
        
        btnToggleService.setOnClickListener {
            toggleService()
        }
        
        btnCalibrate.setOnClickListener {
            startCalibration()
        }
        
        btnConnect.setOnClickListener {
            handleConnectClick()
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
    
    /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
    /**
     * Configura los listeners de Google Sign-In.
     */
    private fun setupGoogleSignInListeners() {
        signInButton.setOnClickListener {
            handleGoogleSignIn()
        }
        
        btnSignOut.setOnClickListener {
            handleGoogleSignOut()
        }
        
        btnCheckModelUpdate.setOnClickListener {
            checkForModelUpdate()
        }
        
        btnSyncNow.setOnClickListener {
            triggerManualSync()
        }
    }
    */
    
    private fun setupSocketIOCallbacks() {
        socketIOManager.setOnConnectionChanged { isConnected, message ->
            runOnUiThread {
                val statusText = if (isConnected) "🟢 $message" else "🔴 $message"
                tvConnectionStatus.text = statusText
                btnConnect.text = if (isConnected) "Desconectar" else "Conectar"
            }
        }
        
        socketIOManager.setOnActionReceived { action ->
            runOnUiThread {
                tvLastAction.text = "Última: ${action.type} (${action.x},${action.y})"
            }
        }
        
        socketIOManager.setOnError { error ->
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
        
        socketIOManager.setOnMetricsUpdate { latency, fps, bytesSent ->
            runOnUiThread {
                tvLatency.text = "${latency}ms"
                tvConnectionFps.text = "${fps} FPS"
            }
        }
    }
    
    private fun handleConnectClick() {
        if (socketIOManager.isConnected()) {
            socketIOManager.disconnect()
        } else {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Ingresa la URL del servidor", Toast.LENGTH_SHORT).show()
                return
            }
            
            ServerConfig.configure(url)
            val success = socketIOManager.connect()
            
            if (!success) {
                Toast.makeText(this, "Error al iniciar conexión", Toast.LENGTH_SHORT).show()
            }
        }
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
            addAction("com.ffai.assistant.CAPTURE_STARTED")
            addAction("com.ffai.assistant.CAPTURE_STOPPED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
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
            getString(R.string.disable_service)
        } else {
            getString(R.string.enable_service)
        }
        
        tvStatus.text = when {
            isCapturing -> getString(R.string.status_format, getString(R.string.status_processing))
            isServiceEnabled -> getString(R.string.status_format, getString(R.string.status_ready))
            else -> getString(R.string.status_format, getString(R.string.status_disabled))
        }
    }
    
    private fun toggleService() {
        if (!isServiceEnabled) {
            showEnableAccessibilityDialog()
            return
        }
        
        if (isCapturing) {
            // Detener captura
            stopScreenCaptureService()
            FFAccessibilityService.instance?.stopAI()
            isCapturing = false
            updateUIState()
            Toast.makeText(this, getString(R.string.service_stopped_message), Toast.LENGTH_SHORT).show()
        } else {
            // Iniciar - solicitar permiso de captura de pantalla
            requestMediaProjection()
        }
    }
    
    private fun requestMediaProjection() {
        // Usar el método seguro para obtener MediaProjectionManager
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        if (projectionManager == null) {
            Logger.e("MainActivity: MediaProjectionManager no disponible")
            Toast.makeText(this, "Error: MediaProjectionManager no disponible", Toast.LENGTH_LONG).show()
            return
        }
        val intent = projectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
    
    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_accessibility_title)
            .setMessage(R.string.dialog_accessibility_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(R.string.cancel, null)
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
            .setTitle(R.string.dialog_calibration_title)
            .setMessage(R.string.dialog_calibration_message)
            .setPositiveButton(R.string.understood, null)
            .show()
    }
    
    /* CLOUD FEATURES DISABLED - FASE 1: IA 100% LOCAL
    // ============================================
    // GOOGLE SIGN-IN METHODS (DISABLED)
    // ============================================
    
    /**
     * Inicia el proceso de Sign-In con Google.
     */
    private fun handleGoogleSignIn() {
        googleAuthManager.signIn(this) { result ->
            result.onSuccess { account ->
                currentGoogleAccount = account
                runOnUiThread {
                    updateGoogleUI(true)
                    Toast.makeText(this, "Bienvenido ${account.displayName}", Toast.LENGTH_SHORT).show()
                }
                Logger.i("MainActivity: Google Sign-In exitoso - ${account.email}")
                
                // Iniciar sincronización periódica
                scheduleDriveSync()
                
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, "Error de inicio de sesión: ${error.message}", Toast.LENGTH_LONG).show()
                }
                Logger.e("MainActivity: Google Sign-In falló", error)
            }
        }
    }
    
    /**
     * Cierra la sesión de Google.
     */
    private fun handleGoogleSignOut() {
        googleAuthManager.signOut { success ->
            runOnUiThread {
                if (success) {
                    currentGoogleAccount = null
                    updateGoogleUI(false)
                    Toast.makeText(this, "Sesión cerrada", Toast.LENGTH_SHORT).show()
                    Logger.i("MainActivity: Google Sign-Out exitoso")
                } else {
                    Toast.makeText(this, "Error al cerrar sesión", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Actualiza la UI según el estado de autenticación.
     */
    private fun updateGoogleUI(isSignedIn: Boolean) {
        if (isSignedIn) {
            signInButton.visibility = View.GONE
            layoutGoogleProfile.visibility = View.VISIBLE
            btnSignOut.visibility = View.VISIBLE
            btnCheckModelUpdate.visibility = View.VISIBLE
            btnSyncNow.visibility = View.VISIBLE
            tvGoogleStatus.text = "🟢 Conectado a Google Drive"
            tvUserName.text = currentGoogleAccount?.displayName ?: currentGoogleAccount?.email
            
            // Mostrar info del modelo actual
            val modelSize = NeuralNetwork.getCurrentModelSize(this)
            val modelSizeMB = modelSize / (1024 * 1024)
            tvModelDownloadStatus.text = "Modelo actual: $modelSizeMB MB"
        } else {
            signInButton.visibility = View.VISIBLE
            layoutGoogleProfile.visibility = View.GONE
            btnSignOut.visibility = View.GONE
            btnCheckModelUpdate.visibility = View.GONE
            btnSyncNow.visibility = View.GONE
            tvGoogleStatus.text = "🔴 Sin conexión a Google Drive"
            tvUserName.text = ""
            tvModelDownloadStatus.text = "Inicia sesión para sincronizar modelos"
        }
    }
    
    /**
     * Verifica si hay actualizaciones de modelo disponibles en Drive.
     */
    private fun checkForModelUpdate() {
        if (!googleAuthManager.isSignedIn()) {
            Toast.makeText(this, "Inicia sesión primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                tvModelDownloadStatus.text = "Verificando actualizaciones..."
                progressBarModelDownload.visibility = View.VISIBLE
                btnCheckModelUpdate.isEnabled = false
                
                val authToken = googleAuthManager.getIdToken()
                if (authToken == null) {
                    Toast.makeText(this@MainActivity, "Error de autenticación", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Aquí se implementaría la verificación con Drive
                // Por ahora, simulamos que hay una actualización disponible
                
                tvModelDownloadStatus.text = "Actualización disponible (simulación)"
                
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Actualización de Modelo")
                    .setMessage("Hay una nueva versión del modelo disponible. ¿Deseas descargarla?")
                    .setPositiveButton("Descargar") { _, _ ->
                        downloadModelFromDrive()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                    
            } catch (e: Exception) {
                Logger.e("MainActivity: Error verificando actualización", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBarModelDownload.visibility = View.GONE
                btnCheckModelUpdate.isEnabled = true
            }
        }
    }
    
    /**
     * Descarga el modelo desde Google Drive.
     */
    private fun downloadModelFromDrive() {
        lifecycleScope.launch {
            try {
                tvModelDownloadStatus.text = "Descargando modelo..."
                progressBarModelDownload.visibility = View.VISIBLE
                progressBarModelDownload.progress = 0
                btnCheckModelUpdate.isEnabled = false
                
                // Implementación real usaría ModelDownloader con Drive API
                // Por ahora simulamos la descarga
                
                for (i in 0..100 step 10) {
                    progressBarModelDownload.progress = i
                    tvModelDownloadStatus.text = "Descargando... $i%"
                    kotlinx.coroutines.delay(200)
                }
                
                tvModelDownloadStatus.text = "✅ Modelo actualizado"
                Toast.makeText(this@MainActivity, "Modelo descargado exitosamente", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Logger.e("MainActivity: Error descargando modelo", e)
                tvModelDownloadStatus.text = "❌ Error en descarga"
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBarModelDownload.visibility = View.GONE
                btnCheckModelUpdate.isEnabled = true
            }
        }
    }
    
    /**
     * Inicia sincronización manual con Drive.
     */
    private fun triggerManualSync() {
        if (!googleAuthManager.isSignedIn()) {
            Toast.makeText(this, "Inicia sesión primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        val authToken = googleAuthManager.getServerAuthCode()
        if (authToken != null) {
            BackupScheduler.runOneTimeSync(this, authToken, syncModels = true, syncData = true)
            Toast.makeText(this, "Sincronización iniciada", Toast.LENGTH_SHORT).show()
            Logger.i("MainActivity: Sincronización manual iniciada")
        } else {
            Toast.makeText(this, "Error obteniendo token de autenticación", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Programa sincronización periódica con Drive.
     */
    private fun scheduleDriveSync() {
        val authToken = googleAuthManager.getServerAuthCode()
        if (authToken != null) {
            // Programar sincronización cada 6 horas, solo WiFi
            BackupScheduler.schedulePeriodicSync(
                this,
                repeatInterval = 6,
                requireWifi = true,
                requireCharging = false
            )
            Logger.i("MainActivity: Sincronización periódica programada")
        }
    }
    
    /**
     * Maneja el resultado de Sign-In desde onActivityResult.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        googleAuthManager.handleSignInResult(requestCode, resultCode, data)
    }
    */
}
