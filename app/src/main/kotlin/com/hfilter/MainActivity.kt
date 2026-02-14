package com.hfilter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.hfilter.model.HostSource
import com.hfilter.service.AdBlockVpnService
import com.hfilter.ui.theme.HfilterTheme
import com.hfilter.util.HostManager
import com.hfilter.util.SettingsManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostManager: HostManager

    private val vpnPermissionResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        hostManager = HostManager(this)
        hostManager.loadFromCache()

        setContent {
            HfilterTheme {
                val vpnActive by settingsManager.vpnEnabled.collectAsState(initial = false)
                MainApp(settingsManager, hostManager, vpnActive, ::onVpnToggle)
            }
        }
    }

    private fun onVpnToggle(enabled: Boolean) {
        lifecycleScope.launch {
            settingsManager.setVpnEnabled(enabled)
        }
        if (enabled) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionResult.launch(intent)
            } else {
                startVpn()
            }
        } else {
            stopVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, AdBlockVpnService::class.java))
    }

    private fun stopVpn() {
        startService(Intent(this, AdBlockVpnService::class.java).apply { action = "STOP" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    settingsManager: SettingsManager,
    hostManager: HostManager,
    vpnActive: Boolean,
    onVpnToggle: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val hostSources by settingsManager.hostSources.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("H-filter") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Hosts") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen(hostManager, vpnActive, onVpnToggle)
                1 -> HostsScreen(hostSources, onAdd = { name, url ->
                    scope.launch {
                        val newList = hostSources + HostSource(name = name, url = url)
                        settingsManager.saveHostSources(newList)
                    }
                }, onToggle = { source ->
                    scope.launch {
                        val newList = hostSources.map {
                            if (it.id == source.id) it.copy(enabled = !it.enabled) else it
                        }
                        settingsManager.saveHostSources(newList)
                    }
                }, onDelete = { source ->
                    scope.launch {
                        val newList = hostSources.filter { it.id != source.id }
                        settingsManager.saveHostSources(newList)
                    }
                }, onReload = {
                    scope.launch {
                        hostManager.reload(hostSources)
                    }
                })
            }
        }
    }
}

@Composable
fun HomeScreen(hostManager: HostManager, vpnActive: Boolean, onVpnToggle: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (vpnActive) "VPN is Active" else "VPN is Inactive",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (vpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${hostManager.getBlockedCount()} domains blocked",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            onClick = {
                onVpnToggle(!vpnActive)
            },
            modifier = Modifier.size(120.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(
                if (vpnActive) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
fun HostsScreen(
    sources: List<HostSource>,
    onAdd: (String, String) -> Unit,
    onToggle: (HostSource) -> Unit,
    onDelete: (HostSource) -> Unit,
    onReload: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Host Sources", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onReload) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sources) { source ->
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(source.url, maxLines = 1) },
                    trailingContent = {
                        Row {
                            Checkbox(checked = source.enabled, onCheckedChange = { onToggle(source) })
                            if (source.type == com.hfilter.model.SourceType.USER) {
                                IconButton(onClick = { onDelete(source) }) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            }
                        }
                    }
                )
            }
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.End)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Source")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Add Host Source") },
            text = {
                Column {
                    TextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") })
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("URL") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    onAdd(newName, newUrl)
                    newName = ""
                    newUrl = ""
                    showDialog = false
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
