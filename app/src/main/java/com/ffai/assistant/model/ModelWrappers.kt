package com.ffai.assistant.model

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.overlay.FrameData
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

/**
 * FASE 2: Wrappers para los 8 modelos del ensemble.
 * Cada modelo es un wrapper alrededor de TFLite Interpreter.
 * 
 * Modelos:
 * 1. CombatNet (25MB) - Detección enemigos y combate
 * 2. TacticalNet (20MB) - Decisiones tácticas
 * 3. StrategyNet (15MB) - Estrategia macro
 * 4. VisionNet (18MB) - Análisis visual
 * 5. UINet (12MB) - OCR y UI
 * 6. MapNet (15MB) - Interpretación mapa
 * 7. RecoilNet (8MB) - Compensación recoil
 * 8. ConfidenceNet (7MB) - Evaluación confianza
 */

// ============================================
// COMBAT NET
// ============================================

class CombatNet(private val context: Context) {
    companion object {
        const val TAG = "CombatNet"
        const val MODEL_NAME = "combatnet_v1.tflite"
        const val INPUT_SIZE = 320
        const val NUM_DETECTIONS = 10
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false) // Deshabilitar NNAPI para compatibilidad
            })
            isLoaded = true
            Logger.i(TAG, "CombatNet cargado: ${INPUT_SIZE}x${INPUT_SIZE}")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando CombatNet", e)
            throw e
        }
    }
    
    suspend fun predict(frameData: FrameData): CombatOutput {
        val startTime = System.currentTimeMillis()
        
        // Preprocesar imagen
        val input = preprocessImage(frameData.bitmap, INPUT_SIZE)
        
        // Output arrays [batch, num_detections, 6] -> [x, y, w, h, confidence, class]
        val output = Array(1) { Array(NUM_DETECTIONS) { FloatArray(6) } }
        
        interpreter?.run(input, output)
        
        // Parsear detecciones
        val enemies = parseDetections(output[0], frameData)
        
        // Seleccionar mejor aim target
        val aimTarget = selectAimTarget(enemies)
        
        // Calcular threat level
        val threatLevel = calculateThreatLevel(enemies)
        
        // Sugerir acción
        val suggestedAction = when {
            enemies.isEmpty() -> ActionSuggestion(ActionType.HOLD, 0.5f)
            aimTarget != null && aimTarget.confidence > 0.7f -> 
                ActionSuggestion(ActionType.SHOOT, aimTarget.confidence)
            else -> ActionSuggestion(ActionType.AIM, 0.6f)
        }
        
        return CombatOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            enemies = enemies,
            aimTarget = aimTarget,
            suggestedAction = suggestedAction,
            confidence = enemies.maxOfOrNull { it.confidence } ?: 0f,
            threatLevel = threatLevel
        )
    }
    
    suspend fun predictFast(frameData: FrameData): CombatOutput {
        // Versión rápida: solo 5 detecciones, sin predicción
        val startTime = System.currentTimeMillis()
        
        val input = preprocessImage(frameData.bitmap, INPUT_SIZE)
        val output = Array(1) { Array(5) { FloatArray(6) } }
        
        interpreter?.run(input, output)
        
        val enemies = parseDetections(output[0], frameData).take(3)
        val aimTarget = enemies.maxByOrNull { it.confidence }?.let {
            AimTarget(it.x, it.y, it.id, it.confidence, FireMode.AUTO, Pair(0f, 0f))
        }
        
        return CombatOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            enemies = enemies,
            aimTarget = aimTarget,
            suggestedAction = if (enemies.isNotEmpty()) ActionSuggestion(ActionType.AIM, 0.6f) else null,
            confidence = enemies.maxOfOrNull { it.confidence } ?: 0f,
            threatLevel = calculateThreatLevel(enemies)
        )
    }
    
    private fun parseDetections(raw: Array<FloatArray>, frameData: FrameData): List<EnemyDetection> {
        val enemies = mutableListOf<EnemyDetection>()
        
        raw.forEachIndexed { index, detection ->
            val confidence = detection[4]
            if (confidence > 0.3f) {
                enemies.add(EnemyDetection(
                    id = index,
                    x = detection[0] * frameData.bitmap.width,
                    y = detection[1] * frameData.bitmap.height,
                    width = detection[2] * frameData.bitmap.width,
                    height = detection[3] * frameData.bitmap.height,
                    confidence = confidence,
                    distance = estimateDistance(detection[3]),
                    isLocked = confidence > 0.8f,
                    predictedPosition = null
                ))
            }
        }
        
        return enemies.sortedByDescending { it.confidence }
    }
    
    private fun selectAimTarget(enemies: List<EnemyDetection>): AimTarget? {
        val target = enemies.maxByOrNull { it.confidence } ?: return null
        
        return AimTarget(
            x = target.x,
            y = target.y,
            targetId = target.id,
            confidence = target.confidence,
            recommendedFireMode = if (target.distance < 30f) FireMode.AUTO else FireMode.SINGLE,
            leadCompensation = Pair(0f, 0f) // TODO: calcular lead
        )
    }
    
    private fun calculateThreatLevel(enemies: List<EnemyDetection>): ThreatLevel {
        return when {
            enemies.isEmpty() -> ThreatLevel.NONE
            enemies.any { it.distance < 20f && it.confidence > 0.7f } -> ThreatLevel.CRITICAL
            enemies.any { it.distance < 50f && it.confidence > 0.7f } -> ThreatLevel.HIGH
            enemies.size > 2 -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
    }
    
    private fun estimateDistance(relativeHeight: Float): Float {
        // Estimación simple basada en altura relativa
        return (1.7f / relativeHeight).coerceIn(5f, 300f)
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// TACTICAL NET
// ============================================

class TacticalNet(private val context: Context) {
    companion object {
        const val TAG = "TacticalNet"
        const val MODEL_NAME = "tacticalnet_v1.tflite"
        const val INPUT_FEATURES = 64
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false) // Deshabilitar NNAPI para compatibilidad A21S
            })
            isLoaded = true
            Logger.i(TAG, "TacticalNet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando TacticalNet", e)
        }
    }
    
    suspend fun predict(frameData: FrameData): TacticalOutput {
        val startTime = System.currentTimeMillis()
        
        // Extraer features de estado
        val features = extractTacticalFeatures(frameData)
        val input = Array(1) { features }
        
        // Output: [probabilidades de situación, acción recomendada]
        val situationOutput = Array(1) { FloatArray(8) } // 8 tipos de situación
        val actionOutput = Array(1) { FloatArray(15) } // 15 acciones posibles
        
        interpreter?.runForMultipleInputsOutputs(
            arrayOf(input),
            mapOf(0 to situationOutput, 1 to actionOutput)
        )
        
        val situation = parseSituation(situationOutput[0])
        val action = parseAction(actionOutput[0])
        
        return TacticalOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            situation = situation,
            suggestedAction = action,
            alternativeActions = emptyList(),
            confidence = actionOutput[0].maxOrNull() ?: 0.5f,
            requiresStrategicUpdate = situation.dangerLevel > 0.7f,
            recommendedStance = Stance.CROUCHING,
            lootPriority = emptyList(),
            coverRecommendations = emptyList()
        )
    }
    
    private fun extractTacticalFeatures(frameData: FrameData): FloatArray {
        // Features: hp, ammo, enemies, allies, distance to zone, etc.
        return FloatArray(INPUT_FEATURES) { 0.5f } // Placeholder
    }
    
    private fun parseSituation(probs: FloatArray): TacticalSituation {
        val maxIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
        val types = SituationType.values()
        
        return TacticalSituation(
            type = if (maxIndex < types.size) types[maxIndex] else SituationType.POSITIONING_ADVANTAGE,
            dangerLevel = 1f - probs[maxIndex],
            allyProximity = 0.5f,
            enemyProximity = probs[maxIndex],
            hasHighGround = false,
            inZone = true,
            timeToNextZone = null
        )
    }
    
    private fun parseAction(probs: FloatArray): ActionSuggestion {
        val maxIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
        val types = ActionType.values()
        
        return ActionSuggestion(
            type = if (maxIndex < types.size) types[maxIndex] else ActionType.HOLD,
            confidence = probs[maxIndex]
        )
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// STRATEGY NET
// ============================================

class StrategyNet(private val context: Context) {
    companion object {
        const val TAG = "StrategyNet"
        const val MODEL_NAME = "strategynet_v1.tflite"
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false)
            })
            isLoaded = true
            Logger.i(TAG, "StrategyNet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando StrategyNet", e)
        }
    }
    
    suspend fun predict(ensembleResult: EnsembleResult): StrategyOutput {
        val startTime = System.currentTimeMillis()
        
        val features = extractStrategyFeatures(ensembleResult)
        val input = Array(1) { features }
        val output = Array(1) { FloatArray(32) } // Rutas, riesgos, etc.
        
        interpreter?.run(input, output)
        
        return StrategyOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            currentPhase = GamePhase.MID_GAME,
            recommendedRoute = null,
            nextPosition = null,
            rotationTiming = null,
            riskAssessment = RiskAssessment(0.5f, emptyMap(), emptyList(), 0.1f, 0.1f),
            lootRoute = null,
            confidence = 0.6f
        )
    }
    
    private fun extractStrategyFeatures(result: EnsembleResult): FloatArray {
        return FloatArray(32) { 0.5f } // Placeholder
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// VISION NET
// ============================================

class VisionNet(private val context: Context) {
    companion object {
        const val TAG = "VisionNet"
        const val MODEL_NAME = "visionnet_v1.tflite"
        const val INPUT_SIZE = 224
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false)
            })
            isLoaded = true
            Logger.i(TAG, "VisionNet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando VisionNet", e)
        }
    }
    
    suspend fun predict(frameData: FrameData): VisionOutput {
        val startTime = System.currentTimeMillis()
        
        val input = preprocessImage(frameData.bitmap, INPUT_SIZE)
        val output = Array(1) { Array(20) { FloatArray(6) } } // 20 objetos max
        
        interpreter?.run(input, output)
        
        val objects = parseObjects(output[0], frameData)
        
        return VisionOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            objects = objects,
            terrain = TerrainAnalysis(0.5f, 0.5f, 0.5f, false, 0.3f),
            visibility = VisibilityAnalysis(true, false, 0f, 1f, WeatherType.CLEAR),
            suggestedAction = null,
            confidence = 0.6f
        )
    }
    
    private fun parseObjects(raw: Array<FloatArray>, frameData: FrameData): List<DetectedObject> {
        return raw.filter { it[4] > 0.3f }.mapIndexed { index, detection ->
            DetectedObject(
                id = index,
                type = DetectedObjectType.values().getOrElse(detection[5].toInt()) { DetectedObjectType.ENEMY },
                x = detection[0] * frameData.bitmap.width,
                y = detection[1] * frameData.bitmap.height,
                width = detection[2] * frameData.bitmap.width,
                height = detection[3] * frameData.bitmap.height,
                confidence = detection[4],
                isOccluded = false
            )
        }
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// UI NET
// ============================================

class UINet(private val context: Context) {
    companion object {
        const val TAG = "UINet"
        const val MODEL_NAME = "uinet_v1.tflite"
        const val INPUT_SIZE = 128
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false)
            })
            isLoaded = true
            Logger.i(TAG, "UINet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando UINet", e)
        }
    }
    
    suspend fun predict(frameData: FrameData): UIOutput {
        val startTime = System.currentTimeMillis()
        
        // Analizar zona HUD
        val hudBitmap = frameData.extractZone(frameData.zones.hud)
        val input = preprocessImage(hudBitmap, INPUT_SIZE)
        
        val output = Array(1) { FloatArray(20) } // HP, ammo, estado, etc.
        interpreter?.run(input, output)
        
        val hp = (output[0][0] * 100).toInt()
        val ammo = (output[0][1] * 200).toInt()
        
        return UIOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            hpInfo = HPInfo(hp, 100, if (hp > 75) HPStatus.HEALTHY else if (hp > 50) HPStatus.INJURED else HPStatus.CRITICAL, 0f),
            ammoInfo = AmmoInfo(ammo, ammo * 3, "AR"),
            weaponInfo = WeaponInfo("M416", null, "M416", "AUTO", emptyList()),
            minimapVisible = output[0][2] > 0.5f,
            isInMenu = output[0][3] > 0.7f,
            menuType = if (output[0][3] > 0.7f) MenuType.INVENTORY else null,
            killFeed = emptyList(),
            gameState = GameState(State.GROUND, 50, 4),
            confidence = 0.7f
        )
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// MAP NET
// ============================================

