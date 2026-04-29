package com.ffai.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ffai.assistant.utils.Config
import com.ffai.assistant.utils.Logger

/**
 * BootReceiver - Reinicia el servicio automáticamente al encender el dispositivo.
 * Requiere permiso RECEIVE_BOOT_COMPLETED en manifest.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        Config.init(context.applicationContext)

        if (
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Logger.i("BootReceiver", "Evento ${intent.action} recibido - restaurando persistencia")

            if (Config.isKeepAliveEnabled() || Config.isAiStartRequested()) {
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
            }

            if (Config.isCaptureActive()) {
                Logger.w(
                    "BootReceiver",
                    "La captura estaba marcada como activa, pero MediaProjection debe concederse de nuevo tras reinicio/proceso muerto"
                )
            }
        }
    }
}
