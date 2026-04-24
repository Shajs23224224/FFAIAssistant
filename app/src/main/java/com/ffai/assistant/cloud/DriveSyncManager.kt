package com.ffai.assistant.cloud

import android.content.Context
import android.content.SharedPreferences
import com.ffai.assistant.utils.Logger
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections

/**
 * Gestor de sincronización con Google Drive.
 * Maneja subida/descarga de modelos, checkpoints y datos de aprendizaje.
 */
class DriveSyncManager(private val context: Context) {

    companion object {
        const val PREFS_NAME = "drive_sync_prefs"
        const val KEY_FOLDER_ID = "ffai_folder_id"
        const val KEY_LAST_SYNC = "last_sync_time"
        const val FFAI_ROOT_FOLDER = "FFAI"
        
        const val FOLDER_MODELS = "models"
        const val FOLDER_CHECKPOINTS = "checkpoints"
        const val FOLDER_DATA = "data"
        const val FOLDER_SESSIONS = "sessions"
        
        // Límites de Drive API
        const val MAX_FILE_SIZE_MB = 100  // 100MB para archivos grandes
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var driveService: Drive? = null
    private val folderIds = mutableMapOf<String, String>()
    
    var syncProgressCallback: ((SyncProgress) -> Unit)? = null

    /**
     * Inicializa el servicio de Drive con las credenciales del usuario.
     * Debe llamarse después de que el usuario haya iniciado sesión.
     */
    suspend fun initialize(authToken: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
            val jsonFactory = GsonFactory.getDefaultInstance()
            
            val credential = com.google.api.client.googleapis.auth.oauth2.GoogleCredential()
                .setAccessToken(authToken)
            
            driveService = Drive.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName("FFAI Assistant")
                .build()
            
            // Verificar conectividad y crear estructura de carpetas
            ensureFolderStructure()
            
            Logger.i("DriveSyncManager: Inicializado exitosamente")
            true
        } catch (e: Exception) {
            Logger.e("DriveSyncManager: Error de inicialización", e)
            false
        }
    }

