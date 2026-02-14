package com.hfilter

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hfilter_settings", Context.MODE_PRIVATE)

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", false)
        set(value) = prefs.edit().putBoolean("start_on_boot", value).apply()

    var autoUpdateOnStart: Boolean
        get() = prefs.getBoolean("auto_update_on_start", true)
        set(value) = prefs.edit().putBoolean("auto_update_on_start", value).apply()

    var isVpnPaused: Boolean
        get() = prefs.getBoolean("vpn_paused", false)
        set(value) = prefs.edit().putBoolean("vpn_paused", value).apply()
}
