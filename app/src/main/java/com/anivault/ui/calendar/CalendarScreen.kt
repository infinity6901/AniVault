package com.anivault.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
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
import com.anivault.data.provider.AiringFormat
import com.anivault.data.provider.AiringItem
import com.anivault.data.provider.AiringRepository
import com.anivault.data.provider.NormalizedAnime
import com.anivault.ui.common.*
import com.anivault.ui.detail.toNormalized
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

data class CalendarUi(
    val loading: Boolean = true,
    val onlyLibrary: Boolean = true,
    val selectedDay: Int = AiringFormat.dayOfWeekIndex(System.currentTimeMillis()),
    val libraryItems: List<AiringItem> = emptyList(),
    val globalItems: List<AiringItem> = emptyList()
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repo: AiringRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(CalendarUi())
    val ui: StateFlow<CalendarUi> = _ui.asStateFlow()

    init { refresh(force = false) }

    fun setOnlyLibrary(only: Boolean) {
        _ui.value = _ui.value.copy(onlyLibrary = only)
        if (!only && _ui.value.globalItems.isEmpty()) refresh(force = false)
    }

    fun setDay(day: Int) { _ui.value = _ui.value.copy(selectedDay = day.coerceIn(0, 6)) }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            runCatching { repo.refresh(if (force) 0L else 2 * 3_600_000L) }
            val lib = repo.items()
            val global = runCatching { repo.globalWeek(force = force) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(loading = false, libraryItems = lib, globalItems = global)
        }
    }
}

@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenCatalog: (NormalizedAnime) -> Unit,
    vm: CalendarViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val todayIdx = remember { AiringFormat.dayOfWeekIndex(now) }
    val sourceItems = if (ui.onlyLibrary) ui.libraryItems else ui.globalItems.ifEmpty { ui.libraryItems }
    val dayItems = remember(sourceItems, ui.selectedDay) {
        sourceItems.filter { AiringFormat.dayOfWeekIndex(it.airingAt) == ui.selectedDay }.sortedBy { it.airingAt }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Airing Calendar", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.refresh(force = true) }, enabled = !ui.loading) {
                        Icon(Icons.Default.Refresh, "Refresh schedule")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = ui.onlyLibrary,
                    onClick = { vm.setOnlyLibrary(true) },
                    label = { Text("My Library (${ui.libraryItems.size})") }
                )
                FilterChip(
                    selected = !ui.onlyLibrary,
                    onClick = { vm.setOnlyLibrary(false) },
                    label = { Text("All Seasonal (${ui.globalItems.size})") }
                )
            }
            ScrollableTabRow(
                selectedTabIndex = ui.selectedDay,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DAYS.forEachIndexed { idx, label ->
                    val count = sourceItems.count { AiringFormat.dayOfWeekIndex(it.airingAt) == idx }
                    Tab(
                        selected = ui.selectedDay == idx,
                        onClick = { vm.setDay(idx) },
                        text = {
                            Text(
                                text = (if (idx == todayIdx) "Today · $label" else label) + (if (count > 0) " ($count)" else ""),
                                fontWeight = if (ui.selectedDay == idx) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when {
                ui.loading && sourceItems.isEmpty() -> LoadingBox()
                dayItems.isEmpty() -> EmptyState(
                    title = "Nothing scheduled on ${DAYS[ui.selectedDay]}",
                    message = if (ui.onlyLibrary) "Switch to “All Seasonal” above to see every anime airing this day, or add airing shows to your library."
                    else "No airing slots reported for this day.",
                    icon = Icons.Default.CalendarMonth,
                    action = if (ui.onlyLibrary) "Show all seasonal anime" else null,
                    onAction = if (ui.onlyLibrary) ({ vm.setOnlyLibrary(false) }) else null
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(dayItems, key = { "${it.libraryId}:${it.title}:${it.episode}:${it.airingAt}" }) { item ->
                        CalendarSlotRow(
                            item = item,
                            now = now,
                            onClick = {
                                if (item.libraryId.isNotBlank()) onOpenEntry(item.libraryId)
                                else item.media?.let { onOpenCatalog(it.toNormalized()) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSlotRow(item: AiringItem, now: Long, onClick: () -> Unit) {
    val timeStr = remember(item.airingAt) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.airingAt))
    }
    val aired = item.airingAt <= now
    val behind = if (item.libraryId.isNotBlank()) {
        val target = if (aired) item.episode else item.episode - 1
        (target - item.watched).coerceAtLeast(0)
    } else 0

    AniCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(item.coverUrl, Modifier.width(52.dp).height(74.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Episode ${item.episode} · $timeStr", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.libraryId.isNotBlank()) {
                        Tag("In library", MaterialTheme.colorScheme.primary)
                    }
                    if (behind > 0) {
                        Tag("$behind behind", MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = AiringFormat.relative(item.airingAt, now),
                fontSize = 12.sp,
                color = if (aired) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
