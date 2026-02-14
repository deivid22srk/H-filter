package com.hfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hfilter.service.AdBlockVpnService
import com.hfilter.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val settingsManager = SettingsManager(context)
            CoroutineScope(Dispatchers.IO).launch {
                if (settingsManager.vpnEnabled.first()) {
                    context.startService(Intent(context, AdBlockVpnService::class.java))
                }
            }
        }
    }
}
