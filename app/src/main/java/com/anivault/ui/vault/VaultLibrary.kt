@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.anivault.ui.vault

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anivault.data.library.Franchise
import com.anivault.data.library.LibrarySort
import com.anivault.data.library.VaultRules
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.model.VaultEpisodePreview
import com.anivault.data.remote.model.VaultRichDetails
import com.anivault.ui.detail.relationLabel
import com.anivault.data.remote.scraper.UncensoredCoverMatcher
import com.anivault.ui.common.*
import com.anivault.ui.library.SeasonBadgeChip
import com.anivault.ui.library.SwipeableLibraryRow
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.TrackGlassOverlay
import com.anivault.ui.theme.dim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun progressText(e: HVaultEntry) = if (e.totalEpisodes > 0) "Ep ${e.watchedEpisodes} / ${e.totalEpisodes}" else "Ep ${e.watchedEpisodes} · total unknown"

private val VAULT_SORTS = listOf(LibrarySort.UPDATED, LibrarySort.TITLE, LibrarySort.RATING, LibrarySort.PROGRESS)

data class VaultQuery(
    val text: String = "",
    val status: WatchStatus? = null,
    val tag: String? = null,
    val studio: String? = null,
    val uncensoredOnly: Boolean = false,
    val minRating: Float? = null,
    val sort: LibrarySort = LibrarySort.UPDATED
) {
    val activeFilterCount: Int
        get() = listOfNotNull(tag, studio, minRating, if (uncensoredOnly) true else null).size
}

data class VaultGroup(val key: String, val members: List<HVaultEntry>) {
    val lead: HVaultEntry get() = members.first()
    val orderedMembers: List<HVaultEntry>
        get() = members.sortedWith(compareBy<HVaultEntry> { it.title.length }.thenBy { it.title.lowercase() })
    val displayTitle: String
        get() {
            if (members.size <= 1) return lead.title
            val cleaned = members.map { Franchise.cleanTitle(it.title) }.filter { it.isNotBlank() }
            return cleaned.minByOrNull { it.length } ?: Franchise.cleanTitle(lead.title)
        }
    val representative: HVaultEntry
        get() {
            if (members.size <= 1) return lead
            val ord = orderedMembers
            return ord.firstOrNull { it.status == WatchStatus.WATCHING }
                ?: ord.firstOrNull { it.status != WatchStatus.COMPLETED }
                ?: ord.first()
        }
    val totalEpisodes: Int get() = members.sumOf { it.totalEpisodes.coerceAtLeast(0) }
    val watchedEpisodes: Int get() = members.sumOf { it.watchedEpisodes.coerceAtLeast(0) }
    val completedParts: Int get() = members.count { it.status == WatchStatus.COMPLETED }
    val overallStatus: WatchStatus
        get() = when {
            members.any { it.status == WatchStatus.WATCHING } -> WatchStatus.WATCHING
            members.all { it.status == WatchStatus.COMPLETED } -> WatchStatus.COMPLETED
            members.any { it.status == WatchStatus.COMPLETED || it.watchedEpisodes > 0 } -> WatchStatus.WATCHING
            members.any { it.status == WatchStatus.ON_HOLD } -> WatchStatus.ON_HOLD
            members.all { it.status == WatchStatus.DROPPED } -> WatchStatus.DROPPED
            else -> WatchStatus.PLAN_TO_WATCH
        }
}

private fun groupVaultEntries(entries: List<HVaultEntry>): List<VaultGroup> {
    val map = LinkedHashMap<String, MutableList<HVaultEntry>>()
    entries.forEach { map.getOrPut(Franchise.key(it.title)) { mutableListOf() }.add(it) }
    return map.map { (k, v) -> VaultGroup(k, v) }
}

private fun sortAndFilter(all: List<HVaultEntry>, q: VaultQuery): List<HVaultEntry> {
    val needle = q.text.trim().lowercase()
    val f = all.filter { e ->
        (q.status == null || e.status == q.status) &&
            (q.tag.isNullOrBlank() || e.tags.any { it.equals(q.tag, true) }) &&
            (q.studio.isNullOrBlank() || (e.studio ?: "").equals(q.studio, true)) &&
            (!q.uncensoredOnly || !e.isCensored) &&
            (q.minRating == null || e.rating >= q.minRating) &&
            (needle.isEmpty() ||
                e.title.lowercase().contains(needle) ||
                (e.studio ?: "").lowercase().contains(needle) ||
                e.tags.any { it.lowercase().contains(needle) } ||
                e.notes.lowercase().contains(needle))
    }
    return when (q.sort) {
        LibrarySort.TITLE -> f.sortedBy { it.title.lowercase() }
        LibrarySort.RATING -> f.sortedByDescending { it.rating }
        LibrarySort.PROGRESS -> f.sortedByDescending { if (it.totalEpisodes > 0) it.watchedEpisodes.toFloat() / it.totalEpisodes else 0f }
        else -> f.sortedByDescending { it.updatedAt }
    }
}

/**
 * Full-featured H-Vault Library tab:
 *  - Vault Summary Metrics Bar (Titles, Watching, Completed, Hours, Uncensored count)
 *  - Group Seasons / Franchise grouping with clean titles & theme-matched badges
 *  - Spin the Wheel random picker
 *  - Advanced Filter Sheet (Tags, Studios, Uncensored only, Min Rating)
 *  - Custom Grid Density (Compact 4-col, Standard 3-col, Large 2-col) & Poster Badges
 *  - Quick +1 buttons, Clipped Swipe Gestures, and Manual Custom Entry creation
 */
