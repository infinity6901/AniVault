package com.anivault.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.data.library.LibraryRepository
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.*
import com.anivault.ui.common.*
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DiscoverSort(val label: String) {
    DEFAULT("Default order"), SCORE("Highest score"), NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title A–Z"), EPISODES("Most episodes");

    fun apply(items: List<NormalizedAnime>): List<NormalizedAnime> = when (this) {
        DEFAULT -> items
        SCORE -> items.sortedByDescending { it.score ?: -1f }
        NEWEST -> items.sortedByDescending { it.startDate ?: (it.year?.let { y -> y * 1000L } ?: Long.MIN_VALUE) }
        OLDEST -> items.sortedBy { it.startDate ?: (it.year?.let { y -> y * 1000L } ?: Long.MAX_VALUE) }
        TITLE -> items.sortedBy { it.title.lowercase() }
        EPISODES -> items.sortedByDescending { it.episodes }
    }
}

sealed interface DiscoverStatus {
    data object Idle : DiscoverStatus
    data object Loading : DiscoverStatus
    data object Content : DiscoverStatus
    data object Empty : DiscoverStatus
    data object NoSources : DiscoverStatus
    data class Error(val failures: List<SourceFailure>) : DiscoverStatus
}

data class DiscoverUi(
    val query: String = "",
    val section: BrowseSection = BrowseSection.TRENDING,
    val sections: List<BrowseSection> = emptyList(),
    val items: List<NormalizedAnime> = emptyList(),
    val status: DiscoverStatus = DiscoverStatus.Loading,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val servedBy: String? = null,
    val skipped: List<SourceFailure> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    val sort: DiscoverSort = DiscoverSort.DEFAULT,
    val inLibrary: Set<String> = emptySet(),
    val message: String? = null
)

@OptIn(FlowPreview::class)
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val library: LibraryRepository,
    private val backfill: com.anivault.data.library.DurationBackfill
) : ViewModel() {
    private val _ui = MutableStateFlow(DiscoverUi())
    val ui: StateFlow<DiscoverUi> = _ui.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var job: Job? = null
    private var page = 1

    init {
        // Keep "In library" markers live.
        viewModelScope.launch { library.observeAll().collect { all -> _ui.update { it.copy(inLibrary = all.map { e -> e.id }.toSet()) } } }
        // Source list changes (enable/disable/reorder) refresh what can be browsed.
        viewModelScope.launch {
            providers.sources.collect {
                val supported = BrowseSection.values().filter { s -> providers.supports(s) }
                _ui.update { u -> u.copy(sections = supported, section = if (u.section in supported || supported.isEmpty()) u.section else supported.first()) }
                reload()
            }
        }
        // Debounced search; collectLatest cancels the previous in-flight request (no stale results).
        viewModelScope.launch {
            queryFlow.debounce(350).distinctUntilChanged().collectLatest { q -> _ui.update { it.copy(query = q) }; load(reset = true) }
        }
    }

    fun onQuery(q: String) { _ui.update { it.copy(query = q) }; queryFlow.value = q }
    fun setSection(s: BrowseSection) { _ui.update { it.copy(section = s) }; reload() }
    fun setFilters(f: SearchFilters) { _ui.update { it.copy(filters = f) }; reload() }
    fun setSort(o: DiscoverSort) { _ui.update { it.copy(sort = o) } }
    fun retry() = reload()
    fun loadMore() { val u = _ui.value; if (u.hasNext && !u.loadingMore && u.status == DiscoverStatus.Content) load(reset = false) }
    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun add(item: NormalizedAnime, status: WatchStatus?) {
        viewModelScope.launch {
            val msg = try {
                if (library.add(item, status).also { if (it) backfill.run() }) "Added “${item.title}”" else "Already in your library"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.anivault.core.Sanitizer.clean(e.message).let { "Couldn't add to library: $it" }
            }
            _ui.update { it.copy(message = msg) }
        }
    }

    private fun reload() { job?.cancel(); load(reset = true) }

    private fun load(reset: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            val u0 = _ui.value
            val q = u0.query.trim()
            if (reset) { page = 1; _ui.update { it.copy(status = DiscoverStatus.Loading, items = emptyList(), hasNext = false) } }
            else _ui.update { it.copy(loadingMore = true) }
            var target = if (reset) 1 else page + 1
            // Filters are applied to each fetched page, so a narrow filter (one genre) can leave a page nearly
            // empty. Keep pulling pages until the list is reasonably full or the source runs out.
            val filtered = u0.filters.activeCount > 0
            var pulls = 0
            var acc = if (reset) emptyList() else _ui.value.items
            var result: ProviderResult
            while (true) {
                result = when {
                    q.length in 1..1 -> { _ui.update { it.copy(status = DiscoverStatus.Idle) }; return@launch }
                    q.isNotEmpty() -> providers.search(q, target, u0.filters)
                    u0.sections.isEmpty() -> ProviderResult.NoSources
                    else -> providers.browse(u0.section, target, u0.filters)
                }
                pulls++
                if (result is ProviderResult.Success) {
                    page = target
                    acc = TitleMatcher.dedupe(acc + result.items)
                    if (filtered && result.hasNext && acc.size < 20 && pulls < 12) { target++; continue }
                }
                break
            }
            val res = result
            val accFinal = acc
            when (res) {
                is ProviderResult.Success -> {
                    _ui.update { u ->
                        val merged = accFinal
                        u.copy(
                            items = merged, hasNext = res.hasNext, loadingMore = false,
                            status = if (merged.isEmpty()) DiscoverStatus.Empty else DiscoverStatus.Content,
                            servedBy = res.source.name, skipped = res.skipped
                        )
                    }
                }
                is ProviderResult.Failed -> _ui.update { u ->
                    // A failed "load more" keeps what is on screen and says so.
                    if (reset) u.copy(status = DiscoverStatus.Error(res.failures), loadingMore = false)
                    else u.copy(loadingMore = false, message = "Couldn't load more results")
                }
                ProviderResult.NoSources -> _ui.update { it.copy(status = DiscoverStatus.NoSources, loadingMore = false) }
            }
        }
    }
}

