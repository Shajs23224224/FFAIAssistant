package com.ffai.assistant.core

import android.content.Context
import com.ffai.assistant.action.Action
import com.ffai.assistant.action.ActionType
import com.ffai.assistant.memory.HierarchicalMemorySystem
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orquestador Central del Sistema de IA
 * Coordina todos los módulos y resuelve conflictos de prioridad
 * Optimizado para Samsung A21S - Latencia objetivo < 16ms
 */
class Orchestrator(private val context: Context) {

    companion object {
        const val TAG = "Orchestrator"
        
        // Frecuencias de actualización
        const val DECISION_RATE_HZ = 60
        const val DECISION_INTERVAL_MS = 16L  // ~16.67ms para 60Hz
        const val STRATEGIC_UPDATE_MS = 500L   // 2Hz para decisiones estratégicas
        
        // Umbrales de prioridad
        const val PRIORITY_CRITICAL = 100
        const val PRIORITY_HIGH = 75
        const val PRIORITY_MEDIUM = 50
        const val PRIORITY_LOW = 25
        const val PRIORITY_NONE = 0
    }

    // ============================================
    // SUBSISTEMAS
    // ============================================
    
    // Memoria jerárquica
    lateinit var memory: HierarchicalMemorySystem
        private set
    
    // Resolutor de conflictos
    private val priorityResolver = PriorityResolver()
    
    // Engine de contexto
    private val contextEngine = ContextEngine()
    
    // Módulos especializados (se inyectan)
    private val modules = ConcurrentHashMap<ModuleType, Module>()
    
    // Pipeline de decisión
    private lateinit var decisionPipeline: DecisionPipeline
    
    // Estado del sistema
    private val isRunning = AtomicBoolean(false)
    private val currentMissionState = AtomicReference(MissionState.SURVIVAL)
    private val lastDecision = AtomicReference<Decision>(Decision(Action.hold(), Priority.NONE))
    
