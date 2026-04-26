package com.ffai.assistant.memory

import com.ffai.assistant.action.Action
import com.ffai.assistant.core.DecisionRecord
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Sistema de Memoria Jerárquica de 4 Capas
 * Optimizado para Samsung A21S - Recuperación O(1) por contexto
 */
class HierarchicalMemorySystem {
    // Capas de memoria accesibles desde fuera
    val ultrashort = UltrashortMemory()
    val shortTerm = ShortTermMemory()
    val mediumTerm = MediumTermMemory()
    val longTerm = LongTermMemory()

    companion object {
        const val TAG = "HierarchicalMemory"
        
        // Límites de tamaño para A21S (4GB RAM)
        const val MAX_ULTRASHORT_ACTIONS = 30      // Últimos frames
        const val MAX_SHORTTERM_EVENTS = 100       // Eventos recientes
        const val MAX_MEDIUM_ENEMIES = 50          // Perfiles enemigos
        const val MAX_LONGTERM_PATTERNS = 200      // Patrones persistentes
    }

    // ============================================
    // CAPA 1: ULTRACORTA (< 1 segundo)
    // ============================================
    class UltrashortMemory {
        val recentActions = CircularBuffer<Action>(MAX_ULTRASHORT_ACTIONS)
        val recentDecisions = CircularBuffer<DecisionRecord>(50)
        val immediateThreats = ConcurrentLinkedQueue<Threat>()
        val frameSequence = CircularBuffer<FrameContext>(30)
        val currentAimTrajectory = TrajectoryBuffer()
        
        // Tiempo de vida: milisegundos a segundos
        fun addAction(action: Action) {
            recentActions.add(action)
        }
        
        fun recordDecision(decision: DecisionRecord) {
            recentDecisions.add(decision)
        }
        
        fun addThreat(threat: Threat) {
            immediateThreats.offer(threat)
            if (immediateThreats.size > 10) immediateThreats.poll()
        }
        
        fun clearOld(thresholdMs: Long = 1000) {
            val now = System.currentTimeMillis()
            immediateThreats.removeIf { now - it.timestamp > thresholdMs }
        }
    }

    // ============================================
    // CAPA 2: CORTA (1-30 segundos)
    // ============================================
    class ShortTermMemory {
        var combatSession: CombatSession? = null
        val enemyLastPositions = ConcurrentHashMap<EnemyId, PositionRecord>()
        val recentDecisions = CircularBuffer<DecisionRecord>(50)
        val coverUsageHistory = CircularBuffer<CoverEvent>(20)
        
        // Recoil aprendido en esta partida
        val weaponRecoilProfiles = ConcurrentHashMap<WeaponId, RecoilProfile>()
        
        fun recordDecision(decision: DecisionRecord) {
            recentDecisions.add(decision)
        }
        
        fun updateEnemyPosition(enemyId: EnemyId, position: Position) {
            enemyLastPositions[enemyId] = PositionRecord(
                position = position,
                timestamp = System.currentTimeMillis()
            )
        }
        
        fun clearOld(thresholdMs: Long = 30000) {
            val now = System.currentTimeMillis()
            enemyLastPositions.entries.removeIf { 
                now - it.value.timestamp > thresholdMs 
            }
        }
    }

    // ============================================
    // CAPA 3: MEDIA (30s - 5 minutos)
    // ============================================
    class MediumTermMemory {
        // Perfiles de enemigos construidos en la partida
        val enemyProfiles = ConcurrentHashMap<EnemyId, EnemyProfile>()
        
        // Rotaciones exitosas
        val successfulRotations = CircularBuffer<Rotation>(30)
        
        // Estadísticas de loot
        val lootStats = LootStatistics()
        
        // Resultados de combate
        val combatOutcomes = CircularBuffer<CombatOutcome>(50)
        
        // Patrón de colapso de zona
        val zoneHistory = ZoneHistory()
        
