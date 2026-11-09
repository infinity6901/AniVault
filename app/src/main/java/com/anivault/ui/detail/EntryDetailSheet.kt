package com.anivault.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.core.AppPrefs
import com.anivault.data.library.LibraryRepository
import com.anivault.data.library.ProgressRules
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.entity.WatchHistoryEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.Dates
import com.anivault.ui.common.*
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    private val repo: LibraryRepository,
    private val prefs: AppPrefs,
    private val mover: com.anivault.data.library.VaultMover
) : ViewModel() {
    val showSynopsis: Boolean get() = prefs.showSynopsis
    val confirmDelete: Boolean get() = prefs.confirmDelete
    val autoComplete: Boolean get() = prefs.autoCompleteOnLast
    suspend fun load(id: String): AnimeEntry? = repo.get(id)
    fun history(id: String): Flow<List<WatchHistoryEntry>> = repo.observeEntryHistory(id)
    suspend fun allTags(): List<String> = repo.observeAll().first().flatMap { it.tags }.distinct().sortedBy { it.lowercase() }
    fun save(e: AnimeEntry, done: () -> Unit) { viewModelScope.launch { repo.update(e); done() } }
    fun moveToVault(id: String, done: () -> Unit) { viewModelScope.launch { mover.toVault(listOf(id)); done() } }
    fun delete(id: String, done: () -> Unit) { viewModelScope.launch { repo.delete(listOf(id)); done() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryDetailSheet(id: String, onDismiss: () -> Unit, vm: EntryDetailViewModel = hiltViewModel()) {
    var original by remember(id) { mutableStateOf<AnimeEntry?>(null) }
    var draft by remember(id) { mutableStateOf<AnimeEntry?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var tagSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val history by remember(id) { vm.history(id) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(id) { original = vm.load(id); draft = original; loaded = true; tagSuggestions = vm.allTags() }

    val dirty = draft != original
    ModalBottomSheet(onDismissRequest = { if (dirty) confirmDiscard = true else onDismiss() }, containerColor = MaterialTheme.colorScheme.surface) {
        val e = draft
        if (!loaded) { LoadingBox(); return@ModalBottomSheet }
        if (e == null) { EmptyState("Not found", "This title is no longer in your library."); return@ModalBottomSheet }
        val accent = rememberCoverAccent(e.imageUrl, MaterialTheme.colorScheme.primary)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), Color.Transparent)))
                    .padding(12.dp)
            ) {
                Row {
                    Poster(e.imageUrl, Modifier.width(96.dp).height(138.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(listOfNotNull(e.format.takeIf { it.isNotBlank() }, e.year?.toString(), e.airingStatus.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
                        run {
                            val per = com.anivault.data.library.StatsCalculator.effectiveMinutes(e).toLong()
                            val approx = if (e.durationMinutes > 0) "" else "~"
                            Text("$approx$per min/episode · watched $approx${formatDuration(e.watchedEpisodes * per)}" +
                                (if (e.totalEpisodes > 0) " · left $approx${formatDuration((e.totalEpisodes - e.watchedEpisodes).coerceAtLeast(0) * per)}" else ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                        }
                        if (e.providerScore > 0) Text("Community score ★ ${"%.1f".format(e.providerScore)}", fontSize = 13.sp)
                        if (e.sourceName.isNotBlank()) Text("Data from ${e.sourceName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                        if (e.pendingSync) Text("Waiting to sync", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Text("Status", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.values().forEach { s -> FilterChip(e.status == s, { draft = ProgressRules.withStatus(e, s, System.currentTimeMillis()) }, { Text(s.label) }) }
            }
            Text("Progress", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { draft = ProgressRules.apply(e, e.watchedEpisodes - 1, vm.autoComplete, System.currentTimeMillis()) }, enabled = e.watchedEpisodes > 0) { Icon(Icons.Default.Remove, "One episode less") }
                Text(if (e.totalEpisodes > 0) "${e.watchedEpisodes} / ${e.totalEpisodes}" else "${e.watchedEpisodes} (total unknown)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                FilledTonalIconButton(onClick = { draft = ProgressRules.apply(e, e.watchedEpisodes + 1, vm.autoComplete, System.currentTimeMillis()) }, enabled = e.totalEpisodes == 0 || e.watchedEpisodes < e.totalEpisodes) { Icon(Icons.Default.Add, "One episode more") }
            }
            if (e.totalEpisodes > 0) {
                LinearProgressIndicator(
                    progress = { (e.watchedEpisodes.toFloat() / e.totalEpisodes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.outline
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Rewatches: ${e.rewatchCount}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                OutlinedIconButton(onClick = { draft = e.copy(rewatchCount = (e.rewatchCount - 1).coerceAtLeast(0)) }, enabled = e.rewatchCount > 0, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, "Fewer rewatches", Modifier.size(16.dp))
                }
                OutlinedIconButton(onClick = { draft = e.copy(rewatchCount = e.rewatchCount + 1) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, "More rewatches", Modifier.size(16.dp))
                }
                if (e.status == WatchStatus.COMPLETED || e.watchedEpisodes > 0) {
                    AssistChip(
                        onClick = { draft = ProgressRules.startRewatch(e, System.currentTimeMillis()) },
                        label = { Text("Start Rewatch") },
                        leadingIcon = { Icon(Icons.Default.Replay, null, Modifier.size(16.dp)) }
                    )
                }
            }
            Text(if (e.rating > 0) "Your rating: ${"%.1f".format(e.rating)} / 10" else "Your rating: not rated", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
            Slider(
                value = e.rating,
                onValueChange = { draft = e.copy(rating = Math.round(it * 2) / 2f) },
                valueRange = 0f..10f,
                steps = 19,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
            )
            if (e.startDate != null || e.endDate != null) {
                Text(listOfNotNull(e.startDate?.let { "Started ${Dates.toIsoDate(it)}" }, e.endDate?.let { "Finished ${Dates.toIsoDate(it)}" }).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            }
            com.anivault.ui.common.TagEditor(e.tags, tagSuggestions) { draft = e.copy(tags = it) }
            OutlinedTextField(e.notes, { draft = e.copy(notes = it) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            com.anivault.ui.detail.RelatedRow(RemoteIds.parse(e.remoteIds))
            if (e.genres.isNotEmpty()) Text(e.genres.joinToString(" · "), fontSize = 13.sp, color = accent)
            if (vm.showSynopsis && e.synopsis.isNotBlank()) Text(e.synopsis, fontSize = 14.sp)
            if (history.isNotEmpty()) {
                val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
                Text("Episode History", color = MaterialTheme.colorScheme.dim, fontSize = 13.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    history.take(5).forEach { h ->
                        Text(
                            "• Episode ${h.episode}" + (if (h.rewatchCount > 0) " (Rewatch #${h.rewatchCount})" else "") + " — ${fmt.format(Date(h.watchedAt))}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.dim
                        )
                    }
                }
            }
            TextButton(onClick = { confirmMove = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Move to H-Vault")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (vm.confirmDelete) confirmDelete = true else vm.delete(e.id, onDismiss) }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                Button(onClick = { vm.save(e, onDismiss) }, Modifier.weight(1f), enabled = dirty, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Save") }
            }
        }
    }
    if (confirmMove) ConfirmDialog("Move to H-Vault?", "This title leaves your library and appears in H-Vault behind your lock. Unsaved edits here are not kept.", "Move", { confirmMove = false; vm.moveToVault(id, onDismiss) }, { confirmMove = false })
    if (confirmDelete) ConfirmDialog("Remove this title?", "It will be deleted from your library.", "Remove", { confirmDelete = false; vm.delete(id, onDismiss) }, { confirmDelete = false }, destructive = true)
    if (confirmDiscard) ConfirmDialog("Discard changes?", "Your edits have not been saved.", "Discard", { confirmDiscard = false; onDismiss() }, { confirmDiscard = false }, destructive = true)
}
