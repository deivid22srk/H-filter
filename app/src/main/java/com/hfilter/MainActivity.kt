package com.hfilter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hfilter.ui.theme.HFilterTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var hostManager: HostManager
    private lateinit var settingsManager: SettingsManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostManager = HostManager(this)
        settingsManager = SettingsManager(this)

        if (settingsManager.autoUpdateOnStart) {
            lifecycleScope.launch {
                hostManager.refreshHosts()
            }
        }

        setContent {
            HFilterTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") { MainScreen(navController) }
                    composable("sources") { SourcesScreen(navController) }
                    composable("settings") { SettingsScreen(navController) }
                }
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply { action = "START" }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply { action = "STOP" }
        startService(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(navController: androidx.navigation.NavController) {
        var isVpnActive by remember { mutableStateOf(false) }
        var blockedCount by remember { mutableStateOf(hostManager.getBlockedCount()) }
        val scope = rememberCoroutineScope()

        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text("H-filter") }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = false, onClick = { navController.navigate("sources") }, icon = { Icon(Icons.Default.List, null) }, label = { Text("Hosts") })
                    NavigationBarItem(selected = false, onClick = { navController.navigate("settings") }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("VPN Status", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isVpnActive) "Running" else "Stopped", modifier = Modifier.weight(1f))
                            Switch(checked = isVpnActive, onCheckedChange = {
                                isVpnActive = it
                                if (it) {
                                    val intent = VpnService.prepare(this@MainActivity)
                                    if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()
                                } else stopVpnService()
                            })
                        }
                    }
                }

                Button(onClick = {
                    scope.launch {
                        hostManager.refreshHosts()
                        blockedCount = hostManager.getBlockedCount()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh Blocklist")
                }

                Text("$blockedCount domains blocked", modifier = Modifier.padding(top = 16.dp))
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SourcesScreen(navController: androidx.navigation.NavController) {
        var selectedTab by remember { mutableStateOf(0) }
        val sources = remember { mutableStateListOf<HostSource>().apply { addAll(hostManager.getHostSources()) } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Host Sources") },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } }
                )
            },
            floatingActionButton = {
                if (selectedTab == 0) {
                    var showDialog by remember { mutableStateOf(false) }
                    ExtendedFloatingActionButton(onClick = { showDialog = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add Source") })

                    if (showDialog) {
                        var name by remember { mutableStateOf("") }
                        var url by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showDialog = false },
                            title = { Text("Add Host Source") },
                            text = {
                                Column {
                                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") })
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    if (name.isNotBlank() && url.isNotBlank()) {
                                        val newSource = HostSource(name = name, url = url)
                                        sources.add(newSource)
                                        hostManager.saveHostSources(sources)
                                        showDialog = false
                                    }
                                }) { Text("Add") }
                            },
                            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("My Sources") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("StevenBlack") })
                }

                val filteredSources = sources.filter {
                    if (selectedTab == 0) it.type == SourceType.USER else it.type == SourceType.BUILT_IN
                }

                LazyColumn {
                    items(filteredSources) { source ->
                        ListItem(
                            headlineContent = { Text(source.name) },
                            supportingContent = { Text(source.url) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = source.enabled, onCheckedChange = { isChecked ->
                                        val index = sources.indexOfFirst { it.id == source.id }
                                        if (index != -1) {
                                            sources[index] = sources[index].copy(enabled = isChecked)
                                            hostManager.saveHostSources(sources)
                                        }
                                    })
                                    if (selectedTab == 0) {
                                        IconButton(onClick = {
                                            sources.removeIf { it.id == source.id }
                                            hostManager.saveHostSources(sources)
                                        }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(navController: androidx.navigation.NavController) {
        var startOnBoot by remember { mutableStateOf(settingsManager.startOnBoot) }
        var autoUpdate by remember { mutableStateOf(settingsManager.autoUpdateOnStart) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                ListItem(
                    headlineContent = { Text("Start on Boot") },
                    trailingContent = {
                        Switch(checked = startOnBoot, onCheckedChange = {
                            startOnBoot = it
                            settingsManager.startOnBoot = it
                        })
                    }
                )
                ListItem(
                    headlineContent = { Text("Auto-update on Start") },
                    trailingContent = {
                        Switch(checked = autoUpdate, onCheckedChange = {
                            autoUpdate = it
                            settingsManager.autoUpdateOnStart = it
                        })
                    }
                )
            }
        }
    }
}
