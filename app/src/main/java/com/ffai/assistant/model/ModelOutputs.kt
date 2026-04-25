package com.ffai.assistant.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * FASE 2: Definiciones de salidas de los modelos del ensemble.
 * Clases de datos para los outputs de cada modelo especializado.
 */

// ============================================
// COMBAT NET - Detección y combate
// ============================================

data class CombatOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val enemies: List<EnemyDetection>,
    val aimTarget: AimTarget?,
    val suggestedAction: ActionSuggestion?,
    override val confidence: Float,
    val threatLevel: ThreatLevel
) : ModelOutput(confidence)

data class EnemyDetection(
    val id: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val distance: Float, // Estimada en metros
    val isLocked: Boolean,
    val predictedPosition: PredictedPosition?,
    val bodyParts: Map<BodyPart, Pair<Float, Float>> = emptyMap() // x,y de partes del cuerpo
)

data class AimTarget(
    val x: Float,
    val y: Float,
    val targetId: Int?,
    val confidence: Float,
    val recommendedFireMode: FireMode,
    val leadCompensation: Pair<Float, Float> // x,y lead
)

data class PredictedPosition(
    val x: Float,
    val y: Float,
    val timeMs: Int // Cuántos ms en el futuro
)

enum class ThreatLevel {
    NONE, LOW, MEDIUM, HIGH, CRITICAL
}

enum class FireMode {
    SINGLE, BURST, AUTO
}

enum class BodyPart {
    HEAD, CHEST, STOMACH, LIMBS
}

// ============================================
// TACTICAL NET - Decisiones tácticas
// ============================================

data class TacticalOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val situation: TacticalSituation,
    val suggestedAction: ActionSuggestion?,
    val alternativeActions: List<ActionSuggestion>,
    override val confidence: Float,
    val requiresStrategicUpdate: Boolean,
    val recommendedStance: Stance,
    val lootPriority: List<LootPriority>,
    val coverRecommendations: List<CoverRecommendation>
) : ModelOutput(confidence)

data class TacticalSituation(
    val type: SituationType,
    val dangerLevel: Float, // 0-1
    val allyProximity: Float, // 0-1 (0 = solo, 1 = rodeado aliados)
    val enemyProximity: Float, // 0-1
    val hasHighGround: Boolean,
    val inZone: Boolean,
    val timeToNextZone: Int? // segundos
)

enum class SituationType {
    SAFE_LOOTING, AMBUSH_RISK, ACTIVE_COMBAT, RETREAT_NEEDED,
    HEALING_WINDOW, RELOAD_NEEDED, POSITIONING_ADVANTAGE, ZONE_RUSH
}

enum class Stance {
    STANDING, CROUCHING, PRONE, MOVING
}

data class LootPriority(
    val itemType: ItemType,
    val priority: Float, // 0-1
    val distance: Float
)

data class CoverRecommendation(
    val direction: Float, // ángulo en grados
    val distance: Float,
    val quality: Float // 0-1
)

enum class ItemType {
    WEAPON_AR, WEAPON_SNIPER, WEAPON_SHOTGUN, WEAPON_SMG,
    ARMOR_LVL3, ARMOR_LVL2, ARMOR_LVL1,
    HELMET_LVL3, HELMET_LVL2, HELMET_LVL1,
    BACKPACK_LVL3, BACKPACK_LVL2, BACKPACK_LVL1,
    HEALTH_MEDKIT, HEALTH_BANDAGE, HEALTH_FIRSTAID,
    BOOST_ADRENALINE, BOOST_PAINKILLER, BOOST_ENERGY,
    AMMO, ATTACHMENTS, THROWABLE_GRENADE, THROWABLE_SMOKE
}

// ============================================
// STRATEGY NET - Estrategia macro
// ============================================

data class StrategyOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val currentPhase: GamePhase,
    val recommendedRoute: List<Pair<Float, Float>>?, // Puntos de ruta x,y
    val nextPosition: Pair<Float, Float>?,
    val rotationTiming: Int?, // segundos hasta rotar
    val riskAssessment: RiskAssessment,
    val lootRoute: List<Pair<Float, Float>>?,
    override val confidence: Float
) : ModelOutput(confidence)

data class RiskAssessment(
    val overallRisk: Float, // 0-1
    val riskByDirection: Map<Float, Float>, // ángulo -> riesgo
    val safeDirections: List<Float>,
    val deathProbability: Float, // 0-1
    val winProbability: Float // 0-1
)

enum class GamePhase {
    EARLY_GAME, MID_GAME, LATE_GAME, FINAL_CIRCLE
}

// ============================================
// VISION NET - Análisis visual profundo
// ============================================

data class VisionOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val objects: List<DetectedObject>,
    val terrain: TerrainAnalysis,
    val visibility: VisibilityAnalysis,
    val suggestedAction: ActionSuggestion?,
    override val confidence: Float
) : ModelOutput(confidence)

