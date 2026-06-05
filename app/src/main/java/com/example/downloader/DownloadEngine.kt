package com.example.downloader

import android.content.Context
import android.util.Log
import com.example.helper.MediaStoreHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL

object DownloadEngine {
    private const val TAG = "DownloadEngine"
    
    // Configurable Cobalt API Endpoint
    var COBALT_API_URL = "https://api.cobalt.tools/api/json"

    // Configurable Custom Backend Options
    var USE_CUSTOM_BACKEND = false
    var CUSTOM_BACKEND_URL = "http://localhost:8000"

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val responseAdapter = moshi.adapter(CobaltResponse::class.java)

    suspend fun downloadMedia(
        context: Context,
        inputUrl: String,
        isAudioOnly: Boolean,
        onProgress: (Int) -> Unit,
        onStatusChange: (String) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val cleanUrl = inputUrl.trim()
        if (cleanUrl.isEmpty()) {
            return@withContext DownloadResult.Error("URL boş olamaz.")
        }

        // Extract simple descriptive title from URL
        val domain = try {
            val host = URL(cleanUrl).host
            host.replace("www.", "").split(".")[0].uppercase()
        } catch (e: Exception) {
            "SOLENZ"
        }
        
        val randomSuffix = (1000..9999).random()
        val baseTitle = "${domain}_Media_$randomSuffix"

        onStatusChange("Ağ Bağlantısı Çözümleniyor...")
        onProgress(5)

        var directDownloadUrl: String? = null
        var extractionError: String? = null

        // If it's a social media link, attempt backend extraction
        val lowercaseUrl = cleanUrl.lowercase()
        val isSocialMedia = lowercaseUrl.contains("youtube.com") || lowercaseUrl.contains("youtu.be") ||
                            lowercaseUrl.contains("tiktok.com") || lowercaseUrl.contains("instagram.com") ||
                            lowercaseUrl.contains("twitter.com") || lowercaseUrl.contains("x.com") ||
                            lowercaseUrl.contains("facebook.com") || lowercaseUrl.contains("reddit.com")

        if (isSocialMedia) {
            onStatusChange("Video & Ses Çözümleme Başlatıldı...")
            onProgress(15)
            
            if (USE_CUSTOM_BACKEND) {
                try {
                    onStatusChange("Özel Solenz Backend Çözümlemesi...")
                    directDownloadUrl = extractWithCustomBackend(cleanUrl, isAudioOnly)
                } catch (e: Exception) {
                    Log.e(TAG, "Custom backend failed, attempting Cobalt fall back", e)
                    onStatusChange("Özel API Hatası! Cobalt Deneniyor...")
                    try {
                        directDownloadUrl = extractWithCobalt(cleanUrl, isAudioOnly)
                    } catch (ex: Exception) {
                        extractionError = "Özel API: ${e.message}\nCobalt API: ${ex.message}"
                    }
                }
            } else {
                try {
                    directDownloadUrl = extractWithCobalt(cleanUrl, isAudioOnly)
                } catch (e: Exception) {
                    extractionError = e.message ?: "Cobalt API ayrıştırma hatası"
                    Log.e(TAG, "Cobalt extraction failed", e)
                }
            }

            if (directDownloadUrl == null && extractionError == null) {
                extractionError = "Her hangi bir indirme bağlantısı algılanamadı."
            }
        } else if (lowercaseUrl.endsWith(".mp3") || lowercaseUrl.endsWith(".mp4") || lowercaseUrl.contains(".mp3?") || lowercaseUrl.contains(".mp4?")) {
            directDownloadUrl = cleanUrl
        } else {
            // General direct link download fallback
            directDownloadUrl = cleanUrl
        }

        if (directDownloadUrl != null) {
            onStatusChange("Dosya Boyutu Hesaplanıyor...")
            onProgress(25)
            try {
                val request = Request.Builder().url(directDownloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext DownloadResult.Error("Medya indirme hatası (Sunucu kodu: ${response.code})")
                    }
                    
                    val body = response.body
                    if (body == null) {
                        return@withContext DownloadResult.Error("Boş dosya gövdesi (Empty Response Body)")
                    }

                    val totalBytes = body.contentLength()
                    val inputStream = body.byteStream()
                    val sizeFormatted = formatBytes(if (totalBytes > 0) totalBytes else 0L)

                    onStatusChange("İndiriliyor...")
                    onProgress(30)
                    
                    val savedPath = if (isAudioOnly) {
                        MediaStoreHelper.saveAudioToMusic(
                            context = context,
                            audioStream = inputStream,
                            displayName = baseTitle,
                            totalSize = totalBytes,
                            onProgress = { progress -> onProgress((30 + (progress * 0.7)).toInt()) }
                        )
                    } else {
                        MediaStoreHelper.saveVideoToMovies(
                            context = context,
                            videoStream = inputStream,
                            displayName = baseTitle,
                            totalSize = totalBytes,
                            onProgress = { progress -> onProgress((30 + (progress * 0.7)).toInt()) }
                        )
                    }

                    if (savedPath != null) {
                        onProgress(100)
                        onStatusChange("Kaydedildi!")
                        return@withContext DownloadResult.Success(
                            title = baseTitle,
                            filePath = savedPath,
                            fileSize = sizeFormatted
                        )
                    } else {
                        return@withContext DownloadResult.Error("Dosya kayıt klasörüne yazılırken hata oluştu.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct transfer failed", e)
                return@withContext DownloadResult.Error("Dosya akış transfer hatası: ${e.localizedMessage ?: "Bağlantı koptu"}")
            }
        } else {
            val fullError = if (extractionError != null) {
                "Link Çözümlenemedi!\nHata: $extractionError"
            } else {
                "Girdiğiniz link desteklenmiyor veya API tarafından çözümlenemedi."
            }
            return@withContext DownloadResult.Error(fullError)
        }
    }

    private fun extractWithCobalt(url: String, isAudioOnly: Boolean): String? {
        // Build payload matching all Cobalt API versions (v7-v10 formats)
        val payloadMap = mutableMapOf<String, Any>(
            "url" to url
        )
        if (isAudioOnly) {
            payloadMap["audioOnly"] = true
            payloadMap["audioFormat"] = "mp3"
            payloadMap["isAudioOnly"] = true
            payloadMap["aFormat"] = "mp3"
        } else {
            payloadMap["videoQuality"] = "720"
        }

        val jsonPayload = moshi.adapter(Map::class.java).toJson(payloadMap)
        val requestBody = jsonPayload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(COBALT_API_URL)
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .build()
            
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: throw Exception("Boş API cevabı (Empty Response)")
            if (!response.isSuccessful) {
                // Parse error text if available in response
                val apiErr = try {
                    responseAdapter.fromJson(bodyString)?.text
                } catch (e: Exception) {
                    null
                }
                throw Exception(apiErr ?: "HTTP Hata Kodu ${response.code}")
            }

            val cobaltRes = responseAdapter.fromJson(bodyString)
            if (cobaltRes?.status == "error") {
                throw Exception(cobaltRes.text ?: "Genel API hatası")
            }
            if (cobaltRes?.status == "picker" && !cobaltRes.picker.isNullOrEmpty()) {
                return cobaltRes.picker.firstOrNull { it.url != null }?.url
            }
            return cobaltRes?.url
        }
    }

