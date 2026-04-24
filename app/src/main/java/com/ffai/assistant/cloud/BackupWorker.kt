package com.ffai.assistant.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ffai.assistant.config.Constants
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Worker de sincronización en background con Google Drive.
 * Maneja backup de modelos, checkpoints y datos de aprendizaje.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "ffai_backup_worker"
        const val ONE_TIME_WORK_NAME = "ffai_backup_one_time"
        
        // Input data keys
        const val KEY_SYNC_MODELS = "sync_models"
        const val KEY_SYNC_DATA = "sync_data"
        const val KEY_FORCE_SYNC = "force_sync"
        const val KEY_AUTH_TOKEN = "auth_token"
        
        // Output data keys
        const val KEY_RESULT_SUCCESS = "result_success"
        const val KEY_RESULT_MESSAGE = "result_message"
        const val KEY_FILES_UPLOADED = "files_uploaded"
        const val KEY_FILES_DOWNLOADED = "files_downloaded"
    }

    private lateinit var driveSyncManager: DriveSyncManager
    private val context = applicationContext

    override suspend fun doWork(): Result {
        Logger.i("BackupWorker: Iniciando sincronización")
        
        val authToken = inputData.getString(KEY_AUTH_TOKEN)
        if (authToken.isNullOrEmpty()) {
            Logger.e("BackupWorker: No hay token de autenticación")
            return Result.failure(workDataOf(
                KEY_RESULT_SUCCESS to false,
                KEY_RESULT_MESSAGE to "No authentication token"
            ))
        }
        
        val syncModels = inputData.getBoolean(KEY_SYNC_MODELS, true)
        val syncData = inputData.getBoolean(KEY_SYNC_DATA, true)
        
        return try {
            driveSyncManager = DriveSyncManager(context)
            
            // Inicializar Drive
            val initialized = driveSyncManager.initialize(authToken)
            if (!initialized) {
                return Result.failure(workDataOf(
                    KEY_RESULT_SUCCESS to false,
                    KEY_RESULT_MESSAGE to "Failed to initialize Drive"
                ))
            }
            
            var filesUploaded = 0
            var filesDownloaded = 0
            
            // Sincronizar modelos
            if (syncModels) {
                val modelResult = syncModels()
                filesUploaded += modelResult.first
                filesDownloaded += modelResult.second
            }
            
            // Sincronizar datos de aprendizaje
            if (syncData) {
                val dataResult = syncLearningData()
                filesUploaded += dataResult.first
                filesDownloaded += dataResult.second
            }
            
            // Actualizar timestamp de última sincronización
            driveSyncManager.updateLastSyncTime()
            
            val message = "Sync completed: $filesUploaded uploaded, $filesDownloaded downloaded"
            Logger.i("BackupWorker: $message")
            
            Result.success(workDataOf(
                KEY_RESULT_SUCCESS to true,
                KEY_RESULT_MESSAGE to message,
                KEY_FILES_UPLOADED to filesUploaded,
                KEY_FILES_DOWNLOADED to filesDownloaded
            ))
            
        } catch (e: Exception) {
            Logger.e("BackupWorker: Error en sincronización", e)
            Result.retry()
        } finally {
            driveSyncManager.cleanup()
        }
    }

    /**
     * Sincroniza modelos con Drive.
     * @return Pair(uploaded, downloaded)
     */
    private suspend fun syncModels(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        
        try {
            val modelDir = File(context.getExternalFilesDir(null), Constants.MODEL_DIR)
            
            // Subir modelo actual si existe
            val currentModel = File(modelDir, Constants.MODEL_CURRENT)
            if (currentModel.exists()) {
                driveSyncManager.uploadFile(currentModel, DriveSyncManager.FOLDER_MODELS)
                uploaded++
                
                // También subir como backup con timestamp
                val timestamp = System.currentTimeMillis()
                val backupName = "model_backup_${timestamp}.tflite"
                driveSyncManager.uploadFile(currentModel, DriveSyncManager.FOLDER_MODELS, backupName)
                uploaded++
            }
            
            // Verificar si hay modelos nuevos en Drive para descargar
            val remoteFiles = driveSyncManager.listFiles(DriveSyncManager.FOLDER_MODELS)
            val manifestFile = remoteFiles.find { it.name == "manifest.json" }
            
            if (manifestFile != null) {
                // Descargar y parsear manifest
                val tempManifest = File(context.cacheDir, "temp_manifest.json")
                if (driveSyncManager.downloadFile(manifestFile.id, tempManifest)) {
                    // Verificar si hay modelo nuevo
                    val currentModelId = remoteFiles.find { 
                        it.name == Constants.MODEL_CURRENT 
                    }?.id
                    
                    if (currentModelId != null && !currentModel.exists()) {
                        // Descargar modelo desde Drive
                        driveSyncManager.downloadFile(currentModelId, currentModel)
                        downloaded++
                    }
                    tempManifest.delete()
                }
            }
            
        } catch (e: Exception) {
            Logger.e("BackupWorker: Error sincronizando modelos", e)
        }
        
        Pair(uploaded, downloaded)
    }

    /**
     * Sincroniza datos de aprendizaje con Drive.
     * @return Pair(uploaded, downloaded)
     */
    private suspend fun syncLearningData(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var uploaded = 0
        var downloaded = 0
        
        try {
            // Comprimir base de datos SQLite
            val dbFile = context.getDatabasePath(Constants.DB_NAME)
            if (dbFile.exists()) {
                val zipFile = File(context.cacheDir, "experiences_backup.zip")
                compressFile(dbFile, zipFile)
                
                // Subir a Drive
                driveSyncManager.uploadFile(zipFile, DriveSyncManager.FOLDER_DATA, "experiences_backup.zip")
                uploaded++
                
                zipFile.delete()
            }
            
            // Verificar si hay datos remotos para restaurar
            val remoteFiles = driveSyncManager.listFiles(DriveSyncManager.FOLDER_DATA)
            val remoteBackup = remoteFiles.find { it.name == "experiences_backup.zip" }
            
            if (remoteBackup != null && !dbFile.exists()) {
                // Descargar y restaurar
                val tempZip = File(context.cacheDir, "temp_experiences.zip")
                if (driveSyncManager.downloadFile(remoteBackup.id, tempZip)) {
                    decompressFile(tempZip, dbFile.parentFile ?: File(context.filesDir, "databases"))
                    downloaded++
                    tempZip.delete()
                }
            }
            
        } catch (e: Exception) {
            Logger.e("BackupWorker: Error sincronizando datos", e)
        }
        
        Pair(uploaded, downloaded)
    }

    /**
     * Comprime un archivo en ZIP.
     */
    private fun compressFile(sourceFile: File, zipFile: File) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            val entry = ZipEntry(sourceFile.name)
            zos.putNextEntry(entry)
            sourceFile.inputStream().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()
        }
    }

    /**
     * Descomprime un archivo ZIP.
     */
    private fun decompressFile(zipFile: File, destinationDir: File) {
        destinationDir.mkdirs()
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destinationDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Configura observador de progreso.
     */
    private fun setupProgressCallback() {
        driveSyncManager.syncProgressCallback = { progress ->
            when (progress) {
                is SyncProgress.Uploading -> {
                    val percent = if (progress.totalBytes > 0) {
                        ((progress.bytesUploaded * 100) / progress.totalBytes).toInt()
                    } else 0
                    setProgressAsync(workDataOf(
                        "progress_type" to "uploading",
                        "file_name" to progress.fileName,
                        "percent" to percent
                    ))
                }
                is SyncProgress.Downloading -> {
                    val percent = if (progress.totalBytes > 0) {
                        ((progress.bytesDownloaded * 100) / progress.totalBytes).toInt()
                    } else 0
                    setProgressAsync(workDataOf(
                        "progress_type" to "downloading",
                        "file_id" to progress.fileId,
                        "percent" to percent
                    ))
                }
                else -> {}
            }
        }
    }
}

