package com.hfilter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class HostManager(private val context: Context) {
    private val client = OkHttpClient()
    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val hostSourcesFile = File(context.filesDir, "host_sources_v2.json")
    private val hostsCacheFile = File(context.filesDir, "hosts_cache.txt")

    suspend fun load() = withContext(Dispatchers.IO) {
        loadCachedHosts()
    }

    fun getHostSources(): List<HostSource> {
        if (!hostSourcesFile.exists()) {
            val defaults = listOf(
                HostSource(name = "HaGeZi Ultimate", url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt"),
                HostSource(name = "StevenBlack Hosts", url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")
            )
            saveHostSources(defaults)
            return defaults
        }
        return try {
            val json = JSONArray(hostSourcesFile.readText())
            val sources = mutableListOf<HostSource>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                sources.add(HostSource(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    enabled = obj.getBoolean("enabled")
                ))
            }
            sources
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHostSources(sources: List<HostSource>) {
        val json = JSONArray()
        sources.forEach { source ->
            val obj = JSONObject()
            obj.put("id", source.id)
            obj.put("name", source.name)
            obj.put("url", source.url)
            obj.put("enabled", source.enabled)
            json.put(obj)
        }
        hostSourcesFile.writeText(json.toString())
    }

    private fun loadCachedHosts() {
        if (hostsCacheFile.exists()) {
            hostsCacheFile.forEachLine { line ->
                if (line.isNotBlank()) {
                    blockedDomains.add(line.trim().lowercase())
                }
            }
        }
    }

    suspend fun refreshHosts() = withContext(Dispatchers.IO) {
        val newBlockedDomains = mutableSetOf<String>()
        val sources = getHostSources().filter { it.enabled }

        for (source in sources) {
            try {
                val request = Request.Builder().url(source.url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.bufferedReader()?.useLines { lines ->
                            lines.forEach { line ->
                                val domain = parseHostLine(line)
                                if (domain != null) {
                                    newBlockedDomains.add(domain)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HostManager", "Error fetching source: ${source.url}", e)
            }
        }

        blockedDomains.clear()
        blockedDomains.addAll(newBlockedDomains)

        // Cache them
        hostsCacheFile.bufferedWriter().use { writer ->
            blockedDomains.forEach {
                writer.write(it)
                writer.newLine()
            }
        }
    }

    internal fun parseHostLine(line: String): String? {
        var trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null

        val commentIndex = trimmed.indexOf('#')
        if (commentIndex != -1) {
            trimmed = trimmed.substring(0, commentIndex).trim()
        }

        val parts = trimmed.split(Regex("\\s+"))
        val domain = if (parts.size >= 2) {
            if (parts[0].matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) {
                parts[1]
            } else {
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
        return blockedDomains.contains(domain.lowercase())
    }

    fun getBlockedCount(): Int = blockedDomains.size
}
