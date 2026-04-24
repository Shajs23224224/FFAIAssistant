package com.ffai.assistant.cloud

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ffai.assistant.config.Constants
import com.ffai.assistant.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Descargador de modelos desde Google Drive.
 * Soporta descarga resumable, progreso en tiempo real, y descompresión.
 */
class ModelDownloader(private val context: Context) {

    companion object {
        const val BUFFER_SIZE = 8192
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
        
        // Tamaño mínimo para considerar un modelo válido (1MB)
        const val MIN_VALID_MODEL_SIZE = 1024 * 1024
    }

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val modelDir = File(context.getExternalFilesDir(null), Constants.MODEL_DIR).apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "model_downloads").apply { mkdirs() }

    /**
     * Descarga un modelo desde Google Drive.
     * Usa la API REST de Drive para soportar archivos grandes (>50MB).
     * 
     * @param fileId ID del archivo en Google Drive
     * @param fileName Nombre del archivo destino
     * @param useResumableDownload Si true, usa descarga resumable (para archivos >5MB)
     * @return Flow con el progreso de descarga
     */
    fun downloadModel(
        fileId: String,
        fileName: String,
        authToken: String,
        useResumableDownload: Boolean = true
    ): Flow<DownloadProgress> = flow {
        val destinationFile = File(modelDir, fileName)
        val tempFile = File(tempDir, "${fileName}.tmp")
        
        emit(DownloadProgress.Starting(fileName))
        
        try {
            val success = if (useResumableDownload && getDriveFileSize(fileId, authToken) > 5 * 1024 * 1024) {
                // Archivo grande: usar descarga con chunks
                downloadWithResume(fileId, tempFile, authToken) { downloaded, total ->
                    emit(DownloadProgress.Downloading(fileName, downloaded, total))
                }
            } else {
                // Archivo pequeño: descarga directa
                downloadDirect(fileId, tempFile, authToken) { downloaded, total ->
                    emit(DownloadProgress.Downloading(fileName, downloaded, total))
                }
            }
            
            if (success) {
                // Verificar integridad
                if (isValidModelFile(tempFile)) {
                    // Mover a destino final
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                    
                    Logger.i("ModelDownloader: Modelo descargado exitosamente - ${destinationFile.absolutePath}")
                    emit(DownloadProgress.Success(destinationFile, destinationFile.length()))
                } else {
                    tempFile.delete()
                    emit(DownloadProgress.Error("Archivo descargado está corrupto o incompleto"))
                }
            } else {
                tempFile.delete()
                emit(DownloadProgress.Error("Descarga fallida después de $MAX_RETRIES intentos"))
            }
        } catch (e: Exception) {
            Logger.e("ModelDownloader: Error en descarga", e)
            tempFile.delete()
            emit(DownloadProgress.Error("Error: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Descarga desde URL pública (fallback si no hay OAuth).
     */
    suspend fun downloadFromUrl(
        urlString: String,
        fileName: String
    ): DownloadResult = withContext(Dispatchers.IO) {
        val destinationFile = File(modelDir, fileName)
        val tempFile = File(tempDir, "${fileName}.tmp")
        
        return@withContext try {
            var retries = 0
            var success = false
            
            while (retries < MAX_RETRIES && !success) {
                try {
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.apply {
                        requestMethod = "GET"
                        connectTimeout = 30000
                        readTimeout = 30000
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    
                    val totalSize = connection.contentLengthLong
                    var downloadedSize = 0L
                    
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedSize += bytesRead
                            }
                        }
                    }
                    
                    connection.disconnect()
                    success = true
                    
                } catch (e: Exception) {
                    retries++
                    Logger.w("ModelDownloader: Reintentando descarga ($retries/$MAX_RETRIES)")
                    if (retries < MAX_RETRIES) {
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
            
            if (success && isValidModelFile(tempFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
                DownloadResult.Success(destinationFile, destinationFile.length())
            } else {
                tempFile.delete()
                DownloadResult.Error("Descarga fallida")
            }
            
        } catch (e: Exception) {
            Logger.e("ModelDownloader: Error descargando desde URL", e)
            tempFile.delete()
            DownloadResult.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Descarga usando la API de Drive REST con soporte resumable.
     */
    private suspend fun downloadWithResume(
        fileId: String,
        destination: File,
        authToken: String,
        onProgress: suspend (Long, Long) -> Unit
    ): Boolean {
        var retries = 0
        
        while (retries < MAX_RETRIES) {
            try {
                val totalSize = getDriveFileSize(fileId, authToken)
                var downloadedBytes = if (destination.exists()) destination.length() else 0L
                
                // Si ya está completo, no hacer nada
                if (downloadedBytes >= totalSize) {
                    return true
                }
                
                val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $authToken")
                    setRequestProperty("Range", "bytes=$downloadedBytes-")
                    connectTimeout = 30000
                    readTimeout = 30000
                }
                
                val responseCode = connection.responseCode
                if (responseCode == 200 || responseCode == 206) {
                    // Append al archivo existente o crear nuevo
                    destination.parentFile?.mkdirs()
                    
                    val append = downloadedBytes > 0
                    destination.outputStream().use { output ->
                        if (append && destination.exists()) {
                            // Saltar al final para append
                            output.channel.position(downloadedBytes)
                        }
                        
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            var lastProgressTime = System.currentTimeMillis()
                            
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                
                                // Reportar progreso cada intervalo
                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime > PROGRESS_UPDATE_INTERVAL_MS) {
                                    onProgress(downloadedBytes, totalSize)
                                    lastProgressTime = now
                                }
                            }
                        }
                    }
                    
                    connection.disconnect()
                    return true
                } else {
                    throw Exception("HTTP $responseCode")
                }
                
            } catch (e: Exception) {
                retries++
                Logger.w("ModelDownloader: Reintentando descarga chunk ($retries/$MAX_RETRIES)")
                if (retries < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * retries)
                }
            }
        }
        
        return false
    }

    /**
     * Descarga directa simple (para archivos pequeños).
     */
    private suspend fun downloadDirect(
        fileId: String,
        destination: File,
        authToken: String,
        onProgress: suspend (Long, Long) -> Unit
    ): Boolean {
        return try {
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $authToken")
                connectTimeout = 30000
                readTimeout = 30000
            }
            
            val totalSize = connection.contentLengthLong
            var downloadedBytes = 0L
            var lastProgressTime = System.currentTimeMillis()
            
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime > PROGRESS_UPDATE_INTERVAL_MS) {
                            onProgress(downloadedBytes, totalSize)
                            lastProgressTime = now
                        }
                    }
                }
            }
            
            connection.disconnect()
            true
        } catch (e: Exception) {
            Logger.e("ModelDownloader: Error en descarga directa", e)
            false
        }
    }

    /**
     * Obtiene el tamaño de un archivo en Drive.
     */
    private fun getDriveFileSize(fileId: String, authToken: String): Long {
        return try {
            val url = URL("https://www.googleapis.com/drive/v3/files/$fileId?fields=size")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            
            // Parsear JSON simple
            val sizeMatch = Regex(""""size":\s*"?(\d+)"?""").find(response)
            sizeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Logger.e("ModelDownloader: Error obteniendo tamaño", e)
            0L
        }
    }

    /**
     * Descomprime un modelo ZIP descargado.
     */
    suspend fun unzipModel(zipFile: File, destinationDir: File = modelDir): File? = withContext(Dispatchers.IO) {
        return@withContext try {
            var extractedFile: File? = null
            
            ZipInputStream(zipFile.inputStream()).use { zis ->
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
                        
                        if (entry.name.endsWith(".tflite")) {
                            extractedFile = newFile
                        }
                    }
                    
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            
            zipFile.delete()
            extractedFile
        } catch (e: Exception) {
            Logger.e("ModelDownloader: Error descomprimiendo", e)
            null
        }
    }

    /**
     * Verifica si un archivo de modelo es válido.
     */
    private fun isValidModelFile(file: File): Boolean {
        return file.exists() && 
               file.length() >= MIN_VALID_MODEL_SIZE &&
               (file.name.endsWith(".tflite") || file.name.endsWith(".pt") || file.name.endsWith(".pth"))
    }

    /**
     * Limpia archivos temporales de descargas.
     */
    fun cleanupTempFiles() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Cancela todas las descargas en progreso (si se usaba DownloadManager).
     */
    fun cancelDownloads() {
        // Obtener IDs de descargas activas y removerlas
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or 
            DownloadManager.STATUS_RUNNING or 
            DownloadManager.STATUS_PAUSED
        )
        downloadManager.query(query)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                downloadManager.remove(id)
            }
        }
    }
}

/**
 * Estados de progreso de descarga.
 */
sealed class DownloadProgress {
    data class Starting(val fileName: String) : DownloadProgress()
    data class Downloading(val fileName: String, val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress()
    data class Success(val file: File, val fileSize: Long) : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
    
    /**
     * Porcentaje de progreso (0-100).
     */
    val percent: Int
        get() = when (this) {
            is Downloading -> {
                if (totalBytes > 0) {
                    ((bytesDownloaded * 100) / totalBytes).toInt()
                } else 0
            }
            is Success -> 100
            else -> 0
        }
}

/**
 * Resultado de descarga.
 */
sealed class DownloadResult {
    data class Success(val file: File, val fileSize: Long) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}
