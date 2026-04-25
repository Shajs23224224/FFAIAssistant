package com.ffai.assistant.economy

import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * EconomyManager - Administración de recursos del juego
 * Maneja munición, curación, escudos, utilidades y economía de compras
 */
class EconomyManager {

    companion object {
        const val TAG = "EconomyManager"
        
        // Umbrales críticos
        const val AMMO_CRITICAL = 30          // Balas mínimas
        const val AMMO_LOW = 60               // Balas bajas
        const val HEALTH_CRITICAL = 30        // Vida crítica
        const val HEALTH_LOW = 60             // Vida baja
        const val SHIELD_CRITICAL = 25      // Escudo crítico
        const val SHIELD_OPTIMAL = 100        // Escudo completo
    }

    // ============================================
    // ESTADO DE RECURSOS
    // ============================================
    
    // Munición por tipo de arma
    private val ammoCounts = ConcurrentHashMap<AmmoType, AtomicInteger>()
    
    // Items de curación
    private val healingItems = ConcurrentHashMap<HealingType, AtomicInteger>()
    
    // Escudos
    private val shields = AtomicReference(ShieldStatus(0, 0))
    
    // Utilidades (granadas, etc.)
    private val utilities = ConcurrentHashMap<UtilityType, AtomicInteger>()
    
    // Dinero para compras
    private val credits = AtomicInteger(0)
    
    // Estado del inventario
    private val inventorySlots = ConcurrentHashMap<Int, InventoryItem>()

    // ============================================
    // INICIALIZACIÓN
    // ============================================
    
    init {
        // Inicializar contadores
        AmmoType.values().forEach { ammoCounts[it] = AtomicInteger(0) }
        HealingType.values().forEach { healingItems[it] = AtomicInteger(0) }
        UtilityType.values().forEach { utilities[it] = AtomicInteger(0) }
    }

    // ============================================
    // API DE ACTUALIZACIÓN
    // ============================================
    
    fun updateAmmo(type: AmmoType, count: Int) {
        ammoCounts[type]?.set(count)
        Logger.d(TAG, "Munición $type: $count")
    }
    
    fun updateHealing(type: HealingType, count: Int) {
        healingItems[type]?.set(count)
    }
    
    fun updateShields(current: Int, max: Int) {
        shields.set(ShieldStatus(current, max))
    }
    
    fun updateCredits(amount: Int) {
        credits.set(amount)
    }
    
    fun addInventoryItem(slot: Int, item: InventoryItem) {
        inventorySlots[slot] = item
    }

    // ============================================
    // EVALUACIÓN DE NECESIDADES
    // ============================================
    
    fun getAmmoNeed(): ResourceNeed {
        val primaryAmmo = getTotalAmmoForPrimary()
        
        return when {
            primaryAmmo <= AMMO_CRITICAL -> ResourceNeed.CRITICAL
            primaryAmmo <= AMMO_LOW -> ResourceNeed.HIGH
            primaryAmmo <= 120 -> ResourceNeed.MEDIUM
            else -> ResourceNeed.LOW
        }
    }
    
    fun getHealingNeed(): ResourceNeed {
        val totalHeals = healingItems.values.sumOf { it.get() }
        
        return when {
            totalHeals == 0 -> ResourceNeed.CRITICAL
            totalHeals <= 1 -> ResourceNeed.HIGH
            totalHeals <= 3 -> ResourceNeed.MEDIUM
            else -> ResourceNeed.LOW
        }
    }
    
    fun getShieldNeed(): ResourceNeed {
        val shield = shields.get()
        val percentage = if (shield.max > 0) (shield.current * 100 / shield.max) else 0
        
        return when {
            percentage <= 25 -> ResourceNeed.CRITICAL
            percentage <= 50 -> ResourceNeed.HIGH
            percentage <= 75 -> ResourceNeed.MEDIUM
            else -> ResourceNeed.LOW
        }
    }

    // ============================================
    // DECISIONES ECONÓMICAS
    // ============================================
    
