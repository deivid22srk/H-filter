package com.hfilter

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class VpnTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val tile = qsTile
        if (tile.state == Tile.STATE_ACTIVE) {
            stopVpn()
            tile.state = Tile.STATE_INACTIVE
        } else {
            startVpn()
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    private fun startVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = "START"
        }
        startService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }

    override fun onStartListening() {
        super.onStartListening()
        // We could use a broadcast or shared state to check if VPN is actually running
        // For now, let's keep it simple.
    }
}
