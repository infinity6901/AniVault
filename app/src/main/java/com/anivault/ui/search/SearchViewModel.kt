package com.anivault.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.model.ScrapedEntry
import com.anivault.data.remote.scraper.AnimeRepository
import com.anivault.data.remote.scraper.HentaiFallbackRepository
import com.anivault.ui.theme.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Result wrapper used by the UI ──────────────────────────────────────────

data class SearchResultItem(
    val id: String,
    val title: String,
    val coverUrl: String,
    val episodeCount: Int,
    /** Extra info line shown under the title */
    val subtitle: String
)

sealed interface SearchUiState {
    data object Idle    : SearchUiState
    data object Loading : SearchUiState
    data class  Success(
        val results: List<SearchResultItem>,
        /** Non-null when the fallback source served the results */
        val fallbackSource: String?
    ) : SearchUiState
    data class  Error(val message: String) : SearchUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val animeRepository:  AnimeRepository,
    private val hentaiRepository: HentaiFallbackRepository
) : ViewModel() {

    private val _query     = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState   = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Tracks whether the last "add" succeeded — drives the snackbar */
    private val _addedTitle = MutableStateFlow<String?>(null)
    val addedTitle: StateFlow<String?> = _addedTitle.asStateFlow()

    private var searchJob: Job? = null

    // Raw results kept in memory so we can look up the full object on "Add"
    private var lastAnimeResults:   List<AnimeEntry>  = emptyList()
    private var lastHentaiResults:  List<ScrapedEntry> = emptyList()

    // ── Query ──────────────────────────────────────────────────────────────

    fun onQueryChange(q: String) {
        _query.value = q
    }

    // ── Search ─────────────────────────────────────────────────────────────

    fun search(mode: AppMode) {
        val q = _query.value.trim()
        if (q.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            runCatching {
                when (mode) {
                    AppMode.ANIME   -> searchAnime(q)
                    AppMode.H_VAULT -> searchHentai(q)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _uiState.value = SearchUiState.Error(
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    private suspend fun searchAnime(q: String) {
        val entries = animeRepository.search(q)
        lastAnimeResults = entries
        _uiState.value = SearchUiState.Success(
            results = entries.map { it.toResultItem() },
            fallbackSource = null
        )
    }

    private suspend fun searchHentai(q: String) {
        val result = hentaiRepository.search(q)
        lastHentaiResults = result.entries
        _uiState.value = SearchUiState.Success(
            results        = result.entries.map { it.toResultItem() },
            fallbackSource = if (result.usedFallback) result.sourceName else null
        )
    }

    // ── Add to vault ───────────────────────────────────────────────────────

    fun addAnimeToVault(id: String) {
        val entry = lastAnimeResults.find { it.id == id } ?: return
        viewModelScope.launch {
            // A repeated add must never reset a user's status, progress, rating, or notes.
            if (animeRepository.getById(id) == null) {
                animeRepository.upsert(entry.copy(status = WatchStatus.PLAN_TO_WATCH))
                _addedTitle.value = entry.title
            }
        }
    }

    fun addHentaiToVault(id: String) {
        val entry = lastHentaiResults.find { it.id == id } ?: return
        viewModelScope.launch {
            hentaiRepository.saveToVault(entry)
            _addedTitle.value = entry.title
        }
    }

    fun onAddedTitleConsumed() {
        _addedTitle.value = null
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private fun AnimeEntry.toResultItem() = SearchResultItem(
        id           = id,
        title        = title,
        coverUrl     = imageUrl,
        episodeCount = totalEpisodes,
        subtitle     = listOfNotNull(
            season.ifBlank { null },
            year?.toString()
        ).joinToString(" · ").ifBlank { "$totalEpisodes eps" }
    )

    private fun ScrapedEntry.toResultItem() = SearchResultItem(
        id           = id,
        title        = title,
        coverUrl     = coverUrl,
        episodeCount = episodeCount,
        subtitle     = listOfNotNull(
            studio,
            if (episodeCount > 0) "$episodeCount eps" else null
        ).joinToString(" · ").ifBlank { "1 ep" }
    )
}
