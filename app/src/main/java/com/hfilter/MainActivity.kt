package com.hfilter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hfilter.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var hostManager: HostManager
    private var isVpnActive = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            binding.switchVpn.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostManager = HostManager(this)

        lifecycleScope.launch {
            hostManager.load()
            updateStats()
        }

        binding.switchVpn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isVpnActive) prepareVpn()
            } else {
                if (isVpnActive) stopVpn()
            }
        }

        binding.btnManageSources.setOnClickListener {
            startActivity(Intent(this, SourcesActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            refreshBlocklist()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStats()
    }

    private fun updateStats() {
        binding.tvStats.text = "${hostManager.getBlockedCount()} domains blocked"
    }

    private fun refreshBlocklist() {
        lifecycleScope.launch {
            binding.btnRefresh.isEnabled = false
            binding.btnRefresh.text = "Refreshing..."
            try {
                hostManager.refreshHosts()
                updateStats()
                Toast.makeText(this@MainActivity, "Blocklist updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to refresh blocklist", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnRefresh.isEnabled = true
                binding.btnRefresh.text = "Refresh Blocklist"
            }
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = "START"
        }
        startService(intent)
        isVpnActive = true
        binding.switchVpn.isChecked = true
    }

    private fun stopVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        isVpnActive = false
        binding.switchVpn.isChecked = false
    }
}
