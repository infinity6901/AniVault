@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.anivault.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.anivault.data.library.LibraryRepository
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.AniListLookup
import com.anivault.data.provider.LookupMedia
import com.anivault.data.provider.MediaExtras
import com.anivault.data.provider.NormalizedAnime
import com.anivault.ui.common.AniCard
import com.anivault.ui.common.Poster
import com.anivault.ui.common.Tag
import com.anivault.ui.discover.CatalogDetailHost
import com.anivault.ui.theme.AniVaultColors
import com.anivault.ui.theme.dim
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

fun LookupMedia.toNormalized() = NormalizedAnime(
    provider = "anilist", providerName = "AniList", providerId = anilistId.toString(), title = title, coverUrl = coverUrl,
    synopsis = synopsis, year = year, season = season, format = format, episodes = episodes, durationMinutes = durationMinutes,
    status = airingStatus, genres = genres, adult = adult,
    remoteIds = buildMap { put("anilist", anilistId.toString()); malId?.let { put("mal", it.toString()) } }
)

fun relationLabel(r: String) = when (r) {
    "SEQUEL" -> "Sequel"; "PREQUEL" -> "Prequel"; "PARENT" -> "Parent story"; "SIDE_STORY" -> "Side story"
    "SPIN_OFF" -> "Spin-off"; "ALTERNATIVE" -> "Alternative"; "SUMMARY" -> "Summary"; "COMPILATION" -> "Compilation"
    else -> r.lowercase().replaceFirstChar { it.uppercase() }
}

@HiltViewModel
class RelatedViewModel @Inject constructor(
    private val lookup: AniListLookup,
    library: LibraryRepository
) : ViewModel() {
    private val _extras = MutableStateFlow(MediaExtras())
    val extras: StateFlow<MediaExtras> = _extras.asStateFlow()
    val libraryEntries: StateFlow<List<AnimeEntry>> = library.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var loadedFor: String? = null

    fun load(ids: Map<String, String>) {
        val key = ids.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
        if (key == loadedFor) return
        loadedFor = key
        val al = ids["anilist"]?.toIntOrNull(); val mal = ids["mal"]?.toIntOrNull()
        if (al == null && mal == null) { _extras.value = MediaExtras(); return }
        viewModelScope.launch {
            _extras.value = runCatching {
                if (al != null) lookup.extras(al) else lookup.extras(mal!!, byMal = true)
            }.getOrDefault(MediaExtras())
        }
    }
}

/**
 * Studios, Characters & Voice Actors, Franchise Watch-Order Timeline, Related cards, and Streaming links.
 */
@Composable
fun RelatedRow(remoteIds: Map<String, String>, vm: RelatedViewModel = hiltViewModel()) {
    LaunchedEffect(remoteIds) { vm.load(remoteIds) }
    val extras by vm.extras.collectAsStateWithLifecycle()
    val library by vm.libraryEntries.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<NormalizedAnime?>(null) }

    val byAnilist = remember(library) {
        val map = HashMap<Int, AnimeEntry>()
        library.forEach { e -> RemoteIds.parse(e.remoteIds)["anilist"]?.toIntOrNull()?.let { map[it] = e } }
        map
    }

    if (extras.studios.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Studio", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                extras.studios.forEach { st -> Tag(st, MaterialTheme.colorScheme.primary) }
            }
        }
    }

    if (extras.characters.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Characters & Voice Actors", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(extras.characters, key = { it.id }) { c ->
                    AniCard(Modifier.width(112.dp)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Poster(c.imageUrl, Modifier.fillMaxWidth().height(110.dp), RoundedCornerShape(8.dp))
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

    if (extras.relations.isNotEmpty()) {
        val timelineOrder = remember(extras.relations) {
            extras.relations.sortedWith(
                compareBy<com.anivault.data.provider.RelatedTitle> {
                    when (it.relation) {
                        "PREQUEL" -> 0
                        "PARENT" -> 1
                        "SEQUEL" -> 2
                        "SIDE_STORY" -> 3
                        else -> 4
                    }
                }.thenBy { it.media.year ?: 9999 }
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Franchise Watch Order & Related", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(timelineOrder, key = { it.media.anilistId }) { r ->
                    val owned = byAnilist[r.media.anilistId]
                    Column(
                        Modifier.width(108.dp).clickable { open = r.media.toNormalized() },
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box {
                            Poster(r.media.coverUrl, Modifier.width(108.dp).height(148.dp), RoundedCornerShape(10.dp))
                            if (owned != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                ) {
                                    Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (owned.status == WatchStatus.COMPLETED) Icons.Default.CheckCircle else Icons.Default.PlayCircle,
                                            contentDescription = null,
                                            tint = if (owned.status == WatchStatus.COMPLETED) AniVaultColors.Success else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = if (owned.status == WatchStatus.COMPLETED) "Done" else "${owned.watchedEpisodes} ep",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.dim,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp)
                                )
                            }
                        }
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

    if (extras.links.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Where to Watch & Links", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                extras.links.forEach { link ->
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

    open?.let { CatalogDetailHost(it, onDismiss = { open = null }) }
}
