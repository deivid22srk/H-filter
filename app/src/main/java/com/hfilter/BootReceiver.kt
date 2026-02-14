package com.hfilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val settingsManager = SettingsManager(context)
            if (settingsManager.startOnBoot) {
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent == null) {
                    val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                        action = "START"
                    }
                    context.startService(startIntent)
                }
            }
        }
    }
}
