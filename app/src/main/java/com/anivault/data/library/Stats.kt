package com.anivault.data.library

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus

data class LibraryStats(
    val total: Int,
    val byStatus: Map<WatchStatus, Int>,
    val episodesWatched: Int,
    /** Only entries with a known episode duration contribute. */
    val watchMinutes: Long,
    val entriesMissingDuration: Int,
    /** Average of the user's own ratings over rated entries; null when nothing is rated. */
    val averageRating: Float?,
    /** index 1..10 → count (index 0 unused = 0). */
    val ratingHistogram: List<Int>,
    val topGenres: List<Pair<String, Int>>
)

object StatsCalculator {
    fun compute(all: List<AnimeEntry>): LibraryStats {
        val rated = all.filter { it.rating > 0f }
        val hist = MutableList(11) { 0 }
        rated.forEach { hist[Math.round(it.rating).coerceIn(1, 10)]++ }
        return LibraryStats(
            total = all.size,
            byStatus = WatchStatus.values().associateWith { s -> all.count { it.status == s } },
            episodesWatched = all.sumOf { it.watchedEpisodes },
            watchMinutes = all.sumOf { it.watchedEpisodes.toLong() * it.durationMinutes },
            entriesMissingDuration = all.count { it.watchedEpisodes > 0 && it.durationMinutes <= 0 },
            averageRating = if (rated.isEmpty()) null else rated.map { it.rating }.average().toFloat(),
            ratingHistogram = hist,
            topGenres = all.flatMap { it.genres }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(6).map { it.key to it.value }
        )
    }
}
