package com.hfilter

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.hfilter.model.*
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

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportPredefinitions(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPredefinitions(it) }
    }

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
                hostManager.reload(
                    settingsManager.hostSources.first(),
                    settingsManager.appPredefinitions.first(),
                    settingsManager.filteringMode.first()
                )
            }
        }

        setContent {
            HfilterTheme {
                val vpnActive by settingsManager.vpnEnabled.collectAsState(initial = false)
                MainApp(
                    settingsManager,
                    hostManager,
                    vpnActive,
                    ::onVpnToggle,
                    onExport = { exportLauncher.launch("hfilter_app_filters.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json")) }
                )
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

    private fun exportPredefinitions(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = settingsManager.appPredefinitions.first()
                val json = Gson().toJson(list)
                contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun importPredefinitions(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val json = input.bufferedReader().use { it.readText() }
                    val type = object : com.google.gson.reflect.TypeToken<List<AppPredefinition>>() {}.type
                    val list: List<AppPredefinition> = Gson().fromJson(json, type)
                    val current = settingsManager.appPredefinitions.first()
                    settingsManager.saveAppPredefinitions(current + list)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    settingsManager: SettingsManager,
    hostManager: HostManager,
    vpnActive: Boolean,
    onVpnToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
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
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    label = { Text("Apps") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
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
                1 -> {
                    val predefinitions by settingsManager.appPredefinitions.collectAsState(initial = emptyList())
                    val mode by settingsManager.filteringMode.collectAsState(initial = FilteringMode.BOTH)
                    HostsScreen(hostSources, downloadProgress, onAdd = { name, url ->
                        scope.launch {
                            val newList = hostSources + HostSource(name = name, url = url)
                            settingsManager.saveHostSources(newList)
                            hostManager.reload(newList, predefinitions, mode, forceDownload = true)
                        }
                    }, onToggle = { source ->
                        scope.launch {
                            val newList = hostSources.map {
                                if (it.id == source.id) it.copy(enabled = !it.enabled) else it
                            }
                            settingsManager.saveHostSources(newList)
                            hostManager.reload(newList, predefinitions, mode, forceDownload = false)
                        }
                    }, onDelete = { source ->
                        scope.launch {
                            val newList = hostSources.filter { it.id != source.id }
                            settingsManager.saveHostSources(newList)
                            hostManager.reload(newList, predefinitions, mode, forceDownload = false)
                        }
                    }, onReload = {
                        scope.launch {
                            hostManager.reload(hostSources, predefinitions, mode, forceDownload = true)
                        }
                    }, onAddSelected = { items ->
                        scope.launch {
                            val newSources = items.map { HostSource(name = it.name, url = it.link) }
                            settingsManager.addHostSources(newSources)
                            val fullList = hostSources + newSources
                            hostManager.reload(fullList, predefinitions, mode, forceDownload = true)
                        }
                    })
                }
                2 -> AppFilterScreen(
                    settingsManager,
                    onExport = onExport,
                    onImport = onImport,
                    onVpnToggle = onVpnToggle
                )
                3 -> SettingsScreen(settingsManager)
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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (vpnActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text(
                    text = "SHIELD STATUS",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (vpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (vpnActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (vpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Text(
                        text = if (vpnActive) "Protected" else "Unprotected",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(0.1f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.1f))
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "$blockedCount",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (vpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Domains currently blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vpnActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f)
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
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (source.enabled) MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = if (source.enabled) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        ListItem(
                            headlineContent = { Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text(source.url, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = source.enabled, onCheckedChange = { onToggle(source) })
                                    if (source.type == com.hfilter.model.SourceType.USER) {
                                        IconButton(onClick = { onDelete(source) }) {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
fun AppFilterScreen(
    settingsManager: SettingsManager,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onVpnToggle: (Boolean) -> Unit
) {
    val predefinitions by settingsManager.appPredefinitions.collectAsState(initial = emptyList())
    val captureApps by settingsManager.captureSessionApps.collectAsState(initial = emptyList())
    val sessionLogs by LogManager.sessionLogs.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pm = context.packageManager
    var showAppSelection by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var editingPredefinition by remember { mutableStateOf<AppPredefinition?>(null) }
    var recapturingPredefinition by remember { mutableStateOf<AppPredefinition?>(null) }

    if (showAppSelection) {
        AppSelectionScreen(
            onBack = { showAppSelection = false },
            onStartCapture = { apps ->
                scope.launch {
                    settingsManager.setCaptureSessionApps(apps)
                    LogManager.clearSessionLogs()
                    onVpnToggle(true)
                    showAppSelection = false
                }
            }
        )
    } else if (editingPredefinition != null) {
        ReviewCaptureScreen(
            domains = editingPredefinition!!.blockedDomains + editingPredefinition!!.allowedDomains,
            initialName = editingPredefinition!!.name,
            initialBlocked = editingPredefinition!!.blockedDomains,
            initialAllowed = editingPredefinition!!.allowedDomains,
            onBack = { editingPredefinition = null },
            onSave = { name, blocked, allowed ->
                scope.launch {
                    val updated = editingPredefinition!!.copy(
                        name = name,
                        blockedDomains = blocked,
                        allowedDomains = allowed
                    )
                    settingsManager.saveAppPredefinitions(predefinitions.map { if (it.id == updated.id) updated else it })
                    editingPredefinition = null
                }
            }
        )
    } else if (showReview) {
        ReviewCaptureScreen(
            domains = if (recapturingPredefinition != null) {
                sessionLogs + recapturingPredefinition!!.blockedDomains + recapturingPredefinition!!.allowedDomains
            } else sessionLogs,
            initialName = recapturingPredefinition?.name ?: "",
            initialBlocked = recapturingPredefinition?.blockedDomains ?: emptySet(),
            initialAllowed = recapturingPredefinition?.allowedDomains ?: emptySet(),
            onBack = { showReview = false },
            onSave = { name, blocked, allowed ->
                scope.launch {
                    if (recapturingPredefinition != null) {
                        val updated = recapturingPredefinition!!.copy(
                            name = name,
                            blockedDomains = blocked,
                            allowedDomains = allowed
                        )
                        settingsManager.saveAppPredefinitions(predefinitions.map { if (it.id == updated.id) updated else it })
                    } else {
                        val newPred = AppPredefinition(
                            name = name,
                            packageNames = captureApps,
                            blockedDomains = blocked,
                            allowedDomains = allowed
                        )
                        settingsManager.saveAppPredefinitions(predefinitions + newPred)
                    }
                    settingsManager.setCaptureSessionApps(emptyList())
                    LogManager.clearSessionLogs()
                    recapturingPredefinition = null
                    showReview = false
                }
            }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (captureApps.isNotEmpty()) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Capture Session Active", style = MaterialTheme.typography.titleMedium)
                        Text("${sessionLogs.size} unique domains captured", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            Button(onClick = { showReview = true }) {
                                Text("Stop and Review")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                scope.launch {
                                    settingsManager.setCaptureSessionApps(emptyList())
                                    LogManager.clearSessionLogs()
                                }
                            }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.padding(16.dp)) {
                    Button(onClick = { showAppSelection = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New App Capture")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onImport) { Icon(Icons.Default.Upload, contentDescription = "Import") }
                    IconButton(onClick = onExport) { Icon(Icons.Default.Download, contentDescription = "Export") }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(predefinitions) { pred ->
                    ListItem(
                        headlineContent = { Text(pred.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("${pred.packageNames.size} apps, ${pred.blockedDomains.size} blocked") },
                        leadingContent = {
                            if (pred.packageNames.isNotEmpty()) {
                                val icon = remember(pred.packageNames[0]) {
                                    try { pm.getApplicationIcon(pred.packageNames[0]).toBitmap().asImageBitmap() } catch (e: Exception) { null }
                                }
                                if (icon != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.Apps, null, modifier = Modifier.size(40.dp))
                                }
                            } else {
                                Icon(Icons.Default.Apps, null, modifier = Modifier.size(40.dp))
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    recapturingPredefinition = pred
                                    scope.launch {
                                        settingsManager.setCaptureSessionApps(pred.packageNames)
                                        LogManager.clearSessionLogs()
                                        onVpnToggle(true)
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Recapture")
                                }
                                IconButton(onClick = { editingPredefinition = pred }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        settingsManager.saveAppPredefinitions(predefinitions.filter { it.id != pred.id })
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppSelectionScreen(onBack: () -> Unit, onStartCapture: (List<String>) -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val apps = remember {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { AppInfo(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.name }
    }
    val selectedApps = remember { mutableStateListOf<String>() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text("Select Apps to Capture", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(apps) { app ->
                ListItem(
                    headlineContent = { Text(app.name, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedApps.contains(app.packageName),
                                onCheckedChange = {
                                    if (it) selectedApps.add(app.packageName) else selectedApps.remove(app.packageName)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val icon = remember { pm.getApplicationIcon(app.packageName).toBitmap().asImageBitmap() }
                            androidx.compose.foundation.Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        if (selectedApps.contains(app.packageName)) selectedApps.remove(app.packageName)
                        else selectedApps.add(app.packageName)
                    }
                )
            }
        }

        Button(
            onClick = { onStartCapture(selectedApps.toList()) },
            enabled = selectedApps.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text("Start Capture")
        }
    }
}

@Composable
fun ReviewCaptureScreen(
    domains: Set<String>,
    initialName: String = "",
    initialBlocked: Set<String> = emptySet(),
    initialAllowed: Set<String> = emptySet(),
    onBack: () -> Unit,
    onSave: (String, Set<String>, Set<String>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val blocked = remember { mutableStateListOf<String>().apply { addAll(initialBlocked) } }
    val allowed = remember { mutableStateListOf<String>().apply { addAll(initialAllowed) } }
    val domainList = remember { domains.toList().sorted() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text("Review Captured Hosts", style = MaterialTheme.typography.titleLarge)
        }

        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Predefinition Name") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(domainList) { domain ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            blocked.contains(domain) -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            allowed.contains(domain) -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                ) {
                    ListItem(
                        headlineContent = { Text(domain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
                        trailingContent = {
                            Row {
                                FilterChip(
                                    selected = blocked.contains(domain),
                                    onClick = {
                                        if (blocked.contains(domain)) blocked.remove(domain)
                                        else {
                                            blocked.add(domain)
                                            allowed.remove(domain)
                                        }
                                    },
                                    label = { Text("Block") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.error,
                                        selectedLabelColor = MaterialTheme.colorScheme.onError
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilterChip(
                                    selected = allowed.contains(domain),
                                    onClick = {
                                        if (allowed.contains(domain)) allowed.remove(domain)
                                        else {
                                            allowed.add(domain)
                                            blocked.remove(domain)
                                        }
                                    },
                                    label = { Text("Allow") }
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    )
                }
            }
        }

        Button(
            onClick = { onSave(name, blocked.toSet(), allowed.toSet()) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text("Save Predefinition")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsManager: SettingsManager) {
    val autoUpdate by settingsManager.autoUpdate.collectAsState(initial = false)
    val startOnBoot by settingsManager.startOnBoot.collectAsState(initial = false)
    val filteringMode by settingsManager.filteringMode.collectAsState(initial = FilteringMode.BOTH)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Filtering Mode",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Choose which blocklists to use:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = filteringMode == FilteringMode.GLOBAL,
                        onClick = { scope.launch { settingsManager.setFilteringMode(FilteringMode.GLOBAL) } },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) { Text("Global") }
                    SegmentedButton(
                        selected = filteringMode == FilteringMode.APPS,
                        onClick = { scope.launch { settingsManager.setFilteringMode(FilteringMode.APPS) } },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) { Text("Apps") }
                    SegmentedButton(
                        selected = filteringMode == FilteringMode.BOTH,
                        onClick = { scope.launch { settingsManager.setFilteringMode(FilteringMode.BOTH) } },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) { Text("Both") }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when(filteringMode) {
                        FilteringMode.GLOBAL -> "Uses only hosts from the Hosts tab for all apps."
                        FilteringMode.APPS -> "Applies only to apps in the Apps tab using their specific hosts."
                        FilteringMode.BOTH -> "Combines global hosts and app predefinitions for all apps."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "Preferences",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Auto-update on Start", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Synchronize all host lists on every app launch") },
                    trailingContent = {
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { scope.launch { settingsManager.setAutoUpdate(it) } }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ListItem(
                    headlineContent = { Text("Start on Boot", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Automatically resume ad-blocking after system restart") },
                    trailingContent = {
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = { scope.launch { settingsManager.setStartOnBoot(it) } }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }
    }
}
