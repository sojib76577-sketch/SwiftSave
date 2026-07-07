package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.data.database.DownloadTask
import com.example.data.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VideoFileHelper {
    private const val TAG = "VideoFileHelper"

    /**
     * Rename the video file in public storage and update its title and filePath in the database.
     */
    suspend fun renameVideo(
        context: Context,
        task: DownloadTask,
        newTitle: String,
        repository: DownloadRepository
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val extension = if (task.title.contains(".")) {
                task.title.substringAfterLast(".", "mp4")
            } else {
                "mp4"
            }
            val cleanTitle = newTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val newDisplayName = if (cleanTitle.contains(".")) cleanTitle else "$cleanTitle.$extension"

            if (task.filePath.startsWith("content://")) {
                // MediaStore Content Uri renaming (Android 10+)
                val uri = Uri.parse(task.filePath)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
                }
                val rowsUpdated = context.contentResolver.update(uri, values, null, null)
                if (rowsUpdated > 0) {
                    val updatedTask = task.copy(title = cleanTitle)
                    repository.updateTask(updatedTask)
                    Log.d(TAG, "Successfully renamed MediaStore file to: $newDisplayName")
                    return@withContext true
                }
            } else {
                // Legacy File renaming (Android 9 or lower)
                val oldFile = File(task.filePath)
                if (oldFile.exists()) {
                    val parentDir = oldFile.parentFile
                    val newFile = File(parentDir, newDisplayName)
                    if (oldFile.renameTo(newFile)) {
                        val updatedTask = task.copy(
                            title = cleanTitle,
                            filePath = newFile.absolutePath
                        )
                        repository.updateTask(updatedTask)
                        Log.d(TAG, "Successfully renamed legacy file to: ${newFile.absolutePath}")
                        return@withContext true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename video: ${e.message}", e)
        }
        return@withContext false
    }

    /**
     * Delete the video file from the storage (and Uri if MediaStore) and delete its task record from the database.
     */
    suspend fun deleteVideo(
        context: Context,
        task: DownloadTask,
        repository: DownloadRepository
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Delete actual file
            if (task.filePath.startsWith("content://")) {
                val uri = Uri.parse(task.filePath)
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: SecurityException) {
                    // On some Android versions/devices, deleting public files might prompt for permission.
                    // Fallback to just DB deletion or logs.
                    Log.w(TAG, "SecurityException deleting file via ContentResolver: ${e.message}")
                }
            } else {
                val file = File(task.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }

            // Delete database task record
            repository.deleteTask(task)
            Log.d(TAG, "Successfully deleted task ${task.id} from database and files.")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete task/file: ${e.message}", e)
        }
        return@withContext false
    }

    /**
     * Launch a standard system Chooser share sheet for the downloaded video.
     */
    fun shareVideo(context: Context, task: DownloadTask) {
        try {
            val uri = Uri.parse(task.filePath)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Video"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share video: ${e.message}", e)
        }
    }
}
