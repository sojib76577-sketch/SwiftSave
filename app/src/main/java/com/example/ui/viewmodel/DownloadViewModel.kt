package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import android.util.Patterns
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ads.AdManager
import com.example.data.database.DownloadTask
import com.example.data.download.VideoDownloaderEngine
import com.example.data.repository.DownloadRepository
import com.example.util.VideoFileHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URL

class DownloadViewModel(private val repository: DownloadRepository) : ViewModel() {
    private val TAG = "DownloadViewModel"

    // Inputs
    val urlInput = MutableStateFlow("")
    val searchQuery = MutableStateFlow("")

    // Active confirmation dialog state
    val showConfirmDialog = MutableStateFlow(false)
    val confirmUrl = MutableStateFlow("")
    val confirmTitle = MutableStateFlow("")

    // Premium speed status from AdManager
    val isPremiumUnlocked: StateFlow<Boolean> = AdManager.isPremiumUnlocked

    // Filtered tasks lists
    private val _allTasks = repository.allTasks

    val downloadingTasks: StateFlow<List<DownloadTask>> = combine(_allTasks, searchQuery) { tasks, query ->
        tasks.filter { 
            (it.status == "DOWNLOADING" || it.status == "PAUSED" || it.status == "QUEUED") &&
            it.title.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedTasks: StateFlow<List<DownloadTask>> = combine(_allTasks, searchQuery) { tasks, query ->
        tasks.filter { 
            it.status == "COMPLETED" &&
            it.title.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedTasks: StateFlow<List<DownloadTask>> = combine(_allTasks, searchQuery) { tasks, query ->
        tasks.filter { 
            it.status == "FAILED" &&
            it.title.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Validate the given URL and prepare for confirmation.
     * Returns true if valid, false otherwise.
     */
    fun validateAndPrepareDownload(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false

        // Basic check for URL validity
        if (!URLUtil.isValidUrl(trimmed) || !Patterns.WEB_URL.matcher(trimmed).matches()) {
            return false
        }

        try {
            val parsedUrl = URL(trimmed)
            var filename = parsedUrl.path.substringAfterLast('/')
            if (filename.isEmpty() || !filename.contains(".")) {
                filename = "video_download_${System.currentTimeMillis()}.mp4"
            }
            
            // Set confirmation states
            confirmUrl.value = trimmed
            confirmTitle.value = filename
            showConfirmDialog.value = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing URL: ${e.message}")
        }
        return false
    }

    /**
     * Confirm download after the user reviews/edits the title.
     */
    fun confirmDownload(context: Context) {
        val url = confirmUrl.value
        var title = confirmTitle.value.trim()
        
        if (title.isEmpty()) {
            title = "video_${System.currentTimeMillis()}.mp4"
        }

        showConfirmDialog.value = false
        urlInput.value = "" // clear input

        viewModelScope.launch {
            // Check if task with this URL already exists
            val existing = repository.getTaskByUrl(url)
            val taskId: Int

            if (existing != null) {
                taskId = existing.id
                // Update to queued/pending state
                repository.updateTask(
                    existing.copy(
                        title = title,
                        status = "QUEUED",
                        progress = 0f,
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L,
                        errorMessage = null,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } else {
                val newTask = DownloadTask(
                    url = url,
                    title = title,
                    filePath = "",
                    status = "QUEUED"
                )
                taskId = repository.insertTask(newTask).toInt()
            }

            // Trigger the engine to start the download
            VideoDownloaderEngine.startDownload(context.applicationContext, taskId)
        }
    }

    // Engine operations exposed to UI
    fun pauseDownload(context: Context, task: DownloadTask) {
        VideoDownloaderEngine.pauseTask(context, task.id)
    }

    fun resumeDownload(context: Context, task: DownloadTask) {
        VideoDownloaderEngine.resumeTask(context, task.id)
    }

    fun cancelDownload(context: Context, task: DownloadTask, deleteRecord: Boolean = false) {
        VideoDownloaderEngine.cancelTask(context, task.id, deleteRecord)
    }

    fun retryDownload(context: Context, task: DownloadTask) {
        viewModelScope.launch {
            repository.updateTask(
                task.copy(
                    status = "QUEUED",
                    progress = 0f,
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    errorMessage = null,
                    timestamp = System.currentTimeMillis()
                )
            )
            VideoDownloaderEngine.startDownload(context.applicationContext, task.id)
        }
    }

    // File Operations
    fun renameTaskFile(context: Context, task: DownloadTask, newTitle: String) {
        viewModelScope.launch {
            VideoFileHelper.renameVideo(context, task, newTitle, repository)
        }
    }

    fun deleteTaskAndFile(context: Context, task: DownloadTask) {
        viewModelScope.launch {
            VideoFileHelper.deleteVideo(context, task, repository)
        }
    }

    fun shareTaskFile(context: Context, task: DownloadTask) {
        VideoFileHelper.shareVideo(context, task)
    }

    /**
     * Trigger Start.io Interstitial Ad shown after every 3 successful downloads.
     */
    fun checkAndShowDownloadSuccessAd(context: Context, onAdDismissed: () -> Unit) {
        AdManager.onDownloadSuccess(context, onAdDismissed)
    }

    /**
     * Trigger Start.io Rewarded Video Ad to unlock multi-threaded high-speed download mode.
     */
    fun unlockPremiumViaRewardedAd(context: Context, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        AdManager.showRewardedAd(context, onSuccess, onFailure)
    }

    fun removePremiumState() {
        AdManager.setPremiumState(false)
    }
}

class DownloadViewModelFactory(private val repository: DownloadRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DownloadViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
