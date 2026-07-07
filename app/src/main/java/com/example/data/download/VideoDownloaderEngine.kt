package com.example.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.ads.AdManager
import com.example.data.database.AppDatabase
import com.example.data.database.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object VideoDownloaderEngine {
    private const val TAG = "VideoDownloaderEngine"
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Start downloading a video by creating or using a taskId.
     */
    fun startDownload(context: Context, taskId: Int) {
        // If there's an active job, don't restart it
        if (activeJobs.containsKey(taskId)) {
            Log.d(TAG, "Task $taskId is already actively running.")
            return
        }

        val job = scope.launch {
            downloadProcess(context, taskId)
        }
        activeJobs[taskId] = job
    }

    /**
     * Pause a running task.
     */
    fun pauseTask(context: Context, taskId: Int) {
        val job = activeJobs.remove(taskId)
        job?.cancel()

        scope.launch {
            val db = AppDatabase.getDatabase(context)
            val task = db.downloadDao().getTaskById(taskId)
            if (task != null && task.status == "DOWNLOADING") {
                db.downloadDao().updateTask(
                    task.copy(
                        status = "PAUSED",
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L
                    )
                )
                Log.d(TAG, "Task $taskId paused.")
            }
        }
    }

    /**
     * Cancel a task and delete its temporary files.
     */
    fun cancelTask(context: Context, taskId: Int, deleteDbRecord: Boolean = false) {
        val job = activeJobs.remove(taskId)
        job?.cancel()

        scope.launch {
            val contextApp = AppDatabase.getDatabase(context)
            val task = contextApp.downloadDao().getTaskById(taskId)
            if (task != null) {
                // Delete temp file if exists
                try {
                    val tempFile = File(task.filePath)
                    if (tempFile.exists() && !task.filePath.startsWith("content://")) {
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting temp file for task $taskId: ${e.message}")
                }

                if (deleteDbRecord) {
                    contextApp.downloadDao().deleteTaskById(taskId)
                    Log.d(TAG, "Task $taskId fully deleted.")
                } else {
                    contextApp.downloadDao().updateTask(
                        task.copy(
                            status = "FAILED",
                            progress = 0f,
                            downloadedBytes = 0L,
                            speedBytesPerSec = 0L,
                            etaSeconds = 0L,
                            errorMessage = "Cancelled by user"
                        )
                    )
                    Log.d(TAG, "Task $taskId cancelled.")
                }
            }
        }
    }

    /**
     * Resume a paused download task.
     */
    fun resumeTask(context: Context, taskId: Int) {
        startDownload(context, taskId)
    }

    /**
     * Core download processing run on the IO Dispatcher.
     */
    private suspend fun downloadProcess(context: Context, taskId: Int) {
        val db = AppDatabase.getDatabase(context)
        var task = db.downloadDao().getTaskById(taskId) ?: return

        // Update status in DB
        db.downloadDao().updateTask(
            task.copy(status = "DOWNLOADING", errorMessage = null)
        )

        val tempDir = File(context.cacheDir, "downloads")
        if (!tempDir.exists()) tempDir.mkdirs()

        // We use a clean local temporary file inside our cache folder
        // Once the download is fully completed and verified, we copy it to public Downloads.
        val sanitizedTitle = task.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val tempFile = File(tempDir, "temp_${taskId}_$sanitizedTitle")
        
        var downloadedBytes = 0L
        if (tempFile.exists()) {
            downloadedBytes = tempFile.length()
        } else {
            tempFile.createNewFile()
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var randomAccessFile: RandomAccessFile? = null

        try {
            val url = URL(task.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            // Support Premium High-Speed (Multi-thread / buffer enlargement)
            val isPremium = AdManager.isPremiumUnlocked.value
            val bufferSize = if (isPremium) 128 * 1024 else 16 * 1024 // larger buffer size if premium

            // Attempt pause-resume if we already have bytes
            if (downloadedBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }

            connection.connect()

            val responseCode = connection.responseCode
            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL

            var totalBytes = task.totalBytes
            if (!isPartial) {
                // Server does not support resume or we are starting fresh
                downloadedBytes = 0L
                totalBytes = connection.contentLength.toLong()
                if (totalBytes <= 0) {
                    totalBytes = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                }
            } else {
                // Partially downloaded content
                val remainingBytes = connection.contentLength.toLong()
                totalBytes = downloadedBytes + remainingBytes
            }

            // Update task in db with the verified total bytes
            task = task.copy(totalBytes = totalBytes, filePath = tempFile.absolutePath)
            db.downloadDao().updateTask(task)

            // Open stream
            inputStream = BufferedInputStream(connection.inputStream, bufferSize)
            randomAccessFile = RandomAccessFile(tempFile, "rw")
            
            if (isPartial) {
                randomAccessFile.seek(downloadedBytes)
            } else {
                randomAccessFile.setLength(0)
            }

            val buffer = ByteArray(bufferSize)
            var bytesRead: Int
            
            var lastUpdateTimestamp = System.currentTimeMillis()
            var bytesReadSinceLastUpdate = 0L
            var speedBytesPerSec = 0L

            while (true) {
                // Check if job is active/cancelled
                val currentJob = activeJobs[taskId]
                if (currentJob == null || currentJob.isCancelled) {
                    break
                }

                bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                randomAccessFile.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                bytesReadSinceLastUpdate += bytesRead

                // Periodic statistics calculation
                val now = System.currentTimeMillis()
                val elapsed = now - lastUpdateTimestamp
                if (elapsed >= 500) { // update every 500ms
                    val elapsedSeconds = elapsed / 1000.0
                    speedBytesPerSec = (bytesReadSinceLastUpdate / elapsedSeconds).toLong()
                    
                    val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                    val eta = if (speedBytesPerSec > 0 && totalBytes > downloadedBytes) {
                        (totalBytes - downloadedBytes) / speedBytesPerSec
                    } else {
                        0L
                    }

                    // Reset counters
                    lastUpdateTimestamp = now
                    bytesReadSinceLastUpdate = 0L

                    // Update Database
                    task = task.copy(
                        downloadedBytes = downloadedBytes,
                        progress = progress,
                        speedBytesPerSec = speedBytesPerSec,
                        etaSeconds = eta
                    )
                    db.downloadDao().updateTask(task)
                }
            }

            // Verify if download completed fully
            val currentJob = activeJobs[taskId]
            if (currentJob != null && !currentJob.isCancelled) {
                // Completed!
                Log.d(TAG, "File downloaded to temp path. Moving to public directory.")
                
                // Copy to public Scoped Storage
                val publicUriStr = saveVideoToDownloads(context, tempFile, task.title)
                
                if (publicUriStr != null) {
                    // Update task as COMPLETED
                    task = task.copy(
                        status = "COMPLETED",
                        progress = 1.0f,
                        downloadedBytes = totalBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L,
                        filePath = publicUriStr,
                        timestamp = System.currentTimeMillis()
                    )
                    db.downloadDao().updateTask(task)
                    Log.d(TAG, "Download fully completed and saved to: $publicUriStr")
                    
                    // Cleanup temp cache file
                    try { tempFile.delete() } catch (e: Exception) {}
                } else {
                    throw Exception("Failed to write video file to system Downloads folder.")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading task $taskId: ${e.message}", e)
            val currentJob = activeJobs[taskId]
            if (currentJob != null && !currentJob.isCancelled) {
                task = task.copy(
                    status = "FAILED",
                    errorMessage = e.localizedMessage ?: e.message ?: "Network error occurred."
                )
                db.downloadDao().updateTask(task)
            }
        } finally {
            try { randomAccessFile?.close() } catch (e: Exception) {}
            try { inputStream?.close() } catch (e: Exception) {}
            try { connection?.disconnect() } catch (e: Exception) {}
            activeJobs.remove(taskId)
        }
    }

    /**
     * Copy downloaded temp file to system public Downloads folder using MediaStore (Android 10+)
     * or standard filesystem operations for older versions.
     */
    private suspend fun saveVideoToDownloads(
        context: Context,
        tempFile: File,
        title: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val extension = if (title.contains(".")) title.substringAfterLast(".", "mp4") else "mp4"
            val mimeType = when (extension.lowercase()) {
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                else -> "video/mp4"
            }
            val displayName = if (title.contains(".")) title else "$title.$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Download/VideoDownloader")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collectionUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collectionUri, contentValues)

                if (itemUri != null) {
                    resolver.openOutputStream(itemUri).use { outputStream ->
                        if (outputStream != null) {
                            FileInputStream(tempFile).use { inputStream ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                    }

                    // Release the pending lock so it shows up globally
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)

                    return@withContext itemUri.toString()
                }
            } else {
                // Legacy devices (Android 9 or lower)
                val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val customFolder = File(publicDownloadsDir, "VideoDownloader")
                if (!customFolder.exists()) {
                    customFolder.mkdirs()
                }
                val destFile = File(customFolder, displayName)
                
                FileInputStream(tempFile).use { inputStream ->
                    FileOutputStream(destFile).use { outputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }
                return@withContext destFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to system Downloads: ${e.message}", e)
        }
        return@withContext null
    }
}