    private fun extractWithCustomBackend(url: String, isAudioOnly: Boolean): String? {
        val payloadMap = mapOf("url" to url)
        val jsonPayload = moshi.adapter(Map::class.java).toJson(payloadMap)
        val requestBody = jsonPayload.toRequestBody("application/json".toMediaType())
        
        val urlToCall = if (CUSTOM_BACKEND_URL.endsWith("/")) {
            "${CUSTOM_BACKEND_URL}api/extract"
        } else {
            "${CUSTOM_BACKEND_URL}/api/extract"
        }

        val request = Request.Builder()
            .url(urlToCall)
            .post(requestBody)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .build()
            
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: throw Exception("Boş API cevabı (Empty Response)")
            if (!response.isSuccessful) {
                throw Exception("HTTP Hata Kodu ${response.code}")
            }

            val customAdapter = moshi.adapter(CustomBackendResponse::class.java)
            val res = customAdapter.fromJson(bodyString)
            if (res == null || !res.success) {
                throw Exception("Özel API başarısız veya geçersiz sonuç döndü.")
            }
            
            val directLink = if (isAudioOnly) {
                res.media?.audio_url ?: res.media?.video_url
            } else {
                res.media?.video_url ?: res.media?.audio_url
            }
            
            return directLink
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Bilinmeyen Boyut"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}

// Result encapsulation
sealed class DownloadResult {
    data class Success(val title: String, val filePath: String, val fileSize: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

// Parsing adapters
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class CobaltResponse(
    val status: String? = null,
    val url: String? = null,
    val text: String? = null,
    val picker: List<CobaltPickerItem>? = null
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class CobaltPickerItem(
    val url: String? = null,
    val type: String? = null
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class CustomBackendResponse(
    val success: Boolean,
    val title: String,
    val thumbnail: String? = null,
    val duration: Int? = 0,
    val media: CustomBackendMedia? = null
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class CustomBackendMedia(
    val video_url: String? = null,
    val audio_url: String? = null
)
