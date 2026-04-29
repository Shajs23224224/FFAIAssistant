package com.ffai.assistant.core

import android.content.Context
import android.content.SharedPreferences
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orquestador Central - Gestiona el estado global de la aplicación y coordina
 * la recuperación ante fallos. Implementa state machine explícita y health monitoring.
 *
 * Patrón: Singleton con persistencia de estado crítico
 * Responsabilidades:
 *   - Mantener state machine del sistema
 *   - Health checks de todos los componentes
 *   - Coordinar transiciones de recuperación
 *   - Persistir estado para recuperación post-crash
 */
object ServiceOrchestrator {

    private const val PREFS_NAME = "orchestrator_state"
    private const val KEY_WAS_ACTIVE = "was_active"
    private const val KEY_LAST_STATE = "last_state"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
    private const val HEALTH_CHECK_INTERVAL_MS = 5000L
    private const val FRAME_TIMEOUT_MS = 6000L
    private const val ACTION_TIMEOUT_MS = 15000L

    /**
     * Estados del sistema con transiciones válidas:
     * UNINITIALIZED → PERMISSIONS_REQUIRED → ACCESSIBILITY_READY → CAPTURE_AUTHORIZED → AI_LOADING → ACTIVE
     * ACTIVE ↔ DEGRADED_MODE (degradación/recuperación parcial)
     * ANY → RECOVERING → [ACTIVE|DEGRADED|FATAL_ERROR]
     * ANY → FATAL_ERROR (requiere intervención usuario)
     */
    enum class SystemState {
        UNINITIALIZED,
        PERMISSIONS_REQUIRED,
        ACCESSIBILITY_READY,
        CAPTURE_AUTHORIZED,
        AI_LOADING,
        ACTIVE,
        DEGRADED_MODE,
        RECOVERING,
        FATAL_ERROR
    }

    enum class Component {
        ACCESSIBILITY_SERVICE,
        CAPTURE_SERVICE,
        KEEP_ALIVE_SERVICE,
        AI_CORE,
        YOLO_DETECTOR,
        ENSEMBLE_MANAGER,
        SUPER_AGENT,
        GESTURE_CONTROLLER
    }

    enum class Issue {
        NONE,
        NOT_RESPONDING,
        NO_FRAMES,
        NO_ACTIONS,
        INFERENCE_FAILED,
        MODEL_LOAD_FAILED,
        PERMISSION_REVOKED,
        CRASHED
    }

    data class HealthStatus(
        val component: Component,
        val isHealthy: Boolean,
        val lastHeartbeat: Long,
        val issue: Issue = Issue.NONE,
        val details: String = ""
    )

    data class SystemMetrics(
        val state: SystemState,
        val fps: Float,
        val frameLatencyMs: Long,
        val lastActionLatencyMs: Long,
        val aiInferenceTimeMs: Long,
        val memoryUsageMb: Long,
        val activeComponents: List<String>,
        val degradedComponents: List<String>,
        val uptimeSeconds: Long
    )

    private val _state = MutableStateFlow(SystemState.UNINITIALIZED)
    val state: StateFlow<SystemState> = _state.asStateFlow()

    private val healthChecks = ConcurrentHashMap<Component, HealthStatus>()
    private val prefs: SharedPreferences by lazy {
        throw IllegalStateException("Call init() before using ServiceOrchestrator")
    }
    private var appContext: Context? = null

    // Timestamps para health checks
    private var lastFrameTimestamp = 0L
    private var lastActionTimestamp = 0L
    private var startTime = System.currentTimeMillis()

    // Control de health monitoring
    private var healthCheckJob: Job? = null
    private val isHealthMonitoring = AtomicBoolean(false)
    private val recoveryAttemptCount = AtomicInteger(0)
    private val MAX_RECOVERY_ATTEMPTS = 5

    // FASE 2: Degradación graceful - contadores de fallos por componente
    private val componentFailureCounts = ConcurrentHashMap<Component, AtomicInteger>()
    private val componentDegradedFlags = ConcurrentHashMap<Component, AtomicBoolean>()
    private val MAX_COMPONENT_FAILURES = 5  // Después de 5 fallos, degradar componente
    private val DEGRADATION_COOLDOWN_MS = 30000L  // 30s antes de intentar recuperar componente
    private val lastDegradationTime = ConcurrentHashMap<Component, Long>()

