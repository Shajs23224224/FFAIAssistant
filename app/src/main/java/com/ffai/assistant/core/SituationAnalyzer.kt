package com.ffai.assistant.core

import com.ffai.assistant.vision.FusedEnemy
import com.ffai.assistant.vision.TrackedDetection
import com.ffai.assistant.utils.Logger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.hypot
import kotlin.math.max

/**
 * FASE 2: SituationAnalyzer - Analiza situación de combate para modo adaptativo.
 */
class SituationAnalyzer {
    
    companion object {
        const val TAG = "SituationAnalyzer"
        const val CRITICAL_DISTANCE = 100f
        const val HIGH_DISTANCE = 250f
        const val MEDIUM_DISTANCE = 400f
        const val CRITICAL_HP = 30
        const val LOW_HP = 50
        const val RECENT_FIRE_MS = 3000
        const val HYSTERESIS_FRAMES = 5
    }
    
    private var currentSituation = SituationLevel.LOW
    private var situationConfidence = 0.5f
    private var hysteresisCounter = 0
    private var pendingSituation: SituationLevel? = null
    private val fireEvents = ConcurrentLinkedQueue<Long>()
    private var analysisCount = 0
    private var currentHp = 100
    private var currentAmmo = 100
    
    fun analyze(
        enemies: List<FusedEnemy>,
        trackedObjects: List<TrackedDetection>,
        hp: Int,
        ammo: Int,
        screenWidth: Int,
        screenHeight: Int
    ): SituationAnalysis {
        val startTime = System.currentTimeMillis()
        currentHp = hp
        currentAmmo = ammo
        
        val screenCenterX = screenWidth / 2f
        val screenCenterY = screenHeight / 2f
        
        val enemyAnalysis = analyzeEnemies(enemies, screenCenterX, screenCenterY)
        val recentFire = hasRecentFire()
        
        val newSituation = determineSituationLevel(enemyAnalysis, recentFire, hp, ammo)
        val finalSituation = applyHysteresis(newSituation)
        
        cleanupOldEvents()
        analysisCount++
        
        return SituationAnalysis(
            level = finalSituation,
            confidence = situationConfidence,
            enemyCount = enemyAnalysis.enemyCount,
            nearestEnemyDistance = enemyAnalysis.nearestDistance,
            threatsInArc = enemyAnalysis.threatsInAimArc,
            hasLowHP = hp < LOW_HP,
            hasLowAmmo = ammo < 20,
            isInCombat = recentFire || enemyAnalysis.nearestDistance < HIGH_DISTANCE,
            recommendedMode = determineReasoningMode(finalSituation),
            shouldUseFastAim = finalSituation == SituationLevel.CRITICAL,
            recommendedAction = recommendAction(finalSituation, enemyAnalysis)
        )
    }
    
    private fun analyzeEnemies(
        enemies: List<FusedEnemy>,
        aimX: Float,
        aimY: Float
    ): EnemyAnalysis {
        if (enemies.isEmpty()) return EnemyAnalysis(0, Float.MAX_VALUE, 0)
        
        val nearest = enemies.minByOrNull {
            hypot(it.centerX() - aimX, it.centerY() - aimY)
        }
        
        val nearestDistance = nearest?.let {
            hypot(it.centerX() - aimX, it.centerY() - aimY)
        } ?: Float.MAX_VALUE
        
        val threatsInArc = enemies.count { enemy ->
            val dx = enemy.centerX() - aimX
            val distance = hypot(dx, enemy.centerY() - aimY)
            kotlin.math.abs(dx) < 100 && distance < HIGH_DISTANCE
        }
        
        return EnemyAnalysis(enemies.size, nearestDistance, threatsInArc)
    }
    
    private fun determineSituationLevel(
        enemyAnalysis: EnemyAnalysis,
        recentFire: Boolean,
        hp: Int,
        ammo: Int
    ): SituationLevel {
        return when {
            enemyAnalysis.nearestDistance < CRITICAL_DISTANCE && (hp < CRITICAL_HP || recentFire) -> {
                situationConfidence = 0.9f
                SituationLevel.CRITICAL
            }
            enemyAnalysis.nearestDistance < HIGH_DISTANCE || recentFire -> {
                situationConfidence = 0.8f
                SituationLevel.HIGH
            }
            enemyAnalysis.nearestDistance < MEDIUM_DISTANCE || enemyAnalysis.enemyCount > 0 || hp < LOW_HP -> {
                situationConfidence = 0.6f
                SituationLevel.MEDIUM
            }
            else -> {
                situationConfidence = 0.4f
                SituationLevel.LOW
            }
        }
    }
    