class MapNet(private val context: Context) {
    companion object {
        const val TAG = "MapNet"
        const val MODEL_NAME = "mapnet_v1.tflite"
        const val INPUT_SIZE = 256
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false)
            })
            isLoaded = true
            Logger.i(TAG, "MapNet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando MapNet", e)
        }
    }
    
    suspend fun predict(mapBitmap: Bitmap): MapOutput {
        val startTime = System.currentTimeMillis()
        
        val input = preprocessImage(mapBitmap, INPUT_SIZE)
        val output = Array(1) { FloatArray(10) } // x, y, zonas, etc.
        
        interpreter?.run(input, output)
        
        return MapOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            playerPosition = Pair(output[0][0], output[0][1]),
            currentSafeZone = Zone(0.5f, 0.5f, 0.3f, 60),
            nextSafeZone = ZonePrediction(0.5f, 0.5f, 0.2f, 0.8f),
            markedLocations = emptyList(),
            teammatePositions = emptyList(),
            confidence = 0.75f
        )
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// RECOIL NET
// ============================================

class RecoilNet(private val context: Context) {
    companion object {
        const val TAG = "RecoilNet"
        const val MODEL_NAME = "recoilnet_v1.tflite"
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false)
            })
            isLoaded = true
            Logger.i(TAG, "RecoilNet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando RecoilNet", e)
        }
    }
    
    suspend fun predict(frameData: FrameData): RecoilOutput {
        val startTime = System.currentTimeMillis()
        
        val input = Array(1) { FloatArray(10) } // Weapon features
        val output = Array(1) { FloatArray(20) } // Recoil pattern
        
        interpreter?.run(input, output)
        
        val pattern = RecoilPattern(
            verticalRecoil = output[0].take(10).toList(),
            horizontalRecoil = output[0].drop(10).toList(),
            patternLength = 10,
            weaponName = "M416"
        )
        
        return RecoilOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            weaponType = "AR",
            recoilPattern = pattern,
            compensationX = 0f,
            compensationY = pattern.verticalRecoil.firstOrNull() ?: 0.1f,
            burstRecommendation = 3,
            confidence = 0.8f
        )
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// CONFIDENCE NET
// ============================================

