package com.ffai.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ffai.assistant.utils.Config
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
        Config.init(applicationContext)
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
        if (Config.isKeepAliveEnabled()) {
            scheduleRestart()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.w("KeepAliveService removido de recientes; reprogramando servicio")
        if (Config.isKeepAliveEnabled()) {
            scheduleRestart()
        }
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(this, KeepAliveService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                2001,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(AlarmManager::class.java)
            val triggerAtMillis = System.currentTimeMillis() + 1500L
            alarmManager?.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: Exception) {
            Logger.e("KeepAliveService: error programando reinicio", e)
        }
    }
}