@Composable
fun VaultLibraryTab(
    entries: List<HVaultEntry>,
    vm: VaultViewModel,
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState()
) {
    val query by vm.vaultQuery.collectAsStateWithLifecycle()
    val grid by vm.vaultGrid.collectAsStateWithLifecycle()
    val grouped by vm.vaultGrouped.collectAsStateWithLifecycle()
    val showStats by vm.vaultShowStats.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showWheel by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    var openGroupKey by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    val shown = remember(entries, query) { sortAndFilter(entries, query) }
    val rows = remember(shown, grouped) {
        if (grouped) groupVaultEntries(shown) else shown.map { VaultGroup(it.id, listOf(it)) }
    }
    val activeGroup = remember(entries, openGroupKey) {
        openGroupKey?.let { k -> groupVaultEntries(entries).firstOrNull { it.key == k } }
    }
    val allTags = remember(entries) { entries.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
    val allStudios = remember(entries) { entries.mapNotNull { it.studio?.trim()?.takeIf(String::isNotEmpty) }.distinct().sortedBy { it.lowercase() } }

    val visible = shown.map { it.id }.toSet()
    val sel = selected.intersect(visible)
    val selecting = sel.isNotEmpty()
    val toggle = { id: String -> selected = if (id in selected) selected - id else selected + id }

    val cellMinWidth: Dp = when (vm.prefs.gridDensity) {
        "compact" -> 92.dp
        "large" -> 148.dp
        else -> 112.dp
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Cancel selection") }
                Text("${sel.size} selected", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { selected = visible }) { Text("All") }
                Box {
                    IconButton(onClick = { statusMenu = true }) { Icon(Icons.Default.Edit, "Change status") }
                    DropdownMenu(statusMenu, { statusMenu = false }) {
                        if (statusMenu) TrackGlassOverlay()
                        WatchStatus.values().forEach { s -> DropdownMenuItem({ Text(s.label) }, onClick = { statusMenu = false; vm.setStatus(sel.toList(), s); selected = emptySet() }) }
                    }
                }
                IconButton(onClick = { confirmMove = true }) { Icon(Icons.Default.VideoLibrary, "Move selected to library") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete selected", tint = MaterialTheme.colorScheme.error) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val scope = rememberCoroutineScope()
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            LaunchedEffect(entries.size) { com.anivault.ui.common.CoverCache.preload(appCtx, entries.map { it.imageUrl }, allowDiskCache = false) }

            val header: @Composable () -> Unit = {
                Column {
                    CompactSearch(
                        value = query.text,
                        onChange = { text -> vm.updateVaultQuery { it.copy(text = text) } },
                        placeholder = "Search titles, studios, tags…",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        trailing = {
                            IconButton(onClick = { showAddCustom = true }, Modifier.size(34.dp)) {
                                Icon(Icons.Default.AddCircleOutline, "Add custom entry", Modifier.size(19.dp))
                            }
                            IconButton(onClick = { showWheel = true }, enabled = entries.isNotEmpty(), modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Casino, "Spin the Wheel", Modifier.size(19.dp))
                            }
                            IconButton(onClick = vm::toggleVaultGrid, Modifier.size(34.dp)) {
                                Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView, if (grid) "Switch to list" else "Switch to grid", Modifier.size(19.dp))
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }, Modifier.size(34.dp)) { Icon(Icons.Default.Sort, "Sort", Modifier.size(19.dp)) }
                                DropdownMenu(sortMenu, { sortMenu = false }) {
                                    if (sortMenu) TrackGlassOverlay()
                                    VAULT_SORTS.forEach { s ->
                                        DropdownMenuItem(
                                            { Text(s.label, fontWeight = if (s == query.sort) FontWeight.Bold else FontWeight.Normal) },
                                            onClick = { sortMenu = false; vm.updateVaultQuery { it.copy(sort = s) } }
                                        )
                                    }
                                }
                            }
                            BadgedBox(badge = { if (query.activeFilterCount > 0) Badge { Text("${query.activeFilterCount}") } }) {
                                IconButton(onClick = { showFilters = true }, Modifier.size(34.dp)) {
                                    Icon(Icons.Default.FilterList, "Vault filters", Modifier.size(19.dp))
                                }
                            }
                        }
                    )

                    if (entries.isNotEmpty() && showStats) {
                        VaultStatsBanner(
                            entries = entries,
                            uncensoredOnly = query.uncensoredOnly,
                            onToggleUncensored = { vm.updateVaultQuery { it.copy(uncensoredOnly = !it.uncensoredOnly) } },
                            onHide = { vm.setVaultShowStats(false) }
                        )
                    }

                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = grouped,
                                onClick = { vm.setVaultGrouped(!grouped) },
                                leadingIcon = { Icon(Icons.Default.Layers, null, Modifier.size(16.dp)) },
                                label = { Text("Group series", fontSize = 13.sp) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = query.status == null && query.activeFilterCount == 0,
                                onClick = { vm.updateVaultQuery { VaultQuery(text = it.text, sort = it.sort) } },
                                label = { Text("All (${entries.size})", fontSize = 13.sp) }
                            )
                        }
                        items(WatchStatus.values().toList()) { s ->
                            FilterChip(
                                selected = query.status == s,
                                onClick = { vm.updateVaultQuery { it.copy(status = if (it.status == s) null else s) } },
                                label = { Text("${s.label} (${entries.count { it.status == s }})", fontSize = 13.sp) }
                            )
                        }
                        if (!showStats) {
                            item {
                                AssistChip(onClick = { vm.setVaultShowStats(true) }, label = { Text("Show stats", fontSize = 12.sp) })
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            Column(Modifier.fillMaxSize()) {
                val listing = entries.isNotEmpty() && shown.isNotEmpty()
                if (!listing) header()
                when {
                    entries.isEmpty() -> EmptyState(
                        title = "Nothing in H-Vault yet",
                        message = "Use Search to add entries, move titles here from your library, or tap + above to create a custom entry.",
                        icon = Icons.Default.Lock,
                        action = "Add custom entry",
                        onAction = { showAddCustom = true }
                    )
                    shown.isEmpty() -> EmptyState(
                        title = "No matches",
                        message = "Nothing in your vault fits these filters.",
                        icon = Icons.Default.SearchOff,
                        action = "Clear filters",
                        onAction = { vm.updateVaultQuery { VaultQuery(sort = it.sort) } }
                    )
                    grid -> LazyVerticalGrid(
                        GridCells.Adaptive(cellMinWidth),
                        state = gridState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.layout(fullBleed)) { header() } }
                        items(rows, key = { it.key }) { g ->
                            val e = g.representative
                            VaultGridItem(
                                e = e,
                                group = if (g.members.size > 1) g else null,
                                selected = e.id in sel,
                                selecting = selecting,
                                onClick = { if (g.members.size > 1) openGroupKey = g.key else if (selecting) toggle(e.id) else open = e.id },
                                onLongClick = { if (g.members.size > 1) openGroupKey = g.key else toggle(e.id) }
                            )
                        }
                    }
                    else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "header") { header() }
                        items(rows, key = { it.key }) { g ->
                            val e = g.representative
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                SwipeableLibraryRow(
                                    enabled = vm.prefs.swipeGestures && !selecting && g.members.size == 1,
                                    onSwipeRightPlusOne = { vm.plusOne(e) },
                                    onSwipeLeftStatus = {
                                        val next = when (e.status) {
                                            WatchStatus.PLAN_TO_WATCH -> WatchStatus.WATCHING
                                            WatchStatus.WATCHING -> WatchStatus.COMPLETED
                                            WatchStatus.COMPLETED -> WatchStatus.ON_HOLD
                                            else -> WatchStatus.WATCHING
                                        }
                                        vm.update(VaultRules.withStatus(e, next))
                                    }
                                ) {
                                    VaultListRow(
                                        e = e,
                                        group = if (g.members.size > 1) g else null,
                                        selected = e.id in sel,
                                        selecting = selecting,
                                        onClick = { if (g.members.size > 1) openGroupKey = g.key else if (selecting) toggle(e.id) else open = e.id },
                                        onLongClick = { if (g.members.size > 1) openGroupKey = g.key else toggle(e.id) },
                                        onPlusOne = if (!selecting && (e.totalEpisodes == 0 || e.watchedEpisodes < e.totalEpisodes)) ({ vm.plusOne(e) }) else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val past = if (grid) gridState.firstVisibleItemIndex > 6 else listState.firstVisibleItemIndex > 3
            ScrollToTop(past && shown.isNotEmpty(), inTabScreen = false) { scope.launch { if (grid) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0) } }
        }
    }

    activeGroup?.let { g ->
        VaultFranchiseSheet(
            group = g,
            onOpenEntry = { id -> openGroupKey = null; open = id },
            onPlusOne = { e -> vm.plusOne(e) },
            onDismiss = { openGroupKey = null }
        )
    }
    if (showFilters) {
        VaultFilterSheet(
            query = query,
            tags = allTags,
            studios = allStudios,
            shownCount = shown.size,
            onUpdate = { updated -> vm.updateVaultQuery { updated } },
            onReset = { vm.updateVaultQuery { VaultQuery(text = it.text, sort = it.sort) } },
            onDismiss = { showFilters = false }
        )
    }
    if (showWheel) {
        VaultRandomPickerSheet(
            all = entries,
            tags = allTags,
            onOpen = { showWheel = false; open = it.id },
            onStartWatching = { showWheel = false; vm.update(VaultRules.withStatus(it, WatchStatus.WATCHING)); open = it.id },
            onDismiss = { showWheel = false }
        )
    }
    if (showAddCustom) {
        AddCustomVaultSheet(
            existingTags = allTags,
            onSave = { custom ->
                showAddCustom = false
                vm.update(custom)
                vm.say("Added “${custom.title}” to H-Vault")
            },
            onDismiss = { showAddCustom = false }
        )
    }
    if (confirmMove) ConfirmDialog(
        "Move ${sel.size} ${if (sel.size == 1) "title" else "titles"} to your library?", "They leave H-Vault and appear in the normal library.", "Move",
        { confirmMove = false; vm.moveToLibrary(sel.toList()); selected = emptySet() }, { confirmMove = false }
    )
    if (confirmDelete) ConfirmDialog(
        "Delete ${sel.size} ${if (sel.size == 1) "title" else "titles"}?", "They will be removed from H-Vault.", "Delete",
        { confirmDelete = false; vm.deleteMany(sel.toList()); selected = emptySet() }, { confirmDelete = false }, destructive = true
    )
    open?.let { id -> entries.firstOrNull { it.id == id }?.let { VaultDetailSheet(it, allTags, vm) { open = null } } ?: run { open = null } }
}

@Composable
private fun VaultStatsBanner(
    entries: List<HVaultEntry>,
    uncensoredOnly: Boolean,
    onToggleUncensored: () -> Unit,
    onHide: () -> Unit
) {
    val totalEps = entries.sumOf { it.watchedEpisodes.coerceAtLeast(0) }
    val minutes = entries.sumOf { it.watchedEpisodes.coerceAtLeast(0).toLong() * (if (it.durationMinutes > 0) it.durationMinutes else 24) }
    val rated = entries.filter { it.rating > 0f }
    val avg = if (rated.isNotEmpty()) rated.map { it.rating }.average().toFloat() else 0f
    val uncensoredCount = entries.count { !it.isCensored }

    AniCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "$totalEps eps watched · ${formatDuration(minutes)}" +
                        (if (avg > 0f) " · ★ ${"%.1f".format(avg)} avg" else ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${entries.count { it.status == WatchStatus.WATCHING }} watching · ${entries.count { it.status == WatchStatus.COMPLETED }} completed · $uncensoredCount uncensored",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.dim
                )
            }
            FilterChip(
                selected = uncensoredOnly,
                onClick = onToggleUncensored,
                label = { Text("Uncensored", fontSize = 11.sp) }
            )
            IconButton(onClick = onHide, Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "Hide vault summary", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.dim)
            }
        }
    }
}

