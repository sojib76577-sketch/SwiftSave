package com.example.data.repository

import com.example.data.database.DownloadDao
import com.example.data.database.DownloadTask
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val downloadDao: DownloadDao) {
    val allTasks: Flow<List<DownloadTask>> = downloadDao.getAllTasks()

    fun getTasksByStatus(status: String): Flow<List<DownloadTask>> =
        downloadDao.getTasksByStatus(status)

    suspend fun getTaskById(id: Int): DownloadTask? =
        downloadDao.getTaskById(id)

    suspend fun getTaskByUrl(url: String): DownloadTask? =
        downloadDao.getTaskByUrl(url)

    suspend fun insertTask(task: DownloadTask): Long =
        downloadDao.insertTask(task)

    suspend fun updateTask(task: DownloadTask) =
        downloadDao.updateTask(task)

    suspend fun deleteTask(task: DownloadTask) =
        downloadDao.deleteTask(task)

    suspend fun deleteTaskById(id: Int) =
        downloadDao.deleteTaskById(id)
}
