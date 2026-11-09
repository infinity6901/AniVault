package com.anivault.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.data.library.*
import com.anivault.data.local.entity.WatchHistoryEntry
import com.anivault.ui.common.*
import com.anivault.ui.theme.color
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repo: LibraryRepository,
    backfill: DurationBackfill
) : ViewModel() {
    init { backfill.run() }
    val stats: StateFlow<LibraryStats?> = repo.observeAll().map { StatsCalculator.compute(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<WatchHistoryEntry>> = repo.observeRecentHistory(25)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearHistory() { viewModelScope.launch { repo.clearHistory() } }
}

@Composable
fun StatsScreen(onBack: () -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val s by vm.stats.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Statistics", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { pad ->
        val st = s
        when {
            st == null -> LoadingBox(Modifier.padding(pad))
            st.total == 0 -> EmptyState("No statistics yet", "Add a few titles and your numbers appear here.", Icons.Default.BarChart, Modifier.padding(pad))
            else -> Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AniCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Summary", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${st.total} titles · ${st.episodesWatched} episodes watched")
                        if (st.totalRewatches > 0) {
                            Text("Rewatches: ${st.totalRewatches} total across ${st.rewatchedTitles} ${if (st.rewatchedTitles == 1) "title" else "titles"}", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                        Text("Watch time: ${formatDuration(st.watchMinutes + st.estimatedMinutes)}")
                        if (st.estimatedMinutes > 0) Text("Includes about ${formatDuration(st.estimatedMinutes)} estimated from typical episode lengths for ${st.entriesMissingDuration} titles whose databases don't list one. Exact lengths are fetched automatically.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                        if (st.remainingMinutes > 0) Text("Still to watch: ${formatDuration(st.remainingMinutes)}")
                        Text(st.averageRating?.let { "Your average rating: ${"%.1f".format(it)} / 10" } ?: "You haven't rated anything yet.")
                    }
                }
                ChartCard("By status", st.byStatus.entries.map { Bar(it.key.label, it.value, it.key.color()) })
                if (st.averageRating != null) ChartCard("Your ratings", (1..10).map { Bar("$it", st.ratingHistogram[it], MaterialTheme.colorScheme.primary) })
                if (st.topGenres.isNotEmpty()) ChartCard("Top genres", st.topGenres.map { Bar(it.first, it.second, MaterialTheme.colorScheme.tertiary) })
                HistoryCard(history, onClear = vm::clearHistory)
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<WatchHistoryEntry>, onClear: () -> Unit) {
    val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    AniCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recent Watch Activity", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear", fontSize = 12.sp) }
            }
            if (history.isEmpty()) {
                Text("Episodes you mark watched appear here with their timestamps.", fontSize = 13.sp, color = MaterialTheme.colorScheme.dim)
            } else {
                history.take(15).forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Poster(h.imageUrl, Modifier.width(34.dp).height(48.dp), RoundedCornerShape(6.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "Episode ${h.episode}" + (if (h.rewatchCount > 0) " · Rewatch #${h.rewatchCount}" else ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(fmt.format(Date(h.watchedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
                    }
                }
            }
        }
    }
}

private data class Bar(val label: String, val value: Int, val color: Color)

/** Horizontal bars that always print their number, so the chart never relies on colour or sight alone. */
@Composable
private fun ChartCard(title: String, bars: List<Bar>) {
    val max = (bars.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1)
    AniCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = title + ": " + bars.joinToString(", ") { "${it.label} ${it.value}" }
    }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            bars.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.label, Modifier.width(92.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.dim, maxLines = 1)
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.outline)) {
                        Box(Modifier.fillMaxWidth(b.value.toFloat() / max).fillMaxHeight().background(b.color))
                    }
                    Text("${b.value}", Modifier.width(36.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}
