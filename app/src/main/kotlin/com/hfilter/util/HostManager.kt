package com.hfilter.util

import android.content.Context
import android.util.Log
import com.hfilter.model.HostSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class HostManager(private val context: Context) {
    private val client = OkHttpClient()
    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Combined cache for fast initial load
    private val cacheFile = File(context.cacheDir, "blocked_domains.txt")
    // Directory for per-source raw host files
    private val hostsDir = File(context.filesDir, "hosts_raw").apply { mkdirs() }

    fun isBlocked(domain: String): Boolean {
        val lowerDomain = domain.lowercase()
        if (blockedDomains.contains(lowerDomain)) return true

        // Handle www. prefix
        if (lowerDomain.startsWith("www.") && blockedDomains.contains(lowerDomain.removePrefix("www."))) return true
        if (!lowerDomain.startsWith("www.") && blockedDomains.contains("www.$lowerDomain")) return true

        return false
    }

    suspend fun reload(sources: List<HostSource>, forceDownload: Boolean = true) = withContext(Dispatchers.IO) {
        _isLoading.value = true
        val enabledSources = sources.filter { it.enabled }
        val total = enabledSources.size

        if (total == 0) {
            blockedDomains.clear()
            saveToCache()
            _isLoading.value = false
            return@withContext
        }

        // Step 1: Ensure we have local files for all enabled sources
        enabledSources.forEachIndexed { index, source ->
            val localFile = getLocalFileForSource(source)
            if (forceDownload || !localFile.exists()) {
                _downloadProgress.value = index.toFloat() / total
                try {
                    downloadToFile(source.url, localFile)
                } catch (e: Exception) {
                    Log.e("HostManager", "Error downloading ${source.url}", e)
                }
            }
        }
        _downloadProgress.value = 1.0f

        // Step 2: Clear memory and parse all enabled local files
        blockedDomains.clear()
        enabledSources.forEach { source ->
            val localFile = getLocalFileForSource(source)
            if (localFile.exists()) {
                parseLocalFile(localFile)
            }
        }

        saveToCache()
        _downloadProgress.value = null
        _isLoading.value = false
    }

    private fun getLocalFileForSource(source: HostSource): File {
        val hash = sha256(source.url)
        return File(hostsDir, "$hash.txt")
    }

    private fun downloadToFile(url: String, file: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            file.outputStream().use { output ->
                response.body?.byteStream()?.copyTo(output)
            }
        }
    }

    private fun parseLocalFile(file: File) {
        file.forEachLine { line ->
            val domain = parseLine(line)
            if (domain != null) {
                blockedDomains.add(domain.lowercase())
            }
        }
    }

    private fun parseLine(line: String): String? {
        val noComment = line.split("#")[0].trim()
        if (noComment.isEmpty()) return null

        val parts = noComment.split(Regex("\\s+"))
        return if (parts.size >= 2) {
            if (parts[0] == "127.0.0.1" || parts[0] == "0.0.0.0") {
                parts[1]
            } else {
                null
            }
        } else {
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

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
