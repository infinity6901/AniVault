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
import com.anivault.core.AppPrefs
import com.anivault.core.Sanitizer
import com.anivault.core.VaultSession
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.scraper.HentaiFallbackRepository
import com.anivault.data.remote.scraper.UncensoredCoverMatcher
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
    private val mover: com.anivault.data.library.VaultMover,
    private val sync: com.anivault.data.sync.SyncManager,
    val prefs: AppPrefs,
    val session: VaultSession
) : ViewModel() {
    val syncing = sync.running
    private val _uncensoring = MutableStateFlow(false)
    val uncensoring: StateFlow<Boolean> = _uncensoring.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun say(msg: String) { _message.value = msg }
    fun clearMessage() { _message.value = null }

    /** Two-way sync of linked H-Vault entries with the connected accounts (the screen is already behind the unlock). */
    fun syncWithAccounts() {
        viewModelScope.launch {
            val reports = sync.syncVault()
            _message.value = if (reports.isEmpty()) "No connected accounts. Connect one in Settings → Account & Sync." else reports.joinToString("\n") { it.summary() }
        }
    }

    /** Upgrades censored AniList/MAL/Kitsu covers across all H-Vault entries using the Aniyomi scraper engines. */
    fun uncensorAllCovers() {
        if (_uncensoring.value) return
        viewModelScope.launch {
            _uncensoring.value = true
            try {
                val count = repo.uncensorAllVaultCovers()
                _message.value = if (count > 0) {
                    "Upgraded $count ${if (count == 1) "entry" else "entries"} to uncensored cover art"
                } else {
                    "All H-Vault covers are already uncensored or no closer match was found"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Cover upgrade failed: ${Sanitizer.clean(e.message)}"
            } finally {
                _uncensoring.value = false
            }
        }
    }

    /** Queries all uncensored scrapers for candidate cover art matching [title]. */
    fun findUncensoredCandidates(title: String, onDone: (List<ScrapedEntry>) -> Unit) {
        viewModelScope.launch {
            val list = runCatching { repo.fetchUncensoredCandidates(title) }.getOrDefault(emptyList())
            onDone(list)
        }
    }

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
    fun save(e: ScrapedEntry) { viewModelScope.launch { repo.saveToVault(e); _message.value = "Saved “${e.title}” to H-Vault" } }
    fun update(e: HVaultEntry) { viewModelScope.launch { repo.updateEntry(e.copy(updatedAt = System.currentTimeMillis())) } }
    fun plusOne(e: HVaultEntry) {
        viewModelScope.launch {
            val updated = com.anivault.data.library.VaultRules.withProgress(e, e.watchedEpisodes + 1)
            repo.updateEntry(updated)
            _message.value = "+1 episode for “${e.title}”"
        }
    }
    fun delete(id: String) { viewModelScope.launch { repo.deleteById(id) } }
    fun deleteMany(ids: List<String>) { viewModelScope.launch { ids.forEach { repo.deleteById(it) } } }
    fun moveToLibrary(ids: List<String>) { viewModelScope.launch { mover.toLibrary(ids) } }
    fun setStatus(ids: List<String>, status: WatchStatus) {
        viewModelScope.launch { ids.mapNotNull { repo.getById(it) }.forEach { repo.updateEntry(com.anivault.data.library.VaultRules.withStatus(it, status)) } }
    }
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
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val uncensoring by vm.uncensoring.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("H-Vault", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = vm::uncensorAllCovers, enabled = !uncensoring && entries.isNotEmpty()) {
                    if (uncensoring) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoFixHigh, "Uncensor covers")
                }
                IconButton(onClick = vm::syncWithAccounts, enabled = !syncing) {
                    if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Sync, "Sync H-Vault with accounts")
                }
                IconButton(onClick = { vm.lock(); onBack() }) { Icon(Icons.Default.Lock, "Lock H-Vault") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab, containerColor = MaterialTheme.colorScheme.surface) {
                Tab(tab == 0, { tab = 0 }, text = { Text("My vault (${entries.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Search") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Sources") })
            }
            if (tab == 2) {
                Column(Modifier.fillMaxSize()) {
                    UncensoredScraperSettingsCard(vm, entries)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        com.anivault.ui.settings.ApiSourcesScreen(onBack = { tab = 1 }, embedded = true, vm = hiltViewModel<com.anivault.ui.settings.VaultApiSourcesViewModel>())
                    }
                }
            } else if (tab == 0) {
                VaultLibraryTab(entries, vm)
            } else {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Search H-Vault sources") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { vm.runSearch(query) }) { Icon(Icons.Default.ArrowForward, "Run search") }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.runSearch(query) }),
                    modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp)
                )
                val owned = entries.map { it.id }.toSet()
                when {
                    search.loading -> LoadingBox()
                    search.error != null -> ErrorState("Search failed", { vm.runSearch(query) }, detail = search.error)
                    search.searched && search.results.isEmpty() -> EmptyState("No results", "Try different words.", Icons.Default.SearchOff)
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        search.source?.let { item { Text("Results from $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim) } }
                        items(search.results, key = { it.id }) { r ->
                            AniCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Poster(r.coverUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp), allowDiskCache = false)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (r.episodeCount > 0) Text("${r.episodeCount} eps", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                                            if (!r.isCensored && !UncensoredCoverMatcher.isCensoredCatalogCover(r.coverUrl)) {
                                                Tag("Uncensored Cover", MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                    if (r.id in owned) Icon(Icons.Default.Check, "Saved", tint = MaterialTheme.colorScheme.primary)
                                    else FilledTonalIconButton(onClick = { vm.save(r) }) { Icon(Icons.Default.Add, "Save ${r.title} to H-Vault") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UncensoredScraperSettingsCard(vm: VaultViewModel, entries: List<HVaultEntry>) {
    var scraperPriority by remember { mutableStateOf(vm.prefs.vaultScraperPriority) }
    var autoUncensor by remember { mutableStateOf(vm.prefs.vaultAutoUncensorCovers) }
    var customMirrors by remember { mutableStateOf(vm.prefs.vaultCustomMirrors) }
    var expanded by remember { mutableStateOf(false) }
    val uncensoring by vm.uncensoring.collectAsStateWithLifecycle()
    val censoredCount = remember(entries) { entries.count { UncensoredCoverMatcher.isCensoredCatalogCover(it.imageUrl) } }

    AniCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Uncensored Cover Scrapers (Aniyomi Engine)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "Prioritizes Hanime API & WP-JSON/DooPlay/Madara scrapers over censored AniList/MAL covers.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.dim
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Configure", fontSize = 12.sp)
                }
            }
            if (censoredCount > 0) {
                OutlinedButton(
                    onClick = vm::uncensorAllCovers,
                    enabled = !uncensoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uncensoring) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching uncensored covers…")
                    } else {
                        Icon(Icons.Default.AutoFixHigh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Uncensor $censoredCount Catalog ${if (censoredCount == 1) "Cover" else "Covers"} Now")
                    }
                }
            }
            if (expanded) {
                SwitchRow(
                    title = "Prioritize Uncensored Scrapers",
                    subtitle = "Query Hanime, WatchHentai, HentaiHaven & nhentai before AniList/MAL/Kitsu.",
                    checked = scraperPriority
                ) {
                    scraperPriority = it
                    vm.prefs.vaultScraperPriority = it
                }
                SwitchRow(
                    title = "Auto-Uncensor Catalog Covers",
                    subtitle = "Automatically replace blurred AniList/MAL covers when moving titles to H-Vault.",
                    checked = autoUncensor
                ) {
                    autoUncensor = it
                    vm.prefs.vaultAutoUncensorCovers = it
                }
                OutlinedTextField(
                    value = customMirrors,
                    onValueChange = {
                        customMirrors = it
                        vm.prefs.vaultCustomMirrors = it.trim()
                    },
                    label = { Text("Custom Mirror URLs (comma-separated https://)") },
                    placeholder = { Text("https://watchhentai.org, https://hentaihaven.xxx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("If a scraper site changes its domain, add the new mirror here — the Aniyomi WP-JSON & multi-theme parser adapts automatically.")
                    }
                )
            }
        }
    }
}