    // FASE 2: Métricas para observabilidad
    private val metricsHistory = mutableListOf<SystemMetrics>()
    private val MAX_METRICS_HISTORY = 100
    private val metricsJob: Job? = null
    private var totalFramesProcessed = 0L
    private var totalActionsExecuted = 0L
    private var errorCount = 0L

    private val stateChangeListeners = mutableListOf<(SystemState, SystemState, String) -> Unit>()

    /**
     * Inicializa el orchestrator. Debe llamarse antes que cualquier otro método.
     */
    fun init(context: Context) {
        if (appContext != null) {
            Logger.w("[ORCH] Already initialized, skipping")
            return
        }

        appContext = context.applicationContext
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Restaurar estado previo si la app murió inesperadamente
        val wasActive = sharedPrefs.getBoolean(KEY_WAS_ACTIVE, false)
        val lastStateName = sharedPrefs.getString(KEY_LAST_STATE, null)
        val lastHeartbeat = sharedPrefs.getLong(KEY_LAST_HEARTBEAT, 0)

        Logger.i("[ORCH] Initialized - wasActive=$wasActive, lastState=$lastStateName, lastHeartbeat=${(System.currentTimeMillis() - lastHeartbeat) / 1000}s ago")

        if (wasActive && lastStateName != null) {
            // La app estaba activa y murió - necesitamos recuperación
            Logger.w("[ORCH] Previous session was active, entering RECOVERING state")
            _state.value = SystemState.RECOVERING
            recordStateChange(SystemState.UNINITIALIZED, SystemState.RECOVERING, "Previous session detected, attempting recovery")
        } else {
            _state.value = SystemState.UNINITIALIZED
        }

        // Persistir estado periódicamente
        startStatePersistence()
    }

    /**
     * Transiciona a un nuevo estado. Todas las transiciones deben pasar por aquí.
     */
    fun transition(to: SystemState, reason: String) {
        val from = _state.value

        if (from == to) {
            Logger.d("[ORCH] No-op transition: already in $to")
            return
        }

        // Validar transición (logging de warnings para transiciones inesperadas)
        if (!isValidTransition(from, to)) {
            Logger.w("[ORCH] Unusual transition: $from → $to ($reason)")
        }

        Logger.i("[ORCH] State: $from → $to ($reason)")
        _state.value = to

        recordStateChange(from, to, reason)
        notifyStateChangeListeners(from, to, reason)

        // Acciones específicas por estado
        when (to) {
            SystemState.ACTIVE -> {
                recoveryAttemptCount.set(0)
                persistActiveState(true)
            }
            SystemState.FATAL_ERROR -> {
                persistActiveState(false)
                stopHealthMonitoring()
            }
            SystemState.UNINITIALIZED, SystemState.PERMISSIONS_REQUIRED -> {
                persistActiveState(false)
            }
            else -> { /* No special action */ }
        }
    }

    /**
     * Inicia el health monitoring periódico.
     */
    fun startHealthMonitoring(coroutineScope: CoroutineScope) {
        if (isHealthMonitoring.getAndSet(true)) {
            Logger.w("[ORCH] Health monitoring already running")
            return
        }

        Logger.i("[ORCH] Starting health monitoring (interval=${HEALTH_CHECK_INTERVAL_MS}ms)")

        healthCheckJob = coroutineScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
    }

    /**
     * Detiene el health monitoring.
     */
    fun stopHealthMonitoring() {
        isHealthMonitoring.set(false)
        healthCheckJob?.cancel()
        healthCheckJob = null
        Logger.i("[ORCH] Health monitoring stopped")
    }

