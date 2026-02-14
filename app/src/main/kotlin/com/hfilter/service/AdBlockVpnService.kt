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
import com.hfilter.util.LogManager
import com.hfilter.util.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val executor = Executors.newFixedThreadPool(15)
    private var isRunning = false
    private var isCaptureMode = false
    private var isBlockingInternet = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        hostManager = HostManager(this)
        settingsManager = SettingsManager(this)

        serviceScope.launch {
            hostManager.loadFromCache()
            // Observe host sources, app predefinitions, filtering mode, and capture block session changes
            combine(
                settingsManager.hostSources,
                settingsManager.appPredefinitions,
                settingsManager.filteringMode,
                settingsManager.captureSessionBlockInternet
            ) { sources, preds, mode, _ ->
                Triple(sources, preds, mode)
            }.collect { (sources, preds, mode) ->
                hostManager.reload(sources, preds, mode, forceDownload = false)
                // If we are running, the UI or logic might have triggered a restart of the VPN
                // to apply new routes (via onStartCommand)
            }
        }
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
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            executor.execute {
                runVpn()
            }
        } else {
            // Force interface recreation to pick up potential changes (e.g. app capture session)
            vpnInterface?.close()
            vpnInterface = null
        }
        return START_STICKY
    }

    private fun runVpn() {
        while (isRunning) {
            try {
                val builder = Builder()
                    .setSession("H-filter")
                    .addAddress("10.0.0.1", 24)
                    .addDnsServer("8.8.8.8")
                    .addRoute("8.8.8.8", 32)
                    .addRoute("8.8.4.4", 32)
                    .addRoute("1.1.1.1", 32)

                val captureApps = runBlocking { settingsManager.captureSessionApps.first() }
                val captureBlock = runBlocking { settingsManager.captureSessionBlockInternet.first() }
                val currentMode = runBlocking { settingsManager.filteringMode.first() }
                val predefinitions = runBlocking { settingsManager.appPredefinitions.first() }

                isCaptureMode = captureApps.isNotEmpty()
                val allAppPackages = predefinitions.filter { it.enabled }.flatMap { it.packageNames }.toSet()
                val blockedApps = predefinitions.filter { it.enabled && it.blockInternet }.flatMap { it.packageNames }.toSet()

                isBlockingInternet = captureBlock || blockedApps.isNotEmpty()

                if (isCaptureMode) {
                    captureApps.forEach {
                        try { builder.addAllowedApplication(it) } catch (e: Exception) { Log.e("AdBlockVpn", "Failed to add allowed app: $it", e) }
                    }
                } else if (currentMode == com.hfilter.model.FilteringMode.APPS || currentMode == com.hfilter.model.FilteringMode.BOTH) {
                    if (allAppPackages.isNotEmpty()) {
                        allAppPackages.forEach {
                            try { builder.addAllowedApplication(it) } catch (e: Exception) { Log.e("AdBlockVpn", "Failed to add app $it", e) }
                        }
                    }
                } else if (blockedApps.isNotEmpty()) {
                    // In GLOBAL mode, but some apps are blocked. We MUST add them to block them.
                    blockedApps.forEach {
                        try { builder.addAllowedApplication(it) } catch (e: Exception) { Log.e("AdBlockVpn", "Failed to add blocked app: $it", e) }
                    }
                }

                if (isBlockingInternet) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                }

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    isRunning = false
                    break
                }

                val input = FileInputStream(vpnInterface?.fileDescriptor)
                val output = FileOutputStream(vpnInterface?.fileDescriptor)
                val buffer = ByteBuffer.allocate(32767)

                while (isRunning && vpnInterface != null) {
                    val length = try { input.read(buffer.array()) } catch (e: Exception) { -1 }
                    if (length > 0) {
                        buffer.limit(length)
                        processPacket(buffer, output)
                        buffer.clear()
                    } else if (length < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("AdBlockVpn", "Error in VPN loop", e)
                if (isRunning) Thread.sleep(1000)
            } finally {
                vpnInterface?.close()
                vpnInterface = null
            }
        }
        stopVpn()
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
        if (domain == null) return

        if (isCaptureMode) {
            LogManager.addSessionLog(domain)
        }

        if (hostManager.isBlocked(domain)) {
            Log.d("AdBlockVpn", "Blocked: $domain")
            serviceScope.launch {
                if (settingsManager.dnsLogging.first()) {
                    LogManager.addLog(domain, true)
                }
            }
            val forgedResponse = forgeDnsResponse(dnsData)
            sendUdpPacket(forgedResponse, dstIp, srcIp, dstPort, srcPort, output)
        } else {
            serviceScope.launch {
                if (settingsManager.dnsLogging.first()) {
                    LogManager.addLog(domain, false)
                }
            }
            executor.execute {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    protect(socket)
                    socket.soTimeout = 5000
                    val forwardPacket = DatagramPacket(dnsData, dnsData.size, InetAddress.getByAddress(dstIp), 53)
                    socket.send(forwardPacket)

                    val responseData = ByteArray(1500)
                    val responsePacket = DatagramPacket(responseData, responseData.size)
                    socket.receive(responsePacket)

                    val actualResponse = responsePacket.data.copyOfRange(0, responsePacket.length)
                    sendUdpPacket(actualResponse, dstIp, srcIp, dstPort, srcPort, output)
                } catch (e: Exception) {
                    Log.e("AdBlockVpn", "DNS forward error for $domain", e)
                } finally {
                    socket?.close()
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
        val ipChecksum = calculateChecksum(packet.array(), 0, 20)
        packet.putShort(10, ipChecksum)

        // UDP Header
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        val udpLen = (8 + payload.size).toShort()
        packet.putShort(udpLen)

        // Calculate UDP Checksum (including pseudo-header)
        packet.putShort(calculateUdpChecksum(payload, srcIp, dstIp, srcPort, dstPort))

        packet.put(payload)

        synchronized(output) {
            output.write(packet.array())
        }
    }

    private fun calculateUdpChecksum(payload: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int): Short {
        val udpLen = 8 + payload.size
        val pseudoHeaderSize = 12 + udpLen
        val buffer = ByteBuffer.allocate(pseudoHeaderSize)

        buffer.put(srcIp)
        buffer.put(dstIp)
        buffer.put(0.toByte())
        buffer.put(17.toByte()) // Protocol UDP
        buffer.putShort(udpLen.toShort())

        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpLen.toShort())
        buffer.putShort(0.toShort()) // Checksum placeholder

        buffer.put(payload)

        return calculateChecksum(buffer.array(), 0, buffer.position())
    }

    private fun calculateChecksum(buffer: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buffer[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun forgeDnsResponse(queryData: ByteArray): ByteArray {
        val queryBuffer = ByteBuffer.wrap(queryData)
        val response = ByteBuffer.allocate(queryData.size + 16)
        response.put(queryData.copyOfRange(0, 2)) // ID

        // Flags: QR=1 (Response), Opcode=0, AA=1, TC=0, RD=1, RA=1, Z=0, RCODE=3 (NXDOMAIN)
        response.put(0x85.toByte())
        response.put(0x83.toByte())

        response.putShort(queryBuffer.getShort(4)) // QDCOUNT
        response.putShort(0.toShort()) // ANCOUNT
        response.putShort(0.toShort()) // NSCOUNT
        response.putShort(0.toShort()) // ARCOUNT

        // Copy Question section
        var pos = 12
        while (pos < queryData.size && queryData[pos] != 0.toByte()) {
            pos += (queryData[pos].toInt() and 0xFF) + 1
        }
        pos += 5 // Null terminator + QTYPE + QCLASS
        if (pos <= queryData.size) {
            response.put(queryData.copyOfRange(12, pos))
        }

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
        serviceScope.cancel()
        super.onDestroy()
    }
}