    // Scope para coroutines
    private val orchestratorScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + 
        CoroutineExceptionHandler { _, throwable ->
            Logger.e(TAG, "Error en Orchestrator", throwable)
        }
    )

    // ============================================
    // INICIALIZACIÓN
    // ============================================
    
    fun initialize() {
        Logger.i(TAG, "Inicializando Orquestador...")
        
        // 1. Inicializar sistema de memoria
        memory = HierarchicalMemorySystem()
        
        // 2. Inicializar pipeline de decisión
        decisionPipeline = DecisionPipeline(context, memory)
        decisionPipeline.initialize()
        
        // 3. Inicializar context engine
        contextEngine.initialize()
        
        Logger.i(TAG, "Orquestador inicializado")
    }

    fun registerModule(type: ModuleType, module: Module) {
        modules[type] = module
        Logger.d(TAG, "Módulo registrado: $type")
    }

    // ============================================
    // CICLO PRINCIPAL
    // ============================================
    
    fun start() {
        if (isRunning.get()) return
        
        isRunning.set(true)
        Logger.i(TAG, "Orquestador iniciado - Ciclo a ${DECISION_RATE_HZ}Hz")
        
        // Loop principal de decisión (60Hz)
        orchestratorScope.launch {
            var lastFrameTime = System.currentTimeMillis()
            
            while (isRunning.get()) {
                val frameStart = System.currentTimeMillis()
                
                // 1. Actualizar contexto global
                val currentContext = contextEngine.update()
                
                // 2. Ejecutar pipeline de decisión
                val decision = decisionPipeline.process(currentContext)
                
                // 3. Resolver conflictos entre módulos
                val resolvedDecision = priorityResolver.resolve(decision, modules.values.toList())
                
                // 4. Ejecutar decisión final
                executeDecision(resolvedDecision)
                
                // 5. Almacenar en memoria
                lastDecision.set(resolvedDecision)
                memory.ultrashort.recordDecision(
                    DecisionRecord(
                        action = resolvedDecision.action,
                        situation = currentContext,
                        result = DecisionResult.UNKNOWN
                    )
                )
                
                // Control de timing (mantener 60Hz)
                val elapsed = System.currentTimeMillis() - frameStart
                val sleepTime = DECISION_INTERVAL_MS - elapsed
                
                if (sleepTime > 0) {
                    delay(sleepTime)
                } else if (elapsed > DECISION_INTERVAL_MS * 1.5) {
                    Logger.w(TAG, "Frame overrun: ${elapsed}ms")
                }
                
                lastFrameTime = frameStart
            }
        }
        
        // Loop estratégico (2Hz)
        orchestratorScope.launch {
            while (isRunning.get()) {
                delay(STRATEGIC_UPDATE_MS)
                updateStrategicLayer()
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        orchestratorScope.cancel()
        Logger.i(TAG, "Orquestador detenido")
    }

    // ============================================
    // EJECUCIÓN DE DECISIONES
    // ============================================
    
    private fun executeDecision(decision: Decision) {
        val module = modules[decision.actionType.module]
        
        module?.execute(decision.action) ?: run {
            Logger.w(TAG, "No hay módulo para ejecutar: ${decision.actionType}")
        }
    }

    private fun updateStrategicLayer() {
        // Actualizar estado de misión
        val newState = determineMissionState()
        if (newState != currentMissionState.get()) {
            Logger.i(TAG, "Cambio de estado: ${currentMissionState.get()} -> $newState")
            currentMissionState.set(newState)
        }
        
        // Ejecutar módulos estratégicos
        modules[ModuleType.STRATEGIC]?.let { strategicModule ->
            if (strategicModule is StrategicModule) {
                strategicModule.strategicUpdate()
            }
        }
    }

    private fun determineMissionState(): MissionState {
        val health = contextEngine.getHealthLevel()
        val enemies = contextEngine.getVisibleEnemies()
        val zoneStatus = contextEngine.getZoneStatus()
        
        return when {
            health == HealthLevel.CRITICAL -> MissionState.SURVIVAL
            enemies.isNotEmpty() -> MissionState.COMBAT
            zoneStatus == ZoneStatus.COLLAPSING -> MissionState.ROTATION
            contextEngine.needsLoot() -> MissionState.LOOT
            else -> MissionState.POSITIONING
        }
    }

    // ============================================
    // API PÚBLICA
    // ============================================
    
    fun getCurrentState(): MissionState = currentMissionState.get()
    
    fun getLastDecision(): Decision = lastDecision.get()
    
    fun getStats(): OrchestratorStats {
        return OrchestratorStats(
            isRunning = isRunning.get(),
            currentState = currentMissionState.get(),
            activeModules = modules.size,
            lastDecision = lastDecision.get().action.type.toString()
        )
    }

    fun forceAction(action: Action) {
        val forcedDecision = Decision(action, Priority.CRITICAL, isForced = true)
        priorityResolver.forceDecision(forcedDecision)
        Logger.i(TAG, "Acción forzada: ${action.type}")
    }

    fun reportActionResult(action: Action, result: ActionResult) {
        // Feedback para aprendizaje
        memory.shortTerm.recordDecision(
            DecisionRecord(
                action = action,
                situation = contextEngine.getCurrentContext(),
                result = when (result) {
                    ActionResult.SUCCESS -> DecisionResult.SUCCESS
                    ActionResult.PARTIAL -> DecisionResult.PARTIAL
                    ActionResult.FAILURE -> DecisionResult.FAILURE
                }
            )
        )
    }
}

// ============================================
// RESOLVEDOR DE PRIORIDADES
// ============================================

class PriorityResolver {
    
    private var forcedDecision: Decision? = null
    
    fun resolve(proposedDecision: Decision, activeModules: List<Module>): Decision {
        // Si hay decisión forzada, usarla
        forcedDecision?.let {
            forcedDecision = null
            return it
        }
        
        // Evaluar propuestas de todos los módulos
        val proposals = activeModules.mapNotNull { it.proposeAction() }
        
        // Si solo hay una propuesta, usarla
        if (proposals.size <= 1) return proposedDecision
        
        // Seleccionar por prioridad más alta
        return proposals.maxByOrNull { it.priority.score } ?: proposedDecision
    }
    
    fun forceDecision(decision: Decision) {
        forcedDecision = decision
    }
}

// ============================================
// ENGINE DE CONTEXTO
// ============================================

class ContextEngine {
    
    private var currentContext = SituationContext()
    private val healthHistory = ArrayDeque<Float>(10)
    
    fun initialize() {
        // Inicializar con contexto por defecto
    }
    
    fun update(): SituationContext {
        // Actualizar contexto basado en estado actual del juego
        // Esto se conecta con PerceptionEngine
        return currentContext
    }
    
    fun getCurrentContext(): SituationContext = currentContext
    
    fun getHealthLevel(): HealthLevel {
        val currentHealth = healthHistory.lastOrNull() ?: 100f
        return when {
            currentHealth > 75f -> HealthLevel.FULL
            currentHealth > 50f -> HealthLevel.GOOD
            currentHealth > 25f -> HealthLevel.LOW
            currentHealth > 0f -> HealthLevel.CRITICAL
            else -> HealthLevel.DOWN
        }
    }
    
    fun getVisibleEnemies(): List<EnemyInfo> {
        // Retornar enemigos detectados
        return emptyList()
    }
    
    fun getZoneStatus(): ZoneStatus {
        // Retornar estado de zona segura
        return ZoneStatus.SAFE
    }
    
    fun needsLoot(): Boolean {
        // Determinar si necesita lootear
        return false
    }
}

// ============================================
// PIPELINE DE DECISIÓN
// ============================================

class DecisionPipeline(
    private val context: Context,
    private val memory: HierarchicalMemorySystem
) {
    
    private lateinit var reflexEngine: ReflexEngine
    private lateinit var tacticalEngine: TacticalEngine
    private lateinit var strategicEngine: StrategicEngine
    
    fun initialize() {
        reflexEngine = ReflexEngine()
        tacticalEngine = TacticalEngine()
        strategicEngine = StrategicEngine()
    }
    
    fun process(context: SituationContext): Decision {
        val startTime = System.currentTimeMillis()
        
        // NIVEL 1: Reflejos (< 3ms)
        val reflexDecision = reflexEngine.decide(context)
        if (reflexDecision != null && reflexDecision.priority >= Priority.HIGH) {
            return reflexDecision
        }
        
        // NIVEL 2: Táctico (3-8ms)
        val tacticalDecision = tacticalEngine.decide(context)
        if (tacticalDecision != null && tacticalDecision.priority >= Priority.MEDIUM) {
            return tacticalDecision
        }
        
        // NIVEL 3: Estratégico (cuando aplica)
        val strategicDecision = strategicEngine.decide(context)
        
        // Seleccionar mejor decisión
        val decision = listOfNotNull(reflexDecision, tacticalDecision, strategicDecision)
            .maxByOrNull { it.priority.score }
            ?: Decision(Action.hold(), Priority.NONE)
        
        val latency = System.currentTimeMillis() - startTime
        if (latency > 16) {
            Logger.w("DecisionPipeline", "Latencia alta: ${latency}ms")
        }
        
        return decision
    }
}

// ============================================
// MOTORES DE DECISIÓN
// ============================================

class ReflexEngine {
    
    fun decide(context: SituationContext): Decision? {
        // Decisiones de supervivencia inmediata
        // Ej: curarse si vida crítica, cubrirse si bajo fuego
        
        return when {
            context.healthLevel == HealthLevel.CRITICAL -> {
                Decision(Action.heal(), Priority.CRITICAL)
            }
            context.enemyCount > 0 && context.healthLevel <= HealthLevel.LOW -> {
                Decision(Action.crouch(), Priority.HIGH)
            }
            else -> null
        }
    }
}

class TacticalEngine {
    
    fun decide(context: SituationContext): Decision? {
        // Decisiones tácticas basadas en situación
        // Ej: apuntar si hay enemigo, moverse si es seguro
        
        return when {
            context.enemyCount > 0 -> {
                Decision(Action.aim(0, 0), Priority.MEDIUM)
            }
            context.zoneStatus == ZoneStatus.EDGE -> {
                Decision(Action.moveForward(), Priority.LOW)
            }
            else -> null
        }
    }
}

class StrategicEngine {
    
    fun decide(context: SituationContext): Decision? {
        // Decisiones estratégicas de alto nivel
        // Solo se ejecutan periódicamente (no cada frame)
        
        return null // Placeholder
    }
}

// ============================================
// MÓDULOS
// ============================================

interface Module {
    val type: ModuleType
    fun proposeAction(): Decision?
    fun execute(action: Action)
}

interface StrategicModule : Module {
    fun strategicUpdate()
}

enum class ModuleType {
    PERCEPTION,
    MEMORY,
    LEARNING,
    ECONOMY,
    PREDICTION,
    TACTIC,
    SHOOTING,
    HUMANIZATION,
    PROFILING,
    META,
    STRATEGIC,
    GESTURE,
    CAMERA
}

// ============================================
// DATA CLASSES
// ============================================

data class Decision(
    val action: Action,
    val priority: Priority,
    val isForced: Boolean = false
) {
    val actionType: ActionType get() = action.type
}

enum class Priority(val score: Int) {
    NONE(0),
    LOW(25),
    MEDIUM(50),
    HIGH(75),
    CRITICAL(100)
}

enum class MissionState {
    SURVIVAL,      // Prioridad: mantenerse vivo
    COMBAT,        // En combate activo
    LOOT,          // Buscando recursos
    ROTATION,      // Moviéndose por zona
    POSITIONING    // Posicionándose estratégicamente
}

enum class ActionResult { SUCCESS, PARTIAL, FAILURE }

data class EnemyInfo(
    val id: String,
    val position: Position,
    val health: Float,
    val distance: Float
)

data class OrchestratorStats(
    val isRunning: Boolean,
    val currentState: MissionState,
    val activeModules: Int,
    val lastDecision: String
)

// Extensiones para Action
fun Action.Companion.hold(): Action = Action(
    type = ActionType.HOLD,
    x = 0, y = 0,
    confidence = 1f,
    durationMs = 0,
    priority = 0
)

fun Action.Companion.heal(): Action = Action(
    type = ActionType.HEAL,
    x = 0, y = 0,
    confidence = 1f,
    durationMs = 500,
    priority = Priority.CRITICAL.score
)

fun Action.Companion.crouch(): Action = Action(
    type = ActionType.CROUCH,
    x = 0, y = 0,
    confidence = 1f,
    durationMs = 200,
    priority = Priority.HIGH.score
)

fun Action.Companion.aim(x: Int, y: Int): Action = Action(
    type = ActionType.AIM,
    x = x, y = y,
    confidence = 0.8f,
    durationMs = 100,
    priority = Priority.MEDIUM.score
)

fun Action.Companion.moveForward(): Action = Action(
    type = ActionType.MOVE_FORWARD,
    x = 0, y = 0,
    confidence = 0.7f,
    durationMs = 300,
    priority = Priority.LOW.score
)
