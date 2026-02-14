package com.hfilter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class HostManager(private val context: Context) {
    private val client = OkHttpClient()
    private val blockedDomains = mutableSetOf<String>()
    private val hostSourcesFile = File(context.filesDir, "host_sources.txt")
    private val hostsCacheFile = File(context.filesDir, "hosts_cache.txt")

    init {
        loadCachedHosts()
    }

    fun getSources(): List<String> {
        if (!hostSourcesFile.exists()) {
            return listOf(
                "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
            )
        }
        return hostSourcesFile.readLines().filter { it.isNotBlank() }
    }

    fun saveSources(sources: List<String>) {
        hostSourcesFile.writeText(sources.joinToString("\n"))
    }

    private fun loadCachedHosts() {
        if (hostsCacheFile.exists()) {
            hostsCacheFile.forEachLine { line ->
                val domain = parseHostLine(line)
                if (domain != null) {
                    blockedDomains.add(domain)
                }
            }
        }
    }

    suspend fun refreshHosts() = withContext(Dispatchers.IO) {
        val newBlockedDomains = mutableSetOf<String>()
        val sources = getSources()

        for (source in sources) {
            try {
                val request = Request.Builder().url(source).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.lineSequence()?.forEach { line ->
                            val domain = parseHostLine(line)
                            if (domain != null) {
                                newBlockedDomains.add(domain)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HostManager", "Error fetching source: $source", e)
            }
        }

        blockedDomains.clear()
        blockedDomains.addAll(newBlockedDomains)

        // Cache them
        hostsCacheFile.writeText(blockedDomains.joinToString("\n"))
    }

    internal fun parseHostLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        // Handles formats like "127.0.0.1 domain.com" or just "domain.com"
        val parts = trimmed.split(Regex("\\s+"))
        return if (parts.size >= 2) {
            parts[1]
        } else if (parts.size == 1 && !parts[0].contains(".")) {
             null
        } else {
            parts[0]
        }
    }

    fun isBlocked(domain: String): Boolean {
        return blockedDomains.contains(domain)
    }
}
