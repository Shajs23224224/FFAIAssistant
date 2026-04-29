package com.ffai.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ffai.assistant.utils.Logger

/**
 * BootReceiver - Reinicia el servicio automáticamente al encender el dispositivo.
 * Requiere permiso RECEIVE_BOOT_COMPLETED en manifest.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Logger.i("BootReceiver", "Dispositivo reiniciado - iniciando servicios")
            
            // Iniciar KeepAliveService para mantener la app viva
            val keepAliveIntent = Intent(context, KeepAliveService::class.java)
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(keepAliveIntent)
                } else {
                    context.startService(keepAliveIntent)
                }
                Logger.i("BootReceiver", "KeepAliveService iniciado automáticamente")
            } catch (e: Exception) {
                Logger.e("BootReceiver", "Error iniciando KeepAliveService", e)
            }
            
            // Nota: FFAccessibilityService requiere que el usuario lo habilite manualmente
            // en Configuración > Accesibilidad. No se puede iniciar automáticamente.
        }
    }
}