    /**
     * Realiza un health check completo del sistema.
     */
    private fun performHealthCheck() {
        val currentState = _state.value
        if (currentState != SystemState.ACTIVE && currentState != SystemState.DEGRADED_MODE) {
            return // Solo chequear cuando estamos operativos
        }

        val now = System.currentTimeMillis()
        val framesFlowing = (now - lastFrameTimestamp) < FRAME_TIMEOUT_MS
        val aiResponding = (now - lastActionTimestamp) < ACTION_TIMEOUT_MS

        // Verificar componentes críticos
        val captureHealthy = checkComponentHealth(Component.CAPTURE_SERVICE, framesFlowing)
        val aiHealthy = checkComponentHealth(Component.AI_CORE, aiResponding)

        if (!captureHealthy || !aiHealthy) {
            Logger.w("[HEALTH] Check warning: capture=$captureHealthy, ai=$aiHealthy")

            if (!framesFlowing) {
                reportIssue(Component.CAPTURE_SERVICE, Issue.NO_FRAMES,
                    "No frames for ${(now - lastFrameTimestamp) / 1000}s")
            }

            if (!aiResponding && framesFlowing) {
                reportIssue(Component.AI_CORE, Issue.NO_ACTIONS,
                    "No actions for ${(now - lastActionTimestamp) / 1000}s")
            }

            // Intentar recuperación automática si estamos en ACTIVE
            if (currentState == SystemState.ACTIVE) {
                attemptRecovery()
            }
        } else {
            Logger.d("[HEALTH] Check passed: capture OK, AI OK")
            clearIssues(Component.CAPTURE_SERVICE, Component.AI_CORE)
        }
    }

    /**
     * Registra heartbeat de un componente.
     */
    fun reportHeartbeat(component: Component) {
        healthChecks[component] = HealthStatus(
            component = component,
            isHealthy = true,
            lastHeartbeat = System.currentTimeMillis(),
            issue = Issue.NONE
        )
    }

    /**
     * Reporta un problema con un componente.
     * FASE 2: Lleva conteo de fallos y activa degradación graceful.
     */
    fun reportIssue(component: Component, issue: Issue, details: String = "") {
        Logger.e("[ORCH] Issue reported: $component - $issue: $details")

        healthChecks[component] = HealthStatus(
            component = component,
            isHealthy = false,
            lastHeartbeat = healthChecks[component]?.lastHeartbeat ?: 0,
            issue = issue,
            details = details
        )

        // FASE 2: Incrementar contador de fallos
        val failureCount = componentFailureCounts
            .getOrPut(component) { AtomicInteger(0) }
            .incrementAndGet()

        Logger.w("[ORCH] $component failure count: $failureCount/$MAX_COMPONENT_FAILURES")

        // FASE 2: Verificar si debemos degradar el componente
        if (failureCount >= MAX_COMPONENT_FAILURES) {
            degradeComponent(component, "Max failures reached: $failureCount")
        }

        // Determinar si necesitamos transición de estado
        if (isCriticalComponent(component) && issue.isCritical()) {
            if (_state.value == SystemState.ACTIVE) {
                transition(SystemState.DEGRADED_MODE, "Critical component failure: $component - $issue")
            }
        }
    }

    /**
     * FASE 2: Marca un componente como degradado.
     * Esto señala que debe usarse fallback o pipeline alternativo.
     */
    fun degradeComponent(component: Component, reason: String) {
        val wasAlreadyDegraded = componentDegradedFlags.put(component, AtomicBoolean(true)) != null

        if (!wasAlreadyDegraded) {
            lastDegradationTime[component] = System.currentTimeMillis()
            Logger.e("[DEGRADE] Component $component DEGRADED: $reason")

            // Notificar a listeners
            notifyDegradation(component, reason)

            // Transicionar a DEGRADED_MODE si no lo estábamos
            if (_state.value == SystemState.ACTIVE) {
                transition(SystemState.DEGRADED_MODE, "Component $component degraded: $reason")
            }
        }
    }

    /**
     * FASE 2: Verifica si un componente está degradado.
     */
    fun isComponentDegraded(component: Component): Boolean {
        return componentDegradedFlags[component]?.get() == true
    }

    /**
     * FASE 2: Intenta recuperar un componente degradado.
     * Se llama cuando el componente tiene éxito nuevamente.
     */
    fun attemptComponentRecovery(component: Component): Boolean {
        val failureCount = componentFailureCounts[component]?.get() ?: 0

        // Si no estaba degradado, solo resetear contador
        if (!isComponentDegraded(component)) {
            if (failureCount > 0) {
                componentFailureCounts[component]?.set(0)
                Logger.i("[RECOVERY] $component counter reset (was at $failureCount)")
            }
            return true
        }

        // Si estaba degradado, verificar cooldown
        val degradationTime = lastDegradationTime[component] ?: 0
        val now = System.currentTimeMillis()

        if (now - degradationTime < DEGRADATION_COOLDOWN_MS) {
            Logger.d("[RECOVERY] $component in cooldown (${(now - degradationTime) / 1000}s)")
            return false
        }

        // Recuperar componente
        componentDegradedFlags[component]?.set(false)
        componentFailureCounts[component]?.set(0)
        lastDegradationTime.remove(component)

        Logger.i("[RECOVERY] Component $component RECOVERED after ${(now - degradationTime) / 1000}s")
        notifyComponentRecovery(component)

        // Verificar si podemos volver a ACTIVE
        val anyDegraded = componentDegradedFlags.values.any { it.get() }
        if (!anyDegraded && _state.value == SystemState.DEGRADED_MODE) {
            transition(SystemState.ACTIVE, "All components recovered")
        }

        return true
    }