@Composable
fun DiscoverScreen(onOpenSources: () -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<NormalizedAnime?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    val nearEnd by remember { derivedStateOf { val l = listState.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd) { if (nearEnd) viewModel.loadMore() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = { Text("Discover", fontWeight = FontWeight.Bold) }, actions = {
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.SwapVert, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        DiscoverSort.values().forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o.label, fontWeight = if (o == ui.sort) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { if (o == ui.sort) Icon(Icons.Default.Check, null) },
                                onClick = { viewModel.setSort(o); sortMenu = false }
                            )
                        }
                    }
                }
                BadgedBox(badge = { val n = ui.filters.activeCount; if (n > 0) Badge { Text("$n") } }) {
                    IconButton(onClick = { showFilters = true }) { Icon(Icons.Default.FilterList, "Filters") }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = ui.query, onValueChange = viewModel::onQuery, singleLine = true,
                placeholder = { Text("Search anime") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (ui.query.isNotEmpty()) IconButton(onClick = { viewModel.onQuery("") }) { Icon(Icons.Default.Clear, "Clear search") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp)
            )
            if (ui.query.isBlank() && ui.sections.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.sections) { s -> FilterChip(ui.section == s, { viewModel.setSection(s) }, { Text(s.label) }) }
                }
            }
            val note = buildString {
                ui.servedBy?.let { if (ui.status == DiscoverStatus.Content) append("Results from $it") }
                if (ui.skipped.isNotEmpty() && ui.status == DiscoverStatus.Content) append(" · ${ui.skipped.joinToString { s -> s.sourceName }} unavailable")
            }
            if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            when (val st = ui.status) {
                DiscoverStatus.Loading -> LoadingBox()
                DiscoverStatus.Idle -> EmptyState("Keep typing", "Enter at least two characters.", Icons.Default.Search)
                DiscoverStatus.Empty -> EmptyState("No results", if (ui.query.isBlank()) "This section is empty right now." else "Nothing matched “${ui.query.trim()}”. Try another spelling or fewer filters.", Icons.Default.SearchOff)
                DiscoverStatus.NoSources -> EmptyState("No usable API source", "Enable at least one source that supports this view.", Icons.Default.Dns, action = "Manage API sources", onAction = onOpenSources)
                is DiscoverStatus.Error -> ErrorState(
                    "Couldn't reach your sources", viewModel::retry,
                    detail = st.failures.joinToString("\n") { "${it.sourceName}: ${it.kind.label}" }
                )
                DiscoverStatus.Content -> LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(remember(ui.items, ui.sort) { ui.sort.apply(ui.items) }, key = { it.canonicalId }) { a -> ResultRow(a, a.canonicalId in ui.inLibrary) { detail = a } }
                    if (ui.loadingMore) item { LoadingBox() }
                }
            }
        }
    }

    detail?.let { a ->
        CatalogSheet(a, a.canonicalId in ui.inLibrary, onAdd = { st -> viewModel.add(a, st); detail = null }, onDismiss = { detail = null })
    }
    if (showFilters) DiscoverFilters(ui.filters, onApply = { viewModel.setFilters(it); showFilters = false }, onDismiss = { showFilters = false })
}