class ConfidenceNet(private val context: Context) {
    companion object {
        const val TAG = "ConfidenceNet"
        const val MODEL_NAME = "confidencenet_v1.tflite"
    }
    
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    
    fun initialize() {
        try {
            val model = loadModelFile(context, MODEL_NAME)
            interpreter = Interpreter(model, Interpreter.Options().apply {
                setNumThreads(2)
                useNNAPI(false)
            })
            isLoaded = true
            Logger.i(TAG, "ConfidenceNet cargado")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cargando ConfidenceNet", e)
        }
    }
    
    suspend fun predict(frameData: FrameData): ConfidenceOutput {
        val startTime = System.currentTimeMillis()
        
        val input = Array(1) { FloatArray(16) } // Features de incertidumbre
        val output = Array(1) { FloatArray(2) } // [confidence, uncertainty]
        
        interpreter?.run(input, output)
        
        val confidence = output[0][0]
        val uncertainty = output[0][1]
        
        return ConfidenceOutput(
            timestamp = System.currentTimeMillis(),
            inferenceTimeMs = System.currentTimeMillis() - startTime,
            confidenceScore = confidence,
            uncertaintyFactors = listOf(UncertaintyFactor("model_variance", uncertainty)),
            recommendation = if (confidence < 0.5f) "Use more conservative actions" else null,
            confidence = confidence
        )
    }
    
    suspend fun predictFast(frameData: FrameData): ConfidenceOutput {
        // Versión ultra rápida para modo corto
        return predict(frameData).copy(inferenceTimeMs = 1)
    }
    
    fun isLoaded(): Boolean = isLoaded
    
    fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}

// ============================================
// FUNCIONES UTILITARIAS
// ============================================

private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
    val fileDescriptor = context.assets.openFd(modelName)
    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel
    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
}

private fun preprocessImage(bitmap: Bitmap, inputSize: Int): Array<Array<Array<FloatArray>>> {
    val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
    val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
    
    val intValues = IntArray(inputSize * inputSize)
    resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
    
    for (i in 0 until inputSize) {
        for (j in 0 until inputSize) {
            val pixel = intValues[i * inputSize + j]
            input[0][i][j][0] = ((pixel shr 16 and 0xFF) / 255.0f)
            input[0][i][j][1] = ((pixel shr 8 and 0xFF) / 255.0f)
            input[0][i][j][2] = ((pixel and 0xFF) / 255.0f)
        }
    }
    
    return input
}
