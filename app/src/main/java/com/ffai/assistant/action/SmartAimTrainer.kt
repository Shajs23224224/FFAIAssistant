package com.ffai.assistant.action

import com.ffai.assistant.model.RecoilPattern
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * FASE 6: SmartAimTrainer - Entrenador avanzado de aim y compensación recoil.
 *
 * Funcionalidades:
 * - Aprende patrones de recoil por arma específica
 * - Dataset de recoil para cada arma (M416, AKM, UMP, etc.)
 * - Compensación predictiva (anticipa en lugar de reaccionar)
 * - Análisis de precisión post-disparo
 * - Mejora continua basada en resultados
 */
class SmartAimTrainer {

    companion object {
        const val TAG = "SmartAimTrainer"
        
        // Tamaño máximo de patrón
        const val MAX_PATTERN_LENGTH = 30
        
        // Armas soportadas
        val SUPPORTED_WEAPONS = listOf(
            "M416", "AKM", "SCAR-L", "M16A4", "G36C",
            "UMP", "UZI", "VECTOR", "MP5",
            "M24", "AWM", "KAR98K", "MINI14", "SKS",
            "S686", "S1897", "S12K",
            "M249", "DP-28"
        )
    }

    // Base de datos de patrones por arma
    private val recoilDatabase = ConcurrentHashMap<String, WeaponProfile>()
    
    // Estado actual
    private val currentWeapon = AtomicReference<String>("M416")
    private val shotsInCurrentBurst = AtomicInteger(0)
    private val isFiring = AtomicReference<Boolean>(false)
    
    // Métricas de precisión
    private val shotsFired = ConcurrentHashMap<String, AtomicInteger>()
    private val hitsScored = ConcurrentHashMap<String, AtomicInteger>()
    private val headshots = ConcurrentHashMap<String, AtomicInteger>()
    
    // Ajustes aprendidos
    private val personalAdjustments = ConcurrentHashMap<String, RecoilAdjustment>()

    init {
        initializeDefaultPatterns()
    }

    /**
     * Inicializa patrones de recoil por defecto basados en datos del juego.
     */
    private fun initializeDefaultPatterns() {
        // M416 - Recoil moderado, patrón predecible ascendente
        recoilDatabase["M416"] = WeaponProfile(
            weaponName = "M416",
            basePattern = RecoilPattern(
                verticalRecoil = listOf(0.15f, 0.18f, 0.20f, 0.22f, 0.24f, 0.22f, 0.20f, 0.18f, 0.15f, 0.12f),
                horizontalRecoil = listOf(0f, 0.02f, -0.03f, 0.04f, -0.02f, 0.03f, -0.04f, 0.02f, 0f, 0f),
                patternLength = 10,
                weaponName = "M416"
            ),
            fireRate = 0.086f, // segundos entre disparos
            recommendedBurst = 5,
            difficulty = WeaponDifficulty.EASY
        )
        
        // AKM - Recoil alto, vertical dominante
        recoilDatabase["AKM"] = WeaponProfile(
            weaponName = "AKM",
            basePattern = RecoilPattern(
                verticalRecoil = listOf(0.25f, 0.30f, 0.35f, 0.38f, 0.40f, 0.38f, 0.35f, 0.32f, 0.28f, 0.25f),
                horizontalRecoil = listOf(0f, 0.05f, -0.08f, 0.06f, -0.10f, 0.08f, -0.06f, 0.04f, -0.02f, 0f),
                patternLength = 10,
                weaponName = "AKM"
            ),
            fireRate = 0.100f,
            recommendedBurst = 3,
            difficulty = WeaponDifficulty.HARD
        )
        
        // UMP - Recoil bajo, SMG estable
        recoilDatabase["UMP"] = WeaponProfile(
            weaponName = "UMP",
            basePattern = RecoilPattern(
                verticalRecoil = listOf(0.10f, 0.12f, 0.14f, 0.15f, 0.14f, 0.12f, 0.10f, 0.08f, 0.06f, 0.05f),
                horizontalRecoil = listOf(0f, 0.03f, -0.04f, 0.03f, -0.03f, 0.02f, -0.02f, 0f, 0f, 0f),
                patternLength = 10,
                weaponName = "UMP"
            ),
            fireRate = 0.092f,
            recommendedBurst = 10,
            difficulty = WeaponDifficulty.VERY_EASY
        )
        
        // Sniper - Sin recoil auto, solo kick
        recoilDatabase["M24"] = WeaponProfile(
            weaponName = "M24",
            basePattern = RecoilPattern(
                verticalRecoil = listOf(0.50f),
                horizontalRecoil = listOf(0f),
                patternLength = 1,
                weaponName = "M24"
            ),
            fireRate = 1.2f,
            recommendedBurst = 1,
            difficulty = WeaponDifficulty.MEDIUM
        )
        
        // Inicializar métricas
        SUPPORTED_WEAPONS.forEach { weapon ->
            shotsFired[weapon] = AtomicInteger(0)
            hitsScored[weapon] = AtomicInteger(0)
            headshots[weapon] = AtomicInteger(0)
        }
        
        Logger.i(TAG, "Patrones de recoil inicializados: ${recoilDatabase.size} armas")
    }

