package com.example.repository

import com.example.db.DownloadDao
import com.example.db.DownloadHistory
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val downloadDao: DownloadDao) {
    val allHistory: Flow<List<DownloadHistory>> = downloadDao.getAllHistory()

    suspend fun insert(history: DownloadHistory) = downloadDao.insert(history)
    suspend fun delete(history: DownloadHistory) = downloadDao.delete(history)
    suspend fun clearAll() = downloadDao.clearAll()
}
