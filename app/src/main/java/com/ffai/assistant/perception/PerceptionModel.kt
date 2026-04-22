package com.ffai.assistant.perception

import android.content.Context
import android.graphics.Bitmap
import com.ffai.assistant.utils.Logger
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * PerceptionModel - CNN TFLite INT8 para detección de enemigos, HUD y botones.
 *
 * Arquitectura: CNN 3 capas conv + 2 FC
 *   - Input: 160x96x3 (RGB) ByteBuffer
 *   - Output: Enemigos (heatmap 20x12) + HUD (4 valores) + Botones (8x3)
 *
 * Modelo: perception.tflite (~2MB INT8)
 */
class PerceptionModel(context: Context) {

    private var interpreter: Interpreter? = null
    private val modelFile: File

    // Buffers reutilizables
    private val inputBuffer: ByteBuffer
    private val outputBuffer: Array<*>

    var isLoaded: Boolean = false
        private set

    companion object {
        const val INPUT_WIDTH = 160
        const val INPUT_HEIGHT = 96
        const val INPUT_CHANNELS = 3
        const val ENEMY_GRID_W = 20
        const val ENEMY_GRID_H = 12
        const val MAX_BUTTONS = 8
    }

    init {
        val modelDir = File(context.getExternalFilesDir(null), "models")
        modelDir.mkdirs()
        modelFile = File(modelDir, "perception.tflite")

        // Pre-alloc buffers
        inputBuffer = ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS * 4)  // float32

        // Output: heatmap [1, 240] + HUD [1, 4] + buttons [1, 24]
        outputBuffer = arrayOf(
            Array(1) { FloatArray(ENEMY_GRID_W * ENEMY_GRID_H) },  // heatmap 20x12
            Array(1) { FloatArray(4) },                            // HUD: health, ammo, coins, armor
            Array(1) { FloatArray(MAX_BUTTONS * 3) }               // buttons: (x, y, confidence) * 8
        )

