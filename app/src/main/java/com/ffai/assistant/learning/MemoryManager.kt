package com.ffai.assistant.learning

import android.content.Context
import com.ffai.assistant.action.Action
import com.ffai.assistant.core.Experience
import com.ffai.assistant.perception.GameState
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * MemoryManager - Gestiona memoria de experiencias y sincronización con nube.
 * PER buffer → SQLite → batch compression → upload → Colab training → model download
 */
class MemoryManager(
    context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val experienceBuffer = ExperienceBuffer(capacity = 10000)
    private val database = LearningDatabase(context)

    var batchSize: Int = 1000
    var uploadIntervalMinutes: Long = 5

    private var pendingExperiences: Int = 0
    private var lastUploadTime: Long = 0
    private var isUploading: Boolean = false
    var currentModelVersion: String = "v0"

    var onBatchReady: ((ByteArray) -> Unit)? = null
    var onModelDownloaded: ((ByteArray) -> Unit)? = null

    private var totalExperiences: Long = 0
    private var totalUploads: Long = 0

    init {
        coroutineScope.launch {
            val saved = database.loadRecentExperiences(5000)
            saved.forEach { experienceBuffer.add(it) }
            totalExperiences = database.getExperienceCount()
            Logger.i("MemoryManager: Loaded ${saved.size} experiences")
        }
        startPeriodicUpload()
    }

    fun recordExperience(state: GameState, action: Action, reward: Float, nextState: GameState, done: Boolean = false, tdError: Float = kotlin.math.abs(reward)) {
        val exp = Experience(state = state, action = action, reward = reward, nextState = nextState, done = done)
        experienceBuffer.add(exp, tdError)
        coroutineScope.launch {
            database.saveExperience(exp)
            totalExperiences++
            pendingExperiences++
            checkAndTriggerUpload()
        }
    }

    fun calculateReward(prev: GameState, curr: GameState, action: Action): Float {
        var r = 0f
        r += (curr.healthRatio - prev.healthRatio) * 50f
        r += (curr.kills - prev.kills) * 100f
        r += (curr.damageDealt - prev.damageDealt) * 0.5f
        if (curr.ammoRatio > prev.ammoRatio) r += 5f
        if (curr.placement < prev.placement) r += 10f
        if (curr.isDead && !prev.isDead) r -= 100f
        if (curr.placement == 1) r += 500f
        return r
    }

    fun sampleForTraining(batchSize: Int = 32): List<Experience> {
        return experienceBuffer.sample(batchSize).experiences
    }

    fun getStats(): MemoryStats {
        return MemoryStats(bufferSize = experienceBuffer.size(), totalExperiences = totalExperiences, pendingUpload = pendingExperiences, currentModelVersion = currentModelVersion)
    }

    private fun startPeriodicUpload() {
        coroutineScope.launch {
            while (isActive) {
                delay(uploadIntervalMinutes * 60 * 1000)
                if (pendingExperiences > 0 && !isUploading) {
                    uploadBatch()
                }
            }
        }
    }

    private suspend fun checkAndTriggerUpload() {
        if (pendingExperiences >= batchSize && !isUploading) {
            uploadBatch()
        }
    }

    private suspend fun uploadBatch() {
        if (isUploading) return
        isUploading = true
        try {
            val batch = experienceBuffer.getRecent(batchSize)
            if (batch.isNotEmpty()) {
                val compressed = compressExperiences(batch)
                onBatchReady?.invoke(compressed)
                pendingExperiences = (pendingExperiences - batch.size).coerceAtLeast(0)
                totalUploads++
                lastUploadTime = System.currentTimeMillis()
                Logger.i("MemoryManager: Uploaded batch of ${batch.size} experiences (${compressed.size} bytes)")
            }
        } catch (e: Exception) {
            Logger.e("MemoryManager: Upload failed", e)
        } finally {
            isUploading = false
        }
    }

    fun onNewModelDownloaded(modelBytes: ByteArray, version: String) {
        currentModelVersion = version
        totalDownloads++
        onModelDownloaded?.invoke(modelBytes)
        Logger.i("MemoryManager: Downloaded model $version (${modelBytes.size} bytes)")
    }

    private fun compressExperiences(experiences: List<Experience>): ByteArray {
        val baos = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            val json = experiencesToJson(experiences)
            val input = json.toByteArray(Charsets.UTF_8)
            deflater.setInput(input)
            deflater.finish()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                baos.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }
        return baos.toByteArray()
    }

    private fun experiencesToJson(experiences: List<Experience>): String {
        val sb = StringBuilder("[")
        experiences.forEachIndexed { i, exp ->
            if (i > 0) sb.append(",")
            sb.append("{").append("\"r\":${exp.reward},")
            sb.append("\"a\":${exp.action.type.ordinal},")
            sb.append("\"d\":${if (exp.done) 1 else 0},")
            sb.append("\"h\":${exp.state.healthRatio},")
            sb.append("\"e\":${if (exp.state.enemyPresent) 1 else 0}")
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    fun destroy() {
        coroutineScope.cancel()
        database.close()
    }

    data class MemoryStats(val bufferSize: Int, val totalExperiences: Long, val pendingUpload: Int, val currentModelVersion: String)
}
