package com.hfilter.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hfilter.model.HostSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    private val gson = Gson()
    private val HOST_SOURCES_KEY = stringPreferencesKey("host_sources")
    private val VPN_ENABLED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("vpn_enabled")
    private val AUTO_UPDATE_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("auto_update")
    private val START_ON_BOOT_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("start_on_boot")
    private val DNS_LOGGING_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("dns_logging")
    private val FILTERING_MODE_KEY = stringPreferencesKey("filtering_mode")
    private val APP_PREDEFINITIONS_KEY = stringPreferencesKey("app_predefinitions")
    private val CAPTURE_SESSION_APPS_KEY = stringPreferencesKey("capture_session_apps")

    val vpnEnabled: Flow<Boolean> = context.dataStore.data.map { it[VPN_ENABLED_KEY] ?: false }
    val autoUpdate: Flow<Boolean> = context.dataStore.data.map { it[AUTO_UPDATE_KEY] ?: false }
    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { it[START_ON_BOOT_KEY] ?: false }
    val dnsLogging: Flow<Boolean> = context.dataStore.data.map { it[DNS_LOGGING_KEY] ?: false }
    val filteringMode: Flow<com.hfilter.model.FilteringMode> = context.dataStore.data.map {
        val modeStr = it[FILTERING_MODE_KEY] ?: com.hfilter.model.FilteringMode.BOTH.name
        try { com.hfilter.model.FilteringMode.valueOf(modeStr) } catch (e: Exception) { com.hfilter.model.FilteringMode.BOTH }
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        context.dataStore.edit { it[VPN_ENABLED_KEY] = enabled }
    }

    suspend fun setAutoUpdate(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_KEY] = enabled }
    }

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[START_ON_BOOT_KEY] = enabled }
    }

    suspend fun setDnsLogging(enabled: Boolean) {
        context.dataStore.edit { it[DNS_LOGGING_KEY] = enabled }
    }

    suspend fun setFilteringMode(mode: com.hfilter.model.FilteringMode) {
        context.dataStore.edit { it[FILTERING_MODE_KEY] = mode.name }
    }

    val appPredefinitions: Flow<List<com.hfilter.model.AppPredefinition>> = context.dataStore.data.map { preferences ->
        val json = preferences[APP_PREDEFINITIONS_KEY]
        if (json == null) {
            emptyList()
        } else {
            val type = object : TypeToken<List<com.hfilter.model.AppPredefinition>>() {}.type
            gson.fromJson(json, type)
        }
    }

    suspend fun saveAppPredefinitions(list: List<com.hfilter.model.AppPredefinition>) {
        context.dataStore.edit { it[APP_PREDEFINITIONS_KEY] = gson.toJson(list) }
    }

    val captureSessionApps: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val json = preferences[CAPTURE_SESSION_APPS_KEY]
        if (json == null) emptyList() else gson.fromJson(json, object : TypeToken<List<String>>() {}.type)
    }

    suspend fun setCaptureSessionApps(apps: List<String>) {
        context.dataStore.edit { it[CAPTURE_SESSION_APPS_KEY] = gson.toJson(apps) }
    }

    val hostSources: Flow<List<HostSource>> = context.dataStore.data.map { preferences ->
        val json = preferences[HOST_SOURCES_KEY]
        if (json == null) {
            listOf(
                HostSource(name = "StevenBlack Hosts", url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", type = com.hfilter.model.SourceType.BUILT_IN),
                HostSource(name = "HaGeZi Ultimate", url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt", type = com.hfilter.model.SourceType.BUILT_IN)
            )
        } else {
            val type = object : TypeToken<List<HostSource>>() {}.type
            gson.fromJson(json, type)
        }
    }

    suspend fun saveHostSources(sources: List<HostSource>) {
        context.dataStore.edit { preferences ->
            preferences[HOST_SOURCES_KEY] = gson.toJson(sources)
        }
    }

    suspend fun addHostSources(newSources: List<HostSource>) {
        context.dataStore.edit { preferences ->
            val json = preferences[HOST_SOURCES_KEY]
            val currentList = if (json == null) {
                listOf(
                    HostSource(name = "StevenBlack Hosts", url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", type = com.hfilter.model.SourceType.BUILT_IN),
                    HostSource(name = "HaGeZi Ultimate", url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt", type = com.hfilter.model.SourceType.BUILT_IN)
                )
            } else {
                val type = object : TypeToken<List<HostSource>>() {}.type
                gson.fromJson<List<HostSource>>(json, type)
            }
            val updatedList = currentList + newSources
            preferences[HOST_SOURCES_KEY] = gson.toJson(updatedList)
        }
    }
}
