package com.anivault.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.BrowseSection
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.provider.ProviderResult
import com.anivault.ui.common.*
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
    val banner: NormalizedAnime? = null,
    val bannerLoading: Boolean = true,
    val showPlusOne: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val providers: ProviderRepository,
    private val prefs: AppPrefs
) : ViewModel() {
    private val banner = MutableStateFlow<NormalizedAnime?>(null)
    private val bannerLoading = MutableStateFlow(true)

    val state: StateFlow<HomeState> = combine(
        library.observeTotal(), library.observeCount(WatchStatus.PLAN_TO_WATCH),
        library.observeContinueWatching(12), banner, bannerLoading
    ) { total, planned, cont, b, loading -> HomeState(total, planned, cont, b, loading, prefs.showPlusOne) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init { loadBanner() }

    /** The banner is whatever the providers say is trending – never hard-coded art. */
    fun loadBanner() {
        viewModelScope.launch {
            bannerLoading.value = true
            val r = providers.browse(BrowseSection.TRENDING)
            banner.value = (r as? ProviderResult.Success)?.items?.firstOrNull { it.bannerUrl.isNotBlank() || it.coverUrl.isNotBlank() }
            bannerLoading.value = false
        }
    }

    fun plusOne(id: String) { viewModelScope.launch { library.incrementProgress(id) } }
}

@Composable
fun HomeScreen(
    onOpenLibrary: (WatchStatus?) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenCatalog: (NormalizedAnime) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("AniVault", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
        item { Banner(s.banner, s.bannerLoading, onClick = { s.banner?.let(onOpenCatalog) }, onRetry = viewModel::loadBanner) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickCard(Icons.Default.VideoLibrary, "My Library", "${s.total} saved", { onOpenLibrary(null) }, Modifier.weight(1f))
                    QuickCard(Icons.Default.Bookmark, "Watchlist", "${s.planned} planned", { onOpenLibrary(WatchStatus.PLAN_TO_WATCH) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickCard(Icons.Default.Explore, "Discover", "Find something new", onOpenDiscover, Modifier.weight(1f))
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