        fun getOrCreateEnemyProfile(enemyId: EnemyId): EnemyProfile {
            return enemyProfiles.getOrPut(enemyId) { EnemyProfile(enemyId) }
        }
        
        fun recordCombatOutcome(outcome: CombatOutcome) {
            combatOutcomes.add(outcome)
        }
    }

    // ============================================
    // CAPA 4: LARGA (persistente entre partidas)
    // ============================================
    class LongTermMemory {
        // Conocimiento de mapas (persistente en storage)
        val mapKnowledge = ConcurrentHashMap<MapId, MapKnowledge>()
        
        // Estadísticas por arma
        val weaponStats = ConcurrentHashMap<WeaponId, WeaponData>()
        
        // Estilo de juego propio aprendido
        var personalPlaystyle: PlaystyleProfile = PlaystyleProfile.DEFAULT
        
        // Arquetipos de oponentes conocidos
        val opponentArchetypes = ConcurrentLinkedQueue<Archetype>()
        
        // Patrones ganadores globales
        val winPatterns = WinPatternDatabase()
        
        fun getMapKnowledge(mapId: MapId): MapKnowledge {
            return mapKnowledge.getOrPut(mapId) { MapKnowledge(mapId) }
        }
    }

    // ============================================
    // INSTANCIAS
    // ============================================
    val ultrashort = UltrashortMemory()
    val shortTerm = ShortTermMemory()
    val mediumTerm = MediumTermMemory()
    val longTerm = LongTermMemory()

    // Sistema de indexación para recuperación O(1)
    private val contextIndex = ConcurrentHashMap<SituationFingerprint, MutableList<MemoryReference>>()
    private val retrievalEngine = FastRetrievalEngine()

    // Métricas
    private val accessCount = AtomicLong(0)
    private val hitCount = AtomicLong(0)

    /**
     * Almacena un recuerdo en la capa apropiada
     */
    fun <T> store(
        content: T,
        context: SituationContext,
        layer: MemoryLayer,
        metadata: MemoryMetadata = MemoryMetadata.DEFAULT
    ): MemoryId {
        val id = MemoryId.generate()
        val entry = MemoryEntry(
            id = id,
            content = content,
            context = context,
            metadata = metadata,
            timestamp = System.currentTimeMillis(),
            layer = layer
        )

        // Almacenar en capa correspondiente
        when (layer) {
            MemoryLayer.ULTRASHORT -> storeInUltrashort(entry)
            MemoryLayer.SHORT -> storeInShortTerm(entry)
            MemoryLayer.MEDIUM -> storeInMediumTerm(entry)
            MemoryLayer.LONG -> storeInLongTerm(entry)
        }

        // Indexar para recuperación rápida
        indexEntry(entry)

        return id
    }

    /**
     * Recupera recuerdos similares al contexto actual
     * Complejidad: O(1) para búsqueda exacta, O(k) para vecinos
     */
    fun <T> retrieveSimilar(
        currentContext: SituationContext,
        targetLayer: MemoryLayer? = null,
        k: Int = 5
    ): List<MemoryEntry<T>> {
        accessCount.incrementAndGet()
        
        val fingerprint = currentContext.fingerprint()
        
        // Búsqueda exacta O(1)
        val exactMatches = contextIndex[fingerprint]
        if (exactMatches != null && exactMatches.isNotEmpty()) {
            hitCount.incrementAndGet()
            return exactMatches.mapNotNull { loadEntry<T>(it) }
        }

        // Búsqueda por similitud
        return retrievalEngine.findNearestNeighbors(fingerprint, targetLayer, k)
    }

    /**
     * Recupera recuerdos de una capa específica
     */
    inline fun <reified T> retrieveFromLayer(layer: MemoryLayer): List<T> {
        return when (layer) {
            MemoryLayer.ULTRASHORT -> ultrashort.recentActions.toList().filterIsInstance<T>()
            MemoryLayer.SHORT -> shortTerm.recentDecisions.toList().filterIsInstance<T>()
            MemoryLayer.MEDIUM -> mediumTerm.combatOutcomes.toList().filterIsInstance<T>()
            MemoryLayer.LONG -> longTerm.winPatterns.allPatterns().filterIsInstance<T>()
        }
    }