    /**
     * Inicia seguimiento de burst de disparos.
     */
    fun startFiring(weaponName: String) {
        currentWeapon.set(weaponName)
        shotsInCurrentBurst.set(0)
        isFiring.set(true)
        
        Logger.d(TAG, "Iniciando burst con $weaponName")
    }

    /**
     * Detiene seguimiento de burst.
     */
    fun stopFiring() {
        isFiring.set(false)
        val burstSize = shotsInCurrentBurst.get()
        val weapon = currentWeapon.get()
        
        // Actualizar estadísticas
        shotsFired[weapon]?.addAndGet(burstSize)
        
        Logger.d(TAG, "Burst terminado: $weapon - $burstSize disparos")
    }

    /**
     * Calcula compensación de recoil para el siguiente disparo.
     * Predictivo: compensa ANTES de que el recoil ocurra.
     */
    fun calculateCompensation(): RecoilCompensation {
        if (!isFiring.get()) {
            return RecoilCompensation(0f, 0f, 0)
        }
        
        val weapon = currentWeapon.get()
        val shotNumber = shotsInCurrentBurst.incrementAndGet()
        
        val profile = recoilDatabase[weapon] ?: return RecoilCompensation(0f, 0f, 0)
        val adjustment = personalAdjustments[weapon]
        
        // Obtener recoil base
        val baseVertical = profile.basePattern.verticalRecoil.getOrElse(shotNumber - 1) { 
            profile.basePattern.verticalRecoil.lastOrNull() ?: 0.1f 
        }
        val baseHorizontal = profile.basePattern.horizontalRecoil.getOrElse(shotNumber - 1) { 
            profile.basePattern.horizontalRecoil.lastOrNull() ?: 0f 
        }
        
        // Aplicar ajustes personales
        val adjustedVertical = baseVertical * (adjustment?.verticalMultiplier ?: 1f)
        val adjustedHorizontal = baseHorizontal * (adjustment?.horizontalMultiplier ?: 1f)
        
        // Aplicar offset aprendido
        val finalVertical = adjustedVertical + (adjustment?.verticalOffset ?: 0f)
        val finalHorizontal = adjustedHorizontal + (adjustment?.horizontalOffset ?: 0f)
        
        // Invertir para compensación (movemos en dirección opuesta)
        return RecoilCompensation(
            compensationX = -finalHorizontal,
            compensationY = finalVertical, // Recoil sube, compensamos bajando
            burstSize = shotNumber,
            maxBurst = profile.recommendedBurst
        )
    }

    /**
     * Reporta resultado de disparo para aprendizaje.
     */
    fun reportShotResult(
        hit: Boolean,
        isHeadshot: Boolean = false,
        damageDealt: Float = 0f,
        distance: Float = 0f
    ) {
        val weapon = currentWeapon.get()
        
        if (hit) {
            hitsScored[weapon]?.incrementAndGet()
            if (isHeadshot) {
                headshots[weapon]?.incrementAndGet()
            }
            
            // Si acertamos, reforzar compensación actual
            reinforceCurrentCompensation(weapon, true)
        } else {
            // Si fallamos, ajustar compensación
            adjustCompensationForMiss(weapon, distance)
        }
        
        // Actualizar ajustes personales
        updatePersonalAdjustment(weapon, hit, isHeadshot)
    }

