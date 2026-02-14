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

    val vpnEnabled: Flow<Boolean> = context.dataStore.data.map { it[VPN_ENABLED_KEY] ?: false }

    suspend fun setVpnEnabled(enabled: Boolean) {
        context.dataStore.edit { it[VPN_ENABLED_KEY] = enabled }
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
}