    private val degradationListeners = mutableListOf<(Component, String) -> Unit>()
    private val componentRecoveryListeners = mutableListOf<(Component) -> Unit>()

    fun addDegradationListener(listener: (Component, String) -> Unit) {
        degradationListeners.add(listener)
    }

    fun addComponentRecoveryListener(listener: (Component) -> Unit) {
        componentRecoveryListeners.add(listener)
    }

    private fun notifyDegradation(component: Component, reason: String) {
        degradationListeners.forEach { try { it(component, reason) } catch (e: Exception) {} }
    }

    private fun notifyComponentRecovery(component: Component) {
        componentRecoveryListeners.forEach { try { it(component) } catch (e: Exception) {} }
    }

    /**
     * FASE 2: Registra una operación exitosa del componente.
     * Esto ayuda a recuperación automática.
     */
    fun reportSuccess(component: Component) {
        if (isComponentDegraded(component) || (componentFailureCounts[component]?.get() ?: 0) > 0) {
            attemptComponentRecovery(component)
        }
    }

    /**
     * Reporta un frame procesado (para health check de captura).
     */
    fun reportFrameProcessed() {
        lastFrameTimestamp = System.currentTimeMillis()
        reportHeartbeat(Component.CAPTURE_SERVICE)
    }

    /**
     * Reporta una acción ejecutada (para health check de IA).
     */
    fun reportActionExecuted() {
        lastActionTimestamp = System.currentTimeMillis()
        reportHeartbeat(Component.AI_CORE)
    }

    /**
     * Intenta recuperación automática del sistema.
     */
    fun attemptRecovery(): Boolean {
        val attempt = recoveryAttemptCount.incrementAndGet()

        if (attempt > MAX_RECOVERY_ATTEMPTS) {
            Logger.e("[RECOVERY] Max attempts ($MAX_RECOVERY_ATTEMPTS) reached, transitioning to FATAL_ERROR")
            transition(SystemState.FATAL_ERROR, "Max recovery attempts exceeded")
            return false
        }

        Logger.i("[RECOVERY] Attempting recovery (attempt $attempt/$MAX_RECOVERY_ATTEMPTS)")
        transition(SystemState.RECOVERING, "Automatic recovery attempt $attempt")

        // La lógica específica de recuperación es responsabilidad de los listeners
        notifyRecoveryAttempt(attempt)

        return true
    }

    /**
     * Obtiene métricas actuales del sistema.
     */
    fun getSystemMetrics(): SystemMetrics {
        val now = System.currentTimeMillis()
        val active = healthChecks.values.filter { it.isHealthy }.map { it.component.name }
        val degraded = healthChecks.values.filter { !it.isHealthy }.map { it.component.name }

        return SystemMetrics(
            state = _state.value,
            fps = calculateFps(),
            frameLatencyMs = now - lastFrameTimestamp,
            lastActionLatencyMs = now - lastActionTimestamp,
            aiInferenceTimeMs = 0, // TODO: medir en AdvancedAICore
            memoryUsageMb = Runtime.getRuntime().totalMemory() / (1024 * 1024),
            activeComponents = active,
            degradedComponents = degraded,
            uptimeSeconds = (now - startTime) / 1000
        )
    }

    /**
     * Registra un listener para cambios de estado.
     */
    fun addStateChangeListener(listener: (SystemState, SystemState, String) -> Unit) {
        stateChangeListeners.add(listener)
    }

    /**
     * Elimina un listener.
     */
    fun removeStateChangeListener(listener: (SystemState, SystemState, String) -> Unit) {
        stateChangeListeners.remove(listener)
    }

