package com.hfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hfilter.MainActivity
import com.hfilter.util.HostManager
import com.hfilter.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class AdBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var hostManager: HostManager
    private lateinit var settingsManager: SettingsManager
    private val executor = Executors.newFixedThreadPool(5)
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        hostManager = HostManager(this)
        hostManager.loadFromCache()
        settingsManager = SettingsManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            serviceScope.launch {
                settingsManager.setVpnEnabled(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            executor.execute {
                runVpn()
            }
        }
        return START_STICKY
    }

    private fun runVpn() {
        try {
            val builder = Builder()
                .setSession("H-filter")
                .addAddress("10.0.0.1", 24)
                .addDnsServer("8.8.8.8")
                // Only route common DNS servers to intercept them
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                .addRoute("1.1.1.1", 32)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e("AdBlockVpn", "Failed to establish VPN interface")
                stopVpn()
                return
            }

            val input = FileInputStream(vpnInterface?.fileDescriptor)
            val output = FileOutputStream(vpnInterface?.fileDescriptor)

            val buffer = ByteBuffer.allocate(32767)

            while (isRunning) {
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    processPacket(buffer, output)
                    buffer.clear()
                }
            }
        } catch (e: Exception) {
            Log.e("AdBlockVpn", "Error in VPN loop", e)
        } finally {
            stopVpn()
        }
    }

    private fun processPacket(buffer: ByteBuffer, output: FileOutputStream) {
        try {
            if (buffer.remaining() < 28) return

            val versionIhl = buffer.get(0).toInt() and 0xFF
            val ihl = (versionIhl and 0x0F) * 4
            val protocol = buffer.get(9).toInt() and 0xFF

            if (protocol == 17) { // UDP
                val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)
                if (dstPort == 53) {
                    handleDnsQuery(buffer, ihl + 8)
                }
            }
        } catch (e: Exception) {
            Log.e("AdBlockVpn", "Error processing packet", e)
        }
    }

    private fun handleDnsQuery(packetBuffer: ByteBuffer, dnsOffset: Int) {
        val dnsData = ByteArray(packetBuffer.remaining() - dnsOffset)
        packetBuffer.position(dnsOffset)
        packetBuffer.get(dnsData)

        val domain = parseDnsDomain(dnsData)
        if (domain != null) {
            if (hostManager.isBlocked(domain)) {
                Log.d("AdBlockVpn", "Blocked domain: $domain")
                // By not forwarding, we effectively block it (timeout)
                // A better way is to send back a REFUSED or 0.0.0.0 response
            } else {
                // In a full implementation, we would forward this and return the response.
                // Intercepting and forwarding DNS is complex because we need to spoof the response IP.
                Log.d("AdBlockVpn", "Allowed domain: $domain")
            }
        }
    }

    private fun parseDnsDomain(dnsData: ByteArray): String? {
        try {
            if (dnsData.size < 12) return null
            var pos = 12
            val sb = StringBuilder()
            while (pos < dnsData.size) {
                val len = dnsData[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > dnsData.size) break
                for (i in 0 until len) {
                    sb.append(dnsData[pos].toInt().toChar())
                    pos++
                }
                sb.append('.')
            }
            if (sb.isNotEmpty() && sb.last() == '.') sb.setLength(sb.length - 1)
            return sb.toString()
        } catch (e: Exception) {
            return null
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false
        serviceScope.launch {
            settingsManager.setVpnEnabled(false)
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("AdBlockVpn", "Error closing interface", e)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "H-filter VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AdBlockVpnService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("H-filter is Active")
            .setContentText("Ad-blocking in progress...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }
}
