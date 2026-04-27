package com.ffai.assistant.model

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ModelDownloadService - Descarga automática de modelos en background.
 * Se ejecuta cuando hay WiFi y carga inicial.
 */
class ModelDownloadService : Service() {
    
    private lateinit var modelManager: ModelManager
    private val scope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(this)
        Logger.i("ModelDownloadService", "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            downloadMissingModels()
        }
        return START_STICKY
    }
    
    private suspend fun downloadMissingModels() {
        val status = modelManager.getStatus()
        val missing = status.filter { it.value.isPlaceholder }.keys.toList()
        
        if (missing.isEmpty()) {
            Logger.i("ModelDownloadService", "All models available")
            stopSelf()
            return
        }
        
        Logger.i("ModelDownloadService", "Downloading ${missing.size} models...")
        
        // Descargar cada modelo (implementar con OkHttp/Retrofit)
        missing.forEach { modelName ->
            val success = downloadModel(modelName)
            if (success) {
                Logger.i("ModelDownloadService", "Downloaded: $modelName")
            } else {
                Logger.e("ModelDownloadService", "Failed: $modelName")
            }
        }
        
        stopSelf()
    }
    
    private suspend fun downloadModel(name: String): Boolean {
        // Implementar descarga real con OkHttp
        // URL: https://models.ffai-assistant.com/v1/$name
        return false // Placeholder
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
