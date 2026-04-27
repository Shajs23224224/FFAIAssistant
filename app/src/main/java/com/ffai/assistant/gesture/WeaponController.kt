package com.ffai.assistant.gesture

import com.ffai.assistant.utils.Logger

/**
 * FASE 4: WeaponController - Control específico por arma.
 * 
 * Perfiles por arma:
 * - Recoil patterns (cada arma tiene patrón único)
 * - Burst modes (single, 3-shot, full-auto)
 * - Fire rate adaptation
 * - Dynamic crosshair placement
 */
class WeaponController(private val gestureEngine: GestureEngine) {
    
    companion object {
        const val TAG = "WeaponController"
    }
    
    // Arma actual
    private var currentWeapon: WeaponType = WeaponType.M416
    
    // Stats de aprendizaje por arma
    private val weaponStats = mutableMapOf<WeaponType, WeaponStats>()
    
    // Configuración por arma
    private val weaponProfiles = mapOf(
        WeaponType.M416 to WeaponProfile(
            verticalRecoil = 0.15f,
            horizontalRecoil = 0.05f,
            fireRateMs = 85,
            optimalRange = 50f,
            burstMode = BurstMode.FULL_AUTO,
            compensationCurve = listOf(0f, 0.1f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.45f, 0.5f, 0.55f)
        ),
        WeaponType.AKM to WeaponProfile(
            verticalRecoil = 0.25f,
            horizontalRecoil = 0.15f,
            fireRateMs = 100,
            optimalRange = 40f,
            burstMode = BurstMode.BURST_3,
            compensationCurve = listOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.85f, 0.9f, 0.95f, 1.0f)
        ),
        WeaponType.M762 to WeaponProfile(
            verticalRecoil = 0.22f,
            horizontalRecoil = 0.12f,
            fireRateMs = 86,
            optimalRange = 45f,
            burstMode = BurstMode.FULL_AUTO,
            compensationCurve = listOf(0f, 0.12f, 0.25f, 0.37f, 0.5f, 0.62f, 0.75f, 0.85f, 0.92f, 0.98f)
        ),
        WeaponType.SCARL to WeaponProfile(
            verticalRecoil = 0.12f,
            horizontalRecoil = 0.04f,
            fireRateMs = 96,
            optimalRange = 60f,
            burstMode = BurstMode.FULL_AUTO,
            compensationCurve = listOf(0f, 0.08f, 0.16f, 0.24f, 0.32f, 0.4f, 0.48f, 0.56f, 0.64f, 0.72f)
        ),
        WeaponType.Sniper to WeaponProfile(
            verticalRecoil = 0.5f,
            horizontalRecoil = 0.3f,
            fireRateMs = 1000,
            optimalRange = 200f,
            burstMode = BurstMode.SINGLE,
            compensationCurve = listOf(0f)
        ),
        WeaponType.DMR to WeaponProfile(
            verticalRecoil = 0.35f,
            horizontalRecoil = 0.2f,
            fireRateMs = 200,
            optimalRange = 150f,
            burstMode = BurstMode.SINGLE,
            compensationCurve = listOf(0f, 0.3f, 0.6f)
        ),
        WeaponType.SMG to WeaponProfile(
            verticalRecoil = 0.08f,
            horizontalRecoil = 0.1f,
            fireRateMs = 70,
            optimalRange = 25f,
            burstMode = BurstMode.FULL_AUTO,
            compensationCurve = listOf(0f, 0.05f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.45f)
        ),
        WeaponType.Shotgun to WeaponProfile(
            verticalRecoil = 0.6f,
            horizontalRecoil = 0.4f,
            fireRateMs = 800,
            optimalRange = 15f,
            burstMode = BurstMode.SINGLE,
            compensationCurve = listOf(0f)
        )
    )
    
    // Estado de disparo
    private var shotsFired = 0
    private var lastShotTime = 0L
    private var isFiring = false
    
    /**
     * Cambia arma actual.
     */
    fun setWeapon(weapon: WeaponType) {
        currentWeapon = weapon
        shotsFired = 0
        Logger.i(TAG, "Weapon changed to $weapon")
    }
    
    /**
     * Detecta arma desde UI (placeholder para OCR/visión).
     */
    fun detectWeaponFromUI(): WeaponType {
        // En implementación real, usar visión para detectar arma del HUD
        return currentWeapon
    }
    
    /**
     * Dispara con configuración óptima para arma actual.
     */
    fun shoot(
        targetX: Float,
        targetY: Float,
        distance: Float = 50f
    ): Boolean {
        val profile = weaponProfiles[currentWeapon] ?: weaponProfiles[WeaponType.M416]!!
        
        return when (profile.burstMode) {
            BurstMode.SINGLE -> shootSingle(targetX, targetY, profile)
            BurstMode.BURST_3 -> shootBurst3(targetX, targetY, profile)
            BurstMode.FULL_AUTO -> shootFullAuto(targetX, targetY, profile, distance)
        }
    }
    
    /**
     * Disparo único.
     */
    private fun shootSingle(
        targetX: Float,
        targetY: Float,
        profile: WeaponProfile
    ): Boolean {
        // Para snipers: ADS + esperar + fire + unADS
        if (currentWeapon == WeaponType.Sniper) {
            gestureEngine.longPress(targetX, targetY, 800)  // ADS
            // Esperar estabilización
            Thread.sleep(300)
        }
        
        val success = gestureEngine.tap(1200f, 700f)  // Fire button
        
        if (success) {
            recordShot(profile)
            applyRecoilCompensation(profile, 0)
        }
        
        return success
    }
    
    /**
     * Ráfaga de 3 disparos.
     */
    private fun shootBurst3(
        targetX: Float,
        targetY: Float,
        profile: WeaponProfile
    ): Boolean {
        var success = true
        
        for (i in 0 until 3) {
            // Fire
            success = success && gestureEngine.tap(1200f, 700f, 30)
            recordShot(profile)
            
            if (!success) break
            
            // Compensar recoil inmediatamente
            applyRecoilCompensation(profile, i)
            
            // Delay entre disparos
            if (i < 2) {
                Thread.sleep(profile.fireRateMs.toLong())
            }
        }
        
        return success
    }
    
    /**
     * Fuego automático con spray control.
     */
    private fun shootFullAuto(
        targetX: Float,
        targetY: Float,
        profile: WeaponProfile,
        distance: Float
    ): Boolean {
        // Determinar duración del spray según distancia
        val sprayDuration = when {
            distance < 20f -> 10  // Cerca: spray largo
            distance < 50f -> 7   // Medio: spray medio
            else -> 4             // Lejos: burst corto
        }
        
        isFiring = true
        var success = true
        
        for (i in 0 until sprayDuration) {
            // Fire
            success = success && gestureEngine.tap(1200f, 700f, 30)
            recordShot(profile)
            
            if (!success) break
            
            // Compensar recoil
            applyRecoilCompensation(profile, i)
            
            // Pequeño delay
            Thread.sleep((profile.fireRateMs * 0.8).toLong())
        }
        
        isFiring = false
        return success
    }
    
    /**
     * Compensa recoil aprendido.
     */
    private fun applyRecoilCompensation(profile: WeaponProfile, shotIndex: Int) {
        val compensationFactor = profile.compensationCurve.getOrElse(shotIndex) { 
            profile.compensationCurve.last() 
        }
        
        // Calcular compensación basada en recoil del arma
        val verticalCompensation = profile.verticalRecoil * compensationFactor * 50  // pixels
        val horizontalCompensation = (kotlin.random.Random.nextFloat() - 0.5f) * 
                                     profile.horizontalRecoil * 20
        
        // Aplicar compensación inmediatamente (micro-aim)
        if (shotIndex > 0) {  // No compensar primer disparo
            gestureEngine.swipe(
                startX = 540f,
                startY = 960f,
                deltaX = horizontalCompensation,
                deltaY = -verticalCompensation,  // Negativo = hacia arriba
                speedFactor = 3.0f  // Muy rápido
            )
        }
    }
    
    /**
     * Apunta a posición óptima según distancia.
     */
    fun aimAtOptimalPosition(
        enemyX: Float,
        enemyY: Float,
        enemyHeight: Float,
        distance: Float
    ): Boolean {
        val profile = weaponProfiles[currentWeapon] ?: return false
        
        // Determinar punto de aim según distancia y arma
        val aimOffsetY = when {
            distance < profile.optimalRange * 0.5f -> -enemyHeight * 0.3f  // Upper chest
            distance < profile.optimalRange -> -enemyHeight * 0.4f          // Head
            else -> -enemyHeight * 0.5f                                      // Head (leading)
        }
        
        // Ajustar por velocidad de movimiento (leading)
        val leadFactor = if (distance > profile.optimalRange) 0.1f else 0f
        val leadX = (kotlin.random.Random.nextFloat() - 0.5f) * enemyHeight * leadFactor
        
        return gestureEngine.swipe(
            startX = 540f,
            startY = 960f,
            deltaX = (enemyX - 540f) + leadX,
            deltaY = (enemyY - 960f) + aimOffsetY,
            speedFactor = if (distance < 30f) 2.0f else 1.0f
        )
    }
    
    /**
     * Spray transfer: mover aim a nuevo enemigo manteniendo spray.
     */
    fun sprayTransfer(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        remainingShots: Int
    ): Boolean {
        val profile = weaponProfiles[currentWeapon] ?: return false
        
        // Mover aim suavemente al nuevo target
        val success = gestureEngine.swipe(
            startX = fromX,
            startY = fromY,
            deltaX = toX - fromX,
            deltaY = toY - fromY,
            speedFactor = 1.5f
        )
        
        if (success && remainingShots > 0) {
            // Continuar spray
            shootFullAuto(toX, toY, profile, 30f)
        }
        
        return success
    }
    
    /**
     * Pre-fire: disparar antes de que enemigo aparezca en corner.
     */
    fun preFire(cornerX: Float, cornerY: Float, direction: PreFireDirection): Boolean {
        val offsetX = when (direction) {
            PreFireDirection.LEFT -> -50f
            PreFireDirection.RIGHT -> 50f
        }
        
        // Aim justo después del corner
        gestureEngine.swipe(
            startX = 540f,
            startY = 960f,
            deltaX = cornerX - 540f + offsetX,
            deltaY = cornerY - 960f,
            speedFactor = 2.0f
        )
        
        // Fire inmediatamente
        return gestureEngine.tap(1200f, 700f)
    }
    
    /**
     * Actualiza curva de compensación basada en hits/misses.
     */
    fun learnRecoilPattern(hits: Int, misses: Int) {
        val stats = weaponStats.getOrPut(currentWeapon) { WeaponStats() }
        stats.record(hits, misses)
        
        // Ajustar curva si muchos misses
        if (misses > hits * 2) {
            Logger.w(TAG, "Many misses with $currentWeapon, adjusting compensation")
            // En implementación real: ajustar compensationCurve
        }
    }
    
    /**
     * Registra disparo.
     */
    private fun recordShot(profile: WeaponProfile) {
        shotsFired++
        lastShotTime = System.currentTimeMillis()
        
        val stats = weaponStats.getOrPut(currentWeapon) { WeaponStats() }
        stats.shotsFired++
    }
    
    /**
     * Obtiene stats del arma actual.
     */
    fun getWeaponStats(): WeaponStatsDisplay {
        val stats = weaponStats[currentWeapon] ?: WeaponStats()
        val profile = weaponProfiles[currentWeapon]
        
        return WeaponStatsDisplay(
            weapon = currentWeapon,
            shotsFired = stats.shotsFired,
            hits = stats.hits,
            accuracy = if (stats.shotsFired > 0) stats.hits.toFloat() / stats.shotsFired else 0f,
            recoilPattern = profile?.compensationCurve ?: emptyList()
        )
    }
    
    /**
     * Reset entre partidas.
     */
    fun reset() {
        shotsFired = 0
        lastShotTime = 0
        isFiring = false
        weaponStats.clear()
        Logger.i(TAG, "WeaponController reset")
    }
}