    /**
     * Actualiza metadatos de un recuerdo (para reinforcement)
     */
    fun updateRelevance(memoryId: MemoryId, newRelevance: Float) {
        // Actualizar en índice y capa correspondiente
        // Implementación según estructura de almacenamiento
    }

    /**
     * Compresión periódica de memoria media/larga
     */
    fun compress() {
        Logger.d(TAG, "Comprimiendo memoria...")
        
        // Comprimir medium term
        if (mediumTerm.enemyProfiles.size > MAX_MEDIUM_ENEMIES) {
            compressEnemyProfiles()
        }
        
        // Limpiar entradas antiguas
        shortTerm.clearOld()
        ultrashort.clearOld()
    }

    /**
     * Limpieza completa entre partidas
     */
    fun clearSessionMemory() {
        // Conservar solo long term
        ultrashort.recentActions.clear()
        ultrashort.immediateThreats.clear()
        ultrashort.frameSequence.clear()
        
        shortTerm.combatSession = null
        shortTerm.enemyLastPositions.clear()
        shortTerm.recentDecisions.clear()
        shortTerm.coverUsageHistory.clear()
        
        mediumTerm.enemyProfiles.clear()
        mediumTerm.successfulRotations.clear()
        mediumTerm.combatOutcomes.clear()
        
        Logger.i(TAG, "Memoria de sesión limpiada")
    }

    fun getStats(): MemoryStats {
        return MemoryStats(
            ultrashortSize = ultrashort.recentActions.size(),
            shortTermSize = shortTerm.recentDecisions.size(),
            mediumTermEnemies = mediumTerm.enemyProfiles.size,
            longTermPatterns = longTerm.winPatterns.count(),
            indexSize = contextIndex.size,
            totalAccesses = accessCount.get(),
            cacheHitRate = if (accessCount.get() > 0) 
                hitCount.get().toFloat() / accessCount.get() else 0f
        )
    }

    // Private helpers
    private fun storeInUltrashort(entry: MemoryEntry<*>) {
        // Almacenamiento en buffer circular ultrarrápido
    }

    private fun storeInShortTerm(entry: MemoryEntry<*>) {
        // Almacenamiento en estructura de corto plazo
    }

    private fun storeInMediumTerm(entry: MemoryEntry<*>) {
        // Almacenamiento con compresión
    }

    private fun storeInLongTerm(entry: MemoryEntry<*>) {
        // Persistencia en storage
    }

    private fun indexEntry(entry: MemoryEntry<*>) {
        val fingerprint = entry.context.fingerprint()
        val refs = contextIndex.getOrPut(fingerprint) { mutableListOf() }
        refs.add(MemoryReference(entry.id, entry.layer, entry.timestamp))
    }

    private fun <T> loadEntry(ref: MemoryReference): MemoryEntry<T>? {
        // Cargar desde la capa correspondiente
        return null // Placeholder
    }

    private fun compressEnemyProfiles() {
        // Fusionar perfiles similares, eliminar redundantes
        val sorted = mediumTerm.enemyProfiles.entries
            .sortedByDescending { it.value.relevanceScore }
        
        // Mantener solo los más relevantes
        val toRemove = sorted.drop(MAX_MEDIUM_ENEMIES)
        toRemove.forEach { mediumTerm.enemyProfiles.remove(it.key) }
    }
}

// ============================================
// DATA CLASSES Y TIPOS
// ============================================

enum class MemoryLayer { ULTRASHORT, SHORT, MEDIUM, LONG }

@JvmInline
value class MemoryId(val value: String) {
    companion object {
        fun generate(): MemoryId = MemoryId(java.util.UUID.randomUUID().toString())
    }
}

data class MemoryEntry<T>(
    val id: MemoryId,
    val content: T,
    val context: SituationContext,
    val metadata: MemoryMetadata,
    val timestamp: Long,
    val layer: MemoryLayer,
    var accessCount: Int = 0,
    var lastAccessed: Long = timestamp
)

