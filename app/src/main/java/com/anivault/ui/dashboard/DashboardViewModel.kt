package com.anivault.ui.dashboard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.HVaultDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.HVaultEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.remote.scraper.AnimeRepository
import com.anivault.data.remote.scraper.HentaiFallbackRepository
import com.anivault.ui.export.DocxExportManager
import com.anivault.ui.export.JsonExportManager
import com.anivault.ui.export.PdfExportManager
import com.anivault.ui.theme.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Shared display model ───────────────────────────────────────────────────
data class DisplayEntry(
    val id: String,
    val title: String,
    val imageUrl: String,
    val watchedEpisodes: Int,
    val totalEpisodes: Int,
    val status: WatchStatus,
    val rating: Float
)

data class DashboardStats(
    val totalEntries: Int  = 0,
    val watchMinutes: Long = 0L,
    val avgRating: Float   = 0f
)

enum class SortOrder { DATE_DESC, DATE_ASC, TITLE_ASC, RATING_DESC }

data class DashboardUiState(
    val mode: AppMode                = AppMode.ANIME,
    val isHVaultUnlocked: Boolean    = false,
    val selectedFilter: WatchStatus? = null,
    val sortOrder: SortOrder         = SortOrder.DATE_DESC,
    val entries: List<DisplayEntry>  = emptyList(),
    val stats: DashboardStats        = DashboardStats(),
    val isLoading: Boolean           = true,
    // Multi-select
    val isMultiSelectMode: Boolean   = false,
    val selectedIds: Set<String>     = emptySet(),
    // Export feedback
    val exportMessage: String?       = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val animeDao:        AnimeDao,
    private val hVaultDao:       HVaultDao,
    private val animeRepository: AnimeRepository,
    private val hentaiRepository:HentaiFallbackRepository,
    private val pdfExportManager:PdfExportManager,
    private val docxExportManager:DocxExportManager,
    private val jsonExportManager:JsonExportManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pendingMode: AppMode? = null
    private var dataJob: Job? = null

    init { collectData() }

    // ── Mode switching ─────────────────────────────────────────────────────

    fun requestModeSwitch(target: AppMode): Boolean {
        if (target == _uiState.value.mode) return false
        return if (target == AppMode.H_VAULT && !_uiState.value.isHVaultUnlocked) {
            pendingMode = target
            true
        } else {
            applyMode(target)
            false
        }
    }

    fun onBiometricSuccess() {
        _uiState.update { it.copy(isHVaultUnlocked = true) }
        pendingMode?.let { applyMode(it) }
        pendingMode = null
    }

    fun onBiometricFailure() { pendingMode = null }

    fun lockHVault() {
        applyMode(AppMode.ANIME)
        _uiState.update { it.copy(isHVaultUnlocked = false) }
    }

    // ── Filtering & sorting ────────────────────────────────────────────────

    fun setFilter(status: WatchStatus?) {
        _uiState.update { it.copy(selectedFilter = status) }
        collectData()
    }

    fun setSortOrder(order: SortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
        collectData()
    }

    // ── Multi-select ───────────────────────────────────────────────────────

    fun enterMultiSelectMode(id: String) {
        _uiState.update { it.copy(isMultiSelectMode = true, selectedIds = setOf(id)) }
    }

    fun toggleSelect(id: String) {
        _uiState.update { s ->
            val newSet = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id
            s.copy(selectedIds = newSet, isMultiSelectMode = newSet.isNotEmpty())
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedIds = it.entries.map { e -> e.id }.toSet(), isMultiSelectMode = true) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(isMultiSelectMode = false, selectedIds = emptySet()) }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (_uiState.value.mode == AppMode.ANIME) animeDao.deleteByIds(ids)
            else hVaultDao.deleteByIds(ids)
            clearSelection()
        }
    }

    fun setStatusForSelected(status: WatchStatus) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (_uiState.value.mode == AppMode.ANIME) animeDao.updateStatusForIds(ids, status)
            else hVaultDao.updateStatusForIds(ids, status)
            clearSelection()
        }
    }

    // ── Entry edit ─────────────────────────────────────────────────────────

    fun updateEntry(entry: DisplayEntry) {
        // Delegated — EntryDetailViewModel handles the actual update.
        // This just triggers a re-collect in case the flow hasn't fired yet.
        collectData()
    }

    // ── Export ─────────────────────────────────────────────────────────────

    fun exportPdf() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (_uiState.value.mode == AppMode.ANIME) {
                    pdfExportManager.exportAnimeToPdf(animeRepository.getAll())
                } else {
                    pdfExportManager.exportHVaultToPdf(hentaiRepository.getAll())
                }
            }
            _uiState.update { it.copy(exportMessage = if (ok) "PDF saved to Downloads" else "PDF export failed") }
        }
    }

    fun exportDocx() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (_uiState.value.mode == AppMode.ANIME) {
                    docxExportManager.exportAnimeToDocs(animeRepository.getAll())
                } else {
                    docxExportManager.exportHVaultToDocs(hentaiRepository.getAll())
                }
            }
            _uiState.update { it.copy(exportMessage = if (ok) "Word file saved to Downloads" else "Export failed") }
        }
    }

    fun exportJson() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (_uiState.value.mode == AppMode.ANIME) {
                    jsonExportManager.exportAnimeJson(animeRepository.getAll())
                } else {
                    jsonExportManager.exportHVaultJson(hentaiRepository.getAll())
                }
            }
            _uiState.update { it.copy(exportMessage = if (ok) "JSON saved to Downloads" else "Export failed") }
        }
    }

    fun importJson(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                jsonExportManager.importFromUri(uri)
            }
            if (result != null) {
                withContext(Dispatchers.IO) {
                    animeRepository.upsertAll(result.anime)
                    hentaiRepository.upsertAll(result.hvault)
                }
                val count = result.anime.size + result.hvault.size
                _uiState.update { it.copy(exportMessage = "Imported $count entries") }
            } else {
                _uiState.update { it.copy(exportMessage = "Import failed – invalid file") }
            }
        }
    }

    fun consumeExportMessage() = _uiState.update { it.copy(exportMessage = null) }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun applyMode(mode: AppMode) {
        _uiState.update { it.copy(mode = mode, selectedFilter = null, isLoading = true, isMultiSelectMode = false, selectedIds = emptySet()) }
        collectData()
    }

    private fun collectData() {
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            val state = _uiState.value
            when (state.mode) {
                AppMode.ANIME   -> collectAnimeData(state.selectedFilter, state.sortOrder)
                AppMode.H_VAULT -> collectHVaultData(state.selectedFilter, state.sortOrder)
            }
        }
    }

    private suspend fun collectAnimeData(filter: WatchStatus?, sort: SortOrder) {
        val entriesFlow = if (filter == null) animeDao.observeAll() else animeDao.observeByStatus(filter)
        combine(
            entriesFlow,
            animeDao.observeTotalCount(),
            animeDao.observeTotalWatchMinutes(),
            animeDao.observeAverageRating()
        ) { entries, count, mins, avg ->
            val sorted = sorted(entries.map { it.toDisplay() }, sort)
            _uiState.update { it.copy(entries = sorted, stats = DashboardStats(count, mins, avg), isLoading = false) }
        }.collect()
    }

    private suspend fun collectHVaultData(filter: WatchStatus?, sort: SortOrder) {
        val entriesFlow = if (filter == null) hVaultDao.observeAll() else hVaultDao.observeByStatus(filter)
        combine(
            entriesFlow,
            hVaultDao.observeTotalCount(),
            hVaultDao.observeTotalWatchMinutes(),
            hVaultDao.observeAverageRating()
        ) { entries, count, mins, avg ->
            val sorted = sorted(entries.map { it.toDisplay() }, sort)
            _uiState.update { it.copy(entries = sorted, stats = DashboardStats(count, mins, avg), isLoading = false) }
        }.collect()
    }

    private fun sorted(list: List<DisplayEntry>, order: SortOrder): List<DisplayEntry> = when (order) {
        SortOrder.DATE_DESC  -> list  // DB already returns DESC by updatedAt
        SortOrder.DATE_ASC   -> list.reversed()
        SortOrder.TITLE_ASC  -> list.sortedBy { it.title.lowercase() }
        SortOrder.RATING_DESC -> list.sortedByDescending { it.rating }
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private fun AnimeEntry.toDisplay() =
        DisplayEntry(id, title, imageUrl, watchedEpisodes, totalEpisodes, status, rating)

    private fun HVaultEntry.toDisplay() =
        DisplayEntry(id, title, imageUrl, watchedEpisodes, totalEpisodes, status, rating)
}