data class DetectedObject(
    val id: Int,
    val type: DetectedObjectType,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val isOccluded: Boolean
)

enum class DetectedObjectType {
    ENEMY, ALLY, VEHICLE, LOOT_CRATE, AIRDROP, SUPPLY_BOX,
    COVER_WALL, COVER_ROCK, COVER_TREE, COVER_BUILDING,
    DOOR_OPEN, DOOR_CLOSED, WINDOW, STAIRS, LADDER,
    ZONE_CIRCLE, ZONE_BOUNDARY, RED_ZONE
}

data class TerrainAnalysis(
    val elevation: Float, // 0-1 (0 = bajo, 1 = alto)
    val coverAvailability: Float, // 0-1
    val openExposure: Float, // 0-1
    val waterNearby: Boolean,
    val vehicleSpawnProbability: Float // 0-1
)

data class VisibilityAnalysis(
    val canSeeEnemies: Boolean,
    val canBeSeen: Boolean,
    val fogLevel: Float, // 0-1
    val lightLevel: Float, // 0-1 (0 = noche, 1 = día)
    val weather: WeatherType
)

enum class WeatherType {
    CLEAR, FOGGY, RAINY, SUNSET, NIGHT
}

// ============================================
// UI NET - OCR y análisis de interfaz
// ============================================

data class UIOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val hpInfo: HPInfo?,
    val ammoInfo: AmmoInfo?,
    val weaponInfo: WeaponInfo?,
    val minimapVisible: Boolean,
    val isInMenu: Boolean,
    val menuType: MenuType?,
    val killFeed: List<KillEvent>,
    val gameState: GameState,
    override val confidence: Float
) : ModelOutput(confidence)

data class HPInfo(
    val current: Int, // 0-100
    val max: Int,
    val status: HPStatus,
    val boostLevel: Float // 0-1
)

enum class HPStatus {
    FULL, HEALTHY, INJURED, CRITICAL, DYING
}

data class AmmoInfo(
    val currentMag: Int,
    val totalRemaining: Int,
    val weaponType: String
)

data class WeaponInfo(
    val primary: String?,
    val secondary: String?,
    val currentWeapon: String,
    val fireMode: String,
    val attachments: List<String>
)

enum class MenuType {
    INVENTORY, MAP, SETTINGS, LOADING, PAUSE, SHOP, NONE
}

data class KillEvent(
    val timestamp: Long,
    val killer: String?,
    val victim: String,
    val weapon: String?,
    val isOwnKill: Boolean
)

data class GameState(
    val state: State,
    val playersRemaining: Int?,
    val teamAliveCount: Int?
)

enum class State {
    LOBBY, AIRPLANE, PARACHUTING, GROUND, DRIVING, SPECTATING, DEAD, VICTORY
}

// ============================================
// MAP NET - Interpretación de mapa
// ============================================

data class MapOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val playerPosition: Pair<Float, Float>?, // x,y normalizados 0-1
    val currentSafeZone: Zone?,
    val nextSafeZone: ZonePrediction?,
    val markedLocations: List<MarkedLocation>,
    val teammatePositions: List<Pair<Float, Float>>,
    override val confidence: Float
) : ModelOutput(confidence)

data class Zone(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val timeRemaining: Int? // segundos
)

data class ZonePrediction(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val probability: Float // 0-1
)

data class MarkedLocation(
    val x: Float,
    val y: Float,
    val type: MarkerType,
    val label: String?
)

enum class MarkerType {
    ENEMY, LOOT, VEHICLE, DANGER, WAYPOINT, AIRDROP
}

// ============================================
// RECOIL NET - Compensación de recoil
// ============================================

data class RecoilOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val weaponType: String,
    val recoilPattern: RecoilPattern,
    val compensationX: Float,
    val compensationY: Float,
    val burstRecommendation: Int, // tamaño de ráfaga recomendada
    override val confidence: Float
) : ModelOutput(confidence)

data class RecoilPattern(
    val verticalRecoil: List<Float>, // por bullet
    val horizontalRecoil: List<Float>, // por bullet
    val patternLength: Int,
    val weaponName: String
)

// ============================================
// CONFIDENCE NET - Evaluación de confianza
// ============================================

data class ConfidenceOutput(
    val timestamp: Long,
    val inferenceTimeMs: Long,
    val confidenceScore: Float, // 0-1
    val uncertaintyFactors: List<UncertaintyFactor>,
    val recommendation: String?, // qué hacer si confianza baja
    override val confidence: Float
) : ModelOutput(confidence)

data class UncertaintyFactor(
    val factor: String,
    val impact: Float // 0-1, cuánto reduce la confianza
)

// ============================================
// CLASE BASE
// ============================================

abstract class ModelOutput(
    open val confidence: Float
)