@Composable
private fun VaultGridItem(
    e: HVaultEntry,
    group: VaultGroup? = null,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shownTitle = group?.displayTitle ?: e.title
    val shownStatus = group?.overallStatus ?: e.status
    val subText = if (group != null) {
        val tot = group.totalEpisodes
        if (tot > 0) "${group.members.size} parts · ${group.watchedEpisodes}/$tot eps"
        else "${group.members.size} parts · ${group.watchedEpisodes} eps"
    } else progressText(e)

    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "$shownTitle, ${shownStatus.label}, $subText${if (selected) ", selected" else ""}" }
    ) {
        Box {
            Poster(e.imageUrl, Modifier.fillMaxWidth().aspectRatio(0.7f), allowDiskCache = false)
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { StatusChip(shownStatus) }
            if (selecting) {
                Checkbox(selected, null, Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color(0x990B0D16), RoundedCornerShape(6.dp)))
            } else if (group != null) {
                SeasonBadgeChip("${group.members.size}", Modifier.align(Alignment.BottomEnd).padding(6.dp))
            } else {
                val badge = when {
                    e.rating > 0f -> "★ ${"%.1f".format(e.rating)}"
                    !e.isCensored -> "UNC"
                    else -> null
                }
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    ) {
                        Text(badge, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text(shownTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onBackground)
        Text(subText, fontSize = 11.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VaultListRow(
    e: HVaultEntry,
    group: VaultGroup? = null,
    partBadge: String? = null,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlusOne: (() -> Unit)? = null
) {
    val shownTitle = group?.displayTitle ?: e.title
    val shownStatus = group?.overallStatus ?: e.status
    val totalEps = group?.totalEpisodes ?: e.totalEpisodes
    val watchedEps = group?.watchedEpisodes ?: e.watchedEpisodes

    val subtitle = if (group != null) {
        val epPart = if (totalEps > 0) "Ep $watchedEps / $totalEps total" else "Ep $watchedEps watched"
        "$epPart · ${group.completedParts}/${group.members.size} completed"
    } else {
        progressText(e) +
            (if (e.rating > 0) "  ·  ★ ${"%.1f".format(e.rating)}" else "") +
            (if (!e.studio.isNullOrBlank()) "  ·  ${e.studio}" else "")
    }

    AniCard(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { Checkbox(selected, null); Spacer(Modifier.width(8.dp)) }
            Poster(e.imageUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp), allowDiskCache = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(shownTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(shownStatus)
                    if (group != null) {
                        SeasonBadgeChip("${group.members.size} Parts")
                    } else if (partBadge != null) {
                        Tag(partBadge, MaterialTheme.colorScheme.primary)
                    }
                    if (!e.isCensored && group == null) {
                        Tag("Uncensored", MaterialTheme.colorScheme.primary)
                    }
                }
                if (totalEps > 0) LinearProgressIndicator(
                    progress = { (watchedEps.toFloat() / totalEps).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AniVaultColors.Progress, trackColor = MaterialTheme.colorScheme.outline
                )
            }
            if (onPlusOne != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onPlusOne, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Add, "Mark next episode of ${e.title} watched", Modifier.size(18.dp))
                }
            } else if (group != null) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.ChevronRight, "Open series parts", tint = MaterialTheme.colorScheme.dim)
            }
        }
    }
}

