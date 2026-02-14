package com.hfilter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hfilter.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var hostManager: HostManager
    private var isVpnActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostManager = HostManager(this)

        loadSources()

        binding.btnToggleVpn.setOnClickListener {
            if (isVpnActive) {
                stopVpn()
            } else {
                prepareVpn()
            }
        }

        binding.btnSaveAndRefresh.setOnClickListener {
            saveAndRefresh()
        }
    }

    private fun loadSources() {
        val sources = hostManager.getSources()
        binding.etSources.setText(sources.joinToString("\n"))
    }

    private fun saveAndRefresh() {
        val sourcesText = binding.etSources.text.toString()
        val sources = sourcesText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        hostManager.saveSources(sources)

        lifecycleScope.launch {
            binding.btnSaveAndRefresh.isEnabled = false
            binding.btnSaveAndRefresh.text = "Refreshing..."
            try {
                hostManager.refreshHosts()
                Toast.makeText(this@MainActivity, "Hosts refreshed successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error refreshing hosts", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnSaveAndRefresh.isEnabled = true
                binding.btnSaveAndRefresh.text = "Save & Refresh Hosts"
            }
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
            onActivityResult(0, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = "START"
        }
        startService(intent)
        isVpnActive = true
        binding.btnToggleVpn.text = "Stop VPN"
    }

    private fun stopVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        isVpnActive = false
        binding.btnToggleVpn.text = "Start VPN"
    }
}
