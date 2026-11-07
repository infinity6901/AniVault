package com.anivault.data.library

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus

enum class LibrarySort(val label: String) {
    UPDATED("Recently updated"),
    ADDED("Recently added"),
    TITLE("Title A–Z"),
    RATING("My rating"),
    PROGRESS("Progress"),
    YEAR("Release year");

    companion object { fun parse(v: String?) = values().firstOrNull { it.name == v } ?: UPDATED }
}

data class LibraryQuery(
    val text: String = "",
    val status: WatchStatus? = null,
    val format: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val tag: String? = null,
    /** Minimum PERSONAL rating (0–10). */
    val minRating: Float? = null,
    val source: String? = null,
    val sort: LibrarySort = LibrarySort.UPDATED
) {
    val activeFilterCount: Int
        get() = listOfNotNull(format, year, genre, tag, minRating, source).size
}

/** Pure filtering/sorting for the Library screen (unit-tested). */
object LibraryFilter {
    fun apply(all: List<AnimeEntry>, q: LibraryQuery): List<AnimeEntry> {
        val needle = q.text.trim().lowercase()
        val filtered = all.filter { e ->
            (q.status == null || e.status == q.status) &&
                (q.format.isNullOrBlank() || e.format.equals(q.format, true)) &&
                (q.year == null || e.year == q.year) &&
                (q.genre.isNullOrBlank() || e.genres.any { it.equals(q.genre, true) }) &&
                (q.tag.isNullOrBlank() || e.tags.any { it.equals(q.tag, true) }) &&
                (q.minRating == null || e.rating >= q.minRating) &&
                (q.source.isNullOrBlank() || e.sourceName.equals(q.source, true)) &&
                (needle.isEmpty() || e.title.lowercase().contains(needle) || e.altTitles.any { it.lowercase().contains(needle) } || e.tags.any { it.lowercase().contains(needle) })
        }
        return when (q.sort) {
            LibrarySort.UPDATED -> filtered.sortedByDescending { it.updatedAt }
            LibrarySort.ADDED -> filtered.sortedByDescending { it.addedAt }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySort.PROGRESS -> filtered.sortedByDescending { progressFraction(it) }
            LibrarySort.YEAR -> filtered.sortedByDescending { it.year ?: 0 }
        }
    }

    /** 0..1; entries with an unknown total sort by raw episode count scaled down so they never beat a finished show. */
    fun progressFraction(e: AnimeEntry): Float =
        if (e.totalEpisodes > 0) (e.watchedEpisodes.toFloat() / e.totalEpisodes).coerceIn(0f, 1f)
        else (e.watchedEpisodes / 1000f).coerceIn(0f, 0.99f)

    data class Facets(val formats: List<String>, val years: List<Int>, val genres: List<String>, val sources: List<String>, val tags: List<String> = emptyList())

    fun facets(all: List<AnimeEntry>) = Facets(
        formats = all.map { it.format }.filter { it.isNotBlank() }.distinct().sorted(),
        years = all.mapNotNull { it.year }.distinct().sortedDescending(),
        genres = all.flatMap { it.genres }.filter { it.isNotBlank() }.distinct().sorted(),
        sources = all.map { it.sourceName }.filter { it.isNotBlank() }.distinct().sorted(),
        tags = all.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    )
}
