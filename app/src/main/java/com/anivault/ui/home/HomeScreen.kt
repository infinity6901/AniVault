package com.anivault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.anivault.core.AppPrefs
import com.anivault.data.library.LibraryRepository
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.WatchHistoryEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.BrowseSection
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.provider.ProviderResult
import com.anivault.ui.common.*
import com.anivault.ui.library.RandomPickerSheet
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val total: Int = 0,
    val planned: Int = 0,
    val continueWatching: List<AnimeEntry> = emptyList(),
    val allEntries: List<AnimeEntry> = emptyList(),
    val recentHistory: List<WatchHistoryEntry> = emptyList(),
    val banners: List<NormalizedAnime> = emptyList(),
    val bannerLoading: Boolean = true,
    val showPlusOne: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val providers: ProviderRepository,
    private val prefs: AppPrefs,
    private val backfill: com.anivault.data.library.DurationBackfill,
    private val airingRepo: com.anivault.data.provider.AiringRepository
) : ViewModel() {
    private val _airing = MutableStateFlow<List<com.anivault.data.provider.AiringItem>>(emptyList())
    val airing: StateFlow<List<com.anivault.data.provider.AiringItem>> = _airing.asStateFlow()

    /** Shows the cached schedule at once, then refreshes it (at most every few hours) and shows that. */
    private fun loadAiring() {
        viewModelScope.launch {
            _airing.value = airingRepo.items()
            if (runCatching { airingRepo.refresh() }.getOrDefault(false)) _airing.value = airingRepo.items()
        }
    }

    private val banner = MutableStateFlow<List<NormalizedAnime>>(emptyList())
    private val bannerLoading = MutableStateFlow(true)

    val state: StateFlow<HomeState> = combine(
        library.observeTotal(),
        library.observeCount(WatchStatus.PLAN_TO_WATCH),
        library.observeContinueWatching(12),
        library.observeAll(),
        library.observeRecentHistory(8)
    ) { total, planned, cont, all, hist ->
        HomeState(total = total, planned = planned, continueWatching = cont, allEntries = all, recentHistory = hist, showPlusOne = prefs.showPlusOne)
    }.combine(banner) { st, b -> st.copy(banners = b) }
        .combine(bannerLoading) { st, loading -> st.copy(bannerLoading = loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init { loadBanner(); backfill.run(); loadAiring() }

    /** The banner is whatever the providers say is trending – never hard-coded art. */
    fun loadBanner() {
        viewModelScope.launch {
            bannerLoading.value = true
            val r = providers.browse(BrowseSection.TRENDING)
            banner.value = (r as? ProviderResult.Success)?.items.orEmpty().filter { it.bannerUrl.isNotBlank() || it.coverUrl.isNotBlank() }.take(10)
            bannerLoading.value = false
        }
    }

    fun plusOne(id: String) { viewModelScope.launch { library.incrementProgress(id) } }
    fun startWatching(id: String) { viewModelScope.launch { library.setStatus(listOf(id), WatchStatus.WATCHING) } }
}

@Composable
fun HomeScreen(
    onOpenLibrary: (WatchStatus?) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenCalendar: () -> Unit = {},
    onOpenEntry: (String) -> Unit,
    onOpenCatalog: (NormalizedAnime) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val airing by viewModel.airing.collectAsStateWithLifecycle()
    var showWheel by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("AniVault", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                if (s.allEntries.isNotEmpty()) {
                    IconButton(onClick = { showWheel = true }) {
                        Icon(Icons.Default.Casino, "Surprise me (Spin the Wheel)", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onOpenCalendar) {
                    Icon(Icons.Default.CalendarMonth, "Airing Calendar", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { BannerPager(s.banners, s.bannerLoading, onOpen = onOpenCatalog, onRetry = viewModel::loadBanner) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickCard(Icons.Default.VideoLibrary, "My Library", "${s.total} saved", { onOpenLibrary(null) }, Modifier.weight(1f))
                    QuickCard(Icons.Default.Bookmark, "Watchlist", "${s.planned} planned", { onOpenLibrary(WatchStatus.PLAN_TO_WATCH) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickCard(Icons.Default.CalendarMonth, "Calendar", "Weekly schedule", onOpenCalendar, Modifier.weight(1f))
                    QuickCard(Icons.Default.BarChart, "Statistics", "Your progress", onOpenStats, Modifier.weight(1f))
                }
            }
        }
        item { SectionHeader("Continue Watching", action = "See All", onAction = { onOpenLibrary(WatchStatus.WATCHING) }) }
        if (s.continueWatching.isEmpty()) {
            item {
                EmptyState(
                    "Nothing in progress", "Titles you mark as Watching show up here.",
                    action = "Browse Discover", onAction = onOpenDiscover
                )
            }
        } else item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(s.continueWatching, key = { it.id }) { e ->
                    ContinueCard(e, s.showPlusOne, onClick = { onOpenEntry(e.id) }, onPlusOne = { viewModel.plusOne(e.id) })
                }
            }
        }
        val now = System.currentTimeMillis()
        val week = airing.filter { it.airingAt in (now - 6 * 3_600_000L)..(now + 7 * 86_400_000L) }
        item { SectionHeader("This week", action = "Full Calendar", onAction = onOpenCalendar) }
        if (week.isEmpty()) {
            item {
                AniCard(Modifier.fillMaxWidth(), onClick = onOpenCalendar) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Open Weekly Airing Calendar", fontWeight = FontWeight.SemiBold)
                            Text("See what airs each day across your library or the entire season.", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                        }
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    week.take(8).forEach { a -> AiringRow(a, now, onClick = { onOpenEntry(a.libraryId) }) }
                }
            }
        }
    }

    if (showWheel) {
        val genres = remember(s.allEntries) { s.allEntries.flatMap { it.genres }.filter { it.isNotBlank() }.distinct().sorted() }
        RandomPickerSheet(
            all = s.allEntries,
            genres = genres,
            onOpen = { showWheel = false; onOpenEntry(it.id) },
            onStartWatching = { showWheel = false; viewModel.startWatching(it.id); onOpenEntry(it.id) },
            onDismiss = { showWheel = false }
        )
    }
}

/** Swipe sideways through the trending titles; dots show the position. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BannerPager(items: List<NormalizedAnime>, loading: Boolean, onOpen: (NormalizedAnime) -> Unit, onRetry: () -> Unit) {
    if (items.isEmpty()) {
        Banner(null, loading, onClick = {}, onRetry = onRetry)
        return
    }
    val pager = rememberPagerState(pageCount = { items.size })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp) { i ->
            Banner(items[i], false, onClick = { onOpen(items[i]) }, onRetry = onRetry)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(items.size) { i ->
                val on = pager.currentPage == i
                Box(
                    Modifier.padding(3.dp).size(if (on) 8.dp else 6.dp).clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}

@Composable
private fun Banner(item: NormalizedAnime?, loading: Boolean, onClick: () -> Unit, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = item != null, onClick = onClick)
    ) {
        if (item != null) {
            Poster(item.bannerUrl.ifBlank { item.coverUrl }, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B0D16)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text("Trending on ${item.providerName}", color = AniVaultColors.Secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Featured titles are unavailable", color = MaterialTheme.colorScheme.dim)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun QuickCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    AniCard(modifier.semantics { contentDescription = "$title, $subtitle" }, onClick = onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
        }
    }
}

@Composable
private fun ContinueCard(e: AnimeEntry, showPlusOne: Boolean, onClick: () -> Unit, onPlusOne: () -> Unit) {
    AniCard(Modifier.width(150.dp), onClick = onClick) {
        Box {
            Poster(e.imageUrl, Modifier.fillMaxWidth().height(190.dp), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            if (showPlusOne && (e.totalEpisodes == 0 || e.watchedEpisodes < e.totalEpisodes)) {
                FilledIconButton(
                    onClick = onPlusOne, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Icon(Icons.Default.Add, "Mark next episode of ${e.title} watched") }
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(progressLabel(e), fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
            if (e.totalEpisodes > 0) {
                LinearProgressIndicator(
                    progress = { (e.watchedEpisodes.toFloat() / e.totalEpisodes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = AniVaultColors.Progress, trackColor = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** "Ep 3 / 12", or "Ep 3" when the total is unknown (never a misleading percentage). */
fun progressLabel(e: AnimeEntry): String =
    if (e.totalEpisodes > 0) "Ep ${e.watchedEpisodes} / ${e.totalEpisodes}" else "Ep ${e.watchedEpisodes} · total unknown"

/** One upcoming episode: cover, title, which episode and when it airs. */
@Composable
private fun AiringRow(a: com.anivault.data.provider.AiringItem, now: Long, onClick: () -> Unit) {
    val day = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(a.airingAt))
    val behind = a.episode - 1 - a.watched
    AniCard(Modifier.fillMaxWidth().semantics { contentDescription = "${a.title}, episode ${a.episode}, $day" }, onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(a.coverUrl, Modifier.width(44.dp).height(62.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Episode ${a.episode} · $day", fontSize = 12.sp, color = MaterialTheme.colorScheme.dim)
                if (behind > 0) Text("$behind episode${if (behind == 1) "" else "s"} behind", fontSize = 11.sp, color = MaterialTheme.colorScheme.dim)
            }
            Text(com.anivault.data.provider.AiringFormat.relative(a.airingAt, now), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}
