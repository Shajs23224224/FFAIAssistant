package com.ffai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ffai.assistant.utils.Logger

/**
 * Servicio watchdog para mantener la app viva en segundo plano.
 * Se ejecuta como Foreground Service para evitar que el sistema lo mate.
 */
class KeepAliveService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "keep_alive_channel"
        const val NOTIFICATION_ID = 1002
        
        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Logger.i("KeepAliveService creado")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        isRunning = true
        Logger.i("KeepAliveService iniciado")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Logger.i("KeepAliveService destruido")
    }

    private fun startForegroundService() {
        createNotificationChannel()
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FFAI Assistant")
            .setContentText("Servicio activo en segundo plano")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Servicio Keep Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la app activa en segundo plano"
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
