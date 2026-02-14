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
        var trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null

        // Remove trailing comments
        val commentIndex = trimmed.indexOf('#')
        if (commentIndex != -1) {
            trimmed = trimmed.substring(0, commentIndex).trim()
        }

        // Handles formats like "127.0.0.1 domain.com" or just "domain.com"
        val parts = trimmed.split(Regex("\\s+"))
        val domain = if (parts.size >= 2) {
            // Check if first part is an IP
            if (parts[0].matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) {
                parts[1]
            } else {
                // Adblock format often has ||domain.com^
                cleanAdblockDomain(parts[0])
            }
        } else {
            cleanAdblockDomain(parts[0])
        }

        return if (domain.contains(".") && !domain.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) {
            domain.lowercase()
        } else {
            null
        }
    }

    private fun cleanAdblockDomain(input: String): String {
        var domain = input
        if (domain.startsWith("||")) domain = domain.substring(2)
        val caretIndex = domain.indexOf('^')
        if (caretIndex != -1) domain = domain.substring(0, caretIndex)
        val slashIndex = domain.indexOf('/')
        if (slashIndex != -1) domain = domain.substring(0, slashIndex)
        return domain
    }

    fun isBlocked(domain: String): Boolean {
        return blockedDomains.contains(domain)
    }
}