    /**
     * Obtiene patrón de recoil para un arma.
     */
    fun getRecoilPattern(weaponName: String): RecoilPattern? {
        return recoilDatabase[weaponName]?.basePattern
    }

    /**
     * Obtiene recomendación de burst size.
     */
    fun getRecommendedBurst(weaponName: String): Int {
        return recoilDatabase[weaponName]?.recommendedBurst ?: 3
    }

    /**
     * Obtiene dificultad del arma.
     */
    fun getWeaponDifficulty(weaponName: String): WeaponDifficulty {
        return recoilDatabase[weaponName]?.difficulty ?: WeaponDifficulty.MEDIUM
    }

    /**
     * Obtiene estadísticas de precisión para un arma.
     */
    fun getAccuracyStats(weaponName: String): AccuracyStats {
        val shots = shotsFired[weaponName]?.get() ?: 0
        val hits = hitsScored[weaponName]?.get() ?: 0
        val heads = headshots[weaponName]?.get() ?: 0
        
        return AccuracyStats(
            totalShots = shots,
            hits = hits,
            headshots = heads,
            accuracy = if (shots > 0) hits.toFloat() / shots else 0f,
            headshotRatio = if (hits > 0) heads.toFloat() / hits else 0f
        )
    }

    /**
     * Obtiene perfil completo del arma actual.
     */
    fun getCurrentWeaponProfile(): WeaponProfile? {
        return recoilDatabase[currentWeapon.get()]
    }

    // ============================================
    // APRENDIZAJE
    // ============================================

    private fun reinforceCurrentCompensation(weapon: String, positive: Boolean) {
        val adjustment = personalAdjustments.getOrPut(weapon) { RecoilAdjustment() }
        
        if (positive) {
            // Reforzar multiplicadores actuales
            adjustment.verticalMultiplier *= 0.99f // Reducir ligeramente
        }
    }

    private fun adjustCompensationForMiss(weapon: String, distance: Float) {
        val adjustment = personalAdjustments.getOrPut(weapon) { RecoilAdjustment() }
        
        // Ajustar basado en distancia (a mayor distancia, más compensación vertical)
        val distanceFactor = (distance / 100f).coerceIn(0.5f, 2f)
        adjustment.verticalMultiplier *= 1.01f * distanceFactor
        adjustment.verticalMultiplier = adjustment.verticalMultiplier.coerceIn(0.5f, 2f)
    }

    private fun updatePersonalAdjustment(weapon: String, hit: Boolean, isHeadshot: Boolean) {
        val adjustment = personalAdjustments.getOrPut(weapon) { RecoilAdjustment() }
        
        // Ajustar basado en ratio de aciertos
        val accuracy = getAccuracyStats(weapon).accuracy
        
        when {
            accuracy > 0.7f -> {
                // Muy preciso, mantener ajustes
                adjustment.confidence = (adjustment.confidence + 0.1f).coerceAtMost(1f)
            }
            accuracy > 0.4f -> {
                // Moderado, ajustar fino
                adjustment.confidence = 0.5f
            }
            else -> {
                // Poco preciso, explorar más
                adjustment.confidence = 0.2f
                // Agregar ruido de exploración
                adjustment.verticalOffset += (kotlin.random.Random.nextFloat() - 0.5f) * 0.02f
            }
        }
        
        // Ajustar offset si consistentemente fallamos arriba/abajo
        if (!hit && adjustment.lastShots.size >= 5) {
            val recentMisses = adjustment.lastShots.takeLast(5).count { !it }
            if (recentMisses >= 4) {
                // Ajustar vertical
                adjustment.verticalOffset += 0.01f
            }
        }
        
        adjustment.lastShots.add(hit)
        if (adjustment.lastShots.size > 20) {
            adjustment.lastShots.removeAt(0)
        }
    }