    /**
     * Crea la estructura de carpetas en Drive si no existe.
     */
    private suspend fun ensureFolderStructure(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveService ?: return@withContext false
            
            // Buscar o crear carpeta raíz FFAI
            val rootFolderId = getOrCreateFolder(FFAI_ROOT_FOLDER, "root")
                ?: return@withContext false
            
            prefs.edit().putString(KEY_FOLDER_ID, rootFolderId).apply()
            folderIds["root"] = rootFolderId
            
            // Crear subcarpetas
            val subfolders = listOf(FOLDER_MODELS, FOLDER_CHECKPOINTS, FOLDER_DATA, FOLDER_SESSIONS)
            subfolders.forEach { folderName ->
                val folderId = getOrCreateFolder(folderName, rootFolderId)
                if (folderId != null) {
                    folderIds[folderName] = folderId
                }
            }
            
            Logger.i("DriveSyncManager: Estructura de carpetas lista")
            true
        } catch (e: Exception) {
            Logger.e("DriveSyncManager: Error creando estructura", e)
            false
        }
    }

    /**
     * Busca una carpeta por nombre en un directorio padre, o la crea si no existe.
     */
    private fun getOrCreateFolder(name: String, parentId: String): String? {
        val drive = driveService ?: return null
        
        return try {
            // Buscar carpeta existente
            val query = "mimeType='application/vnd.google-apps.folder' and name='$name' and '$parentId' in parents and trashed=false"
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            if (result.files.isNotEmpty()) {
                result.files[0].id
            } else {
                // Crear nueva carpeta
                val metadata = File().apply {
                    setName(name)
                    setMimeType("application/vnd.google-apps.folder")
                    setParents(Collections.singletonList(parentId))
                }
                
                val folder = drive.files().create(metadata).setFields("id").execute()
                Logger.i("DriveSyncManager: Carpeta creada - $name (${folder.id})")
                folder.id
            }
        } catch (e: IOException) {
            Logger.e("DriveSyncManager: Error con carpeta $name", e)
            null
        }
    }

    /**
     * Sube un archivo a Drive.
     * @param localFile Archivo local a subir
     * @param folderName Nombre de la carpeta destino (models, checkpoints, data, sessions)
     * @param customName Nombre opcional para el archivo en Drive (si es null, usa el nombre local)
     * @return ID del archivo en Drive o null si falla
     */
    suspend fun uploadFile(
        localFile: java.io.File,
        folderName: String = FOLDER_MODELS,
        customName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveService ?: return@withContext null
            val folderId = folderIds[folderName] ?: return@withContext null
            
            val fileName = customName ?: localFile.name
            val mimeType = getMimeType(fileName)
            
            syncProgressCallback?.invoke(SyncProgress.Uploading(fileName, 0, localFile.length()))
            
            // Buscar archivo existente para reemplazarlo
            val existingFile = findFileInFolder(fileName, folderId)
            
            if (existingFile != null) {
                // Actualizar archivo existente
                val fileContent = FileContent(mimeType, localFile)
                drive.files().update(existingFile.id, null, fileContent).execute()
                Logger.i("DriveSyncManager: Archivo actualizado - $fileName")
                syncProgressCallback?.invoke(SyncProgress.UploadComplete(fileName, existingFile.id))
                existingFile.id
            } else {
                // Crear nuevo archivo
                val fileMetadata = File().apply {
                    setName(fileName)
                    setParents(Collections.singletonList(folderId))
                }
                
                val fileContent = FileContent(mimeType, localFile)
                val uploadedFile = drive.files().create(fileMetadata, fileContent)
                    .setFields("id, name")
                    .execute()
                
                Logger.i("DriveSyncManager: Archivo subido - ${uploadedFile.name} (${uploadedFile.id})")
                syncProgressCallback?.invoke(SyncProgress.UploadComplete(fileName, uploadedFile.id))
                uploadedFile.id
            }
        } catch (e: Exception) {
            Logger.e("DriveSyncManager: Error subiendo archivo", e)
            syncProgressCallback?.invoke(SyncProgress.Error("Upload failed: ${e.message}"))
            null
        }
    }

    /**
     * Descarga un archivo desde Drive.
     * @param fileId ID del archivo en Drive
     * @param destinationFile Archivo local donde guardar
     * @return true si éxito
     */
    suspend fun downloadFile(fileId: String, destinationFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveService ?: return@withContext false
            
            syncProgressCallback?.invoke(SyncProgress.Downloading(fileId, 0, 0))
            
            drive.files().get(fileId).executeMediaAndDownloadTo(destinationFile.outputStream())
            
            Logger.i("DriveSyncManager: Archivo descargado - ${destinationFile.name}")
            syncProgressCallback?.invoke(SyncProgress.DownloadComplete(destinationFile.name, destinationFile.length()))
            true
        } catch (e: Exception) {
            Logger.e("DriveSyncManager: Error descargando archivo", e)
            syncProgressCallback?.invoke(SyncProgress.Error("Download failed: ${e.message}"))
            false
        }
    }

    /**
     * Descarga un archivo por nombre desde una carpeta específica.
     */
    suspend fun downloadFileByName(
        fileName: String,
        folderName: String = FOLDER_MODELS,
        destinationFile: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        val folderId = folderIds[folderName] ?: return@withContext false
        val file = findFileInFolder(fileName, folderId)
        
        return@withContext if (file != null) {
            downloadFile(file.id, destinationFile)
        } else {
            Logger.w("DriveSyncManager: Archivo no encontrado - $fileName")
            false
        }
    }

    /**
     * Lista archivos en una carpeta.
     */
    suspend fun listFiles(folderName: String = FOLDER_MODELS): List<DriveFile> = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveService ?: return@withContext emptyList()
            val folderId = folderIds[folderName] ?: return@withContext emptyList()
            
            val query = "'$folderId' in parents and trashed=false"
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, size, modifiedTime, mimeType)")
                .execute()
            
            result.files.map { file ->
                DriveFile(
                    id = file.id,
                    name = file.name,
                    size = file.getSize() ?: 0,
                    modifiedTime = file.modifiedTime?.value ?: 0,
                    mimeType = file.mimeType
                )
            }
        } catch (e: Exception) {
            Logger.e("DriveSyncManager: Error listando archivos", e)
            emptyList()
        }
    }

    /**
     * Busca un archivo por nombre en una carpeta.
     */
    private fun findFileInFolder(fileName: String, folderId: String): File? {
        val drive = driveService ?: return null
        
        return try {
            val query = "name='$fileName' and '$folderId' in parents and trashed=false"
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, modifiedTime)")
                .execute()
            
            result.files.firstOrNull()
        } catch (e: IOException) {
            Logger.e("DriveSyncManager: Error buscando archivo", e)
            null
        }
    }

    /**
     * Elimina un archivo de Drive.
     */
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveService ?: return@withContext false
            drive.files().delete(fileId).execute()
            Logger.i("DriveSyncManager: Archivo eliminado - $fileId")
            true
        } catch (e: Exception) {
            Logger.e("DriveSyncManager: Error eliminando archivo", e)
            false
        }
    }

    /**
     * Verifica si existe un archivo en Drive.
     */
    suspend fun fileExists(fileName: String, folderName: String = FOLDER_MODELS): Boolean = withContext(Dispatchers.IO) {
        val folderId = folderIds[folderName] ?: return@withContext false
        return@withContext findFileInFolder(fileName, folderId) != null
    }

    /**
     * Obtiene la fecha de última sincronización.
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0)
    }

    /**
     * Actualiza la fecha de última sincronización.
     */
    fun updateLastSyncTime() {
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    /**
     * Obtiene el MIME type basado en la extensión del archivo.
     */
    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".tflite") -> "application/octet-stream"
            fileName.endsWith(".pth") -> "application/octet-stream"
            fileName.endsWith(".pt") -> "application/octet-stream"
            fileName.endsWith(".db") -> "application/x-sqlite3"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * Limpia recursos.
     */
    fun cleanup() {
        driveService = null
        folderIds.clear()
    }
}

/**
 * Representa un archivo en Google Drive.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedTime: Long,
    val mimeType: String?
)

/**
 * Estados de progreso de sincronización.
 */
sealed class SyncProgress {
    data class Uploading(val fileName: String, val bytesUploaded: Long, val totalBytes: Long) : SyncProgress()
    data class UploadComplete(val fileName: String, val fileId: String) : SyncProgress()
    data class Downloading(val fileId: String, val bytesDownloaded: Long, val totalBytes: Long) : SyncProgress()
    data class DownloadComplete(val fileName: String, val fileSize: Long) : SyncProgress()
    data class Error(val message: String) : SyncProgress()
    object SyncComplete : SyncProgress()
}
