package com.anivault.data.library

import com.anivault.core.AppPrefs
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.provider.toEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Pure progress rules (spec §8) so they can be unit-tested without a database. */
object ProgressRules {
    /**
     * Applies a status chosen by the user. Completed means "watched all of it", so a known episode
     * count is filled in and the finish date set; leaving Completed keeps the progress as it was.
     */
    fun withStatus(entry: AnimeEntry, status: WatchStatus, now: Long): AnimeEntry {
        if (status != WatchStatus.COMPLETED) return entry.copy(status = status)
        return entry.copy(
            status = status,
            watchedEpisodes = if (entry.totalEpisodes > 0) entry.totalEpisodes else entry.watchedEpisodes,
            startDate = entry.startDate ?: now,
            endDate = entry.endDate ?: now
        )
    }

    /** Result of moving an entry to [watched] episodes. Idempotent: the same input gives the same output. */
    fun apply(entry: AnimeEntry, watched: Int, autoComplete: Boolean, now: Long): AnimeEntry {
        val max = if (entry.totalEpisodes > 0) entry.totalEpisodes else Int.MAX_VALUE
        val w = watched.coerceIn(0, max)
        var status = entry.status
        var start = entry.startDate
        var end = entry.endDate
        val reachedEnd = entry.totalEpisodes > 0 && w >= entry.totalEpisodes
        when {
            autoComplete && reachedEnd -> {
                status = WatchStatus.COMPLETED
                if (start == null) start = now
                if (end == null) end = now
            }
            w > 0 && (status == WatchStatus.PLAN_TO_WATCH || status == WatchStatus.COMPLETED) -> {
                status = WatchStatus.WATCHING
                if (start == null) start = now
                end = null
            }
            w == 0 && status == WatchStatus.WATCHING -> {
                // Stepped back to zero: stays Watching, nothing else to change.
            }
        }
        return entry.copy(watchedEpisodes = w, status = status, startDate = start, endDate = end, updatedAt = now, pendingSync = true)
    }
}

@Singleton
class LibraryRepository @Inject constructor(
    private val dao: AnimeDao,
    private val prefs: AppPrefs
) {
    fun observeAll(): Flow<List<AnimeEntry>> = dao.observeAll()
    fun observe(id: String): Flow<AnimeEntry?> = dao.observeById(id)
    fun observeContinueWatching(limit: Int = 12): Flow<List<AnimeEntry>> = dao.observeRecentByStatus(WatchStatus.WATCHING, limit)
    fun observeCount(status: WatchStatus): Flow<Int> = dao.observeCountByStatus(status)
    fun observeTotal(): Flow<Int> = dao.observeTotalCount()

    suspend fun get(id: String): AnimeEntry? = dao.getById(id)

    /** Adds a catalog result. Returns false (and changes nothing) when it is already in the library. */
    suspend fun add(anime: NormalizedAnime, status: WatchStatus? = null): Boolean {
        val id = anime.canonicalId
        if (dao.getById(id) != null) return false
        val chosen = status ?: WatchStatus.parse(prefs.defaultAddStatus)
        dao.upsert(ProgressRules.withStatus(anime.toEntry(chosen), chosen, System.currentTimeMillis()))
        return true
    }

    suspend fun update(entry: AnimeEntry) =
        dao.upsert(entry.copy(updatedAt = System.currentTimeMillis(), pendingSync = true))

    /** Sets progress to an absolute value (so a double tap on "+1" can't skip an episode). */
    suspend fun setProgress(id: String, watched: Int) {
        val e = dao.getById(id) ?: return
        dao.upsert(ProgressRules.apply(e, watched, prefs.autoCompleteOnLast, System.currentTimeMillis()))
    }

    suspend fun incrementProgress(id: String) {
        val e = dao.getById(id) ?: return
        dao.upsert(ProgressRules.apply(e, e.watchedEpisodes + 1, prefs.autoCompleteOnLast, System.currentTimeMillis()))
    }

    suspend fun setStatus(ids: List<String>, status: WatchStatus) {
        if (ids.isEmpty()) return
        if (status == WatchStatus.COMPLETED) {
            val now = System.currentTimeMillis()
            ids.mapNotNull { dao.getById(it) }.forEach { dao.upsert(ProgressRules.withStatus(it, status, now).copy(updatedAt = now, pendingSync = true)) }
        } else dao.updateStatusForIds(ids, status)
    }

    /** Deletes and returns the removed rows so the UI can offer Undo. */
    suspend fun delete(ids: List<String>): List<AnimeEntry> {
        val removed = ids.mapNotNull { dao.getById(it) }
        if (removed.isNotEmpty()) dao.deleteByIds(removed.map { it.id })
        return removed
    }

    suspend fun restore(entries: List<AnimeEntry>) { if (entries.isNotEmpty()) dao.upsertAll(entries) }
}
