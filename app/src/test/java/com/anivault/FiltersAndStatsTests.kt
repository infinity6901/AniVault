package com.anivault

import com.anivault.data.library.StatsCalculator
import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.entity.RemoteIds
import com.anivault.data.local.model.WatchStatus
import com.anivault.data.provider.NormalizedAnime
import com.anivault.data.provider.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersAndStatsTests {
    private fun a(id: String, genres: List<String> = emptyList(), status: String = "", country: String = "") =
        NormalizedAnime("p", "P", id, "T$id", genres = genres, status = status, country = country)

    @Test fun genre_filter_requires_all() {
        val items = listOf(a("1", listOf("Action", "Comedy")), a("2", listOf("Action")))
        assertEquals(listOf("1"), SearchFilters(genres = setOf("Action", "Comedy")).apply(items).map { it.providerId })
    }

    @Test fun airing_status_normalises_provider_wording() {
        assertEquals("Finished", SearchFilters.normalizeAiring("Finished Airing"))
        assertEquals("Releasing", SearchFilters.normalizeAiring("Currently Airing"))
        assertEquals("Not yet released", SearchFilters.normalizeAiring("not_yet_aired"))
    }

    @Test fun country_filter_keeps_unknown() {
        val items = listOf(a("1", country = "JP"), a("2", country = "KR"), a("3"))
        assertEquals(listOf("1", "3"), SearchFilters(country = "JP").apply(items).map { it.providerId })
    }

    @Test fun watch_time_estimates_missing_durations_and_remaining() {
        val known = AnimeEntry(id = "k", watchedEpisodes = 4, durationMinutes = 20, totalEpisodes = 12, status = WatchStatus.WATCHING)
        val unknown = AnimeEntry(id = "u", watchedEpisodes = 2, durationMinutes = 0, format = "Movie", totalEpisodes = 3, status = WatchStatus.PLAN_TO_WATCH)
        val s = StatsCalculator.compute(listOf(known, unknown))
        assertEquals(80L, s.watchMinutes)
        assertEquals(200L, s.estimatedMinutes)
        assertEquals(8L * 20 + 1L * 100, s.remainingMinutes)
    }

    @Test fun remote_ids_round_trip_without_reflection() {
        val m = mapOf("anilist" to "1", "mal" to "2")
        assertEquals(m, RemoteIds.parse(RemoteIds.encode(m)))
        assertTrue(RemoteIds.parse("not json").isEmpty())
    }

    @Test fun completed_status_fills_progress() {
        val e = AnimeEntry(id = "c", watchedEpisodes = 0, totalEpisodes = 37, status = WatchStatus.PLAN_TO_WATCH)
        val done = com.anivault.data.library.ProgressRules.withStatus(e, WatchStatus.COMPLETED, 1000L)
        assertEquals(37, done.watchedEpisodes)
        assertEquals(1000L, done.endDate)
        val unknown = com.anivault.data.library.ProgressRules.withStatus(e.copy(totalEpisodes = 0), WatchStatus.COMPLETED, 1L)
        assertEquals(0, unknown.watchedEpisodes)
        assertEquals(5, com.anivault.data.library.ProgressRules.withStatus(done.copy(watchedEpisodes = 5), WatchStatus.DROPPED, 2L).watchedEpisodes)
    }

    @Test fun export_counts_completed_as_fully_watched() {
        val rows = com.anivault.ui.export.ExportData.anime(listOf(AnimeEntry(id = "d", watchedEpisodes = 0, totalEpisodes = 37, durationMinutes = 23, status = WatchStatus.COMPLETED)))
        assertEquals(37, rows[0].watched)
        assertEquals(37L * 23, rows[0].minutes)
        assertEquals("14h 11m", com.anivault.ui.export.ExportData.duration(rows[0].minutes))
    }
}
