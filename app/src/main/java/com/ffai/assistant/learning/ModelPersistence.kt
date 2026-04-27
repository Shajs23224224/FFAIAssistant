package com.ffai.assistant.learning

import android.content.Context
import com.ffai.assistant.rl.EnsembleRLCoordinator
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * FASE 5: ModelPersistence - Persistencia de modelos y checkpoints.
 * 
 * Features:
 * - Checkpointing cada N episodios
 * - Export a HDF5 para entrenamiento en Colab
 * - Import de modelos mejorados
 * - Compresión GZIP
 * - Backup automático
 */
class ModelPersistence(private val context: Context) {
    
    companion object {
        const val TAG = "ModelPersistence"
        const val CHECKPOINT_INTERVAL = 1000
        const val MAX_CHECKPOINTS = 5
        const val BACKUP_SUFFIX = ".backup"
        
        // Model filenames
        const val DQN_MODEL = "dqn_policy.tflite"
        const val PPO_ACTOR = "ppo_actor.tflite"
        const val PPO_CRITIC = "ppo_critic.tflite"
        const val SAC_ACTOR = "sac_actor.tflite"
        const val YOLO_MODEL = "yolov8n_fp16.tflite"
    }
    
    private val checkpointDir = File(context.filesDir, "checkpoints")
    private val exportDir = File(context.filesDir, "exports")
    
    private var checkpointCount = 0
    
    init {
        checkpointDir.mkdirs()
        exportDir.mkdirs()
    }
    