@Composable
private fun VaultFranchiseSheet(
    group: VaultGroup,
    onOpenEntry: (String) -> Unit,
    onPlusOne: (HVaultEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val members = group.orderedMembers
    val totalEps = group.totalEpisodes
    val watchedEps = group.watchedEpisodes

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.62f)
    ) {
        TrackGlassOverlay()
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Poster(group.representative.imageUrl, Modifier.width(64.dp).height(92.dp), RoundedCornerShape(10.dp), allowDiskCache = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.displayTitle, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(
                        "${group.completedParts} of ${members.size} parts completed" +
                            (if (totalEps > 0) " · $watchedEps / $totalEps eps" else " · $watchedEps eps watched"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.dim
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(group.overallStatus)
                        SeasonBadgeChip("${members.size} Parts")
                    }
                    if (totalEps > 0) {
                        LinearProgressIndicator(
                            progress = { (watchedEps.toFloat() / totalEps).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = AniVaultColors.Progress,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text("Series Parts & Episodes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            members.forEachIndexed { idx, m ->
                val canPlus = m.totalEpisodes == 0 || m.watchedEpisodes < m.totalEpisodes
                VaultListRow(
                    e = m,
                    group = null,
                    partBadge = "#${idx + 1}",
                    selected = false,
                    selecting = false,
                    onClick = { onOpenEntry(m.id) },
                    onLongClick = { onOpenEntry(m.id) },
                    onPlusOne = if (canPlus) ({ onPlusOne(m) }) else null
                )
            }
        }
    }
}

@Composable
private fun VaultFilterSheet(
    query: VaultQuery,
    tags: List<String>,
    studios: List<String>,
    shownCount: Int,
    onUpdate: (VaultQuery) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, scrimColor = Color.Black.copy(alpha = 0.62f)) {
        TrackGlassOverlay()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("H-Vault Filters", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = onReset) { Text("Reset") }
            }
            SwitchRow(
                title = "Uncensored only",
                subtitle = "Hide entries marked as censored.",
                checked = query.uncensoredOnly
            ) { onUpdate(query.copy(uncensoredOnly = it)) }

            Text("My score at least", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5f, 6f, 7f, 8f, 9f).forEach { sc ->
                    FilterChip(
                        selected = query.minRating == sc,
                        onClick = { onUpdate(query.copy(minRating = if (query.minRating == sc) null else sc)) },
                        label = { Text("${sc.toInt()}+") }
                    )
                }
            }

            if (tags.isNotEmpty()) {
                Text("My tags", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        FilterChip(
                            selected = query.tag.equals(t, true),
                            onClick = { onUpdate(query.copy(tag = if (query.tag.equals(t, true)) null else t)) },
                            label = { Text(t) }
                        )
                    }
                }
            }

            if (studios.isNotEmpty()) {
                Text("Studio", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    studios.forEach { st ->
                        FilterChip(
                            selected = query.studio.equals(st, true),
                            onClick = { onUpdate(query.copy(studio = if (query.studio.equals(st, true)) null else st)) },
                            label = { Text(st) }
                        )
                    }
                }
            }

            Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Show $shownCount titles") }
        }
    }
}

