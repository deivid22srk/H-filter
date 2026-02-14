package com.hfilter.util

import android.content.Context
import android.util.Log
import com.hfilter.model.HostSource
import com.hfilter.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class HostManager(private val context: Context) {
    private val client = OkHttpClient()
    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // File to cache the combined blocked domains
    private val cacheFile = File(context.cacheDir, "blocked_domains.txt")

    fun isBlocked(domain: String): Boolean {
        val lowerDomain = domain.lowercase()
        if (blockedDomains.contains(lowerDomain)) return true

        // Handle www. prefix
        if (lowerDomain.startsWith("www.") && blockedDomains.contains(lowerDomain.removePrefix("www."))) return true
        if (!lowerDomain.startsWith("www.") && blockedDomains.contains("www.$lowerDomain")) return true

        return false
    }

    suspend fun reload(sources: List<HostSource>) = withContext(Dispatchers.IO) {
        _isLoading.value = true
        blockedDomains.clear()
        val enabledSources = sources.filter { it.enabled }
        val total = enabledSources.size

        enabledSources.forEachIndexed { index, source ->
            _downloadProgress.value = index.toFloat() / total
            try {
                downloadAndParse(source.url)
            } catch (e: Exception) {
                Log.e("HostManager", "Error downloading ${source.url}", e)
            }
        }
        _downloadProgress.value = 1.0f
        saveToCache()
        _downloadProgress.value = null
        _isLoading.value = false
    }

    private fun downloadAndParse(url: String) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            response.body?.charStream()?.forEachLine { line ->
                val domain = parseLine(line)
                if (domain != null) {
                    blockedDomains.add(domain.lowercase())
                }
            }
        }
    }

    private fun parseLine(line: String): String? {
        val noComment = line.split("#")[0].trim()
        if (noComment.isEmpty()) return null

        // Handle "127.0.0.1 domain.com" or just "domain.com"
        val parts = noComment.split(Regex("\\s+"))
        return if (parts.size >= 2) {
            if (parts[0] == "127.0.0.1" || parts[0] == "0.0.0.0") {
                parts[1]
            } else {
                null
            }
        } else {
            // Some lists just have one domain per line
            if (noComment.contains(".")) noComment else null
        }
    }

    private fun saveToCache() {
        cacheFile.bufferedWriter().use { writer ->
            blockedDomains.forEach { domain ->
                writer.write(domain)
                writer.newLine()
            }
        }
    }

    fun loadFromCache() {
        _isLoading.value = true
        if (cacheFile.exists()) {
            cacheFile.forEachLine { line ->
                if (line.isNotEmpty()) {
                    blockedDomains.add(line)
                }
            }
        }
        _isLoading.value = false
    }

    fun getBlockedCount(): Int = blockedDomains.size
}
