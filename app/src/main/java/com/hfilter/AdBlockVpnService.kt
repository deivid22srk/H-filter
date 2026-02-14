package com.hfilter

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AdBlockVpnService : VpnService(), Runnable {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val dnsExecutor = Executors.newFixedThreadPool(4)
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var vpnThread: Thread? = null

    private lateinit var hostManager: HostManager

    override fun onCreate() {
        super.onCreate()
        hostManager = HostManager(this)
        serviceScope.launch {
            hostManager.load()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START") {
            startVpn()
        } else if (intent?.action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning.get()) return

        isRunning.set(true)
        vpnThread = Thread(this, "HFilterVpnThread").apply { start() }
    }

    private fun stopVpn() {
        isRunning.set(false)
        vpnThread?.interrupt()
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun run() {
        try {
            val builder = Builder()
                .setSession("H-filter AdBlock")
                .addAddress("10.1.1.1", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("8.8.8.8", 32) // Only route DNS traffic to the VPN

            vpnInterface = builder.establish()

            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface?.fileDescriptor)

            val buffer = ByteBuffer.allocate(32767)
            val dnsSocket = DatagramSocket()
            protect(dnsSocket)

            while (isRunning.get()) {
                val readBytes = inputStream.read(buffer.array())
                if (readBytes > 0) {
                    val packet = buffer.array().copyOf(readBytes)
                    if (isDnsRequest(packet, readBytes)) {
                        val domain = extractDomain(packet, readBytes)
                        if (domain != null && hostManager.isBlocked(domain)) {
                            Log.i("VpnService", "Blocking ad domain: $domain")
                            // Return 0.0.0.0 response
                            val response = createDnsResponse(packet, readBytes, "0.0.0.0")
                            outputStream.write(response)
                        } else {
                            // Forward DNS query to 8.8.8.8
                            forwardDnsQuery(packet, readBytes, dnsSocket, outputStream)
                        }
                    } else {
                        // For other traffic (shouldn't happen with our route but just in case)
                        outputStream.write(packet, 0, readBytes)
                    }
                    buffer.clear()
                }
                if (readBytes <= 0) {
                    Thread.sleep(10)
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Error in VPN loop", e)
        } finally {
            stopVpn()
        }
    }

    private fun isDnsRequest(packet: ByteArray, length: Int): Boolean {
        if (length < 28) return false
        val ipVersion = (packet[0].toInt() shr 4) and 0x0F
        if (ipVersion != 4) return false
        val protocol = packet[9].toInt()
        if (protocol != 17) return false
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val udpStart = ihl
        val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        return destPort == 53
    }

    private fun forwardDnsQuery(packet: ByteArray, length: Int, dnsSocket: DatagramSocket, outputStream: FileOutputStream) {
        dnsExecutor.execute {
            try {
                val ihl = (packet[0].toInt() and 0x0F) * 4
                val dnsData = packet.copyOfRange(ihl + 8, length)

                val outPacket = DatagramPacket(dnsData, dnsData.size, InetAddress.getByName("8.8.8.8"), 53)
                dnsSocket.send(outPacket)

                val responseBuffer = ByteArray(4096)
                val inPacket = DatagramPacket(responseBuffer, responseBuffer.size)
                dnsSocket.soTimeout = 2000
                dnsSocket.receive(inPacket)

                val dnsResponseData = inPacket.data.copyOf(inPacket.length)
                val fullPacket = wrapInIpUdp(packet, dnsResponseData)
                synchronized(outputStream) {
                    outputStream.write(fullPacket)
                }
            } catch (e: Exception) {
                // Ignore or log
            }
        }
    }

    private fun extractDomain(packet: ByteArray, length: Int): String? {
        try {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val dnsStart = ihl + 8
            var pos = dnsStart + 12
            val domain = StringBuilder()
            while (pos < length) {
                val labelLen = packet[pos].toInt() and 0xFF
                if (labelLen == 0) break
                if (domain.isNotEmpty()) domain.append(".")
                pos++
                if (pos + labelLen > length) return null
                domain.append(String(packet, pos, labelLen))
                pos += labelLen
            }
            return domain.toString()
        } catch (e: Exception) {
            return null
        }
    }

    private fun wrapInIpUdp(originalPacket: ByteArray, dnsData: ByteArray): ByteArray {
        val ihl = (originalPacket[0].toInt() and 0x0F) * 4
        val totalLength = 20 + 8 + dnsData.size
        val result = ByteArray(totalLength)

        // IP Header
        result[0] = 0x45
        result[2] = (totalLength shr 8).toByte()
        result[3] = (totalLength and 0xFF).toByte()
        result[6] = 0x40.toByte() // Don't fragment
        result[7] = 0x00
        result[8] = 64.toByte() // TTL
        result[9] = 17 // UDP

        // Swap IPs
        System.arraycopy(originalPacket, 16, result, 12, 4) // Src = Original Dst
        System.arraycopy(originalPacket, 12, result, 16, 4) // Dst = Original Src

        // IP Checksum
        fillChecksum(result, 0, 20, 10)

        // UDP Header
        val udpStart = 20
        result[udpStart] = (53 shr 8).toByte()
        result[udpStart + 1] = (53 and 0xFF).toByte()
        val origUdpStart = ihl
        result[udpStart + 2] = originalPacket[origUdpStart] // Dst port = Original Src port
        result[udpStart + 3] = originalPacket[origUdpStart + 1]
        val udpLength = 8 + dnsData.size
        result[udpStart + 4] = (udpLength shr 8).toByte()
        result[udpStart + 5] = (udpLength and 0xFF).toByte()

        // DNS Data
        System.arraycopy(dnsData, 0, result, 28, dnsData.size)

        // UDP Checksum (Optional, can be 0)
        result[udpStart + 6] = 0
        result[udpStart + 7] = 0

        return result
    }

    private fun fillChecksum(data: ByteArray, start: Int, length: Int, checksumPos: Int) {
        data[checksumPos] = 0
        data[checksumPos + 1] = 0
        var sum = 0
        var i = start
        while (i < start + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        sum = sum.inv()
        data[checksumPos] = (sum shr 8).toByte()
        data[checksumPos + 1] = (sum and 0xFF).toByte()
    }

    private fun createDnsResponse(queryPacket: ByteArray, length: Int, ip: String): ByteArray {
        val ihl = (queryPacket[0].toInt() and 0x0F) * 4
        val dnsQuery = queryPacket.copyOfRange(ihl + 8, length)

        // Basic DNS Response (Hardcoded for A record)
        val response = ByteBuffer.allocate(dnsQuery.size + 16)
        response.put(dnsQuery.copyOf(2)) // ID
        response.putShort(0x8180.toShort()) // Flags: Response, No error
        response.putShort(dnsQuery.getShort(4)) // QDCOUNT
        response.putShort(1) // ANCOUNT
        response.putShort(0) // NSCOUNT
        response.putShort(0) // ARCOUNT

        // Question section (copy from query)
        var pos = 12
        while (pos < dnsQuery.size && dnsQuery[pos] != 0.toByte()) {
            pos += (dnsQuery[pos].toInt() and 0xFF) + 1
        }
        pos += 5 // Null terminator + Type + Class
        response.put(dnsQuery.copyOfRange(12, pos))

        // Answer section
        response.putShort(0xC00C.toShort()) // Pointer to name
        response.putShort(1) // Type A
        response.putShort(1) // Class IN
        response.putInt(60) // TTL
        response.putShort(4) // RDLENGTH
        val ipParts = ip.split(".").map { it.toInt().toByte() }
        response.put(ipParts.toByteArray())

        return wrapInIpUdp(queryPacket, response.array().copyOf(response.position()))
    }

    private fun ByteArray.getShort(index: Int): Short {
        return (((this[index].toInt() and 0xFF) shl 8) or (this[index + 1].toInt() and 0xFF)).toShort()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
