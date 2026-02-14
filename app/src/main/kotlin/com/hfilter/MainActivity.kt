package com.hfilter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.hfilter.model.HostSource
import com.hfilter.model.FilterResponse
import com.hfilter.model.FilterItem
import com.hfilter.util.LogManager
import com.hfilter.service.AdBlockVpnService
import com.hfilter.ui.theme.HfilterTheme
import com.hfilter.util.HostManager
import com.hfilter.util.SettingsManager
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostManager: HostManager

    private val vpnPermissionResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
            lifecycleScope.launch { settingsManager.setVpnEnabled(true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        hostManager = HostManager(this)
        lifecycleScope.launch(Dispatchers.IO) {
            hostManager.loadFromCache()
            if (settingsManager.autoUpdate.first()) {
                hostManager.reload(settingsManager.hostSources.first())
            }
        }

        setContent {
            HfilterTheme {
                val vpnActive by settingsManager.vpnEnabled.collectAsState(initial = false)
                MainApp(settingsManager, hostManager, vpnActive, ::onVpnToggle)
            }
        }
    }

    private fun onVpnToggle(enabled: Boolean) {
        if (enabled) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionResult.launch(intent)
            } else {
                startVpn()
                lifecycleScope.launch { settingsManager.setVpnEnabled(true) }
            }
        } else {
            stopVpn()
            lifecycleScope.launch { settingsManager.setVpnEnabled(false) }
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
    var showDnsLogs by remember { mutableStateOf(false) }
    val hostSources by settingsManager.hostSources.collectAsState(initial = emptyList())
    val downloadProgress by hostManager.downloadProgress.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(if (showDnsLogs) "DNS Request Logs" else "H-filter") },
                navigationIcon = {
                    if (showDnsLogs) {
                        IconButton(onClick = { showDnsLogs = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
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
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (showDnsLogs) {
                DnsLogScreen(settingsManager)
            } else {
            when (selectedTab) {
                0 -> HomeScreen(hostManager, vpnActive, onVpnToggle, onShowLogs = { showDnsLogs = true })
                1 -> HostsScreen(hostSources, downloadProgress, onAdd = { name, url ->
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
                }, onAddSelected = { items ->
                    scope.launch {
                        val newSources = items.map { HostSource(name = it.name, url = it.link) }
                        settingsManager.addHostSources(newSources)
                    }
                })
                2 -> SettingsScreen(settingsManager)
            }
            }
        }
    }
}

@Composable
fun DnsLogScreen(settingsManager: SettingsManager) {
    val dnsLogging by settingsManager.dnsLogging.collectAsState(initial = false)
    val logs by LogManager.logs.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("Enable Logging") },
            supportingContent = { Text("Record and show DNS requests") },
            trailingContent = {
                Switch(
                    checked = dnsLogging,
                    onCheckedChange = { scope.launch { settingsManager.setDnsLogging(it) } }
                )
            }
        )
        HorizontalDivider()

        if (!dnsLogging) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Logging is disabled", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logs, key = { it.id }) { log ->
                    ListItem(
                        headlineContent = { Text(log.domain) },
                        supportingContent = {
                            val date = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))
                            Text(date)
                        },
                        trailingContent = {
                            Text(
                                text = if (log.blocked) "BLOCKED" else "ALLOWED",
                                color = if (log.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ExploreFiltersScreen(
    onAdd: (String, String) -> Unit,
    onAddSelected: (List<FilterItem>) -> Unit,
    alreadyAddedUrls: Set<String>
) {
    var filters by remember { mutableStateOf<FilterResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val selectedItems = remember { mutableStateListOf<FilterItem>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://raw.githubusercontent.com/deivid22srk/H-filter-Host/refs/heads/main/hosts_e_filtros.json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = response.body?.string()
                        filters = Gson().fromJson(json, FilterResponse::class.java)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (filters != null) {
        val allItems = remember(filters) {
            val list = mutableListOf<Pair<String, List<FilterItem>>>()
            filters?.let {
                if (it.stevenBlackHosts.isNotEmpty()) list.add("StevenBlack Hosts" to it.stevenBlackHosts)
                if (it.oneDmHost.isNotEmpty()) list.add("1DM Hosts" to it.oneDmHost)
                if (it.filters.isNotEmpty()) list.add("General Filters" to it.filters)
            }
            list
        }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            allItems.forEach { (category, items) ->
                item {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(items.filter { !alreadyAddedUrls.contains(it.link) }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = { Text(item.link, maxLines = 1) },
                        leadingContent = {
                            Checkbox(
                                checked = selectedItems.contains(item),
                                onCheckedChange = { checked ->
                                    if (checked) selectedItems.add(item) else selectedItems.remove(item)
                                }
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { onAdd(item.name, item.link) }) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                            }
                        }
                    )
                }
            }
        }

        if (selectedItems.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = {
                    onAddSelected(selectedItems.toList())
                    selectedItems.clear()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .padding(bottom = 80.dp), // Avoid navigation bar
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Selected (${selectedItems.size})") }
            )
        }
    }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Failed to load filters")
        }
    }
}

@Composable
fun HomeScreen(hostManager: HostManager, vpnActive: Boolean, onVpnToggle: (Boolean) -> Unit, onShowLogs: () -> Unit) {
    val isLoading by hostManager.isLoading.collectAsState()
    val blockedCount = hostManager.getBlockedCount()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (vpnActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (vpnActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (vpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (vpnActive) "Protection Active" else "Protection Paused",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "$blockedCount domains in blocklist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 32.dp))
            Text("Loading blocklists...", style = MaterialTheme.typography.bodySmall)
        } else {
            Surface(
                onClick = { onVpnToggle(!vpnActive) },
                modifier = Modifier.size(160.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = if (vpnActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (vpnActive) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        OutlinedButton(
            onClick = onShowLogs,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Icon(Icons.Default.History, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DNS Request Logs")
        }
    }
}

@Composable
fun HostsScreen(
    sources: List<HostSource>,
    downloadProgress: Float?,
    onAdd: (String, String) -> Unit,
    onAddSelected: (List<FilterItem>) -> Unit,
    onToggle: (HostSource) -> Unit,
    onDelete: (HostSource) -> Unit,
    onReload: () -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (downloadProgress != null) {
            LinearProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("My Hosts") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Explore") })
        }

        if (subTab == 0) {
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

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(sources) { source ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (source.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        ListItem(
                            headlineContent = { Text(source.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(source.url, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = source.enabled, onCheckedChange = { onToggle(source) })
                                    if (source.type == com.hfilter.model.SourceType.USER) {
                                        IconButton(onClick = { onDelete(source) }) {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }
        } else {
            ExploreFiltersScreen(
                onAdd = onAdd,
                onAddSelected = onAddSelected,
                alreadyAddedUrls = sources.map { it.url }.toSet()
            )
        }
    }

    if (subTab == 0) {
        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = 80.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Source")
        }
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

@Composable
fun SettingsScreen(settingsManager: SettingsManager) {
    val autoUpdate by settingsManager.autoUpdate.collectAsState(initial = false)
    val startOnBoot by settingsManager.startOnBoot.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Preferences",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Auto-update on Start") },
                    supportingContent = { Text("Download hosts every time the app opens") },
                    trailingContent = {
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { scope.launch { settingsManager.setAutoUpdate(it) } }
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("Start on Boot") },
                    supportingContent = { Text("Start VPN service when device boots") },
                    trailingContent = {
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = { scope.launch { settingsManager.setStartOnBoot(it) } }
                        )
                    }
                )
            }
        }
    }
}
