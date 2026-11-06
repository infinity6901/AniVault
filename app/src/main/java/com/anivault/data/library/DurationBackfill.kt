package com.anivault.data.library

import com.anivault.data.local.dao.AnimeDao
import com.anivault.data.provider.ProviderRepository
import com.anivault.data.provider.ProviderResult
import com.anivault.data.provider.TitleMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills in missing episode lengths (and episode totals) from the catalog databases. Runs in the
 * background at most once at a time, only touches entries that lack a duration, and sends nothing
 * but the title text of those entries to the enabled catalog sources.
 */
@Singleton
class DurationBackfill @Inject constructor(
    private val dao: AnimeDao,
    private val providers: ProviderRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    fun run(maxEntries: Int = 12) {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                // Earlier versions saved Completed titles with 0 episodes watched; make them consistent.
                val now = System.currentTimeMillis()
                dao.getAll().filter { it.status == com.anivault.data.local.model.WatchStatus.COMPLETED && it.totalEpisodes > 0 && it.watchedEpisodes < it.totalEpisodes }
                    .forEach { dao.upsert(it.copy(watchedEpisodes = it.totalEpisodes, endDate = it.endDate ?: now, updatedAt = now, pendingSync = true)) }
                val missing = dao.getAll().filter { it.durationMinutes <= 0 }.take(maxEntries)
                for (e in missing) {
                    val result = providers.search(e.title) as? ProviderResult.Success ?: continue
                    val wanted = TitleMatcher.normalize(e.title)
                    val match = result.items.firstOrNull {
                        it.durationMinutes > 0 && TitleMatcher.names(it.title, it.altTitles).contains(wanted) &&
                            (e.year == null || it.year == null || it.year == e.year)
                    } ?: continue
                    // Re-read so a concurrent edit by the user is not overwritten.
                    val fresh = dao.getById(e.id) ?: continue
                    dao.upsert(
                        fresh.copy(
                            durationMinutes = match.durationMinutes,
                            totalEpisodes = if (fresh.totalEpisodes <= 0 && match.episodes > 0) match.episodes else fresh.totalEpisodes,
                            updatedAt = System.currentTimeMillis(), pendingSync = fresh.pendingSync
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: statistics fall back to typical lengths.
            } finally {
                running.set(false)
            }
        }
    }
}