    /**
     * Decide si debería lootear basado en necesidades
     */
    fun shouldLoot(): Boolean {
        return getAmmoNeed().ordinal >= ResourceNeed.MEDIUM.ordinal ||
               getHealingNeed().ordinal >= ResourceNeed.HIGH.ordinal ||
               getShieldNeed().ordinal >= ResourceNeed.MEDIUM.ordinal
    }
    
    /**
     * Decide prioridad de loot por tipo
     */
    fun getLootPriority(): List<LootPriority> {
        val priorities = mutableListOf<LootPriority>()
        
        // 1. Curación (si crítico)
        if (getHealingNeed() == ResourceNeed.CRITICAL) {
            priorities.add(LootPriority(HealingType.MEDIKIT, 100))
            priorities.add(LootPriority(HealingType.FIRST_AID, 95))
        }
        
        // 2. Munición (si crítico o bajo)
        if (getAmmoNeed().ordinal >= ResourceNeed.HIGH.ordinal) {
            priorities.add(LootPriority(AmmoType.RIFLE, 90))
            priorities.add(LootPriority(AmmoType.SMG, 85))
        }
        
        // 3. Escudos
        if (getShieldNeed().ordinal >= ResourceNeed.HIGH.ordinal) {
            priorities.add(LootPriority(ShieldType.LEVEL_3, 80))
            priorities.add(LootPriority(ShieldType.LEVEL_2, 70))
        }
        
        // 4. Utilidades útiles
        if (utilities[UtilityType.SMOKE]?.get() ?: 0 < 2) {
            priorities.add(LootPriority(UtilityType.SMOKE, 60))
        }
        
        return priorities.sortedByDescending { it.priority }
    }
    
    /**
     * Decide si usar item de curación ahora
     */
    fun shouldHealNow(currentHealth: Int, inCombat: Boolean): Boolean {
        // No curar en combate intenso a menos que crítico
        if (inCombat && currentHealth > HEALTH_CRITICAL) return false
        
        return when {
            currentHealth <= HEALTH_CRITICAL -> true
            currentHealth <= HEALTH_LOW && !inCombat -> true
            else -> false
        }
    }
    
    /**
     * Selecciona mejor item de curación disponible
     */
    fun selectHealingItem(currentHealth: Int, healthMax: Int): HealingType? {
        val healthNeeded = healthMax - currentHealth
        
        return when {
            // Usar medikit si mucha vida perdida y disponible
            healthNeeded > 50 && (healingItems[HealingType.MEDIKIT]?.get() ?: 0) > 0 ->
                HealingType.MEDIKIT
            
            // Usar first aid para curación media
            healthNeeded > 25 && (healingItems[HealingType.FIRST_AID]?.get() ?: 0) > 0 ->
                HealingType.FIRST_AID
            
            // Usar bandage para pequeñas curaciones
            healthNeeded > 0 && (healingItems[HealingType.BANDAGE]?.get() ?: 0) > 0 ->
                HealingType.BANDAGE
            
            else -> null
        }
    }
    
    /**
     * Decide si recargar ahora
     */
    fun shouldReloadNow(ammoInMag: Int, magSize: Int, inCombat: Int): Boolean {
        val percentage = (ammoInMag * 100) / magSize
        
        return when {
            // Siempre recargar si vacío
            ammoInMag == 0 -> true
            
            // No recargar en combate intenso con >30%
            inCombat > 2 && percentage > 30 -> false
            
            // Recargar si bajo y no en combate crítico
            percentage < 20 && inCombat <= 1 -> true
            
            // Recargar en oportunidad segura
            percentage < 50 && inCombat == 0 -> true
            
            else -> false
        }
    }
    
    /**
     * Evalúa si vale la pena arriesgarse por loot
     */
    fun isLootWorthRisk(distance: Float, enemiesNearby: Int, lootValue: Float): Boolean {
        // Riesgo aumenta con enemigos y distancia
        val riskScore = (enemiesNearby * 20) + (distance / 10)
        
        // Valor debe superar riesgo
        return lootValue > riskScore
    }
    
