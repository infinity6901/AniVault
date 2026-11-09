package com.anivault.data.library

import com.anivault.core.AppPrefs
import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.local.dao.WatchHistoryDao
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.WatchHistoryEntry
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
        val lastWatched = if (w > entry.watchedEpisodes) now else entry.lastWatchedAt
        return entry.copy(
            watchedEpisodes = w,
            status = status,
            startDate = start,
            endDate = end,
            lastWatchedAt = lastWatched,
            updatedAt = now,
            pendingSync = true
        )
    }

    /** Starts a fresh rewatch cycle: increments [AnimeEntry.rewatchCount], resets episode progress to 0, and sets status to WATCHING. */
    fun startRewatch(entry: AnimeEntry, now: Long): AnimeEntry = entry.copy(
        status = WatchStatus.WATCHING,
        watchedEpisodes = 0,
        rewatchCount = (entry.rewatchCount + 1).coerceAtLeast(1),
        endDate = null,
        updatedAt = now,
        pendingSync = true
    )
}

@Singleton
class LibraryRepository @Inject constructor(
    private val dao: AnimeDao,
    private val historyDao: WatchHistoryDao,
    private val prefs: AppPrefs,
    private val remoteOps: com.anivault.data.sync.RemoteOps
) {
    fun observeAll(): Flow<List<AnimeEntry>> = dao.observeAll()
    fun observe(id: String): Flow<AnimeEntry?> = dao.observeById(id)
    fun observeContinueWatching(limit: Int = 12): Flow<List<AnimeEntry>> = dao.observeRecentByStatus(WatchStatus.WATCHING, limit)
    fun observeCount(status: WatchStatus): Flow<Int> = dao.observeCountByStatus(status)
    fun observeTotal(): Flow<Int> = dao.observeTotalCount()
    fun observeRecentHistory(limit: Int = 25): Flow<List<WatchHistoryEntry>> = historyDao.observeRecent(limit)
    fun observeEntryHistory(id: String, limit: Int = 15): Flow<List<WatchHistoryEntry>> = historyDao.observeForEntry(id, limit)

    suspend fun get(id: String): AnimeEntry? = dao.getById(id)

    /** Adds a catalog result. Returns false (and changes nothing) when it is already in the library. */
    suspend fun add(anime: NormalizedAnime, status: WatchStatus? = null): Boolean {
        val id = anime.canonicalId
        if (dao.getById(id) != null) return false
        val chosen = status ?: WatchStatus.parse(prefs.defaultAddStatus)
        val now = System.currentTimeMillis()
        val entry = ProgressRules.withStatus(anime.toEntry(chosen, now), chosen, now)
        dao.upsert(entry)
        if (entry.watchedEpisodes > 0) recordHistory(entry, entry.watchedEpisodes, now)
        return true
    }

    suspend fun update(entry: AnimeEntry) {
        val now = System.currentTimeMillis()
        val prev = dao.getById(entry.id)
        val advanced = prev != null && (entry.watchedEpisodes > prev.watchedEpisodes || entry.rewatchCount > prev.rewatchCount)
        val stamped = entry.copy(
            lastWatchedAt = if (advanced) now else entry.lastWatchedAt,
            updatedAt = now,
            pendingSync = true
        )
        dao.upsert(stamped)
        if (advanced && stamped.watchedEpisodes > 0) {
            recordHistory(stamped, stamped.watchedEpisodes, now)
        }
    }

    /** Sets progress to an absolute value (so a double tap on "+1" can't skip an episode). */
    suspend fun setProgress(id: String, watched: Int) {
        val e = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val updated = ProgressRules.apply(e, watched, prefs.autoCompleteOnLast, now)
        dao.upsert(updated)
        if (updated.watchedEpisodes > e.watchedEpisodes) {
            recordHistory(updated, updated.watchedEpisodes, now)
        }
    }

    suspend fun incrementProgress(id: String) {
        val e = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val updated = ProgressRules.apply(e, e.watchedEpisodes + 1, prefs.autoCompleteOnLast, now)
        dao.upsert(updated)
        if (updated.watchedEpisodes > e.watchedEpisodes) {
            recordHistory(updated, updated.watchedEpisodes, now)
        }
    }

    suspend fun startRewatch(id: String) {
        val e = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val updated = ProgressRules.startRewatch(e, now)
        dao.upsert(updated)
    }

    suspend fun setStatus(ids: List<String>, status: WatchStatus) {
        if (ids.isEmpty()) return
        if (status == WatchStatus.COMPLETED) {
            val now = System.currentTimeMillis()
            ids.mapNotNull { dao.getById(it) }.forEach {
                val updated = ProgressRules.withStatus(it, status, now).copy(lastWatchedAt = now, updatedAt = now, pendingSync = true)
                dao.upsert(updated)
                if (updated.watchedEpisodes > it.watchedEpisodes) recordHistory(updated, updated.watchedEpisodes, now)
            }
        } else dao.updateStatusForIds(ids, status)
    }

    /** Deletes and returns the removed rows so the UI can offer Undo. */
    suspend fun delete(ids: List<String>): List<AnimeEntry> {
        val removed = ids.mapNotNull { dao.getById(it) }
        if (removed.isNotEmpty()) dao.deleteByIds(removed.map { it.id })
        // Deleting here removes the title from the connected accounts too (at the next sync, or right away when online).
        if (prefs.deleteOnAccounts) removed.forEach { remoteOps.enqueueAll(it.remoteIds, com.anivault.data.sync.OpType.DELETE) }
        return removed
    }

    suspend fun restore(entries: List<AnimeEntry>) {
        if (entries.isEmpty()) return
        dao.upsertAll(entries)
        entries.forEach { remoteOps.cancelAll(it.remoteIds, com.anivault.data.sync.OpType.DELETE) }
    }

    suspend fun clearHistory() = historyDao.deleteAll()

    private suspend fun recordHistory(e: AnimeEntry, episode: Int, now: Long) {
        historyDao.insert(
            WatchHistoryEntry(
                entryId = e.id,
                title = e.title,
                imageUrl = e.imageUrl,
                episode = episode,
                rewatchCount = e.rewatchCount,
                watchedAt = now
            )
        )
    }
}
