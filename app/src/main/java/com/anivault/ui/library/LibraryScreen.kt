package com.anivault.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.core.AppPrefs
import com.anivault.data.library.*
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.ui.common.*
import com.anivault.ui.home.progressLabel
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.color
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUi(
    val all: List<AnimeEntry> = emptyList(),
    val shown: List<AnimeEntry> = emptyList(),
    val query: LibraryQuery = LibraryQuery(),
    val facets: LibraryFilter.Facets = LibraryFilter.Facets(emptyList(), emptyList(), emptyList(), emptyList()),
    val selected: Set<String> = emptySet(),
    val grid: Boolean = true,
    val loaded: Boolean = false,
    val undo: List<AnimeEntry>? = null
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val prefs: AppPrefs,
    private val mover: VaultMover,
    saved: SavedStateHandle
) : ViewModel() {
    private val query = MutableStateFlow(
        LibraryQuery(
            status = saved.get<String>("status")?.takeIf { it.isNotBlank() && it != "ALL" }?.let { WatchStatus.parse(it) },
            sort = LibrarySort.parse(prefs.librarySort)
        )
    )
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val grid = MutableStateFlow(prefs.libraryView == "grid")
    private val undo = MutableStateFlow<List<AnimeEntry>?>(null)

    val ui: StateFlow<LibraryUi> = combine(repo.observeAll(), query, selected, grid, undo) { all, q, sel, g, u ->
        val shown = LibraryFilter.apply(all, q)
        // Selection never keeps ids that are no longer visible.
        val visible = shown.map { it.id }.toSet()
        LibraryUi(all, shown, q, LibraryFilter.facets(all), sel.intersect(visible), g, true, u)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    val confirmDelete: Boolean get() = prefs.confirmDelete

    fun setQuery(f: (LibraryQuery) -> LibraryQuery) { query.update(f); prefs.librarySort = query.value.sort.name }
    fun clearFilters() = query.update { it.copy(format = null, year = null, genre = null, tag = null, minRating = null, source = null) }
    fun toggleView() { grid.update { !it }; prefs.libraryView = if (grid.value) "grid" else "list" }
    fun toggleSelect(id: String) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll() { selected.value = ui.value.shown.map { it.id }.toSet() }
    fun setStatus(status: WatchStatus) {
        val ids = selected.value.toList()
        viewModelScope.launch { repo.setStatus(ids, status); clearSelection() }
    }
    fun deleteSelected() {
        val ids = selected.value.toList()
        viewModelScope.launch { undo.value = repo.delete(ids); clearSelection() }
    }
    fun moveSelectedToVault() {
        val ids = selected.value.toList()
        viewModelScope.launch { mover.toVault(ids); clearSelection() }
    }
    fun undoDelete() { val rows = undo.value ?: return; undo.value = null; viewModelScope.launch { repo.restore(rows) } }
    fun dismissUndo() { undo.value = null }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenEntry: (String) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var grouped by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var openGroup by remember { mutableStateOf<com.anivault.data.library.Franchise.Group?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.undo) {
        val rows = ui.undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Removed ${rows.size} ${if (rows.size == 1) "title" else "titles"}", "Undo", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.dismissUndo()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (ui.selecting) {
                TopAppBar(
                    title = { Text("${ui.selected.size} selected") },
                    navigationIcon = { IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, "Cancel selection") } },
                    actions = {
                        TextButton(onClick = viewModel::selectAll) { Text("All") }
                        Box {
                            IconButton(onClick = { statusMenu = true }) { Icon(Icons.Default.Edit, "Change status") }
                            DropdownMenu(statusMenu, { statusMenu = false }) {
                                WatchStatus.values().forEach { s -> DropdownMenuItem({ Text(s.label) }, onClick = { statusMenu = false; viewModel.setStatus(s) }) }
                            }
                        }
                        IconButton(onClick = { confirmMove = true }) { Icon(Icons.Default.Lock, "Move selected to H-Vault") }
                        IconButton(onClick = { if (viewModel.confirmDelete) confirmDelete = true else viewModel.deleteSelected() }) {
                            Icon(Icons.Default.Delete, "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            } else {
                TopAppBar(
                    title = { Text("Library", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = viewModel::toggleView) {
                            Icon(if (ui.grid) Icons.Default.ViewList else Icons.Default.GridView, if (ui.grid) "Switch to list" else "Switch to grid")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                LibrarySort.values().forEach { s ->
                                    DropdownMenuItem(
                                        { Text(s.label, fontWeight = if (s == ui.query.sort) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { sortMenu = false; viewModel.setQuery { it.copy(sort = s) } })
                                }
                            }
                        }
                        BadgedBox(badge = { if (ui.query.activeFilterCount > 0) Badge { Text("${ui.query.activeFilterCount}") } }) {
                            IconButton(onClick = { showFilters = true }) { Icon(Icons.Default.FilterList, "Filters") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        val gridState = rememberLazyGridState()
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(ui.all.size) { com.anivault.ui.common.CoverCache.preload(appCtx, ui.all.map { it.imageUrl }) }
        val rows = remember(ui.shown, grouped) {
            if (grouped) com.anivault.data.library.Franchise.group(ui.shown) else ui.shown.map { com.anivault.data.library.Franchise.Group(it.id, listOf(it)) }
        }
        val header: @Composable () -> Unit = {
            Column {
                CompactSearch(ui.query.text, { t -> viewModel.setQuery { it.copy(text = t) } }, "Search your library", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(grouped, { grouped = !grouped }, { Text("Group seasons", fontSize = 13.sp) }) }
                    item { FilterChip(ui.query.status == null, { viewModel.setQuery { it.copy(status = null) } }, { Text("All (${ui.all.size})", fontSize = 13.sp) }) }
                    items(WatchStatus.values().toList()) { s ->
                        val n = ui.all.count { it.status == s }
                        FilterChip(ui.query.status == s, { viewModel.setQuery { it.copy(status = if (it.status == s) null else s) } }, { Text("${s.label} ($n)", fontSize = 13.sp) })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Column(Modifier.fillMaxSize()) {
            val listing = ui.loaded && ui.all.isNotEmpty() && ui.shown.isNotEmpty()
            if (!listing) header()
            when {
                !ui.loaded -> LoadingBox()
                ui.all.isEmpty() -> EmptyState("Your library is empty", "Add titles from Discover and they will appear here.", Icons.Default.VideoLibrary)
                ui.shown.isEmpty() -> EmptyState(
                    "No matches", "Nothing in your library fits these filters.", Icons.Default.SearchOff,
                    action = "Clear filters", onAction = { viewModel.setQuery { LibraryQuery(sort = it.sort) } }
                )
                ui.grid -> LazyVerticalGrid(
                    GridCells.Adaptive(112.dp), state = gridState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Search and filters are the first item, so they scroll away after the first rows.
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.layout(fullBleed)) { header() } }
                    items(rows, key = { it.key }) { g ->
                        val e = g.lead
                        Box {
                            GridItem(e, e.id in ui.selected, ui.selecting, { if (g.members.size > 1) openGroup = g else if (ui.selecting) viewModel.toggleSelect(e.id) else onOpenEntry(e.id) }, { if (g.members.size > 1) openGroup = g else viewModel.toggleSelect(e.id) })
                            if (g.members.size > 1) Text("×${g.members.size}", Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "header") { header() }
                    items(rows, key = { it.key }) { g ->
                        val e = g.lead
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            ListItemRow(e, e.id in ui.selected, ui.selecting, { if (g.members.size > 1) openGroup = g else if (ui.selecting) viewModel.toggleSelect(e.id) else onOpenEntry(e.id) }, { if (g.members.size > 1) openGroup = g else viewModel.toggleSelect(e.id) })
                            if (g.members.size > 1) Text("×${g.members.size} seasons", Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        val past = if (ui.grid) gridState.firstVisibleItemIndex > 6 else listState.firstVisibleItemIndex > 3
        ScrollToTop(past && ui.shown.isNotEmpty()) { scope.launch { if (ui.grid) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0) } }
        }
    }

    openGroup?.let { g ->
        ModalBottomSheet(onDismissRequest = { openGroup = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(g.lead.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                g.members.sortedBy { it.year ?: 9999 }.forEach { m -> ListItemRow(m, false, false, { openGroup = null; onOpenEntry(m.id) }, { openGroup = null; onOpenEntry(m.id) }) }
            }
        }
    }
    if (confirmMove) ConfirmDialog(
        "Move ${ui.selected.size} ${if (ui.selected.size == 1) "title" else "titles"} to H-Vault?",
        "They leave this library and appear in H-Vault behind your lock. You can move them back from H-Vault at any time.", "Move",
        onConfirm = { confirmMove = false; viewModel.moveSelectedToVault() }, onDismiss = { confirmMove = false }
    )
    if (confirmDelete) ConfirmDialog(
        "Delete ${ui.selected.size} ${if (ui.selected.size == 1) "title" else "titles"}?",
        "They will be removed from your library. You can undo right after.", "Delete",
        onConfirm = { confirmDelete = false; viewModel.deleteSelected() }, onDismiss = { confirmDelete = false }, destructive = true
    )
    if (showFilters) FilterSheet(ui, viewModel, onDismiss = { showFilters = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(e: AnimeEntry, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "${e.title}, ${e.status.label}, ${progressLabel(e)}${if (selected) ", selected" else ""}" }
    ) {
        Box {
            Poster(e.imageUrl, Modifier.fillMaxWidth().aspectRatio(0.7f))
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { StatusChip(e.status) }
            if (selecting) Checkbox(selected, null, Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color(0x990B0D16), RoundedCornerShape(6.dp)))
        }
        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onBackground)
        Text(progressLabel(e), fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItemRow(e: AnimeEntry, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    AniCard(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { Checkbox(selected, null); Spacer(Modifier.width(8.dp)) }
            Poster(e.imageUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(progressLabel(e) + if (e.rating > 0) "  ·  ★ ${"%.1f".format(e.rating)}" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                StatusChip(e.status)
                if (e.totalEpisodes > 0) LinearProgressIndicator(
                    progress = { (e.watchedEpisodes.toFloat() / e.totalEpisodes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AniVaultColors.Progress, trackColor = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(ui: LibraryUi, vm: LibraryViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = vm::clearFilters) { Text("Reset") }
            }
            ChipGroup("Format", ui.facets.formats.map { it to it }, ui.query.format) { v -> vm.setQuery { it.copy(format = v) } }
            ChipGroup("Year", ui.facets.years.map { it to it.toString() }, ui.query.year) { v -> vm.setQuery { it.copy(year = v) } }
            if (ui.facets.tags.isNotEmpty()) ChipGroup("My tags", ui.facets.tags.map { it to it }, ui.query.tag) { v -> vm.setQuery { it.copy(tag = v) } }
            ChipGroup("Genre", ui.facets.genres.map { it to it }, ui.query.genre) { v -> vm.setQuery { it.copy(genre = v) } }
            ChipGroup("Source", ui.facets.sources.map { it to it }, ui.query.source) { v -> vm.setQuery { it.copy(source = v) } }
            ChipGroup("My score at least", listOf(5f, 6f, 7f, 8f, 9f).map { it to it.toInt().toString() }, ui.query.minRating) { v -> vm.setQuery { it.copy(minRating = v) } }
            Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Show ${ui.shown.size} titles") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(title: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T?) -> Unit) {
    if (options.isEmpty()) return
    Text(title, color = MaterialTheme.colorScheme.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> FilterChip(selected == v, { onSelect(if (selected == v) null else v) }, { Text(label) }) }
    }
}
