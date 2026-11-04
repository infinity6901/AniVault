@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.anivault.ui.vault

import android.app.Activity
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.core.Sanitizer
import com.anivault.core.VaultSession
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.scraper.HentaiFallbackRepository
import com.anivault.ui.common.*
import com.anivault.ui.theme.AniVaultTheme
import com.anivault.ui.theme.AppMode
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultSearchState(
    val loading: Boolean = false, val results: List<ScrapedEntry> = emptyList(), val searched: Boolean = false,
    val error: String? = null, val source: String? = null
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repo: HentaiFallbackRepository,
    val session: VaultSession
) : ViewModel() {
    val entries: StateFlow<List<HVaultEntry>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _search = MutableStateFlow(VaultSearchState())
    val search: StateFlow<VaultSearchState> = _search.asStateFlow()
    private var job: Job? = null

    fun runSearch(q: String) {
        job?.cancel()
        if (q.isBlank()) { _search.value = VaultSearchState(); return }
        job = viewModelScope.launch {
            _search.value = VaultSearchState(loading = true)
            try {
                val r = repo.search(q.trim())
                _search.value = VaultSearchState(results = r.entries, searched = true, source = r.sourceName)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _search.value = VaultSearchState(searched = true, error = Sanitizer.clean(e.message)) }
        }
    }
    fun save(e: ScrapedEntry) { viewModelScope.launch { repo.saveToVault(e) } }
    fun update(e: HVaultEntry) { viewModelScope.launch { repo.updateEntry(e) } }
    fun delete(id: String) { viewModelScope.launch { repo.deleteById(id) } }
    fun lock() = session.lock()
}

@Composable
fun VaultScreen(onBack: () -> Unit, vm: VaultViewModel = hiltViewModel()) {
    val unlocked by vm.session.unlocked.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // No screenshots / recents thumbnails while this screen is showing.
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var authError by remember { mutableStateOf<String?>(null) }
    val authenticate = remember(ctx) {
        {
            val fa = ctx as? FragmentActivity
            if (fa != null) {
                val prompt = BiometricPrompt(fa, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { authError = null; vm.session.unlock() }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) { authError = msg.toString() }
                })
                prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder().setTitle("Unlock H-Vault").setSubtitle("Authenticate to access H-Vault content")
                        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL).build()
                )
            }
        }
    }
    LaunchedEffect(unlocked) { if (!unlocked) authenticate() }

    AniVaultTheme(theme = "dark", mode = AppMode.H_VAULT) {
        if (!unlocked) {
            Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                TopAppBar(title = { Text("H-Vault") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
            }) { pad ->
                Column(Modifier.padding(pad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("H-Vault is locked", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    authError?.let { Text(it, color = MaterialTheme.colorScheme.dim, fontSize = 13.sp) }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { authenticate() }) { Text("Unlock") }
                }
            }
        } else VaultContent(onBack, vm)
    }
}

@Composable
private fun VaultContent(onBack: () -> Unit, vm: VaultViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<HVaultEntry?>(null) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("H-Vault") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.lock(); onBack() }) { Icon(Icons.Default.Lock, "Lock H-Vault") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("My vault (${entries.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Search") })
            }
            if (tab == 0) {
                if (entries.isEmpty()) EmptyState("Nothing here yet", "Use Search to add entries.", Icons.Default.Lock)
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.id }) { e ->
                        AniCard(Modifier.fillMaxWidth(), onClick = { editing = e }) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Poster(e.imageUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text(if (e.totalEpisodes > 0) "${e.watchedEpisodes} / ${e.totalEpisodes}" else "${e.watchedEpisodes} watched", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                                    StatusChip(e.status)
                                }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Search H-Vault sources") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.runSearch(query) }),
                    modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp)
                )
                val owned = entries.map { it.id }.toSet()
                when {
                    search.loading -> LoadingBox()
                    search.error != null -> ErrorState("Search failed", { vm.runSearch(query) }, detail = search.error)
                    search.searched && search.results.isEmpty() -> EmptyState("No results", "Try different words.", Icons.Default.SearchOff)
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        search.source?.let { item { Text("Results from $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim) } }
                        items(search.results, key = { it.id }) { r ->
                            AniCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Poster(r.coverUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(r.title, Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (r.id in owned) Icon(Icons.Default.Check, "Saved")
                                    else IconButton(onClick = { vm.save(r) }) { Icon(Icons.Default.Add, "Save ${r.title} to H-Vault") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { e0 ->
        var e by remember(e0.id) { mutableStateOf(e0) }
        var confirm by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WatchStatus.values().forEach { s -> FilterChip(e.status == s, { e = e.copy(status = s) }, { Text(s.label) }) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { e = e.copy(watchedEpisodes = (e.watchedEpisodes - 1).coerceAtLeast(0)) }) { Icon(Icons.Default.Remove, "One less") }
                    Text("${e.watchedEpisodes}${if (e.totalEpisodes > 0) " / ${e.totalEpisodes}" else ""}")
                    IconButton(onClick = { e = e.copy(watchedEpisodes = if (e.totalEpisodes > 0) (e.watchedEpisodes + 1).coerceAtMost(e.totalEpisodes) else e.watchedEpisodes + 1) }) { Icon(Icons.Default.Add, "One more") }
                }
                Text(if (e.rating > 0) "Rating ${"%.1f".format(e.rating)}" else "Not rated", fontSize = 12.sp)
                Slider(e.rating, { e = e.copy(rating = Math.round(it * 2) / 2f) }, valueRange = 0f..10f, steps = 19)
            }
        }, confirmButton = { TextButton(onClick = { vm.update(e); editing = null }) { Text("Save") } },
            dismissButton = { Row { TextButton(onClick = { confirm = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { editing = null }) { Text("Cancel") } } })
        if (confirm) ConfirmDialog("Remove from H-Vault?", "This entry will be deleted.", "Remove", { vm.delete(e.id); confirm = false; editing = null }, { confirm = false }, destructive = true)
    }
}
