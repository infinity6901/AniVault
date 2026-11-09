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
    /** Minutes estimated (typical length for the format) for watched entries with no known duration. */
    val estimatedMinutes: Long = 0,
    /** Minutes still to watch for entries with a known episode count (Watching / Planned). */
    val remainingMinutes: Long = 0,
    /** Average of the user's own ratings over rated entries; null when nothing is rated. */
    val averageRating: Float?,
    /** index 1..10 → count (index 0 unused = 0). */
    val ratingHistogram: List<Int>,
    val topGenres: List<Pair<String, Int>>,
    /** Total number of rewatches across all entries. */
    val totalRewatches: Int = 0,
    /** Number of distinct titles rewatched at least once. */
    val rewatchedTitles: Int = 0
)

object StatsCalculator {
    /** Typical runtime when the database doesn't say: used only as a clearly-labelled estimate. */
    fun typicalMinutes(format: String): Int = when (format.trim().lowercase()) {
        "movie" -> 100
        "ova", "special", "ona" -> 30
        "music" -> 4
        else -> 24
    }

    fun effectiveMinutes(e: AnimeEntry): Int = if (e.durationMinutes > 0) e.durationMinutes else typicalMinutes(e.format)

    /** Total episodes watched including completed rewatch cycles. */
    fun totalWatchedWithRewatches(e: AnimeEntry): Int {
        val perRewatch = if (e.totalEpisodes > 0) e.totalEpisodes else e.watchedEpisodes
        return e.watchedEpisodes + (e.rewatchCount.coerceAtLeast(0) * perRewatch)
    }

    fun compute(all: List<AnimeEntry>): LibraryStats {
        val rated = all.filter { it.rating > 0f }
        val hist = MutableList(11) { 0 }
        rated.forEach { hist[Math.round(it.rating).coerceIn(1, 10)]++ }
        return LibraryStats(
            total = all.size,
            byStatus = WatchStatus.values().associateWith { s -> all.count { it.status == s } },
            episodesWatched = all.sumOf { totalWatchedWithRewatches(it) },
            watchMinutes = all.sumOf { totalWatchedWithRewatches(it).toLong() * it.durationMinutes },
            entriesMissingDuration = all.count { totalWatchedWithRewatches(it) > 0 && it.durationMinutes <= 0 },
            estimatedMinutes = all.filter { it.durationMinutes <= 0 }.sumOf { totalWatchedWithRewatches(it).toLong() * typicalMinutes(it.format) },
            remainingMinutes = all.filter { it.totalEpisodes > 0 && (it.status == WatchStatus.WATCHING || it.status == WatchStatus.PLAN_TO_WATCH) }
                .sumOf { (it.totalEpisodes - it.watchedEpisodes).coerceAtLeast(0).toLong() * effectiveMinutes(it) },
            averageRating = if (rated.isEmpty()) null else rated.map { it.rating }.average().toFloat(),
            ratingHistogram = hist,
            topGenres = all.flatMap { it.genres }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(6).map { it.key to it.value },
            totalRewatches = all.sumOf { it.rewatchCount.coerceAtLeast(0) },
            rewatchedTitles = all.count { it.rewatchCount > 0 }
        )
    }
}
