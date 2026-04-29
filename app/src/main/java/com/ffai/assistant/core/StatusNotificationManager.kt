package com.ffai.assistant.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ffai.assistant.MainActivity
import com.ffai.assistant.R
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*

/**
 * FASE 2: Notificación persistente mostrando estado del sistema en tiempo real.
 * Proporciona observabilidad al usuario sin necesidad de abrir la app.
 */
class StatusNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ai_status_channel"
        const val NOTIFICATION_ID = 2001
        const val UPDATE_INTERVAL_MS = 5000L  // Actualizar cada 5 segundos
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var updateJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Track último estado para evitar actualizaciones innecesarias
    private var lastState: String = ""
    private var lastFps = 0
    private var lastActionText = ""

    init {
        createNotificationChannel()
    }

    /**
     * Inicia la notificación persistente y comienza actualizaciones periódicas.
     */
    fun start() {
        // Mostrar notificación inicial
        showNotification()

        // Comenzar actualizaciones periódicas
        startPeriodicUpdates()

        Logger.i("[NOTIFICATION] Status notification manager started")
    }

    /**
     * Detiene las actualizaciones y remueve la notificación.
     */
    fun stop() {
        updateJob?.cancel()
        updateJob = null
        notificationManager.cancel(NOTIFICATION_ID)
        Logger.i("[NOTIFICATION] Status notification manager stopped")
    }

    /**
     * Actualiza la notificación manualmente (útil para eventos importantes).
     */
    fun updateNow() {
        showNotification()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Estado de IA",
                NotificationManager.IMPORTANCE_LOW  // Low = no sound/vibration
            ).apply {
                description = "Muestra el estado actual de la IA en tiempo real"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startPeriodicUpdates() {
        if (updateJob != null) return

        updateJob = scope.launch {
            while (isActive) {
                delay(UPDATE_INTERVAL_MS)
                showNotification()
            }
        }
    }

    private fun showNotification() {
        try {
            val metrics = ServiceOrchestrator.getSystemMetrics()
            val state = metrics.state

            // Construir contenido según estado
            val title = buildTitle(state)
            val content = buildContent(metrics)
            val expandedContent = buildExpandedContent(metrics)

            // Solo actualizar si cambió algo significativo
            val stateChanged = lastState != state.name || lastFps != metrics.fps.toInt()
            if (!stateChanged && expandedContent == lastActionText) {
                return  // No hay cambios significativos
            }

            lastState = state.name
            lastFps = metrics.fps.toInt()
            lastActionText = expandedContent

            // Intent para abrir MainActivity al tocar
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Intent para detener servicios (botón de acción)
            val stopIntent = Intent(context, MainActivity::class.java).apply {
                action = "STOP_AI"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val stopPendingIntent = PendingIntent.getActivity(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)  // Asegúrate de tener este ícono
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedContent))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)  // No puede ser descartada por el usuario
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_stop, "Detener", stopPendingIntent)
                .setOnlyAlertOnce(true)  // Solo sonar/vibrar la primera vez
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)

        } catch (e: Exception) {
            Logger.e("[NOTIFICATION] Error showing notification", e)
        }
    }

    private fun buildTitle(state: ServiceOrchestrator.SystemState): String {
        return when (state) {
            ServiceOrchestrator.SystemState.ACTIVE -> "✓ IA Activa"
            ServiceOrchestrator.SystemState.DEGRADED_MODE -> "⚠ IA Degradada"
            ServiceOrchestrator.SystemState.RECOVERING -> "⟳ Recuperando..."
            ServiceOrchestrator.SystemState.PERMISSIONS_REQUIRED -> "⚠ Permisos requeridos"
            ServiceOrchestrator.SystemState.FATAL_ERROR -> "✗ Error crítico"
            ServiceOrchestrator.SystemState.AI_LOADING -> "⟳ Cargando IA..."
            ServiceOrchestrator.SystemState.CAPTURE_AUTHORIZED -> "⟳ Iniciando captura..."
            else -> "IA Asistente"
        }
    }

    private fun buildContent(metrics: ServiceOrchestrator.SystemMetrics): String {
        val fps = metrics.fps.toInt()
        val latency = metrics.frameLatencyMs

        return when (metrics.state) {
            ServiceOrchestrator.SystemState.ACTIVE ->
                "FPS: $fps | Latencia: ${latency}ms"
            ServiceOrchestrator.SystemState.DEGRADED_MODE ->
                "Modo degradado - ${metrics.degradedComponents.size} componentes afectados"
            ServiceOrchestrator.SystemState.RECOVERING ->
                "Intentando recuperar el sistema..."
            else ->
                "Estado: ${metrics.state.name}"
        }
    }

    private fun buildExpandedContent(metrics: ServiceOrchestrator.SystemMetrics): String {
        val sb = StringBuilder()

        // Estado general
        sb.appendLine("Estado: ${metrics.state.name}")
        sb.appendLine()

        // Métricas de rendimiento
        sb.appendLine("📊 Rendimiento:")
        sb.appendLine("  • FPS: ${metrics.fps.toInt()}")
        sb.appendLine("  • Latencia frame: ${metrics.frameLatencyMs}ms")
        sb.appendLine("  • Última acción: hace ${metrics.lastActionLatencyMs / 1000}s")
        sb.appendLine()

        // Componentes activos
        if (metrics.activeComponents.isNotEmpty()) {
            sb.appendLine("✓ Activos (${metrics.activeComponents.size}):")
            metrics.activeComponents.take(4).forEach { component ->
                sb.appendLine("  • ${formatComponentName(component)}")
            }
            if (metrics.activeComponents.size > 4) {
                sb.appendLine("  • ... y ${metrics.activeComponents.size - 4} más")
            }
            sb.appendLine()
        }

        // Componentes degradados
        if (metrics.degradedComponents.isNotEmpty()) {
            sb.appendLine("⚠ Degradados (${metrics.degradedComponents.size}):")
            metrics.degradedComponents.forEach { component ->
                sb.appendLine("  • ${formatComponentName(component)}")
            }
            sb.appendLine()
        }

        // Uptime
        val uptimeMinutes = metrics.uptimeSeconds / 60
        val uptimeSeconds = metrics.uptimeSeconds % 60
        sb.appendLine("⏱ Uptime: ${uptimeMinutes}m ${uptimeSeconds}s")

        return sb.toString()
    }

    private fun formatComponentName(name: String): String {
        return name.replace("_", " ")
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}
