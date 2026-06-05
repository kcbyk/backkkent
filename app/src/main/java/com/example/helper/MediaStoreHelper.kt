package com.example.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream

object MediaStoreHelper {

    fun saveAudioToMusic(
        context: Context,
        audioStream: java.io.InputStream,
        displayName: String,
        mimeType: String = "audio/mpeg",
        totalSize: Long = -1L,
        onProgress: (Int) -> Unit = {}
    ): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$displayName.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.TITLE, displayName)
            put(MediaStore.Audio.Media.ARTIST, "Solenz Utility")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SolenzDownloads")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val fileUri = resolver.insert(collectionUri, contentValues) ?: return null

        try {
            resolver.openOutputStream(fileUri)?.use { outputStream ->
                copyStream(audioStream, outputStream, totalSize, onProgress)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(fileUri, contentValues, null, null)
            }
            
            // Media Scanner Triggering block: Scan absolute file path
            getPathFromUri(context, fileUri)?.let { absolutePath ->
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(absolutePath),
                    arrayOf(mimeType),
                    null
                )
            }

            return fileUri.toString()
        } catch (e: Exception) {
            resolver.delete(fileUri, null, null)
            e.printStackTrace()
            return null
        }
    }

    fun saveVideoToMovies(
        context: Context,
        videoStream: java.io.InputStream,
        displayName: String,
        mimeType: String = "video/mp4",
        totalSize: Long = -1L,
        onProgress: (Int) -> Unit = {}
    ): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.TITLE, displayName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SolenzDownloads")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val fileUri = resolver.insert(collectionUri, contentValues) ?: return null

        try {
            resolver.openOutputStream(fileUri)?.use { outputStream ->
                copyStream(videoStream, outputStream, totalSize, onProgress)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(fileUri, contentValues, null, null)
            }

            // Media Scanner Triggering block: Scan absolute file path
            getPathFromUri(context, fileUri)?.let { absolutePath ->
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(absolutePath),
                    arrayOf(mimeType),
                    null
                )
            }

            return fileUri.toString()
        } catch (e: Exception) {
            resolver.delete(fileUri, null, null)
            e.printStackTrace()
            return null
        }
    }

    private fun copyStream(
        input: InputStream, 
        output: OutputStream, 
        totalSize: Long,
        onProgress: (Int) -> Unit
    ) {
        val buffer = ByteArray(1024 * 32)
        var bytesRead: Int
        var totalBytesRead = 0L
        val sizeToUse = if (totalSize > 0) totalSize else 1024 * 1024 * 10 // default 10MB approx for styling if undefined
        
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalBytesRead += bytesRead
            val percentage = ((totalBytesRead * 100) / sizeToUse).coerceIn(0, 100).toInt()
            onProgress(percentage)
        }
        output.flush()
        onProgress(100)
    }

    private fun getPathFromUri(context: Context, contentUri: Uri): String? {
        var path: String? = null
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        try {
            context.contentResolver.query(contentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    path = cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return path
    }
}