    // ========== PRIVATE METHODS ==========

    private fun isValidTransition(from: SystemState, to: SystemState): Boolean {
        return when (from) {
            SystemState.UNINITIALIZED -> to in setOf(
                SystemState.PERMISSIONS_REQUIRED, SystemState.RECOVERING,
                SystemState.ACCESSIBILITY_READY, SystemState.FATAL_ERROR
            )
            SystemState.PERMISSIONS_REQUIRED -> to in setOf(
                SystemState.ACCESSIBILITY_READY, SystemState.RECOVERING, SystemState.FATAL_ERROR
            )
            SystemState.ACCESSIBILITY_READY -> to in setOf(
                SystemState.CAPTURE_AUTHORIZED, SystemState.RECOVERING, SystemState.FATAL_ERROR
            )
            SystemState.CAPTURE_AUTHORIZED -> to in setOf(
                SystemState.AI_LOADING, SystemState.RECOVERING, SystemState.DEGRADED_MODE
            )
            SystemState.AI_LOADING -> to in setOf(
                SystemState.ACTIVE, SystemState.DEGRADED_MODE, SystemState.RECOVERING, SystemState.FATAL_ERROR
            )
            SystemState.ACTIVE -> to in setOf(
                SystemState.DEGRADED_MODE, SystemState.RECOVERING, SystemState.FATAL_ERROR
            )
            SystemState.DEGRADED_MODE -> to in setOf(
                SystemState.ACTIVE, SystemState.RECOVERING, SystemState.FATAL_ERROR
            )
            SystemState.RECOVERING -> to in setOf(
                SystemState.ACTIVE, SystemState.DEGRADED_MODE, SystemState.PERMISSIONS_REQUIRED, SystemState.FATAL_ERROR
            )
            SystemState.FATAL_ERROR -> to in setOf(
                SystemState.UNINITIALIZED, SystemState.PERMISSIONS_REQUIRED, SystemState.RECOVERING
            )
        }
    }

    private fun isCriticalComponent(component: Component): Boolean {
        return component in setOf(Component.CAPTURE_SERVICE, Component.AI_CORE)
    }

    private fun Issue.isCritical(): Boolean {
        return this in setOf(Issue.CRASHED, Issue.PERMISSION_REVOKED, Issue.NOT_RESPONDING)
    }

    private fun checkComponentHealth(component: Component, condition: Boolean): Boolean {
        val currentStatus = healthChecks[component]
        val isHealthy = condition && (currentStatus?.isHealthy != false)

        if (!isHealthy && currentStatus?.issue == Issue.NONE) {
            reportIssue(component, Issue.NOT_RESPONDING, "Health check condition failed")
        }

        return isHealthy
    }

    private fun clearIssues(vararg components: Component) {
        components.forEach { component ->
            val status = healthChecks[component]
            if (status != null && !status.isHealthy) {
                healthChecks[component] = status.copy(isHealthy = true, issue = Issue.NONE, details = "")
            }
        }
    }

    private fun recordStateChange(from: SystemState, to: SystemState, reason: String) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putString(KEY_LAST_STATE, to.name)
            putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            apply()
        }
    }

    private fun persistActiveState(isActive: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
            putBoolean(KEY_WAS_ACTIVE, isActive)
            apply()
        }
    }

    private fun startStatePersistence() {
        // El estado se persiste en cada transición, no necesitamos job separado
    }

    private fun notifyStateChangeListeners(from: SystemState, to: SystemState, reason: String) {
        stateChangeListeners.forEach { listener ->
            try {
                listener(from, to, reason)
            } catch (e: Exception) {
                Logger.e("[ORCH] Error in state change listener", e)
            }
        }
    }

    private fun notifyRecoveryAttempt(attempt: Int) {
        // Notificar a todos los listeners que deben intentar recuperarse
        Logger.i("[RECOVERY] Notifying components to attempt recovery (attempt $attempt)")
        // Los componentes específicos deben escuchar cambios de estado a RECOVERING
    }

    private fun calculateFps(): Float {
        // Simplificación - en implementación real calcularíamos de verdad
        return if (lastFrameTimestamp > 0) {
            val elapsed = System.currentTimeMillis() - lastFrameTimestamp
            if (elapsed < 1000) 30f else 0f
        } else 0f
    }
}