@Composable
private fun VaultRandomPickerSheet(
    all: List<HVaultEntry>,
    tags: List<String>,
    onOpen: (HVaultEntry) -> Unit,
    onStartWatching: (HVaultEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var status by remember { mutableStateOf<WatchStatus?>(null) }
    var uncensoredOnly by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<HVaultEntry?>(null) }
    val scope = rememberCoroutineScope()

    fun spin() {
        val pool = all.filter { e ->
            (status == null || e.status == status) &&
                (!uncensoredOnly || !e.isCensored) &&
                (tag == null || e.tags.any { it.equals(tag, true) })
        }
        if (pool.isEmpty()) { picked = null; return }
        scope.launch {
            spinning = true
            repeat(8) {
                picked = pool.random()
                delay(50L)
            }
            picked = pool.random()
            spinning = false
        }
    }

    LaunchedEffect(Unit) { spin() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, scrimColor = Color.Black.copy(alpha = 0.62f)) {
        TrackGlassOverlay()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Casino, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Spin the Vault Wheel", fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { spin() }, enabled = !spinning) { Text("Spin Again") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(status == null, { status = null; spin() }, { Text("Any status") })
                FilterChip(status == WatchStatus.PLAN_TO_WATCH, { status = WatchStatus.PLAN_TO_WATCH; spin() }, { Text("Plan to Watch") })
                FilterChip(status == WatchStatus.WATCHING, { status = WatchStatus.WATCHING; spin() }, { Text("Watching") })
                FilterChip(status == WatchStatus.COMPLETED, { status = WatchStatus.COMPLETED; spin() }, { Text("Completed") })
                FilterChip(uncensoredOnly, { uncensoredOnly = !uncensoredOnly; spin() }, { Text("Uncensored only") })
            }
            if (tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(tag == null, { tag = null; spin() }, { Text("Any tag") }) }
                    items(tags) { t -> FilterChip(tag == t, { tag = if (tag == t) null else t; spin() }, { Text(t) }) }
                }
            }
            val p = picked
            if (p == null) {
                EmptyState("No matching titles", "Broaden the filters above to spin.", Icons.Default.SearchOff)
            } else {
                AniCard(Modifier.fillMaxWidth(), onClick = { onOpen(p) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Poster(p.imageUrl, Modifier.width(76.dp).height(108.dp), RoundedCornerShape(10.dp), allowDiskCache = false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(progressText(p) + (if (!p.studio.isNullOrBlank()) " · ${p.studio}" else ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(p.status)
                                if (!p.isCensored) Tag("Uncensored", MaterialTheme.colorScheme.primary)
                            }
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

@Composable
private fun AddCustomVaultSheet(
    existingTags: List<String>,
    onSave: (HVaultEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var studio by remember { mutableStateOf("") }
    var totalEps by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var isCensored by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(WatchStatus.PLAN_TO_WATCH) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, scrimColor = Color.Black.copy(alpha = 0.62f)) {
        TrackGlassOverlay()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add Custom Vault Entry", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(title, { title = it }, label = { Text("Title *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(studio, { studio = it }, label = { Text("Studio / Circle") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(totalEps, { totalEps = it.filter(Char::isDigit).take(4) }, label = { Text("Episodes") }, singleLine = true, modifier = Modifier.width(110.dp))
            }
            OutlinedTextField(imageUrl, { imageUrl = it.trim() }, label = { Text("Cover image URL (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.values().forEach { s -> FilterChip(status == s, { status = s }, { Text(s.label) }) }
            }
            SwitchRow("Uncensored release", null, !isCensored) { isCensored = !it }
            TagEditor(tags = tags, suggestions = existingTags, onChange = { tags = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = title.isNotBlank(),
                    onClick = {
                        val eps = totalEps.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        val now = System.currentTimeMillis()
                        val entry = HVaultEntry(
                            id = "custom:$now",
                            title = title.trim(),
                            imageUrl = imageUrl,
                            totalEpisodes = eps,
                            watchedEpisodes = if (status == WatchStatus.COMPLETED && eps > 0) eps else 0,
                            status = status,
                            rating = 0f,
                            studio = studio.trim().takeIf { it.isNotEmpty() },
                            isCensored = isCensored,
                            tags = tags,
                            notes = "",
                            durationMinutes = 24,
                            updatedAt = now
                        )
                        onSave(entry)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save to Vault") }
            }
        }
    }
}

@Composable
private fun VaultDetailSheet(original: HVaultEntry, allTags: List<String>, vm: VaultViewModel, onDismiss: () -> Unit) {
    var e by remember(original.id) { mutableStateOf(original) }
    var studioText by remember(original.id) { mutableStateOf(original.studio.orEmpty()) }
    var totalEpsText by remember(original.id) { mutableStateOf(if (original.totalEpisodes > 0) original.totalEpisodes.toString() else "") }
    var confirmDelete by remember(original.id) { mutableStateOf(false) }
    var confirmMove by remember(original.id) { mutableStateOf(false) }
    var confirmDiscard by remember(original.id) { mutableStateOf(false) }
    var loadingCovers by remember(original.id) { mutableStateOf(false) }
    var coverCandidates by remember(original.id) { mutableStateOf<List<ScrapedEntry>?>(null) }
    var richDetails by remember(original.id) { mutableStateOf<VaultRichDetails?>(null) }
    var loadingRich by remember(original.id) { mutableStateOf(true) }
    var activePreview by remember(original.id) { mutableStateOf<VaultEpisodePreview?>(null) }

    val ctx = LocalContext.current
    val dirty = e != original
    val hasCensoredCatalogCover = UncensoredCoverMatcher.isCensoredCatalogCover(e.imageUrl)
    val accent = rememberCoverAccent(e.imageUrl, MaterialTheme.colorScheme.primary)

    LaunchedEffect(original.id) {
        loadingRich = true
        vm.loadRichDetails(original) { det ->
            richDetails = det
            loadingRich = false
            if (studioText.isBlank() && det.studios.isNotEmpty()) {
                val st = det.studios.first()
                studioText = st
                e = e.copy(studio = st)
            }
            if (e.totalEpisodes <= 0 && det.episodeCount > 0) {
                totalEpsText = det.episodeCount.toString()
                e = e.copy(totalEpisodes = det.episodeCount)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (dirty) confirmDiscard = true else onDismiss() }, containerColor = MaterialTheme.colorScheme.surface, scrimColor = Color.Black.copy(alpha = 0.62f)) {
        TrackGlassOverlay()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Hero Header Card (matches normal library EntryDetailSheet) ──
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), Color.Transparent)))
                    .padding(12.dp)
            ) {
                Row {
                    Poster(e.imageUrl, Modifier.width(96.dp).height(138.dp), allowDiskCache = false)
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        Text(e.title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        val det = richDetails
                        val metaLine = listOfNotNull(
                            det?.format?.takeIf { it.isNotBlank() } ?: "OVA",
                            det?.year?.toString(),
                            det?.airingStatus?.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (metaLine.isNotBlank()) {
                            Text(metaLine, fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
                        }
                        val per = (if (e.durationMinutes > 0) e.durationMinutes else det?.durationMinutes?.takeIf { it > 0 } ?: 24).toLong()
                        val approx = if (e.durationMinutes > 0) "" else "~"
                        val leftPart = if (e.totalEpisodes > 0) " · left $approx${formatDuration((e.totalEpisodes - e.watchedEpisodes).coerceAtLeast(0) * per)}" else ""
                        Text(
                            "$approx$per min/episode · watched $approx${formatDuration(e.watchedEpisodes * per)}$leftPart",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.dim
                        )
                        if (det != null && det.communityScore > 0f) {
                            Text("Community score ★ ${"%.1f".format(det.communityScore)}", fontSize = 13.sp)
                        }
                        if (det != null && det.sourceLabel.isNotBlank()) {
                            Text("Data from ${det.sourceLabel}", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                            StatusChip(e.status)
                            Tag(if (e.isCensored) "Censored" else "Uncensored", if (e.isCensored) MaterialTheme.colorScheme.dim else MaterialTheme.colorScheme.primary)
                            if (hasCensoredCatalogCover) {
                                Tag("Catalog Cover", MaterialTheme.colorScheme.error)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                loadingCovers = true
                                vm.findUncensoredCandidates(e.title) { list ->
                                    loadingCovers = false
                                    coverCandidates = list
                                }
                            },
                            enabled = !loadingCovers,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            if (loadingCovers) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Searching scrapers…", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.AutoFixHigh, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (hasCensoredCatalogCover) "Replace Censored Cover" else "Find Uncensored Cover", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // ── Candidate Uncensored Covers Picker (with Source Name under each cover) ──
            coverCandidates?.let { candidates ->
                AniCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Uncensored Covers (${candidates.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { coverCandidates = null }) { Text("Close", fontSize = 12.sp) }
                        }
                        if (candidates.isEmpty()) {
                            Text("No matching covers found across Hanime / WatchHentai / nhentai scrapers.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(candidates, key = { it.coverUrl }) { cand ->
                                    Column(
                                        Modifier.width(96.dp).combinedClickable(
                                            onClick = {
                                                e = e.copy(
                                                    imageUrl = cand.coverUrl,
                                                    isCensored = cand.isCensored,
                                                    studio = e.studio?.takeIf { it.isNotBlank() } ?: cand.studio,
                                                    tags = (e.tags + cand.tags).distinct()
                                                )
                                                if (studioText.isBlank() && !cand.studio.isNullOrBlank()) {
                                                    studioText = cand.studio
                                                }
                                                coverCandidates = null
                                            },
                                            onLongClick = {}
                                        ),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Poster(cand.coverUrl, Modifier.width(96.dp).height(134.dp), RoundedCornerShape(8.dp), allowDiskCache = false)
                                        Text(
                                            cand.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        Text(
                                            cand.displaySource,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Status & Progress Controls ──
            Text("Status", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.values().forEach { s -> FilterChip(e.status == s, { e = VaultRules.withStatus(e, s) }, { Text(s.label) }) }
            }
            Text("Progress", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalIconButton(onClick = { e = VaultRules.withProgress(e, e.watchedEpisodes - 1) }, enabled = e.watchedEpisodes > 0) { Icon(Icons.Default.Remove, "One episode less") }
                Text(if (e.totalEpisodes > 0) "${e.watchedEpisodes} / ${e.totalEpisodes}" else "${e.watchedEpisodes} (total unknown)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                FilledTonalIconButton(onClick = { e = VaultRules.withProgress(e, e.watchedEpisodes + 1) }, enabled = e.totalEpisodes == 0 || e.watchedEpisodes < e.totalEpisodes) { Icon(Icons.Default.Add, "One episode more") }
                Spacer(Modifier.weight(1f))
                if (e.watchedEpisodes > 0) {
                    AssistChip(
                        onClick = { e = e.copy(status = WatchStatus.WATCHING, watchedEpisodes = 0) },
                        label = { Text("Start Rewatch") },
                        leadingIcon = { Icon(Icons.Default.Replay, null, Modifier.size(16.dp)) }
                    )
                }
            }
            if (e.totalEpisodes > 0) {
                LinearProgressIndicator(
                    progress = { (e.watchedEpisodes.toFloat() / e.totalEpisodes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.outline
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = studioText,
                    onValueChange = { studioText = it; e = e.copy(studio = it.trim().takeIf(String::isNotEmpty)) },
                    label = { Text("Studio / Circle") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = totalEpsText,
                    onValueChange = {
                        val clean = it.filter(Char::isDigit).take(4)
                        totalEpsText = clean
                        val total = clean.toIntOrNull() ?: 0
                        e = e.copy(totalEpisodes = total, watchedEpisodes = if (total > 0) e.watchedEpisodes.coerceAtMost(total) else e.watchedEpisodes)
                    },
                    label = { Text("Total eps") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
            }
            SwitchRow(
                title = "Uncensored version",
                subtitle = "Mark whether this entry is uncensored.",
                checked = !e.isCensored
            ) { uncensored -> e = e.copy(isCensored = !uncensored) }

            Text(if (e.rating > 0) "Your rating: ${"%.1f".format(e.rating)} / 10" else "Your rating: not rated", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Slider(
                value = e.rating,
                onValueChange = { e = e.copy(rating = Math.round(it * 2) / 2f) },
                valueRange = 0f..10f,
                steps = 19,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
            )

            TagEditor(tags = e.tags, suggestions = allTags, onChange = { e = e.copy(tags = it) })

            OutlinedTextField(e.notes, { e = e.copy(notes = it) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            // ── Episode Previews (Fetched from Scrapers) ──
            val det = richDetails
            if (loadingRich) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Loading source details & episode previews…", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                }
            } else if (det != null) {
                if (det.episodePreviews.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Episode Previews (${det.episodePreviews.size})",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Tap to view / mark watched", fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(det.episodePreviews, key = { "${it.episodeNumber}:${it.previewImageUrl}" }) { ep ->
                                val watched = ep.episodeNumber <= e.watchedEpisodes
                                AniCard(
                                    Modifier.width(172.dp),
                                    onClick = { activePreview = ep }
                                ) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box {
                                            Poster(
                                                ep.previewImageUrl,
                                                Modifier.fillMaxWidth().height(98.dp),
                                                RoundedCornerShape(8.dp),
                                                allowDiskCache = false
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                                modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    if (watched) {
                                                        Icon(
                                                            Icons.Default.CheckCircle,
                                                            null,
                                                            tint = AniVaultColors.Success,
                                                            modifier = Modifier.size(11.dp)
                                                        )
                                                    }
                                                    Text(
                                                        "Ep ${ep.episodeNumber}",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (watched) AniVaultColors.Success else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                            if (ep.sourceName.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                                                ) {
                                                    Text(
                                                        ep.sourceName,
                                                        Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            ep.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val epSub = listOfNotNull(
                                            ep.durationMinutes.takeIf { it > 0 }?.let { "${it}m" },
                                            ep.releaseDate.takeIf { it.isNotBlank() }
                                        ).joinToString(" · ")
                                        if (epSub.isNotEmpty()) {
                                            Text(epSub, fontSize = 10.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Studios ──
                val allStudios = (listOfNotNull(e.studio?.takeIf { it.isNotBlank() }) + det.studios).distinct()
                if (allStudios.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Studio", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            allStudios.forEach { st -> Tag(st, MaterialTheme.colorScheme.primary) }
                        }
                    }
                }

                // ── Characters & Voice Actors ──
                if (det.characters.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Characters & Voice Actors", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(det.characters, key = { it.id }) { c ->
                                AniCard(Modifier.width(112.dp)) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Poster(c.imageUrl, Modifier.fillMaxWidth().height(110.dp), RoundedCornerShape(8.dp), allowDiskCache = false)
                                        Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(c.role.ifBlank { "Character" }, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                        c.voiceActor?.takeIf { it.isNotBlank() }?.let { va ->
                                            Text("CV: $va", fontSize = 10.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Franchise Watch Order & Related ──
                if (det.relations.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Franchise Watch Order & Related", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(det.relations, key = { it.media.anilistId }) { r ->
                                Column(
                                    Modifier.width(108.dp).clickable {
                                        vm.save(
                                            ScrapedEntry(
                                                id = "sync:anilist:${r.media.anilistId}",
                                                title = r.media.title,
                                                coverUrl = r.media.coverUrl,
                                                tags = r.media.genres,
                                                studio = null,
                                                isCensored = false,
                                                episodeCount = r.media.episodes,
                                                durationMinutes = r.media.durationMinutes,
                                                detailUrl = "https://anilist.co/anime/${r.media.anilistId}",
                                                sourceName = "AniList"
                                            )
                                        )
                                    },
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Poster(r.media.coverUrl, Modifier.width(108.dp).height(148.dp), RoundedCornerShape(10.dp), allowDiskCache = false)
                                    Text(
                                        text = relationLabel(r.relation) + (r.media.year?.let { " · $it" } ?: ""),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(r.media.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // ── Where to Watch & Links ──
                if (det.links.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Where to Watch & Links", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            det.links.forEach { link ->
                                AssistChip(
                                    onClick = {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    },
                                    label = { Text(link.site, fontSize = 12.sp) },
                                    trailingIcon = { Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                }

                // ── Genres & Synopsis (matches normal library EntryDetailSheet) ──
                if (det.genres.isNotEmpty()) {
                    Text(det.genres.joinToString(" · "), fontSize = 13.sp, color = accent)
                }
                if (det.synopsis.isNotBlank()) {
                    Text(det.synopsis, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            TextButton(onClick = { confirmMove = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Move to library")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                Button(onClick = { vm.update(e); onDismiss() }, Modifier.weight(1f), enabled = dirty, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Save") }
            }
        }
    }

    // ── Full-Size Episode Preview Dialog ──
    activePreview?.let { ep ->
        AlertDialog(
            onDismissRequest = { activePreview = null },
            title = {
                Text("Episode ${ep.episodeNumber} · ${ep.title}", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            text = {
                TrackGlassOverlay()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Poster(
                        ep.previewImageUrl,
                        Modifier.fillMaxWidth().height(190.dp),
                        RoundedCornerShape(12.dp),
                        allowDiskCache = false
                    )
                    val meta = listOfNotNull(
                        ep.sourceName.takeIf { it.isNotBlank() }?.let { "Source: $it" },
                        ep.durationMinutes.takeIf { it > 0 }?.let { "${it} min" },
                        ep.releaseDate.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                    }
                    if (ep.detailUrl.startsWith("https://")) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ep.detailUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open on ${ep.sourceName.ifBlank { "Source" }}")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        e = VaultRules.withProgress(e, maxOf(e.watchedEpisodes, ep.episodeNumber))
                        activePreview = null
                    }
                ) {
                    Text("Mark Ep ${ep.episodeNumber} Watched")
                }
            },
            dismissButton = {
                TextButton(onClick = { activePreview = null }) { Text("Close") }
            }
        )
    }

    if (confirmMove) ConfirmDialog("Move to library?", "This title leaves H-Vault and appears in the normal library.", "Move", { confirmMove = false; vm.moveToLibrary(listOf(original.id)); onDismiss() }, { confirmMove = false })
    if (confirmDelete) ConfirmDialog("Remove from H-Vault?", "This entry will be deleted.", "Remove", { confirmDelete = false; vm.delete(original.id); onDismiss() }, { confirmDelete = false }, destructive = true)
    if (confirmDiscard) ConfirmDialog("Discard changes?", "Your edits have not been saved.", "Discard", { confirmDiscard = false; onDismiss() }, { confirmDiscard = false }, destructive = true)
}
