package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val format: String, // "MP3" or "MP4"
    val fileSize: String,
    val filePath: String,
    val timestamp: Long = System.currentTimeMillis()
)
