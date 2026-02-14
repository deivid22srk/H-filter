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
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AdBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var hostManager: HostManager
    private lateinit var settingsManager: SettingsManager
    private val executor = Executors.newFixedThreadPool(10)
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dnsForwarderSocket: DatagramSocket

    companion object {
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        hostManager = HostManager(this)
        serviceScope.launch {
            hostManager.loadFromCache()
        }
        settingsManager = SettingsManager(this)
        dnsForwarderSocket = DatagramSocket()
        protect(dnsForwarderSocket)
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
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                .addRoute("1.1.1.1", 32)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
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

            val packet = buffer.array().copyOfRange(buffer.position(), buffer.limit())
            val versionIhl = packet[0].toInt() and 0xFF
            val ihl = (versionIhl and 0x0F) * 4
            val protocol = packet[9].toInt() and 0xFF

            if (protocol == 17) { // UDP
                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

                if (dstPort == 53) {
                    val srcIp = packet.copyOfRange(12, 16)
                    val dstIp = packet.copyOfRange(16, 20)
                    val dnsData = packet.copyOfRange(ihl + 8, packet.size)

                    handleDnsQuery(dnsData, srcIp, dstIp, srcPort, dstPort, output)
                }
            }
        } catch (e: Exception) {
            Log.e("AdBlockVpn", "Error processing packet", e)
        }
    }

    private fun handleDnsQuery(dnsData: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, output: FileOutputStream) {
        val domain = parseDnsDomain(dnsData)
        if (domain != null && hostManager.isBlocked(domain)) {
            Log.d("AdBlockVpn", "Blocked: $domain")
            val forgedResponse = forgeDnsResponse(dnsData, domain, true)
            sendUdpPacket(forgedResponse, dstIp, srcIp, dstPort, srcPort, output)
        } else {
            executor.execute {
                try {
                    val forwardPacket = DatagramPacket(dnsData, dnsData.size, InetAddress.getByAddress(dstIp), 53)
                    dnsForwarderSocket.send(forwardPacket)

                    val responseData = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseData, responseData.size)
                    dnsForwarderSocket.receive(responsePacket)

                    val actualResponse = responsePacket.data.copyOfRange(0, responsePacket.length)
                    sendUdpPacket(actualResponse, dstIp, srcIp, dstPort, srcPort, output)
                } catch (e: Exception) {
                    Log.e("AdBlockVpn", "DNS forward error for $domain", e)
                }
            }
        }
    }

    private fun sendUdpPacket(payload: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, output: FileOutputStream) {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteBuffer.allocate(totalLength)

        // IP Header
        packet.put(0x45.toByte()) // Version & IHL
        packet.put(0.toByte()) // DSCP & ECN
        packet.putShort(totalLength.toShort())
        packet.putShort(0.toShort()) // ID
        packet.putShort(0x4000.toShort()) // Flags & Offset (Don't Fragment)
        packet.put(64.toByte()) // TTL
        packet.put(17.toByte()) // Protocol (UDP)
        packet.putShort(0.toShort()) // Checksum placeholder
        packet.put(srcIp)
        packet.put(dstIp)

        // Update IP Checksum
        val ipChecksum = calculateChecksum(packet.array(), 20)
        packet.putShort(10, ipChecksum)

        // UDP Header
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort((8 + payload.size).toShort())
        packet.putShort(0.toShort()) // Checksum (optional for IPv4)

        packet.put(payload)

        synchronized(output) {
            output.write(packet.array())
        }
    }

    private fun calculateChecksum(buffer: ByteArray, length: Int): Short {
        var sum = 0
        var i = 0
        while (i < length - 1) {
            sum += (buffer[i].toInt() and 0xFF shl 8) or (buffer[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) {
            sum += (buffer[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun forgeDnsResponse(queryData: ByteArray, domain: String, blocked: Boolean): ByteArray {
        val queryBuffer = ByteBuffer.wrap(queryData)
        val response = ByteBuffer.allocate(queryData.size + 16)
        response.put(queryData.copyOfRange(0, 2)) // ID
        response.put(0x81.toByte()) // Flags: QR, Opcode, AA, TC, RD
        response.put(0x80.toByte()) // Flags: RA, Z, RCODE (Success)
        response.putShort(queryBuffer.getShort(4)) // QDCOUNT
        response.putShort(1.toShort()) // ANCOUNT
        response.putShort(0.toShort()) // NSCOUNT
        response.putShort(0.toShort()) // ARCOUNT

        // Question section (copy from query)
        var pos = 12
        while (pos < queryData.size && queryData[pos] != 0.toByte()) {
            pos += (queryData[pos].toInt() and 0xFF) + 1
        }
        pos += 5 // Null terminator + QTYPE + QCLASS
        response.put(queryData.copyOfRange(12, pos))

        // Answer section
        response.put(0xC0.toByte()) // Name pointer to offset 12
        response.put(0x0C.toByte())
        response.putShort(1.toShort()) // Type A
        response.putShort(1.toShort()) // Class IN
        response.putInt(60) // TTL
        response.putShort(4.toShort()) // Data length
        response.put(byteArrayOf(0, 0, 0, 0)) // IP 0.0.0.0

        return response.array().copyOfRange(0, response.position())
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
        dnsForwarderSocket.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}
