package com.anivault.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewModelScope
import com.anivault.data.remote.scraper.AnimeApiSource
import com.anivault.data.remote.scraper.AnimeApiSources
import com.anivault.data.remote.scraper.AnimeApiType
import com.anivault.ui.theme.AniVaultColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApiSourcesViewModel @Inject constructor(private val manager: AnimeApiSources) : ViewModel() {
    private val _sources = mutableStateListOf<AnimeApiSource>()
    val sources: SnapshotStateList<AnimeApiSource> = _sources
    var message by mutableStateOf<String?>(null); private set
    init { reload() }
    fun reload() { _sources.clear(); _sources.addAll(manager.getSources()) }
    fun toggle(source: AnimeApiSource, enabled: Boolean) = update(source.copy(enabled = enabled))
    fun update(source: AnimeApiSource) { val i = _sources.indexOfFirst { it.id == source.id }; if (i >= 0) _sources[i] = source; manager.save(_sources.toList()) }
    fun delete(source: AnimeApiSource) { _sources.remove(source); manager.save(_sources.toList()) }
    fun move(source: AnimeApiSource, delta: Int) { val i = _sources.indexOf(source); val j = i + delta; if (i >= 0 && j in _sources.indices) { val x = _sources[i]; _sources[i] = _sources[j]; _sources[j] = x; manager.save(_sources.toList()) } }
    fun add(name: String, url: String, type: AnimeApiType, clientId: String) {
        if (name.isBlank() || !url.startsWith("https://")) { message = "Enter a name and a valid HTTPS base URL."; return }
        if (_sources.any { it.name.equals(name.trim(), true) || it.baseUrl.equals(url.trimEnd('/'), true) }) { message = "A source with that name or URL already exists."; return }
        _sources.add(manager.newSource(name, url, type, clientId)); manager.save(_sources.toList()); message = "Source added. It will be tried in priority order."
    }
    fun clearMessage() { message = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSourcesScreen(onBack: () -> Unit, viewModel: ApiSourcesViewModel = hiltViewModel()) {
    val sources = viewModel.sources.toList()
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AnimeApiType.ANILIST_GRAPHQL) }
    var typeMenu by remember { mutableStateOf(false) }
    val message = viewModel.message
    LaunchedEffect(message) { if (message != null) { /* message remains visible until dismissed */ } }
    Scaffold(containerColor = AniVaultColors.Background, topBar = {
        TopAppBar(title = { Text("API Sources", color = AniVaultColors.OnSurface, fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AniVaultColors.AnimePrimary) }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AniVaultColors.Surface))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AniVaultColors.Surface), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Smart fallback", color = AniVaultColors.OnSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("AniVault tries enabled sources in priority order. Network errors, timeouts, HTTP errors, and empty results trigger the next provider automatically.", color = AniVaultColors.OnSurfaceMid, fontSize = 13.sp)
                        Text("Drag-free priority controls: use the arrows on each source to move it up or down.", color = AniVaultColors.OnSurfaceMid, fontSize = 12.sp)
                    }
                }
            }
            item { Text("CONFIGURED SOURCES", color = AniVaultColors.AnimePrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            items(sources, key = { it.id }) { source ->
                Card(colors = CardDefaults.cardColors(containerColor = AniVaultColors.Surface), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, null, tint = AniVaultColors.AnimePrimary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, color = AniVaultColors.OnSurface, fontWeight = FontWeight.SemiBold)
                                Text(source.baseUrl, color = AniVaultColors.OnSurfaceMid, fontSize = 11.sp, maxLines = 1)
                                Text(when (source.type) { AnimeApiType.ANILIST_GRAPHQL -> "AniList GraphQL"; AnimeApiType.JIKAN_COMPATIBLE -> "Jikan-compatible REST"; AnimeApiType.MAL_V2 -> "MyAnimeList v2 · Client ID required" }, color = AniVaultColors.OnSurfaceMid, fontSize = 11.sp)
                            }
                            Switch(checked = source.enabled, onCheckedChange = { viewModel.toggle(source, it) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.move(source, -1) }, enabled = sources.indexOf(source) > 0) { Text("↑ Priority") }
                            TextButton(onClick = { viewModel.move(source, 1) }, enabled = sources.indexOf(source) < sources.lastIndex) { Text("↓") }
                            IconButton(onClick = { viewModel.delete(source) }) { Icon(Icons.Default.Delete, "Remove source", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item {
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AniVaultColors.AnimePrimary, contentColor = AniVaultColors.AnimeOnPrimary)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add API source")
                }
            }
            item { Text("Provider notes: AniList works without a key. MAL requires a Client ID from MyAnimeList. Custom Jikan-compatible endpoints must return a /anime search response with a data array. Tenrai and Jikan-Edge are disabled by default until you verify the endpoint is reachable.", color = AniVaultColors.OnSurfaceMid, fontSize = 12.sp) }
        }
    }
    if (message != null) AlertDialog(onDismissRequest = viewModel::clearMessage, title = { Text("API Sources") }, text = { Text(message) }, confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } })
    if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Add API source") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("Base URL (HTTPS)") }, singleLine = true)
            Box {
                OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(when(type) { AnimeApiType.ANILIST_GRAPHQL -> "AniList GraphQL"; AnimeApiType.JIKAN_COMPATIBLE -> "Jikan-compatible REST"; AnimeApiType.MAL_V2 -> "MyAnimeList v2" }) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(text = { Text("AniList GraphQL") }, onClick = { type = AnimeApiType.ANILIST_GRAPHQL; typeMenu = false })
                    DropdownMenuItem(text = { Text("Jikan-compatible REST") }, onClick = { type = AnimeApiType.JIKAN_COMPATIBLE; typeMenu = false })
                    DropdownMenuItem(text = { Text("MyAnimeList v2") }, onClick = { type = AnimeApiType.MAL_V2; typeMenu = false })
                }
            }
            if (type == AnimeApiType.MAL_V2) OutlinedTextField(clientId, { clientId = it }, label = { Text("MAL Client ID") }, singleLine = true)
            Text("Use a trusted HTTPS endpoint. API keys are stored locally on this device.", color = AniVaultColors.OnSurfaceMid, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { viewModel.add(name, url, type, clientId); if (viewModel.message?.startsWith("Source added") == true) { showAdd = false; name = ""; url = ""; clientId = "" } }) { Text("Add") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } })
}
