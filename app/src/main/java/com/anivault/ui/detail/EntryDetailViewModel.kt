package com.anivault.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.scraper.AnimeRepository
import com.anivault.data.remote.scraper.HentaiFallbackRepository
import com.anivault.ui.theme.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EntryEditState(
    val id: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val status: WatchStatus = WatchStatus.PLAN_TO_WATCH,
    val watchedEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    val rating: Float = 0f,
    val notes: String = "",
    val durationMinutes: Int = 0,
    val mode: AppMode = AppMode.ANIME,
    // Rich metadata loaded from DB
    val synopsis: String = "",
    val genres: List<String> = emptyList(),   // anime
    val tags: List<String> = emptyList(),      // hvault
    val studio: String? = null,                // hvault
    val year: Int? = null,                     // anime
    val season: String = "",                   // anime
    val isCensored: Boolean = false,           // hvault
    val isLoaded: Boolean = false,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false
)

@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    private val animeRepository:  AnimeRepository,
    private val hentaiRepository: HentaiFallbackRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EntryEditState())
    val state: StateFlow<EntryEditState> = _state.asStateFlow()

    // ── Load from DB by ID (preferred – fetches full metadata) ─────────────

    fun loadById(id: String, mode: AppMode) {
        // Clear the previous sheet's data immediately so a missing/new ID can
        // never display or accidentally save the previous entry's values.
        _state.value = EntryEditState(id = id, mode = mode)
        viewModelScope.launch {
            if (mode == AppMode.ANIME) {
                val entry = animeRepository.getById(id)
                if (entry != null) loadFromAnime(entry)
                else _state.update { current -> current.copy(isLoaded = true) }
            } else {
                val entry = hentaiRepository.getById(id)
                if (entry != null) loadFromHVault(entry)
                else _state.update { current -> current.copy(isLoaded = true) }
            }
        }
    }

    /** Populate from a full AnimeEntry (also used by loadById internally) */
    fun loadFromAnime(entry: AnimeEntry) {
        _state.update {
            EntryEditState(
                id              = entry.id,
                title           = entry.title,
                imageUrl        = entry.imageUrl,
                status          = entry.status,
                watchedEpisodes = entry.watchedEpisodes,
                totalEpisodes   = entry.totalEpisodes,
                rating          = entry.rating,
                notes           = entry.notes,
                durationMinutes = entry.durationMinutes,
                mode            = AppMode.ANIME,
                synopsis        = entry.synopsis,
                genres          = entry.genres,
                year            = entry.year,
                season          = entry.season,
                isLoaded        = true
            )
        }
    }

    /** Populate from a full HVaultEntry */
    fun loadFromHVault(entry: HVaultEntry) {
        _state.update {
            EntryEditState(
                id              = entry.id,
                title           = entry.title,
                imageUrl        = entry.imageUrl,
                status          = entry.status,
                watchedEpisodes = entry.watchedEpisodes,
                totalEpisodes   = entry.totalEpisodes,
                rating          = entry.rating,
                notes           = entry.notes,
                durationMinutes = entry.durationMinutes,
                mode            = AppMode.H_VAULT,
                tags            = entry.tags,
                studio          = entry.studio,
                isCensored      = entry.isCensored,
                isLoaded        = true
            )
        }
    }

    // ── Editing ────────────────────────────────────────────────────────────

    fun setStatus(s: WatchStatus) = _state.update { it.copy(status = s) }
    fun setWatched(n: Int) = _state.update { current ->
        val watched = n.coerceAtLeast(0)
        current.copy(watchedEpisodes = if (current.totalEpisodes > 0) watched.coerceAtMost(current.totalEpisodes) else watched)
    }
    fun setTotal(n: Int) = _state.update { current ->
        val total = n.coerceAtLeast(0)
        current.copy(
            totalEpisodes = total,
            watchedEpisodes = if (total > 0) current.watchedEpisodes.coerceAtMost(total) else current.watchedEpisodes
        )
    }
    fun setRating(r: Float)       = _state.update { it.copy(rating = r.coerceIn(0f, 10f)) }
    fun setNotes(t: String)       = _state.update { it.copy(notes = t) }
    fun setDuration(m: Int)       = _state.update { it.copy(durationMinutes = m.coerceAtLeast(0)) }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            val saved = if (s.mode == AppMode.ANIME) {
                val existing = animeRepository.getById(s.id)
                if (existing == null) false else {
                    animeRepository.updateEntry(
                        existing.copy(
                            status = s.status,
                            watchedEpisodes = s.watchedEpisodes,
                            totalEpisodes = s.totalEpisodes,
                            rating = s.rating,
                            notes = s.notes,
                            durationMinutes = s.durationMinutes
                        )
                    )
                    true
                }
            } else {
                val existing = hentaiRepository.getById(s.id)
                if (existing == null) false else {
                    hentaiRepository.updateEntry(
                        existing.copy(
                            status = s.status,
                            watchedEpisodes = s.watchedEpisodes,
                            totalEpisodes = s.totalEpisodes,
                            rating = s.rating,
                            notes = s.notes,
                            durationMinutes = s.durationMinutes
                        )
                    )
                    true
                }
            }
            _state.update { it.copy(isSaved = saved) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            val s = _state.value
            if (s.mode == AppMode.ANIME) animeRepository.deleteById(s.id)
            else hentaiRepository.deleteById(s.id)
            _state.update { it.copy(isDeleted = true) }
        }
    }

    fun resetFlags() = _state.update { it.copy(isSaved = false, isDeleted = false) }
}
