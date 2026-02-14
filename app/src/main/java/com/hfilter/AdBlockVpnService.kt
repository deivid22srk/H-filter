package com.hfilter

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AdBlockVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var vpnThread: Thread? = null

    private lateinit var hostManager: HostManager

    override fun onCreate() {
        super.onCreate()
        hostManager = HostManager(this)
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
                .addRoute("10.1.1.0", 24)
                // To intercept all traffic, one would use:
                // .addRoute("0.0.0.0", 0)
                // But this requires a full NAT/Proxy implementation to avoid loops.
                // For a DNS-based blocker, we usually intercept DNS port or set DNS server.

            vpnInterface = builder.establish()

            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface?.fileDescriptor)

            val buffer = ByteBuffer.allocate(32767)

            while (isRunning.get()) {
                val readBytes = inputStream.read(buffer.array())
                if (readBytes > 0) {
                    // Implementation of a full IP stack and DNS proxy is complex in pure Kotlin.
                    // In a production app, we would use a library like 'tun2socks' or a native implementation.
                    // Here is where we would:
                    // 1. Parse the IP/UDP header.
                    // 2. If it's a DNS request (port 53), extract the domain.
                    // 3. Check with hostManager.isBlocked(domain).
                    // 4. If blocked, inject an NXDOMAIN response packet.
                    // 5. If not blocked, forward to the real DNS server and proxy the response back.

                    // For now, we continue to route traffic.
                    outputStream.write(buffer.array(), 0, readBytes)
                    buffer.clear()
                }
                // Yield to other threads
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

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
