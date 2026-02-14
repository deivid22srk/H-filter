package com.hfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hfilter.MainActivity
import com.hfilter.util.HostManager
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
    private val executor = Executors.newFixedThreadPool(5)
    private var isRunning = false
    private val dnsForwarderSocket = DatagramSocket()

    companion object {
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        hostManager = HostManager(this)
        hostManager.loadFromCache()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            startForeground(NOTIFICATION_ID, createNotification())
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
                .addRoute("8.8.8.8", 32)

            vpnInterface = builder.establish()
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
        if (buffer.remaining() < 28) return // Min IP + UDP header

        val versionIhl = buffer.get(0).toInt() and 0xFF
        val version = versionIhl shr 4
        if (version != 4) return // Only IPv4 for simplicity

        val ihl = (versionIhl and 0x0F) * 4
        val protocol = buffer.get(9).toInt() and 0xFF

        if (protocol == 17) { // UDP
            val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 1).toInt() and 0xFF)
            val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)

            if (dstPort == 53) {
                handleDnsQuery(buffer, ihl + 8, output)
            }
        }
    }

    private fun handleDnsQuery(packetBuffer: ByteBuffer, dnsOffset: Int, output: FileOutputStream) {
        val dnsData = ByteArray(packetBuffer.remaining() - dnsOffset)
        packetBuffer.position(dnsOffset)
        packetBuffer.get(dnsData)

        val domain = parseDnsDomain(dnsData)
        if (domain != null && hostManager.isBlocked(domain)) {
            Log.d("AdBlockVpn", "Blocked: $domain")
            // In a real implementation, forge a 0.0.0.0 response here.
            // For now, we just drop the packet to "block" it.
        } else {
            // Forward to real DNS
            executor.execute {
                try {
                    val forwardPacket = DatagramPacket(dnsData, dnsData.size, InetAddress.getByName("8.8.8.8"), 53)
                    dnsForwarderSocket.send(forwardPacket)

                    val responseData = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseData, responseData.size)
                    dnsForwarderSocket.receive(responsePacket)

                    // We'd need to send the response back to the TUN interface.
                    // This requires forging an IP/UDP packet.
                } catch (e: Exception) {
                    Log.e("AdBlockVpn", "DNS forwarding failed", e)
                }
            }
        }
    }

    private fun parseDnsDomain(dnsData: ByteArray): String? {
        try {
            var pos = 12 // Skip DNS header
            val sb = StringBuilder()
            while (pos < dnsData.size) {
                val len = dnsData[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
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
        isRunning = false
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
                "VPN Service",
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
            .setContentText("Blocking ads...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        dnsForwarderSocket.close()
        super.onDestroy()
    }
}