    /**
     * Decide compras en tienda/máquina
     */
    fun decidePurchases(availableItems: List<ShopItem>): List<ShopItem> {
        val purchases = mutableListOf<ShopItem>()
        var remainingCredits = credits.get()
        
        // Prioridad 1: Curación si necesaria
        if (getHealingNeed().ordinal >= ResourceNeed.HIGH.ordinal) {
            availableItems.find { it.type == ShopItemType.MEDIKIT && it.price <= remainingCredits }?.let {
                purchases.add(it)
                remainingCredits -= it.price
            }
        }
        
        // Prioridad 2: Munición si baja
        if (getAmmoNeed().ordinal >= ResourceNeed.MEDIUM.ordinal) {
            availableItems.find { it.type == ShopItemType.AMMO_PACK && it.price <= remainingCredits }?.let {
                purchases.add(it)
                remainingCredits -= it.price
            }
        }
        
        // Prioridad 3: Mejorar escudo si barato
        if (getShieldNeed() == ResourceNeed.MEDIUM && remainingCredits > 500) {
            availableItems.find { it.type == ShopItemType.SHIELD_UPGRADE }?.let {
                purchases.add(it)
                remainingCredits -= it.price
            }
        }
        
        return purchases
    }

    // ============================================
    // ANÁLISIS DE COSTO/BENEFICIO
    // ============================================
    
    /**
     * Calcula costo de oportunidad de una acción
     */
    fun calculateOpportunityCost(action: EconomicAction, context: EconomicContext): OpportunityCost {
        val timeCost = action.timeRequiredMs * context.timePressureFactor
        val riskCost = action.riskLevel * context.dangerLevel * 100
        val resourceCost = action.resourcesConsumed.sumOf { getResourceValue(it) }
        
        val totalCost = timeCost + riskCost + resourceCost
        val expectedBenefit = action.expectedBenefit
        
        return OpportunityCost(
            totalCost = totalCost,
            expectedBenefit = expectedBenefit,
            roi = if (totalCost > 0) expectedBenefit / totalCost else 0f
        )
    }
    
    /**
     * Evalúa múltiples opciones y selecciona mejor ROI
     */
    fun selectBestEconomicAction(options: List<EconomicAction>, context: EconomicContext): EconomicAction? {
        return options.map { action ->
            Pair(action, calculateOpportunityCost(action, context))
        }.filter { it.second.roi > 1.0f }  // Solo opciones rentables
            .maxByOrNull { it.second.roi }
            ?.first
    }

    // ============================================
    // CONSERVACIÓN INTELIGENTE
    // ============================================
    
    /**
     * Modo de conservación para late game
     */
    fun enableConservationMode() {
        // Reducir gasto de munición, priorizar supervivencia
        Logger.i(TAG, "Modo conservación activado")
    }
    
    /**
     * Evalúa si conservar o gastar según fase del juego
     */
    fun shouldConserveResources(gamePhase: GamePhase, playersRemaining: Int): Boolean {
        return when (gamePhase) {
            GamePhase.EARLY -> false  // Gastar libremente early
            GamePhase.MID -> playersRemaining < 30  // Conservar si quedan pocos
            GamePhase.LATE -> true    // Siempre conservar late
            GamePhase.ENDGAME -> true // Máxima conservación
        }
    }

    // ============================================
    // ESTADÍSTICAS
    // ============================================
    
    fun getEconomyStats(): EconomyStats {
        return EconomyStats(
            totalAmmo = ammoCounts.values.sumOf { it.get() },
            totalHealingItems = healingItems.values.sumOf { it.get() },
            shieldPercentage = getShieldPercentage(),
            credits = credits.get(),
            inventoryUtilization = calculateInventoryUtilization(),
            ammoNeed = getAmmoNeed(),
            healingNeed = getHealingNeed(),
            shieldNeed = getShieldNeed()
        )
    }
    
