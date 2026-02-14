package com.hfilter

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hfilter.databinding.ActivitySourcesBinding

class SourcesActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySourcesBinding
    private lateinit var hostManager: HostManager
    private lateinit var adapter: SourceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hostManager = HostManager(this)

        setupRecyclerView()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.fabAddSource.setOnClickListener { showAddSourceDialog() }
    }

    private fun setupRecyclerView() {
        adapter = SourceAdapter(
            onToggle = { source, isEnabled ->
                val updatedSources = hostManager.getHostSources().map {
                    if (it.id == source.id) it.copy(enabled = isEnabled) else it
                }
                hostManager.saveHostSources(updatedSources)
            },
            onDelete = { source ->
                val updatedSources = hostManager.getHostSources().filter { it.id != source.id }
                hostManager.saveHostSources(updatedSources)
                adapter.submitList(updatedSources)
            }
        )
        binding.rvSources.layoutManager = LinearLayoutManager(this)
        binding.rvSources.adapter = adapter
        adapter.submitList(hostManager.getHostSources())
    }

    private fun showAddSourceDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_source, null)
        val etName = view.findViewById<EditText>(R.id.etSourceName)
        val etUrl = view.findViewById<EditText>(R.id.etSourceUrl)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Host Source")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString()
                val url = etUrl.text.toString()
                if (name.isNotBlank() && url.isNotBlank()) {
                    val newSource = HostSource(name = name, url = url)
                    val updatedSources = hostManager.getHostSources() + newSource
                    hostManager.saveHostSources(updatedSources)
                    adapter.submitList(updatedSources)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
