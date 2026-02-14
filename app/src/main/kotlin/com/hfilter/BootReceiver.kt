package com.hfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
                if (settingsManager.startOnBoot.first() && settingsManager.vpnEnabled.first()) {
                    val vpnIntent = Intent(context, AdBlockVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        context.startService(vpnIntent)
                    }
                }
            }
        }
    }
}
