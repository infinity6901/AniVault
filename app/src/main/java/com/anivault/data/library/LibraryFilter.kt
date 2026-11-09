package com.anivault.data.library

import com.anivault.data.local.entity.AnimeEntry
import com.anivault.data.local.model.WatchStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

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

/** A user-pinned filter combination shown as a one-tap chip on the Library screen. */
data class SmartListPreset(
    val name: String,
    val status: WatchStatus? = null,
    val format: String? = null,
    val genre: String? = null,
    val tag: String? = null,
    val minRating: Float? = null
) {
    fun toQuery(currentSort: LibrarySort): LibraryQuery = LibraryQuery(
        status = status, format = format, genre = genre, tag = tag, minRating = minRating, sort = currentSort
    )

    fun matches(q: LibraryQuery): Boolean =
        q.status == status && q.format == format && q.genre == genre && q.tag == tag && q.minRating == minRating
}

object SmartLists {
    fun parse(raw: String): List<SmartListPreset> {
        if (raw.isBlank()) return defaults()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return defaults()
        val out = ArrayList<SmartListPreset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            out += SmartListPreset(
                name = name,
                status = o.optString("status").takeIf { it.isNotBlank() }?.let { WatchStatus.parse(it) },
                format = o.optString("format").takeIf { it.isNotBlank() },
                genre = o.optString("genre").takeIf { it.isNotBlank() },
                tag = o.optString("tag").takeIf { it.isNotBlank() },
                minRating = o.optDouble("minRating", -1.0).takeIf { it >= 0.0 }?.toFloat()
            )
        }
        return out
    }

    fun encode(list: List<SmartListPreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    p.status?.let { put("status", it.name) }
                    p.format?.let { put("format", it) }
                    p.genre?.let { put("genre", it) }
                    p.tag?.let { put("tag", it) }
                    p.minRating?.let { put("minRating", it.toDouble()) }
                }
            )
        }
        return arr.toString()
    }

    fun defaults(): List<SmartListPreset> = listOf(
        SmartListPreset("Movies", format = "Movie"),
        SmartListPreset("Top Rated (8+)", minRating = 8f)
    )

    /** Picks a random entry matching the "Spin the Wheel" constraints. */
    fun pickRandom(
        all: List<AnimeEntry>,
        status: WatchStatus? = WatchStatus.PLAN_TO_WATCH,
        maxEpisodes: Int? = null,
        genre: String? = null,
        format: String? = null,
        random: Random = Random.Default
    ): AnimeEntry? {
        val pool = all.filter { e ->
            (status == null || e.status == status) &&
                (maxEpisodes == null || (e.totalEpisodes in 1..maxEpisodes)) &&
                (genre.isNullOrBlank() || e.genres.any { it.equals(genre, ignoreCase = true) }) &&
                (format.isNullOrBlank() || e.format.equals(format, ignoreCase = true))
        }
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }
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