/**
 * Tipos de armas.
 */
enum class WeaponType {
    M416, AKM, M762, SCARL,  // ARs
    Sniper, DMR,             // Precision
    SMG,                     // Close range
    Shotgun                  // Very close
}

/**
 * Perfil de arma.
 */
data class WeaponProfile(
    val verticalRecoil: Float,
    val horizontalRecoil: Float,
    val fireRateMs: Int,
    val optimalRange: Float,
    val burstMode: BurstMode,
    val compensationCurve: List<Float>
)

/**
 * Modo de ráfaga.
 */
enum class BurstMode {
    SINGLE,    // Un disparo
    BURST_3,   // Ráfaga de 3
    FULL_AUTO  // Automático
}

/**
 * Dirección de pre-fire.
 */
enum class PreFireDirection { LEFT, RIGHT }

/**
 * Stats de arma.
 */
private class WeaponStats {
    var shotsFired = 0
    var hits = 0
    
    fun record(hits: Int, misses: Int) {
        this.hits += hits
        this.shotsFired += hits + misses
    }
}

/**
 * Stats display.
 */
data class WeaponStatsDisplay(
    val weapon: WeaponType,
    val shotsFired: Int,
    val hits: Int,
    val accuracy: Float,
    val recoilPattern: List<Float>
)
