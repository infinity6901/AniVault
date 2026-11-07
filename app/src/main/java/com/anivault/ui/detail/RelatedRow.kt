package com.anivault.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anivault.data.provider.AniListLookup
import com.anivault.data.provider.LookupMedia
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.provider.RelatedTitle
import com.anivault.ui.common.Poster
import com.anivault.ui.discover.CatalogDetailHost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class RelatedViewModel @Inject constructor(private val lookup: AniListLookup) : ViewModel() {
    private val _items = MutableStateFlow<List<RelatedTitle>>(emptyList())
    val items: StateFlow<List<RelatedTitle>> = _items.asStateFlow()
    private var loadedFor: String? = null

    fun load(ids: Map<String, String>) {
        val key = ids.entries.joinToString { "${it.key}=${it.value}" }
        if (key == loadedFor) return
        loadedFor = key
        val al = ids["anilist"]?.toIntOrNull(); val mal = ids["mal"]?.toIntOrNull()
        if (al == null && mal == null) { _items.value = emptyList(); return }
        viewModelScope.launch {
            // Related titles are a nicety: any failure just leaves the row out.
            _items.value = runCatching { if (al != null) lookup.relations(al) else lookup.relations(mal!!, byMal = true) }.getOrDefault(emptyList())
        }
    }
}

/** "Prequel / Sequel / Side story" cards for a title. Tapping one opens its page with an Add button. */
@Composable
fun RelatedRow(remoteIds: Map<String, String>, vm: RelatedViewModel = hiltViewModel()) {
    LaunchedEffect(remoteIds) { vm.load(remoteIds) }
    val items by vm.items.collectAsState()
    var open by remember { mutableStateOf<NormalizedAnime?>(null) }
    if (items.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Related", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.media.anilistId }) { r ->
                    Column(Modifier.width(96.dp).clickable { open = r.media.toNormalized() }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Poster(r.media.coverUrl, Modifier.width(96.dp).height(136.dp), RoundedCornerShape(10.dp))
                        Text(relationLabel(r.relation), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(r.media.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    open?.let { CatalogDetailHost(it, onDismiss = { open = null }) }
}