data class SituationContext(
    val mapId: MapId = MapId.UNKNOWN,
    val gamePhase: GamePhase = GamePhase.EARLY,
    val healthLevel: HealthLevel = HealthLevel.FULL,
    val weaponType: WeaponCategory = WeaponCategory.ASSAULT_RIFLE,
    val zoneStatus: ZoneStatus = ZoneStatus.SAFE,
    val teamStatus: TeamStatus = TeamStatus.SOLO,
    val enemyCount: Int = 0,
    val resourceLevel: ResourceLevel = ResourceLevel.MEDIUM
) {
    fun fingerprint(): SituationFingerprint {
        // Hash compacto del contexto para indexación O(1)
        val hash = listOf(
            mapId.hashCode(),
            gamePhase.ordinal,
            healthLevel.ordinal,
            weaponType.ordinal,
            zoneStatus.ordinal,
            enemyCount.coerceIn(0, 5)  // Bucketed
        ).fold(0) { acc, h -> acc * 31 + h }
        return SituationFingerprint(hash)
    }
}

@JvmInline
value class SituationFingerprint(val hash: Int)

@JvmInline
value class MapId(val value: String) {
    companion object {
        val UNKNOWN = MapId("unknown")
        val BERMUDA = MapId("bermuda")
        val PURGATORY = MapId("purgatory")
        val KALAHARI = MapId("kalahari")
    }
}

data class MemoryMetadata(
    val relevance: Float,        // 0.0 - 1.0
    val recency: Float,          // Decay temporal 0-1
    val impact: Float,           // Magnitud del evento
    val rarity: Float,           // Qué tan único (0-1)
    val success: Boolean         // Resultado positivo/negativo
) {
    val priorityScore: Float
        get() = relevance * 0.4f + recency * 0.3f + impact * 0.2f + rarity * 0.1f

    companion object {
        val DEFAULT = MemoryMetadata(
            relevance = 0.5f,
            recency = 1.0f,
            impact = 0.5f,
            rarity = 0.5f,
            success = true
        )
    }
}

data class MemoryReference(
    val id: MemoryId,
    val layer: MemoryLayer,
    val timestamp: Long
)

// Enums de contexto
enum class GamePhase { EARLY, MID, LATE, FINAL, ENDGAME }
enum class HealthLevel { FULL, GOOD, LOW, CRITICAL, DOWN }
enum class WeaponCategory { PISTOL, SMG, ASSAULT_RIFLE, SNIPER, SHOTGUN, LMG, MELEE }
enum class ZoneStatus { SAFE, EDGE, COLLAPSING, OUTSIDE }
enum class TeamStatus { SOLO, DUO_ALIVE, DUO_DOWN, SQUAD_FULL, SQUAD_DAMAGED }
enum class ResourceLevel { POOR, LOW, MEDIUM, HIGH, RICH }

// Clases de soporte (placeholders - se completarán)
class CircularBuffer<T>(private val capacity: Int) {
    private val buffer = ArrayDeque<T>(capacity)
    
    fun add(element: T) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(element)
    }
    
    fun toList(): List<T> = buffer.toList()
    fun size(): Int = buffer.size
    fun clear() = buffer.clear()
}