/**
 * Helper para programar trabajos de sincronización.
 */
object BackupScheduler {
    
    /**
     * Programa sincronización periódica.
     * Por defecto: cada 6 horas, solo WiFi, solo cuando carga.
     */
    fun schedulePeriodicSync(
        context: Context,
        repeatInterval: Long = 6,
        timeUnit: TimeUnit = TimeUnit.HOURS,
        requireWifi: Boolean = true,
        requireCharging: Boolean = true
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(requireCharging)
            .build()
        
        val workRequest = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatInterval, timeUnit
        )
            .setConstraints(constraints)
            .addTag(BackupWorker.WORK_NAME)
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        
        Logger.i("BackupScheduler: Sincronización periódica programada cada $repeatInterval $timeUnit")
    }
    
    /**
     * Ejecuta sincronización única inmediata.
     */
    fun runOneTimeSync(
        context: Context,
        authToken: String,
        syncModels: Boolean = true,
        syncData: Boolean = true,
        forceSync: Boolean = false
    ) {
        val inputData = workDataOf(
            BackupWorker.KEY_AUTH_TOKEN to authToken,
            BackupWorker.KEY_SYNC_MODELS to syncModels,
            BackupWorker.KEY_SYNC_DATA to syncData,
            BackupWorker.KEY_FORCE_SYNC to forceSync
        )
        
        val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(inputData)
            .addTag(BackupWorker.ONE_TIME_WORK_NAME)
            .build()
        
        WorkManager.getInstance(context).enqueue(workRequest)
        
        Logger.i("BackupScheduler: Sincronización one-time programada")
    }
    
    /**
     * Cancela todas las sincronizaciones.
     */
    fun cancelAllSync(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(BackupWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelAllWorkByTag(BackupWorker.ONE_TIME_WORK_NAME)
        Logger.i("BackupScheduler: Todas las sincronizaciones canceladas")
    }
    
    /**
     * Verifica si hay sincronización en progreso.
     */
    suspend fun isSyncInProgress(context: Context): Boolean {
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosByTagFlow(BackupWorker.ONE_TIME_WORK_NAME).first()
        
        return workInfos.any { 
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED 
        }
    }
    
    /**
     * Obtiene información de última sincronización.
     */
    suspend fun getLastSyncInfo(context: Context): SyncInfo? {
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosByTagFlow(BackupWorker.WORK_NAME).first()
        
        return workInfos.lastOrNull()?.let { workInfo ->
            SyncInfo(
                state = workInfo.state.name,
                lastSyncTime = workInfo.runAttemptCount,
                outputData = workInfo.outputData
            )
        }
    }
}

/**
 * Información de sincronización.
 */
data class SyncInfo(
    val state: String,
    val lastSyncTime: Int,
    val outputData: androidx.work.Data
)