    /**
     * Entrena con un patrón de recoil grabado manualmente.
     */
    fun trainWithRecordedPattern(weaponName: String, recordedPattern: List<Pair<Float, Float>>) {
        val verticalRecoil = recordedPattern.map { it.second }
        val horizontalRecoil = recordedPattern.map { it.first }
        
        val newPattern = RecoilPattern(
            verticalRecoil = verticalRecoil,
            horizontalRecoil = horizontalRecoil,
            patternLength = recordedPattern.size,
            weaponName = weaponName
        )
        
        // Actualizar base de datos
        val existing = recoilDatabase[weaponName]
        if (existing != null) {
            // Promediar con patrón existente
            val blendedPattern = blendPatterns(existing.basePattern, newPattern, 0.3f)
            recoilDatabase[weaponName] = existing.copy(basePattern = blendedPattern)
        } else {
            recoilDatabase[weaponName] = WeaponProfile(
                weaponName = weaponName,
                basePattern = newPattern,
                fireRate = 0.1f,
                recommendedBurst = 5,
                difficulty = WeaponDifficulty.MEDIUM
            )
        }
        
        Logger.i(TAG, "Patrón entrenado para $weaponName: ${recordedPattern.size} puntos")
    }

    private fun blendPatterns(pattern1: RecoilPattern, pattern2: RecoilPattern, weight: Float): RecoilPattern {
        val maxLength = kotlin.math.max(pattern1.verticalRecoil.size, pattern2.verticalRecoil.size)
        
        val blendedVertical = List(maxLength) { i ->
            val v1 = pattern1.verticalRecoil.getOrElse(i) { 0f }
            val v2 = pattern2.verticalRecoil.getOrElse(i) { 0f }
            v1 * (1 - weight) + v2 * weight
        }
        
        val blendedHorizontal = List(maxLength) { i ->
            val h1 = pattern1.horizontalRecoil.getOrElse(i) { 0f }
            val h2 = pattern2.horizontalRecoil.getOrElse(i) { 0f }
            h1 * (1 - weight) + h2 * weight
        }
        
        return RecoilPattern(
            verticalRecoil = blendedVertical,
            horizontalRecoil = blendedHorizontal,
            patternLength = maxLength,
            weaponName = pattern1.weaponName
        )
    }

    /**
     * Resetea estadísticas y ajustes.
     */
    fun reset() {
        personalAdjustments.clear()
        SUPPORTED_WEAPONS.forEach { weapon ->
            shotsFired[weapon]?.set(0)
            hitsScored[weapon]?.set(0)
            headshots[weapon]?.set(0)
        }
        shotsInCurrentBurst.set(0)
        isFiring.set(false)
        Logger.i(TAG, "SmartAimTrainer reseteado")
    }
}

// ============================================
// DATA CLASSES
// ============================================

data class WeaponProfile(
    val weaponName: String,
    val basePattern: RecoilPattern,
    val fireRate: Float, // segundos entre disparos
    val recommendedBurst: Int,
    val difficulty: WeaponDifficulty,
    val attachments: List<String> = emptyList() // grip, stock, etc.
)

data class RecoilCompensation(
    val compensationX: Float,
    val compensationY: Float,
    val burstSize: Int,
    val maxBurst: Int = 0
) {
    fun shouldStopBurst(): Boolean = burstSize >= maxBurst
}

data class RecoilAdjustment(
    var verticalMultiplier: Float = 1.0f,
    var horizontalMultiplier: Float = 1.0f,
    var verticalOffset: Float = 0f,
    var horizontalOffset: Float = 0f,
    var confidence: Float = 0.5f,
    val lastShots: MutableList<Boolean> = mutableListOf()
)

data class AccuracyStats(
    val totalShots: Int,
    val hits: Int,
    val headshots: Int,
    val accuracy: Float,
    val headshotRatio: Float
)

enum class WeaponDifficulty {
    VERY_EASY, EASY, MEDIUM, HARD, VERY_HARD
}