@Composable
private fun ResultRow(a: NormalizedAnime, inLibrary: Boolean, onClick: () -> Unit) {
    AniCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(10.dp)) {
            Poster(a.coverUrl, Modifier.width(72.dp).height(104.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    listOfNotNull(a.format.takeIf { it.isNotBlank() }, a.year?.toString(), if (a.episodes > 0) "${a.episodes} eps" else null).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.dim
                )
                if (a.genres.isNotEmpty()) Text(a.genres.take(3).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.score?.let { Tag("★ ${"%.1f".format(it)}") }
                    Tag(a.providerName)
                    if (inLibrary) Tag("In library", MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun CatalogSheet(a: NormalizedAnime, inLibrary: Boolean, onAdd: (WatchStatus?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Poster(a.coverUrl, Modifier.width(100.dp).height(144.dp))
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    a.altTitles.firstOrNull()?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim) }
                    Text(
                        listOfNotNull(a.format.takeIf { it.isNotBlank() }, a.year?.toString(), if (a.episodes > 0) "${a.episodes} episodes" else "Episodes unknown", a.status.takeIf { it.isNotBlank() }).joinToString(" · "),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.dim
                    )
                    a.score?.let { Text("Community score ★ ${"%.1f".format(it)}", fontSize = 13.sp) }
                    Text("Data from ${a.providerName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                }
            }
            if (a.genres.isNotEmpty()) Text(a.genres.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Text(a.synopsis.ifBlank { "No synopsis available." }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            if (inLibrary) {
                AssistChip(onClick = {}, label = { Text("Already in your library") }, leadingIcon = { Icon(Icons.Default.Check, null) })
            } else {
                Button(onClick = { onAdd(null) }, Modifier.fillMaxWidth()) { Text("Add to library") }
                Text("Add as…", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(WatchStatus.values().toList()) { s -> AssistChip(onClick = { onAdd(s) }, label = { Text(s.label) }) }
                }
            }
        }
    }
}

private val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi",
    "Slice of Life", "Sports", "Supernatural", "Thriller", "Mecha", "Music", "Psychological", "Ecchi"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverFilters(current: SearchFilters, onApply: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    var year by remember { mutableStateOf(current.year?.toString().orEmpty()) }
    var format by remember { mutableStateOf(current.format) }
    var minScore by remember { mutableStateOf(current.minScore) }
    var genres by remember { mutableStateOf(current.genres) }
    var airing by remember { mutableStateOf(current.airing) }
    var country by remember { mutableStateOf(current.country) }
    var minEps by remember { mutableStateOf(current.minEpisodes?.toString().orEmpty()) }
    var maxEps by remember { mutableStateOf(current.maxEpisodes?.toString().orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Filters", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Applied on this device to what each source returns. A filter only hides a title when the source reports that detail.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            Text("Genres (must match all)", fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GENRES.forEach { g -> FilterChip(g in genres, { genres = if (g in genres) genres - g else genres + g }, { Text(g) }) }
            }
            Text("Status", fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Releasing", "Finished", "Not yet released").forEach { st -> FilterChip(airing == st, { airing = if (airing == st) null else st }, { Text(st) }) }
            }
            Text("Original language / country", fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("JP" to "Japanese", "CN" to "Chinese", "KR" to "Korean", "TW" to "Taiwanese").forEach { (code, label) ->
                    FilterChip(country == code, { country = if (country == code) null else code }, { Text(label) })
                }
            }
            Text("Sub/dub availability isn’t published by AniList, Kitsu or MyAnimeList, so it can’t be filtered here.", fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
            Text("Format", fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TV", "Movie", "OVA", "ONA", "Special").forEach { f -> FilterChip(format.equals(f, true), { format = if (format.equals(f, true)) null else f }, { Text(f) }) }
            }
            Text("Minimum community score", fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6f, 7f, 8f, 9f).forEach { sc -> FilterChip(minScore == sc, { minScore = if (minScore == sc) null else sc }, { Text("${sc.toInt()}+") }) }
            }
            OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Release year") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minEps, { minEps = it.filter(Char::isDigit).take(4) }, label = { Text("Min episodes") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(maxEps, { maxEps = it.filter(Char::isDigit).take(4) }, label = { Text("Max episodes") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApply(SearchFilters()) }, Modifier.weight(1f)) { Text("Reset") }
                Button(onClick = {
                    onApply(SearchFilters(year.toIntOrNull(), format, minScore, genres, airing, country, minEps.toIntOrNull(), maxEps.toIntOrNull()))
                }, Modifier.weight(1f)) { Text("Apply") }
            }
        }
    }
}

@HiltViewModel
class CatalogAddViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val backfill: com.anivault.data.library.DurationBackfill
) : ViewModel() {
    fun inLibrary(id: String) = library.observe(id).map { it != null }

    fun add(item: NormalizedAnime, status: WatchStatus?, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val msg = try {
                if (library.add(item, status).also { if (it) backfill.run() }) "Added “${item.title}”" else "Already in your library"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't add to library: ${com.anivault.core.Sanitizer.clean(e.message)}"
            }
            onResult(msg)
        }
    }
}

/** The anime page shown from anywhere in the app (for example when tapping a trending title). */
@Composable
fun CatalogDetailHost(item: NormalizedAnime, onDismiss: () -> Unit, vm: CatalogAddViewModel = hiltViewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val inLib by remember(item.canonicalId) { vm.inLibrary(item.canonicalId) }.collectAsStateWithLifecycle(false)
    CatalogSheet(item, inLib, onAdd = { st -> vm.add(item, st) { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }; onDismiss() }, onDismiss = onDismiss)
}