        if (modelFile.exists() && modelFile.length() > 0) {
            loadModel()
        } else {
            Logger.w("PerceptionModel: Model file not found at ${modelFile.absolutePath}")
            isLoaded = false
        }
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile(modelFile)

            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
                // GPU delegate opcional para Mali-G52
                // val gpuDelegate = GpuDelegate()
                // addDelegate(gpuDelegate)
            }

            interpreter = Interpreter(modelBuffer, options)
            isLoaded = true
            Logger.i("PerceptionModel: Loaded successfully (${modelFile.length()} bytes)")

        } catch (e: Exception) {
            Logger.e("PerceptionModel: Failed to load", e)
            isLoaded = false
        }
    }

    /**
     * Analiza un frame y extrae percepción.
     *
     * @param bitmap Frame RGB 160x96 (preprocesado)
     * @return PerceptionOutput con detecciones
     */
    fun analyze(bitmap: Bitmap): PerceptionOutput? {
        if (!isLoaded || interpreter == null) return null

        return try {
            val startTime = System.currentTimeMillis()

            // Preparar input (convertir bitmap a ByteBuffer)
            preprocessBitmap(bitmap)

            // Inferencia
            interpreter?.run(inputBuffer, outputBuffer)

            // Parsear outputs
            val heatmap = outputBuffer[0] as Array<FloatArray>
            val hud = outputBuffer[1] as Array<FloatArray>
            val buttons = outputBuffer[2] as Array<FloatArray>

            // Construir PerceptionOutput
            val enemies = parseEnemyHeatmap(heatmap[0])
            val hudState = parseHUD(hud[0])
            val buttonDetections = parseButtons(buttons[0])

            PerceptionOutput(
                enemies = enemies,
                hud = hudState,
                buttons = buttonDetections,
                processingTimeMs = System.currentTimeMillis() - startTime
            )

        } catch (e: Exception) {
            Logger.e("PerceptionModel: Inference error", e)
            null
        }
    }

    /**
     * Análisis rápido: solo detección de enemigos (más rápido, menos preciso).
     */
    fun analyzeQuick(bitmap: Bitmap): PerceptionOutput? {
        return analyze(bitmap)  // Por ahora, mismo path
    }

    private fun preprocessBitmap(bitmap: Bitmap) {
        inputBuffer.rewind()

        // Resize si es necesario
        val scaled = if (bitmap.width != INPUT_WIDTH || bitmap.height != INPUT_HEIGHT) {
            Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        } else {
            bitmap
        }

        // Convertir a RGB float32 normalizado [0,1]
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        inputBuffer.rewind()

        if (scaled !== bitmap) {
            scaled.recycle()
        }
    }

    private fun parseEnemyHeatmap(raw: FloatArray): EnemyHeatmap {
        val grid = raw.copyOf()

        // Encontrar centroides (NMS simple)
        val centroids = mutableListOf<EnemyCentroid>()
        val visited = BooleanArray(grid.size) { false }

        for (i in grid.indices) {
            if (grid[i] > 0.5f && !visited[i]) {
                // Encontrar región conectada
                val region = findConnectedRegion(grid, i, visited)
                if (region.isNotEmpty()) {
                    val (avgX, avgY, maxConf) = computeCentroid(region, grid)
                    val screenX = (avgX / ENEMY_GRID_W) * 1080f  // Asumir pantalla 1080
                    val screenY = (avgY / ENEMY_GRID_H) * 2400f  // Asumir pantalla 2400

                    centroids.add(EnemyCentroid(
                        cellX = avgX.toInt().coerceIn(0, ENEMY_GRID_W - 1),
                        cellY = avgY.toInt().coerceIn(0, ENEMY_GRID_H - 1),
                        screenX = screenX,
                        screenY = screenY,
                        confidence = maxConf,
                        size = region.size.toFloat() / (ENEMY_GRID_W * ENEMY_GRID_H)
                    ))
                }
            }
        }

        // Ordenar por confianza
        return EnemyHeatmap(
            grid = grid,
            centroids = centroids.sortedByDescending { it.confidence }
        )
    }

    private fun findConnectedRegion(grid: FloatArray, startIdx: Int, visited: BooleanArray): List<Int> {
        val region = mutableListOf<Int>()
        val stack = mutableListOf(startIdx)

        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            if (visited[idx] || grid[idx] <= 0.3f) continue

            visited[idx] = true
            region.add(idx)

            // Vecinos 4-conectados
            val x = idx % ENEMY_GRID_W
            val y = idx / ENEMY_GRID_W

            if (x > 0) stack.add(idx - 1)
            if (x < ENEMY_GRID_W - 1) stack.add(idx + 1)
            if (y > 0) stack.add(idx - ENEMY_GRID_W)
            if (y < ENEMY_GRID_H - 1) stack.add(idx + ENEMY_GRID_W)
        }

        return region
    }

    private fun computeCentroid(region: List<Int>, grid: FloatArray): Triple<Float, Float, Float> {
        var sumX = 0f
        var sumY = 0f
        var totalConf = 0f
        var maxConf = 0f

        for (idx in region) {
            val x = idx % ENEMY_GRID_W
            val y = idx / ENEMY_GRID_W
            val conf = grid[idx]

            sumX += x * conf
            sumY += y * conf
            totalConf += conf
            maxConf = maxOf(maxConf, conf)
        }

        val avgX = if (totalConf > 0) sumX / totalConf else region.map { it % ENEMY_GRID_W }.average().toFloat()
        val avgY = if (totalConf > 0) sumY / totalConf else region.map { it / ENEMY_GRID_W }.average().toFloat()

        return Triple(avgX, avgY, maxConf)
    }

    private fun parseHUD(raw: FloatArray): HUDState {
        return HUDState(
            health = raw[0].coerceIn(0f, 1f),
            ammo = raw[1].coerceIn(0f, 1f),
            coins = (raw[2] * 1000).toInt(),  // Normalizar a monedas
            armor = raw[3].coerceIn(0f, 1f)
        )
    }

    private fun parseButtons(raw: FloatArray): List<ButtonDetection> {
        val buttons = mutableListOf<ButtonDetection>()

        for (i in 0 until MAX_BUTTONS) {
            val offset = i * 3
            val x = raw[offset]
            val y = raw[offset + 1]
            val conf = raw[offset + 2]

            if (conf > 0.5f && x > 0f && y > 0f) {
                buttons.add(ButtonDetection(
                    type = ButtonType.UNKNOWN,  // El modelo no predice tipo, solo ubicación
                    screenX = x * 1080f,
                    screenY = y * 2400f,
                    confidence = conf
                ))
            }
        }

        return buttons
    }

    fun updateModel(newModelBytes: ByteArray): Boolean {
        return try {
            val backupFile = File(modelFile.parent, "perception_backup.tflite")
            if (modelFile.exists()) {
                modelFile.copyTo(backupFile, overwrite = true)
            }

            modelFile.writeBytes(newModelBytes)
            interpreter?.close()
            loadModel()

            Logger.i("PerceptionModel: Updated with ${newModelBytes.size} bytes")
            isLoaded
        } catch (e: Exception) {
            Logger.e("PerceptionModel: Update failed", e)
            false
        }
    }

    fun isAvailable(): Boolean = isLoaded && interpreter != null

    fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }

    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        FileInputStream(modelFile).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
        }
    }
}