    private fun applyHysteresis(newSituation: SituationLevel): SituationLevel {
        if (newSituation == currentSituation) {
            hysteresisCounter = 0
            pendingSituation = null
            return currentSituation
        }
        
        if (newSituation.ordinal > currentSituation.ordinal) {
            Logger.i(TAG, "Escalada: ${currentSituation.name} → ${newSituation.name}")
            currentSituation = newSituation
            hysteresisCounter = 0
            pendingSituation = null
            return currentSituation
        }
        
        if (newSituation != pendingSituation) {
            pendingSituation = newSituation
            hysteresisCounter = 0
        }
        
        hysteresisCounter++
        if (hysteresisCounter >= HYSTERESIS_FRAMES) {
            Logger.i(TAG, "Reducción: ${currentSituation.name} → ${newSituation.name}")
            currentSituation = newSituation
            hysteresisCounter = 0
            pendingSituation = null
        }
        
        return currentSituation
    }
    
    private fun determineReasoningMode(situation: SituationLevel): ReasoningMode {
        return when (situation) {
            SituationLevel.CRITICAL -> ReasoningMode.SHORT
            SituationLevel.HIGH -> ReasoningMode.SHORT
            SituationLevel.MEDIUM -> ReasoningMode.MEDIUM
            SituationLevel.LOW -> ReasoningMode.LONG
        }
    }
    
    private fun recommendAction(
        situation: SituationLevel,
        enemyAnalysis: EnemyAnalysis
    ): RecommendedAction {
        return when (situation) {
            SituationLevel.CRITICAL -> if (enemyAnalysis.threatsInAimArc > 0) 
                RecommendedAction.ENGAGE_NEAREST else RecommendedAction.RETREAT_TO_COVER
            SituationLevel.HIGH -> if (enemyAnalysis.threatsInAimArc > 0) 
                RecommendedAction.ENGAGE_NEAREST else RecommendedAction.REPOSITION
            SituationLevel.MEDIUM -> RecommendedAction.MOVE_TO_ADVANTAGE
            SituationLevel.LOW -> RecommendedAction.ROTATE_TO_ZONE
        }
    }
    
    private fun hasRecentFire(): Boolean {
        val now = System.currentTimeMillis()
        return fireEvents.any { now - it < RECENT_FIRE_MS }
    }
    
    fun reportFireEvent() {
        fireEvents.offer(System.currentTimeMillis())
    }
    
    fun reportDamage(damage: Int) {
        currentHp = max(0, currentHp - damage)
    }
    
    private fun cleanupOldEvents() {
        val now = System.currentTimeMillis()
        fireEvents.removeIf { now - it > RECENT_FIRE_MS * 2 }
        while (fireEvents.size > 50) fireEvents.poll()
    }
    
    fun reset() {
        currentSituation = SituationLevel.LOW
        situationConfidence = 0.5f
        hysteresisCounter = 0
        pendingSituation = null
        fireEvents.clear()
        currentHp = 100
        currentAmmo = 100
    }
    
    fun getStats() = SituationStats(currentSituation, analysisCount, fireEvents.size)
}

enum class SituationLevel { LOW, MEDIUM, HIGH, CRITICAL }
enum class ReasoningMode { SHORT, MEDIUM, LONG }
enum class RecommendedAction { ENGAGE_NEAREST, RETREAT_TO_COVER, REPOSITION, MOVE_TO_ADVANTAGE, ROTATE_TO_ZONE }

data class SituationAnalysis(
    val level: SituationLevel,
    val confidence: Float,
    val enemyCount: Int,
    val nearestEnemyDistance: Float,
    val threatsInArc: Int,
    val hasLowHP: Boolean,
    val hasLowAmmo: Boolean,
    val isInCombat: Boolean,
    val recommendedMode: ReasoningMode,
    val shouldUseFastAim: Boolean,
    val recommendedAction: RecommendedAction
)

private data class EnemyAnalysis(val enemyCount: Int, val nearestDistance: Float, val threatsInAimArc: Int)
data class SituationStats(val currentSituation: SituationLevel, val totalAnalyses: Int, val fireEventsInQueue: Int)
