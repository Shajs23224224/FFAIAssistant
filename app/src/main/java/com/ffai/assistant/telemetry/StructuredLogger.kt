package com.ffai.assistant.telemetry

import android.content.Context
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FASE 6: StructuredLogger - Logging estructurado en JSON.
 * 
 * Features:
 * - JSON logs para análisis posterior
 * - Estado cada 100ms
 * - Decisiones con justificación
 * - Rotación automática de archivos
 * - Export a archivo
 */
class StructuredLogger(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    companion object {
        const val TAG = "StructuredLogger"
        const val LOG_INTERVAL_MS = 100L
        const val MAX_LOG_SIZE = 10 * 1024 * 1024L  // 10MB
        const val MAX_LOG_AGE_DAYS = 7
    }
    
    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val eventQueue = ConcurrentLinkedQueue<LogEvent>()
    private var currentLogFile: FileWriter? = null
    private var currentFileSize = 0L
    
    // Stats
    private var eventsLogged = 0
    private var startTime = System.currentTimeMillis()
    
    // Running job
    private var loggingJob: Job? = null
    
    /**
     * Inicia logging.
     */
    fun startLogging() {
        createNewLogFile()
        
        loggingJob = scope.launch {
            while (isActive) {
                delay(LOG_INTERVAL_MS)
                flushEvents()
            }
        }
        
        Logger.i(TAG, "Structured logging started to ${logDir.absolutePath}")
    }
    
    /**
     * Log de evento de decisión.
     */
    fun logDecision(
        timestamp: Long,
        state: Map<String, Any>,
        action: ActionType,
        confidence: Float,
        reasoning: String,
        latencyMs: Long
    ) {
        eventQueue.offer(LogEvent.Decision(
            timestamp = timestamp,
            state = state,
            action = action.name,
            confidence = confidence,
            reasoning = reasoning,
            latencyMs = latencyMs
        ))
    }
    
    /**
     * Log de estado del sistema.
     */
    fun logState(
        timestamp: Long,
        fps: Int,
        latency: Long,
        enemiesDetected: Int,
        currentMode: String,
        memoryUsage: Long,
        cpuUsage: Float
    ) {
        eventQueue.offer(LogEvent.State(
            timestamp = timestamp,
            fps = fps,
            latency = latency,
            enemiesDetected = enemiesDetected,
            currentMode = currentMode,
            memoryUsage = memoryUsage,
            cpuUsage = cpuUsage
        ))
    }
    
    /**
     * Log de evento de juego.
     */
    fun logGameEvent(
        timestamp: Long,
        eventType: GameEventType,
        details: Map<String, Any>
    ) {
        eventQueue.offer(LogEvent.Game(
            timestamp = timestamp,
            eventType = eventType.name,
            details = details
        ))
    }
    
    /**
     * Log de error.
     */
    fun logError(
        timestamp: Long,
        component: String,
        error: String,
        stackTrace: String? = null
    ) {
        eventQueue.offer(LogEvent.Error(
            timestamp = timestamp,
            component = component,
            error = error,
            stackTrace = stackTrace
        ))
    }
    
    /**
     * Flushea eventos a archivo.
     */
    private suspend fun flushEvents() = withContext(Dispatchers.IO) {
        if (eventQueue.isEmpty()) return@withContext
        
        val batch = mutableListOf<LogEvent>()
        while (batch.size < 100 && eventQueue.isNotEmpty()) {
            eventQueue.poll()?.let { batch.add(it) }
        }
        
        if (batch.isEmpty()) return@withContext
        
        try {
            val writer = currentLogFile ?: createNewLogFile()
            
            for (event in batch) {
                val json = eventToJson(event)
                writer.append(json.toString())
                writer.append("\n")
                currentFileSize += json.toString().length
                eventsLogged++
            }
            
            writer.flush()
            
            // Rotar archivo si muy grande
            if (currentFileSize > MAX_LOG_SIZE) {
                rotateLogFile()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error writing logs", e)
        }
    }
    
    /**
     * Convierte evento a JSON.
     */
    private fun eventToJson(event: LogEvent): JSONObject {
        val json = JSONObject()
        
        when (event) {
            is LogEvent.Decision -> {
                json.put("type", "decision")
                json.put("timestamp", event.timestamp)
                json.put("action", event.action)
                json.put("confidence", event.confidence)
                json.put("reasoning", event.reasoning)
                json.put("latency_ms", event.latencyMs)
                json.put("state", JSONObject(event.state))
            }
            is LogEvent.State -> {
                json.put("type", "state")
                json.put("timestamp", event.timestamp)
                json.put("fps", event.fps)
                json.put("latency", event.latency)
                json.put("enemies", event.enemiesDetected)
                json.put("mode", event.currentMode)
                json.put("memory", event.memoryUsage)
                json.put("cpu", event.cpuUsage)
            }
            is LogEvent.Game -> {
                json.put("type", "game")
                json.put("timestamp", event.timestamp)
                json.put("event", event.eventType)
                json.put("details", JSONObject(event.details))
            }
            is LogEvent.Error -> {
                json.put("type", "error")
                json.put("timestamp", event.timestamp)
                json.put("component", event.component)
                json.put("error", event.error)
                event.stackTrace?.let { json.put("stacktrace", it) }
            }
        }
        
        return json
    }
    
    /**
     * Crea nuevo archivo de log.
     */
    private fun createNewLogFile(): FileWriter {
        closeCurrentFile()
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(logDir, "ffai_log_$timestamp.jsonl")
        
        currentLogFile = FileWriter(file, true)
        currentFileSize = file.length()
        
        // Escribir header
        val header = JSONObject().apply {
            put("type", "session_start")
            put("timestamp", System.currentTimeMillis())
            put("version", "1.0.0")
        }
        currentLogFile?.append(header.toString())
        currentLogFile?.append("\n")
        
        return currentLogFile!!
    }
    
    /**
     * Rota archivo de log.
     */
    private fun rotateLogFile() {
        closeCurrentFile()
        createNewLogFile()
        Logger.i(TAG, "Log file rotated")
    }
    
    /**
     * Cierra archivo actual.
     */
    private fun closeCurrentFile() {
        try {
            currentLogFile?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing log file", e)
        }
        currentLogFile = null
    }
    
    /**
     * Limpia logs antiguos.
     */
    fun cleanupOldLogs() {
        val cutoff = System.currentTimeMillis() - (MAX_LOG_AGE_DAYS * 24 * 60 * 60 * 1000)
        
        logDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
                Logger.d(TAG, "Deleted old log: ${file.name}")
            }
        }
    }
    
    /**
     * Exporta logs a archivo accesible.
     */
    suspend fun exportLogs(): File = withContext(Dispatchers.IO) {
        flushEvents()
        
        val exportFile = File(context.getExternalFilesDir(null), "ffai_export_${System.currentTimeMillis()}.jsonl")
        
        logDir.listFiles()?.sortedBy { it.lastModified() }?.forEach { logFile ->
            val existingContent = if (exportFile.exists()) exportFile.readText() else ""
            val newContent = existingContent + logFile.readText()
            exportFile.writeText(newContent)
        }
        
        Logger.i(TAG, "Logs exported to ${exportFile.absolutePath}")
        exportFile
    }
    
    /**
     * Obtiene stats.
     */
    fun getStats(): LoggerStats {
        val logFiles = logDir.listFiles()?.size ?: 0
        val totalSize = logDir.listFiles()?.sumOf { it.length() } ?: 0
        
        return LoggerStats(
            eventsLogged = eventsLogged,
            queueSize = eventQueue.size,
            logFiles = logFiles,
            totalSize = totalSize,
            uptime = System.currentTimeMillis() - startTime
        )
    }
    
    /**
     * Detiene logging.
     */
    suspend fun stopLogging() {
        loggingJob?.cancelAndJoin()
        flushEvents()
        closeCurrentFile()
        Logger.i(TAG, "Structured logging stopped")
    }
    
    /**
     * Tipos de eventos de juego.
     */
    enum class GameEventType {
        KILL, DEATH, DAMAGE_DEALT, DAMAGE_TAKEN, LOOT, ZONE_CHANGE, MATCH_START, MATCH_END
    }
    
    /**
     * Eventos de log.
     */
    sealed class LogEvent {
        abstract val timestamp: Long
        
        data class Decision(
            override val timestamp: Long,
            val state: Map<String, Any>,
            val action: String,
            val confidence: Float,
            val reasoning: String,
            val latencyMs: Long
        ) : LogEvent()
        
        data class State(
            override val timestamp: Long,
            val fps: Int,
            val latency: Long,
            val enemiesDetected: Int,
            val currentMode: String,
            val memoryUsage: Long,
            val cpuUsage: Float
        ) : LogEvent()
        
        data class Game(
            override val timestamp: Long,
            val eventType: String,
            val details: Map<String, Any>
        ) : LogEvent()
        
        data class Error(
            override val timestamp: Long,
            val component: String,
            val error: String,
            val stackTrace: String?
        ) : LogEvent()
    }
}

/**
 * Stats del logger.
 */
data class LoggerStats(
    val eventsLogged: Int,
    val queueSize: Int,
    val logFiles: Int,
    val totalSize: Long,
    val uptime: Long
)
