@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.anivault.ui.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.anivault.ui.common.CompactSearch
import com.anivault.ui.common.ScrollToTop
import com.anivault.ui.common.fullBleed
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
import com.anivault.data.library.LibrarySort
import com.anivault.data.library.VaultRules
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.ui.common.*
import com.anivault.ui.library.SwipeableLibraryRow
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.dim

private fun progressText(e: HVaultEntry) = if (e.totalEpisodes > 0) "Ep ${e.watchedEpisodes} / ${e.totalEpisodes}" else "Ep ${e.watchedEpisodes} · total unknown"

private val VAULT_SORTS = listOf(LibrarySort.UPDATED, LibrarySort.TITLE, LibrarySort.RATING, LibrarySort.PROGRESS)

private fun sortAndFilter(all: List<HVaultEntry>, text: String, status: WatchStatus?, sort: LibrarySort): List<HVaultEntry> {
    val needle = text.trim().lowercase()
    val f = all.filter { (status == null || it.status == status) && (needle.isEmpty() || it.title.lowercase().contains(needle)) }
    return when (sort) {
        LibrarySort.TITLE -> f.sortedBy { it.title.lowercase() }
        LibrarySort.RATING -> f.sortedByDescending { it.rating }
        LibrarySort.PROGRESS -> f.sortedByDescending { if (it.totalEpisodes > 0) it.watchedEpisodes.toFloat() / it.totalEpisodes else 0f }
        else -> f.sortedByDescending { it.updatedAt }
    }
}

/** "My vault" tab: same layout and behaviour as the normal Library (search, status chips, grid/list, sort, multi-select). */
@Composable
fun VaultLibraryTab(entries: List<HVaultEntry>, vm: VaultViewModel) {
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<WatchStatus?>(null) }
    var sort by remember { mutableStateOf(LibrarySort.UPDATED) }
    var grid by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val shown = remember(entries, text, status, sort) { sortAndFilter(entries, text, status, sort) }
    val visible = shown.map { it.id }.toSet()
    val sel = selected.intersect(visible)
    val selecting = sel.isNotEmpty()
    val toggle = { id: String -> selected = if (id in selected) selected - id else selected + id }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Cancel selection") }
                Text("${sel.size} selected", Modifier.weight(1f))
                TextButton(onClick = { selected = visible }) { Text("All") }
                Box {
                    IconButton(onClick = { statusMenu = true }) { Icon(Icons.Default.Edit, "Change status") }
                    DropdownMenu(statusMenu, { statusMenu = false }) {
                        WatchStatus.values().forEach { s -> DropdownMenuItem({ Text(s.label) }, onClick = { statusMenu = false; vm.setStatus(sel.toList(), s); selected = emptySet() }) }
                    }
                }
                IconButton(onClick = { confirmMove = true }) { Icon(Icons.Default.VideoLibrary, "Move selected to library") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete selected", tint = MaterialTheme.colorScheme.error) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        val gridState = rememberLazyGridState()
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(entries.size) { com.anivault.ui.common.CoverCache.preload(appCtx, entries.map { it.imageUrl }, allowDiskCache = false) }
        val header: @Composable () -> Unit = {
            Column {
                CompactSearch(text, { text = it }, "Search your vault", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), trailing = {
                    IconButton(onClick = { grid = !grid }, Modifier.size(36.dp)) { Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView, if (grid) "Switch to list" else "Switch to grid", Modifier.size(20.dp)) }
                    Box {
                        IconButton(onClick = { sortMenu = true }, Modifier.size(36.dp)) { Icon(Icons.Default.Sort, "Sort", Modifier.size(20.dp)) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            VAULT_SORTS.forEach { s -> DropdownMenuItem({ Text(s.label, fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) }, onClick = { sortMenu = false; sort = s }) }
                        }
                    }
                })
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(status == null, { status = null }, { Text("All (${entries.size})", fontSize = 13.sp) }) }
                    items(WatchStatus.values().toList()) { s ->
                        FilterChip(status == s, { status = if (status == s) null else s }, { Text("${s.label} (${entries.count { it.status == s }})", fontSize = 13.sp) })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Column(Modifier.fillMaxSize()) {
            val listing = entries.isNotEmpty() && shown.isNotEmpty()
            if (!listing) header()
            when {
                entries.isEmpty() -> EmptyState("Nothing here yet", "Use Search to add entries, or move titles here from your library.", Icons.Default.Lock)
                shown.isEmpty() -> EmptyState("No matches", "Nothing in your vault fits this search.", Icons.Default.SearchOff, action = "Clear filters", onAction = { text = ""; status = null })
                grid -> LazyVerticalGrid(
                    GridCells.Adaptive(112.dp), state = gridState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.layout(fullBleed)) { header() } }
                    items(shown, key = { it.id }) { e -> VaultGridItem(e, e.id in sel, selecting, { if (selecting) toggle(e.id) else open = e.id }, { toggle(e.id) }) }
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "header") { header() }
                    items(shown, key = { it.id }) { e ->
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SwipeableLibraryRow(
                                enabled = !selecting,
                                onSwipeRightPlusOne = { vm.update(VaultRules.withProgress(e, e.watchedEpisodes + 1)) },
                                onSwipeLeftStatus = {
                                    val next = if (e.status == WatchStatus.WATCHING) WatchStatus.COMPLETED else WatchStatus.WATCHING
                                    vm.update(VaultRules.withStatus(e, next))
                                }
                            ) {
                                VaultListRow(e, e.id in sel, selecting, { if (selecting) toggle(e.id) else open = e.id }, { toggle(e.id) })
                            }
                        }
                    }
                }
            }
        }
        val past = if (grid) gridState.firstVisibleItemIndex > 6 else listState.firstVisibleItemIndex > 3
        ScrollToTop(past && shown.isNotEmpty()) { scope.launch { if (grid) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0) } }
        }
    }

    if (confirmMove) ConfirmDialog(
        "Move ${sel.size} ${if (sel.size == 1) "title" else "titles"} to your library?", "They leave H-Vault and appear in the normal library.", "Move",
        { confirmMove = false; vm.moveToLibrary(sel.toList()); selected = emptySet() }, { confirmMove = false }
    )
    if (confirmDelete) ConfirmDialog(
        "Delete ${sel.size} ${if (sel.size == 1) "title" else "titles"}?", "They will be removed from H-Vault.", "Delete",
        { confirmDelete = false; vm.deleteMany(sel.toList()); selected = emptySet() }, { confirmDelete = false }, destructive = true
    )
    open?.let { id -> entries.firstOrNull { it.id == id }?.let { VaultDetailSheet(it, vm) { open = null } } ?: run { open = null } }
}