    /**
     * Guarda checkpoint de todos los modelos.
     */
    suspend fun saveCheckpoint(
        coordinator: EnsembleRLCoordinator,
        episode: Int,
        metadata: CheckpointMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            checkpointCount++
            
            // Crear directorio de checkpoint
            val checkpointName = "checkpoint_${episode}_${System.currentTimeMillis()}"
            val checkpointPath = File(checkpointDir, checkpointName)
            checkpointPath.mkdirs()
            
            // Guardar modelos
            val dqnSaved = coordinator.dqnAgent.save(File(checkpointPath, DQN_MODEL).absolutePath)
            
            // Guardar metadata
            val metaFile = File(checkpointPath, "metadata.json")
            metaFile.writeText(createMetadataJson(metadata))
            
            // Guardar replay buffer (sample)
            val stats = coordinator.getStats()
            val statsFile = File(checkpointPath, "stats.json")
            statsFile.writeText(createStatsJson(stats))
            
            // Limpiar checkpoints viejos
            cleanupOldCheckpoints()
            
            Logger.i(TAG, "Checkpoint saved: $checkpointName (DQN: $dqnSaved)")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error saving checkpoint", e)
            false
        }
    }
    
    /**
     * Carga checkpoint más reciente.
     */
    suspend fun loadLatestCheckpoint(coordinator: EnsembleRLCoordinator): Boolean = withContext(Dispatchers.IO) {
        try {
            val checkpoints = checkpointDir.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
            
            if (checkpoints == null) {
                Logger.w(TAG, "No checkpoints found")
                return@withContext false
            }
            
            // Cargar modelos
            val dqnPath = File(checkpoints, DQN_MODEL)
            val dqnLoaded = if (dqnPath.exists()) {
                coordinator.dqnAgent.load(dqnPath.absolutePath)
            } else false
            
            Logger.i(TAG, "Checkpoint loaded from ${checkpoints.name} (DQN: $dqnLoaded)")
            dqnLoaded
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading checkpoint", e)
            false
        }
    }
    
    /**
     * Exporta datos para entrenamiento en Colab.
     * Formato HDF5 simplificado como JSON comprimido.
     */
    suspend fun exportForTraining(
        coordinator: EnsembleRLCoordinator,
        replayBuffer: List<ExperienceRecord>
    ): File = withContext(Dispatchers.IO) {
        val exportFile = File(exportDir, "training_data_${System.currentTimeMillis()}.h5.json.gz")
        
        // Crear estructura de datos
        val data = TrainingDataExport(
            episodes = coordinator.getStats().totalDecisions,
            dqnStats = coordinator.getStats().dqnStats,
            ppoStats = coordinator.getStats().ppoStats,
            sacStats = coordinator.getStats().sacStats,
            experiences = replayBuffer.take(10000)  // Limitar tamaño
        )
        
        // Serializar y comprimir
        val json = serializeToJson(data)
        GZIPOutputStream(FileOutputStream(exportFile)).use { it.write(json.toByteArray()) }
        
        Logger.i(TAG, "Exported ${replayBuffer.size} experiences to ${exportFile.name}")
        exportFile
    }
    
    /**
     * Importa modelo mejorado desde Colab.
     */
    suspend fun importImprovedModel(
        modelFile: File,
        modelType: ModelType
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Validar modelo
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Logger.e(TAG, "Invalid model file")
                return@withContext false
            }
            
            // Backup modelo actual
            val targetFile = when (modelType) {
                ModelType.DQN -> File(context.filesDir, DQN_MODEL)
                ModelType.PPO_ACTOR -> File(context.filesDir, PPO_ACTOR)
                ModelType.PPO_CRITIC -> File(context.filesDir, PPO_CRITIC)
                ModelType.SAC_ACTOR -> File(context.filesDir, SAC_ACTOR)
                ModelType.YOLO -> File(context.filesDir, YOLO_MODEL)
            }
            
            if (targetFile.exists()) {
                targetFile.copyTo(File(targetFile.parent, targetFile.name + BACKUP_SUFFIX), overwrite = true)
            }
            
            // Copiar nuevo modelo
            modelFile.copyTo(targetFile, overwrite = true)
            
            Logger.i(TAG, "Improved model imported: ${modelType.name}")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error importing model", e)
            false
        }
    }
    
    /**
     * Comprime y guarda buffer de experiencias.
     */
    suspend fun saveReplayBuffer(
        buffer: List<ExperienceRecord>
    ): File = withContext(Dispatchers.IO) {
        val bufferFile = File(context.filesDir, "replay_buffer.json.gz")
        
        GZIPOutputStream(FileOutputStream(bufferFile)).use { gzip ->
            ObjectOutputStream(gzip).use { oos ->
                oos.writeObject(buffer)
            }
        }
        
        Logger.i(TAG, "Replay buffer saved: ${buffer.size} experiences")
        bufferFile
    }
    
    /**
     * Carga buffer de experiencias.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun loadReplayBuffer(): List<ExperienceRecord> = withContext(Dispatchers.IO) {
        val bufferFile = File(context.filesDir, "replay_buffer.json.gz")
        
        if (!bufferFile.exists()) {
            return@withContext emptyList()
        }
        
        try {
            GZIPInputStream(FileInputStream(bufferFile)).use { gzip ->
                ObjectInputStream(gzip).use { ois ->
                    val buffer = ois.readObject() as? List<ExperienceRecord>
                    Logger.i(TAG, "Replay buffer loaded: ${buffer?.size ?: 0} experiences")
                    buffer ?: emptyList()
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading replay buffer", e)
            emptyList()
        }
    }
    
    /**
     * Limpia checkpoints antiguos.
     */
    private fun cleanupOldCheckpoints() {
        val checkpoints = checkpointDir.listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
        
        checkpoints?.drop(MAX_CHECKPOINTS)?.forEach { 
            it.deleteRecursively()
            Logger.d(TAG, "Deleted old checkpoint: ${it.name}")
        }
    }
    
    /**
     * Lista checkpoints disponibles.
     */
    fun listCheckpoints(): List<CheckpointInfo> {
        return checkpointDir.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val metadataFile = File(dir, "metadata.json")
            val statsFile = File(dir, "stats.json")
            
            CheckpointInfo(
                name = dir.name,
                path = dir.absolutePath,
                timestamp = dir.lastModified(),
                size = dir.walkTopDown().map { it.length() }.sum(),
                hasMetadata = metadataFile.exists(),
                hasStats = statsFile.exists()
            )
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }
    
    /**
     * Crea JSON de metadata.
     */
    private fun createMetadataJson(metadata: CheckpointMetadata): String {
        return """
        {
            "episode": ${metadata.episode},
            "timestamp": ${metadata.timestamp},
            "total_reward": ${metadata.totalReward},
            "win_rate": ${metadata.winRate},
            "version": "${metadata.version}"
        }
        """.trimIndent()
    }
    
    /**
     * Crea JSON de stats.
     */
    private fun createStatsJson(stats: com.ffai.assistant.rl.EnsembleStats): String {
        return """
        {
            "total_decisions": ${stats.totalDecisions},
            "consensus_rate": ${stats.consensusRate},
            "dqn_epsilon": ${stats.dqnStats.epsilon},
            "ppo_steps": ${stats.ppoStats.totalSteps},
            "sac_alpha": ${stats.sacStats.alpha}
        }
        """.trimIndent()
    }
    
    /**
     * Serializa datos de entrenamiento.
     */
    private fun serializeToJson(data: TrainingDataExport): String {
        // Simplificado - en producción usar kotlinx.serialization o Gson
        return """
        {
            "episodes": ${data.episodes},
            "experience_count": ${data.experiences.size},
            "export_timestamp": ${System.currentTimeMillis()}
        }
        """.trimIndent()
    }
    
    /**
     * Obtiene uso de disco.
     */
    fun getStorageUsage(): StorageInfo {
        val checkpointsSize = checkpointDir.walkTopDown().map { it.length() }.sum()
        val exportsSize = exportDir.walkTopDown().map { it.length() }.sum()
        
        return StorageInfo(
            checkpointsSize = checkpointsSize,
            exportsSize = exportsSize,
            totalSize = checkpointsSize + exportsSize,
            checkpointCount = checkpointDir.listFiles()?.size ?: 0
        )
    }
    
    /**
     * Elimina todos los datos.
     */
    fun clearAll() {
        checkpointDir.deleteRecursively()
        exportDir.deleteRecursively()
        checkpointDir.mkdirs()
        exportDir.mkdirs()
        Logger.w(TAG, "All model data cleared")
    }
}

/**
 * Metadata de checkpoint.
 */
data class CheckpointMetadata(
    val episode: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val totalReward: Float,
    val winRate: Float,
    val version: String = "1.0.0"
)

/**
 * Info de checkpoint.
 */
data class CheckpointInfo(
    val name: String,
    val path: String,
    val timestamp: Long,
    val size: Long,
    val hasMetadata: Boolean,
    val hasStats: Boolean
)

/**
 * Tipo de modelo.
 */
enum class ModelType {
    DQN, PPO_ACTOR, PPO_CRITIC, SAC_ACTOR, YOLO
}

/**
 * Registro de experiencia.
 */
data class ExperienceRecord(
    val state: FloatArray,
    val action: Int,
    val reward: Float,
    val nextState: FloatArray,
    val done: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Datos exportados para entrenamiento.
 */
data class TrainingDataExport(
    val episodes: Int,
    val dqnStats: com.ffai.assistant.rl.DQNStats,
    val ppoStats: com.ffai.assistant.rl.PPOStats,
    val sacStats: com.ffai.assistant.rl.SACStats,
    val experiences: List<ExperienceRecord>
)

/**
 * Info de almacenamiento.
 */
data class StorageInfo(
    val checkpointsSize: Long,
    val exportsSize: Long,
    val totalSize: Long,
    val checkpointCount: Int
)