    private fun getTotalAmmoForPrimary(): Int {
        // Sumar munición de tipos primarios
        return (ammoCounts[AmmoType.RIFLE]?.get() ?: 0) +
               (ammoCounts[AmmoType.SMG]?.get() ?: 0) +
               (ammoCounts[AmmoType.SHOTGUN]?.get() ?: 0)
    }
    
    private fun getShieldPercentage(): Int {
        val shield = shields.get()
        return if (shield.max > 0) (shield.current * 100 / shield.max) else 0
    }
    
    private fun calculateInventoryUtilization(): Float {
        val usedSlots = inventorySlots.size
        return usedSlots.toFloat() / 6f  // Asumiendo 6 slots
    }
    
    private fun getResourceValue(resource: ResourceType): Float {
        return when (resource) {
            ResourceType.AMMO -> 1f
            ResourceType.HEALING -> 5f
            ResourceType.SHIELD -> 10f
            ResourceType.UTILITY -> 3f
            ResourceType.TIME -> 50f  // Tiempo es más valioso
        }
    }

    // ============================================
    // RESET
    // ============================================
    
    fun reset() {
        ammoCounts.values.forEach { it.set(0) }
        healingItems.values.forEach { it.set(0) }
        utilities.values.forEach { it.set(0) }
        credits.set(0)
        inventorySlots.clear()
        shields.set(ShieldStatus(0, 0))
        
        Logger.i(TAG, "EconomyManager reseteado")
    }
}

// ============================================
// ENUMS Y DATA CLASSES
// ============================================

enum class AmmoType {
    PISTOL, SMG, RIFLE, SHOTGUN, SNIPER, LMG
}

enum class HealingType {
    BANDAGE, FIRST_AID, MEDIKIT, SYRINGE
}

enum class ShieldType {
    LEVEL_1, LEVEL_2, LEVEL_3, LEVEL_4
}

enum class UtilityType {
    GRENADE_FRAG, GRENADE_SMOKE, GRENADE_FLASH, 
    MOLOTOV, DECOY, TRAP
}

enum class ResourceNeed {
    NONE,      // 0 - No necesita
    LOW,       // 1 - Bien surtido
    MEDIUM,    // 2 - Podría necesitar
    HIGH,      // 3 - Debería buscar
    CRITICAL   // 4 - Necesita urgentemente
}

enum class GamePhase {
    EARLY,     // 50+ jugadores
    MID,       // 20-50 jugadores  
    LATE,      // 10-20 jugadores
    ENDGAME    // < 10 jugadores
}

enum class ResourceType {
    AMMO, HEALING, SHIELD, UTILITY, TIME
}

enum class ShopItemType {
    MEDIKIT, AMMO_PACK, SHIELD_UPGRADE, UTILITY_BOX, WEAPON_TOKEN
}

data class ShieldStatus(
    val current: Int,
    val max: Int
)

data class InventoryItem(
    val name: String,
    val type: ItemType,
    val quantity: Int
)

enum class ItemType {
    WEAPON, AMMO, HEALING, SHIELD, UTILITY, ATTACHMENT
}

data class LootPriority(
    val item: Any,  // Puede ser HealingType, AmmoType, ShieldType, etc.
    val priority: Int
)

data class ShopItem(
    val type: ShopItemType,
    val name: String,
    val price: Int,
    val value: Float
)

data class EconomicAction(
    val name: String,
    val timeRequiredMs: Float,
    val riskLevel: Float,  // 0-1
    val resourcesConsumed: List<ResourceType>,
    val expectedBenefit: Float
)

data class EconomicContext(
    val timePressureFactor: Float,  // Multiplicador de costo de tiempo
    val dangerLevel: Float,        // 0-1 nivel de peligro actual
    val gamePhase: GamePhase
)

data class OpportunityCost(
    val totalCost: Float,
    val expectedBenefit: Float,
    val roi: Float
)

data class EconomyStats(
    val totalAmmo: Int,
    val totalHealingItems: Int,
    val shieldPercentage: Int,
    val credits: Int,
    val inventoryUtilization: Float,
    val ammoNeed: ResourceNeed,
    val healingNeed: ResourceNeed,
    val shieldNeed: ResourceNeed
)