@Composable
private fun VaultGridItem(e: HVaultEntry, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "${e.title}, ${e.status.label}, ${progressText(e)}${if (selected) ", selected" else ""}" }
    ) {
        Box {
            Poster(e.imageUrl, Modifier.fillMaxWidth().aspectRatio(0.7f), allowDiskCache = false)
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { StatusChip(e.status) }
            if (selecting) Checkbox(selected, null, Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color(0x990B0D16), RoundedCornerShape(6.dp)))
        }
        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onBackground)
        Text(progressText(e), fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
    }
}

@Composable
private fun VaultListRow(e: HVaultEntry, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    AniCard(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { Checkbox(selected, null); Spacer(Modifier.width(8.dp)) }
            Poster(e.imageUrl, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(8.dp), allowDiskCache = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(progressText(e) + if (e.rating > 0) "  ·  ★ ${"%.1f".format(e.rating)}" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
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

@Composable
private fun VaultDetailSheet(original: HVaultEntry, vm: VaultViewModel, onDismiss: () -> Unit) {
    var e by remember(original.id) { mutableStateOf(original) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = e != original
    ModalBottomSheet(onDismissRequest = { if (dirty) confirmDiscard = true else onDismiss() }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Poster(e.imageUrl, Modifier.width(96.dp).height(138.dp), allowDiskCache = false)
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    e.studio?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.dim) }
                    val per = if (e.durationMinutes > 0) e.durationMinutes else 24
                    Text("${if (e.durationMinutes > 0) "" else "~"}$per min/episode · watched ${if (e.durationMinutes > 0) "" else "~"}${formatDuration(e.watchedEpisodes.toLong() * per)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                }
            }
            Text("Status", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.values().forEach { s -> FilterChip(e.status == s, { e = VaultRules.withStatus(e, s) }, { Text(s.label) }) }
            }
            Text("Progress", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { e = VaultRules.withProgress(e, e.watchedEpisodes - 1) }, enabled = e.watchedEpisodes > 0) { Icon(Icons.Default.Remove, "One episode less") }
                Text(if (e.totalEpisodes > 0) "${e.watchedEpisodes} / ${e.totalEpisodes}" else "${e.watchedEpisodes} (total unknown)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                FilledTonalIconButton(onClick = { e = VaultRules.withProgress(e, e.watchedEpisodes + 1) }, enabled = e.totalEpisodes == 0 || e.watchedEpisodes < e.totalEpisodes) { Icon(Icons.Default.Add, "One episode more") }
            }
            Text(if (e.rating > 0) "Your rating: ${"%.1f".format(e.rating)} / 10" else "Your rating: not rated", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Slider(value = e.rating, onValueChange = { e = e.copy(rating = Math.round(it * 2) / 2f) }, valueRange = 0f..10f, steps = 19)
            OutlinedTextField(e.notes, { e = e.copy(notes = it) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (e.tags.isNotEmpty()) Text(e.tags.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { confirmMove = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Move to library")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                Button(onClick = { vm.update(e); onDismiss() }, Modifier.weight(1f), enabled = dirty) { Text("Save") }
            }
        }
    }
    if (confirmMove) ConfirmDialog("Move to library?", "This title leaves H-Vault and appears in the normal library.", "Move", { confirmMove = false; vm.moveToLibrary(listOf(original.id)); onDismiss() }, { confirmMove = false })
    if (confirmDelete) ConfirmDialog("Remove from H-Vault?", "This entry will be deleted.", "Remove", { confirmDelete = false; vm.delete(original.id); onDismiss() }, { confirmDelete = false }, destructive = true)
    if (confirmDiscard) ConfirmDialog("Discard changes?", "Your edits have not been saved.", "Discard", { confirmDiscard = false; onDismiss() }, { confirmDiscard = false }, destructive = true)
}
