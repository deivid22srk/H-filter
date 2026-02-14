package com.hfilter.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.hfilter.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val settingsManager = SettingsManager(this)
        scope.launch {
            val enabled = settingsManager.vpnEnabled.first()
            val tile = qsTile
            if (tile != null) {
                tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (enabled) "Active" else "Inactive"
                }
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val settingsManager = SettingsManager(this)
        scope.launch {
            val enabled = settingsManager.vpnEnabled.first()
            val nextState = !enabled

            settingsManager.setVpnEnabled(nextState)

            if (nextState) {
                val intent = VpnService.prepare(this@VpnTileService)
                if (intent == null) {
                    startVpn()
                } else {
                    // Cannot request permission from TileService directly easily
                    // User needs to open the app
                }
            } else {
                stopVpn()
            }
            updateTileState()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply { action = "STOP" }
        startService(intent)
    }
}