data class Threat(
    val enemyId: EnemyId,
    val position: Position,
    val dangerLevel: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@JvmInline
value class EnemyId(val value: String)

@JvmInline
value class WeaponId(val value: String)

data class Position(val x: Float, val y: Float, val z: Float = 0f)
data class PositionRecord(val position: Position, val timestamp: Long)

class TrajectoryBuffer {
    private val points = ArrayDeque<Position>(20)
    fun add(point: Position) {
        if (points.size >= 20) points.removeFirst()
        points.addLast(point)
    }
    fun getPoints(): List<Position> = points.toList()
}

data class FrameContext(
    val timestamp: Long,
    val fps: Int,
    val gameState: String
)

class CombatSession {
    var damageDealt: Float = 0f
    var damageReceived: Float = 0f
    var startTime: Long = System.currentTimeMillis()
    var enemyCount: Int = 0
}

data class DecisionRecord(
    val action: Action,
    val situation: SituationContext,
    val result: MemoryDecisionResult,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MemoryDecisionResult { SUCCESS, PARTIAL, FAILURE, UNKNOWN }

data class CoverEvent(
    val position: Position,
    val effectiveness: Float,
    val timestamp: Long
)

class RecoilProfile {
    val patternPoints = mutableListOf<Pair<Float, Float>>()
}

class EnemyProfile(val enemyId: EnemyId) {
    var aggressionLevel: Float = 0.5f
    var movementPattern: String = "unknown"
    var preferredRange: Float = 50f
    var accuracyEstimate: Float = 0.5f
    var relevanceScore: Float = 0.5f
}

class LootStatistics {
    val successfulLoots = mutableListOf<LootEvent>()
    val zoneEfficiency = mutableMapOf<String, Float>()
}

data class LootEvent(
    val itemType: String,
    val location: Position,
    val value: Float,
    val timeSpentMs: Long
)

class Rotation(
    val from: Position,
    val to: Position,
    val success: Boolean,
    val safetyScore: Float
)

data class CombatOutcome(
    val won: Boolean,
    val enemiesEliminated: Int,
    val healthRemaining: Float,
    val ammoRemaining: Float,
    val durationMs: Long
)

class ZoneHistory {
    private val collapses = mutableListOf<ZoneCollapse>()
    fun recordCollapse(collapse: ZoneCollapse) = collapses.add(collapse)
    fun getPattern(): ZonePattern = ZonePattern(collapses)
}

data class ZoneCollapse(
    val center: Position,
    val radius: Float,
    val timeRemaining: Long,
    val timestamp: Long
)

class ZonePattern(collapses: List<ZoneCollapse>)

class MapKnowledge(val mapId: MapId) {
    val hotZones = mutableListOf<HotZone>()
    val goodLootSpots = mutableListOf<Position>()
    val rotationRoutes = mutableListOf<Rotation>()
}

data class HotZone(
    val position: Position,
    val dangerLevel: Float,
    val frequency: Float
)

class WeaponData(val weaponId: WeaponId) {
    var avgDamagePerMatch: Float = 0f
    var killCount: Int = 0
    var accuracy: Float = 0f
    var preferenceScore: Float = 0.5f
}

data class PlaystyleProfile(
    val aggression: Float,
    val preferredRange: Float,
    val lootSpeed: Float,
    val rotationStyle: String
) {
    companion object {
        val DEFAULT = PlaystyleProfile(
            aggression = 0.5f,
            preferredRange = 50f,
            lootSpeed = 0.5f,
            rotationStyle = "balanced"
        )
    }
}

class Archetype(
    val name: String,
    val characteristics: Map<String, Float>
)

class WinPatternDatabase {
    private val patterns = mutableListOf<WinPattern>()
    fun addPattern(pattern: WinPattern) = patterns.add(pattern)
    fun allPatterns(): List<WinPattern> = patterns.toList()
    fun count(): Int = patterns.size
}

class WinPattern(
    val situation: SituationContext,
    val actions: List<Action>,
    val outcome: CombatOutcome
)

// Motor de recuperación rápida
class FastRetrievalEngine {
    fun <T> findNearestNeighbors(
        fingerprint: SituationFingerprint,
        targetLayer: MemoryLayer?,
        k: Int
    ): List<MemoryEntry<T>> {
        // Implementación de búsqueda por similitud
        return emptyList() // Placeholder
    }
}

// Estadísticas
data class MemoryStats(
    val ultrashortSize: Int,
    val shortTermSize: Int,
    val mediumTermEnemies: Int,
    val longTermPatterns: Int,
    val indexSize: Int,
    val totalAccesses: Long,
    val cacheHitRate: Float
)
