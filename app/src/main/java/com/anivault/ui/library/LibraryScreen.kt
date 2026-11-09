package com.anivault.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

data class LibraryUi(
    val all: List<AnimeEntry> = emptyList(),
    val shown: List<AnimeEntry> = emptyList(),
    val query: LibraryQuery = LibraryQuery(),
    val facets: LibraryFilter.Facets = LibraryFilter.Facets(emptyList(), emptyList(), emptyList(), emptyList()),
    val selected: Set<String> = emptySet(),
    val grid: Boolean = true,
    val gridDensity: String = "standard",
    val posterBadge: String = "rating",
    val smartLists: List<SmartListPreset> = emptyList(),
    val loaded: Boolean = false,
    val undo: List<AnimeEntry>? = null,
    val note: String? = null
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
    private val smartLists = MutableStateFlow(SmartLists.parse(prefs.savedSmartLists))
    private val undo = MutableStateFlow<List<AnimeEntry>?>(null)
    private val note = MutableStateFlow<String?>(null)

    val ui: StateFlow<LibraryUi> = combine(repo.observeAll(), query, selected, grid, undo) { all, q, sel, g, u ->
        val shown = LibraryFilter.apply(all, q)
        val visible = shown.map { it.id }.toSet()
        LibraryUi(
            all = all,
            shown = shown,
            query = q,
            facets = LibraryFilter.facets(all),
            selected = sel.intersect(visible),
            grid = g,
            gridDensity = prefs.gridDensity,
            posterBadge = prefs.posterBadge,
            smartLists = smartLists.value,
            loaded = true,
            undo = u,
            note = note.value
        )
    }.combine(smartLists) { u, sl -> u.copy(smartLists = sl) }
        .combine(note) { u, n -> u.copy(note = n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

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
    fun plusOne(e: AnimeEntry) {
        viewModelScope.launch {
            repo.incrementProgress(e.id)
            note.value = "+1 episode for “${e.title}”"
        }
    }
    fun cycleStatus(e: AnimeEntry) {
        val next = when (e.status) {
            WatchStatus.PLAN_TO_WATCH -> WatchStatus.WATCHING
            WatchStatus.WATCHING -> WatchStatus.COMPLETED
            WatchStatus.COMPLETED -> WatchStatus.ON_HOLD
            WatchStatus.ON_HOLD -> WatchStatus.WATCHING
            WatchStatus.DROPPED -> WatchStatus.WATCHING
        }
        viewModelScope.launch {
            repo.setStatus(listOf(e.id), next)
            note.value = "Marked “${e.title}” as ${next.label}"
        }
    }
    fun startWatching(id: String) {
        viewModelScope.launch { repo.setStatus(listOf(id), WatchStatus.WATCHING) }
    }
    fun saveSmartList(name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        val q = query.value
        val preset = SmartListPreset(clean, q.status, q.format, q.genre, q.tag, q.minRating)
        val updated = (smartLists.value.filterNot { it.name.equals(clean, ignoreCase = true) } + preset).take(12)
        smartLists.value = updated
        prefs.savedSmartLists = SmartLists.encode(updated)
        note.value = "Pinned smart list “$clean”"
    }
    fun removeSmartList(preset: SmartListPreset) {
        val updated = smartLists.value.filterNot { it.name == preset.name }
        smartLists.value = updated
        prefs.savedSmartLists = SmartLists.encode(updated)
    }
    fun consumeNote() { note.value = null }
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
    var showWheel by remember { mutableStateOf(false) }
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
    LaunchedEffect(ui.note) {
        val msg = ui.note ?: return@LaunchedEffect
        snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.consumeNote()
    }

    val cellMinWidth: Dp = when (ui.gridDensity) {
        "compact" -> 92.dp
        "large" -> 148.dp
        else -> 112.dp
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
                        IconButton(onClick = { showWheel = true }, enabled = ui.all.isNotEmpty()) {
                            Icon(Icons.Default.Casino, "Spin the Wheel random picker")
                        }
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
                    item { FilterChip(ui.query.status == null && ui.query.activeFilterCount == 0, { viewModel.setQuery { LibraryQuery(text = it.text, sort = it.sort) } }, { Text("All (${ui.all.size})", fontSize = 13.sp) }) }
                    items(WatchStatus.values().toList()) { s ->
                        val n = ui.all.count { it.status == s }
                        FilterChip(ui.query.status == s && ui.query.activeFilterCount == 0, { viewModel.setQuery { it.copy(status = if (it.status == s) null else s) } }, { Text("${s.label} ($n)", fontSize = 13.sp) })
                    }
                    items(ui.smartLists, key = { "sl:${it.name}" }) { preset ->
                        val active = preset.matches(ui.query)
                        InputChip(
                            selected = active,
                            onClick = {
                                viewModel.setQuery {
                                    if (active) LibraryQuery(text = it.text, sort = it.sort) else preset.toQuery(it.sort).copy(text = it.text)
                                }
                            },
                            label = { Text("★ ${preset.name}", fontSize = 12.sp) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    "Remove smart list ${preset.name}",
                                    Modifier.size(14.dp).combinedClickable(onClick = { viewModel.removeSmartList(preset) })
                                )
                            }
                        )
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
                    GridCells.Adaptive(cellMinWidth), state = gridState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.layout(fullBleed)) { header() } }
                    items(rows, key = { it.key }) { g ->
                        val e = g.lead
                        Box {
                            GridItem(
                                e = e,
                                selected = e.id in ui.selected,
                                selecting = ui.selecting,
                                badgeMode = ui.posterBadge,
                                onClick = { if (g.members.size > 1) openGroup = g else if (ui.selecting) viewModel.toggleSelect(e.id) else onOpenEntry(e.id) },
                                onLongClick = { if (g.members.size > 1) openGroup = g else viewModel.toggleSelect(e.id) }
                            )
                            if (g.members.size > 1) Text("×${g.members.size}", Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "header") { header() }
                    items(rows, key = { it.key }) { g ->
                        val e = g.lead
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SwipeableLibraryRow(
                                enabled = !ui.selecting && g.members.size == 1,
                                onSwipeRightPlusOne = { viewModel.plusOne(e) },
                                onSwipeLeftStatus = { viewModel.cycleStatus(e) }
                            ) {
                                ListItemRow(
                                    e = e,
                                    selected = e.id in ui.selected,
                                    selecting = ui.selecting,
                                    onClick = { if (g.members.size > 1) openGroup = g else if (ui.selecting) viewModel.toggleSelect(e.id) else onOpenEntry(e.id) },
                                    onLongClick = { if (g.members.size > 1) openGroup = g else viewModel.toggleSelect(e.id) },
                                    onPlusOne = if (!ui.selecting && g.members.size == 1 && (e.totalEpisodes == 0 || e.watchedEpisodes < e.totalEpisodes)) ({ viewModel.plusOne(e) }) else null
                                )
                            }
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
    if (showWheel) RandomPickerSheet(
        all = ui.all,
        genres = ui.facets.genres,
        onOpen = { showWheel = false; onOpenEntry(it.id) },
        onStartWatching = { showWheel = false; viewModel.startWatching(it.id); onOpenEntry(it.id) },
        onDismiss = { showWheel = false }
    )
}

/**
 * Horizontal swipe wrapper for list rows:
 *  - Swipe right (> 96px): +1 episode watched
 *  - Swipe left (< -96px): cycle status (Plan to Watch → Watching → Completed)
 */
@Composable
fun SwipeableLibraryRow(
    enabled: Boolean,
    onSwipeRightPlusOne: () -> Unit,
    onSwipeLeftStatus: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    val threshold = 140f
    Box(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (dragX > threshold) onSwipeRightPlusOne()
                    else if (dragX < -threshold) onSwipeLeftStatus()
                    dragX = 0f
                },
                onDragCancel = { dragX = 0f },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragX = (dragX + amount).coerceIn(-220f, 220f)
                }
            )
        }
    ) {
        if (dragX > 24f) {
            Row(
                Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("+1 Episode", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        } else if (dragX < -24f) {
            Row(
                Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text("Next Status", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.SwapHoriz, null, tint = MaterialTheme.colorScheme.tertiary)
            }
        }
        Box(Modifier.offset { IntOffset(dragX.roundToInt(), 0) }) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(
    e: AnimeEntry,
    selected: Boolean,
    selecting: Boolean,
    badgeMode: String = "rating",
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "${e.title}, ${e.status.label}, ${progressLabel(e)}${if (selected) ", selected" else ""}" }
    ) {
        Box {
            Poster(e.imageUrl, Modifier.fillMaxWidth().aspectRatio(0.7f))
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { StatusChip(e.status) }
            if (selecting) {
                Checkbox(selected, null, Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color(0x990B0D16), RoundedCornerShape(6.dp)))
            } else {
                val badgeText = when (badgeMode) {
                    "rating" -> if (e.rating > 0f) "★ ${"%.1f".format(e.rating)}" else null
                    "unwatched" -> if (e.totalEpisodes > e.watchedEpisodes) "${e.totalEpisodes - e.watchedEpisodes} left" else null
                    else -> null
                }
                if (badgeText != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xCC0B0D16),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    ) {
                        Text(badgeText, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onBackground)
        Text(progressLabel(e), fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItemRow(
    e: AnimeEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlusOne: (() -> Unit)? = null
) {
    AniCard(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { Checkbox(selected, null); Spacer(Modifier.width(8.dp)) }
            Poster(e.imageUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    progressLabel(e) +
                        (if (e.rating > 0) "  ·  ★ ${"%.1f".format(e.rating)}" else "") +
                        (if (e.rewatchCount > 0) "  ·  ↻ ${e.rewatchCount}" else ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.dim
                )
                StatusChip(e.status)
                if (e.totalEpisodes > 0) LinearProgressIndicator(
                    progress = { (e.watchedEpisodes.toFloat() / e.totalEpisodes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AniVaultColors.Progress, trackColor = MaterialTheme.colorScheme.outline
                )
            }
            if (onPlusOne != null) {
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = onPlusOne, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Add, "Mark next episode of ${e.title} watched", Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RandomPickerSheet(
    all: List<AnimeEntry>,
    genres: List<String>,
    onOpen: (AnimeEntry) -> Unit,
    onStartWatching: (AnimeEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var status by remember { mutableStateOf<WatchStatus?>(WatchStatus.PLAN_TO_WATCH) }
    var maxEps by remember { mutableStateOf<Int?>(null) }
    var format by remember { mutableStateOf<String?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<AnimeEntry?>(null) }
    val scope = rememberCoroutineScope()

    fun spin() {
        val candidates = all.filter { e ->
            (status == null || e.status == status) &&
                (maxEps == null || (e.totalEpisodes in 1..maxEps!!)) &&
                (genre == null || e.genres.any { it.equals(genre, true) }) &&
                (format == null || e.format.equals(format, true))
        }
        if (candidates.isEmpty()) { picked = null; return }
        scope.launch {
            spinning = true
            repeat(10) {
                picked = candidates.random()
                delay(55L)
            }
            picked = SmartLists.pickRandom(all, status, maxEps, genre, format)
            spinning = false
        }
    }

    LaunchedEffect(Unit) { spin() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Casino, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Spin the Wheel", fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { spin() }, enabled = !spinning) { Text("Spin Again") }
            }
            Text("Pool", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(status == WatchStatus.PLAN_TO_WATCH, { status = WatchStatus.PLAN_TO_WATCH; spin() }, { Text("Plan to Watch") })
                FilterChip(status == WatchStatus.ON_HOLD, { status = WatchStatus.ON_HOLD; spin() }, { Text("On Hold") })
                FilterChip(status == WatchStatus.WATCHING, { status = WatchStatus.WATCHING; spin() }, { Text("Watching") })
                FilterChip(status == null, { status = null; spin() }, { Text("Any status") })
            }
            Text("Length / Format", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(maxEps == null && format == null, { maxEps = null; format = null; spin() }, { Text("Any length") })
                FilterChip(maxEps == 13 && format == null, { maxEps = 13; format = null; spin() }, { Text("Short (≤13 eps)") })
                FilterChip(maxEps == 26 && format == null, { maxEps = 26; format = null; spin() }, { Text("1–2 Cours (≤26 eps)") })
                FilterChip(format == "Movie", { format = if (format == "Movie") null else "Movie"; maxEps = null; spin() }, { Text("Movie") })
            }
            if (genres.isNotEmpty()) {
                Text("Genre", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(genre == null, { genre = null; spin() }, { Text("Any") }) }
                    items(genres.take(16)) { g -> FilterChip(genre == g, { genre = if (genre == g) null else g; spin() }, { Text(g) }) }
                }
            }
            val p = picked
            if (p == null) {
                EmptyState("No matching titles", "Try broadening the status or length filters above.", Icons.Default.SearchOff)
            } else {
                AniCard(Modifier.fillMaxWidth(), onClick = { onOpen(p) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Poster(p.imageUrl, Modifier.width(80.dp).height(114.dp), RoundedCornerShape(10.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(p.format.takeIf { it.isNotBlank() }, p.year?.toString(), if (p.totalEpisodes > 0) "${p.totalEpisodes} eps" else null).joinToString(" · "),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.dim
                            )
                            if (p.genres.isNotEmpty()) Text(p.genres.take(3).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            StatusChip(p.status)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(p) }, Modifier.weight(1f)) { Text("Open Details") }
                    Button(onClick = { onStartWatching(p) }, Modifier.weight(1f)) { Text("Start Watching") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(ui: LibraryUi, vm: LibraryViewModel, onDismiss: () -> Unit) {
    var presetName by remember { mutableStateOf("") }
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

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Pin as Smart List", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(24) },
                    placeholder = { Text("e.g. Short Sci-Fi, Must Watch") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { vm.saveSmartList(presetName); presetName = "" },
                    enabled = presetName.isNotBlank()
                ) { Text("Pin") }
            }
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
